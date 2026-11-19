package dev.phoneagent.session

import android.content.Context
import android.content.Intent
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Data passed to this interface is persisted locally. Keep provider credentials out of it. */
class SessionRun internal constructor(
    val id: String,
    private val sessions: AgentSessions,
) {
    suspend fun event(kind: String, text: String) = sessions.record(id, kind, text)
    suspend fun checkpoint(value: String) = sessions.saveCheckpoint(id, value)

    suspend fun waitingForApproval(waiting: Boolean) = sessions.setActiveStatus(
        id,
        if (waiting) SessionStatus.WAITING_FOR_APPROVAL else SessionStatus.RUNNING,
        if (waiting) "Waiting for your approval." else "Running",
    )
}

/**
 * Shares the active job between Activity instances and the foreground service.
 * The database journal survives process death. Commands from a dead process are never replayed.
 */
class AgentSessions private constructor(private val context: Context) {
    private val database = SessionDatabase(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val startLock = Mutex()
    private val statusLock = Mutex()
    private val mutableCurrent = MutableStateFlow<SessionSnapshot?>(null)
    private val mutableRevision = MutableStateFlow(0L)
    val current: StateFlow<SessionSnapshot?> = mutableCurrent.asStateFlow()
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    private class ActiveRun(val id: String, val job: Job, val ready: CompletableDeferred<Unit>) {
        var interrupted = false
    }

    private var active: ActiveRun? = null
    private val restoration = scope.async {
        val sessions = withContext(Dispatchers.IO) { database.restoreInterrupted() }
        mutableCurrent.value = sessions.firstOrNull()
        mutableRevision.value++
    }

    /** Start from an explicit action in a visible Activity. */
    suspend fun start(spec: SessionSpec, execute: suspend SessionRun.() -> String): String =
        withContext(Dispatchers.Main.immediate) {
            restoration.await()
            startLock.withLock {
                check(active == null) { "A session is already active." }
                require(spec.task.isNotBlank() && spec.task.length <= 24_000) {
                    "Enter a task of up to 24,000 characters."
                }
                require(spec.project.length <= 4_096 && spec.model.length <= 256 && spec.mode.length <= 128) {
                    "Project or model settings are too long."
                }
                // Once the row is committed, ensure that either the service is requested or the
                // row is marked as failed even if the Activity was recreated in the meantime.
                withContext(NonCancellable) { requestRun(spec, execute) }
            }
        }

    private suspend fun requestRun(spec: SessionSpec, execute: suspend SessionRun.() -> String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val initial = SessionSnapshot(id, spec, SessionStatus.PENDING, "Starting foreground service…", now, now)
        withContext(Dispatchers.IO) { database.create(initial) }
        val ready = CompletableDeferred<Unit>()
        lateinit var run: ActiveRun
        val job = scope.launch(start = CoroutineStart.LAZY) { runAgent(run, execute) }
        run = ActiveRun(id, job, ready)
        active = run
        try {
            context.startForegroundService(
                Intent(context, AgentRunService::class.java)
                    .setAction(AgentRunService.ACTION_START)
                    .putExtra(AgentRunService.EXTRA_ID, id),
            )
        } catch (failure: RuntimeException) {
            job.cancel()
            mutableCurrent.value = initial
            finish(run, SessionStatus.FAILED, "Android could not start the foreground service.")
            throw failure
        }
        job.start()
        mutableCurrent.value = initial
        mutableRevision.value++
        return id
    }

    private suspend fun runAgent(run: ActiveRun, execute: suspend SessionRun.() -> String) {
        try {
            val promoted = try {
                withTimeout(10_000) { run.ready.await() }
                true
            } catch (_: TimeoutCancellationException) {
                false
            }
            check(promoted) { "Android did not activate the foreground service." }
            setActiveStatus(run.id, SessionStatus.RUNNING, "Running")
            val answer = withContext(Dispatchers.Default) { execute(SessionRun(run.id, this@AgentSessions)) }
            record(run.id, "answer", answer)
            finish(run, SessionStatus.COMPLETED, "Completed")
        } catch (cancel: CancellationException) {
            val status = if (run.interrupted) SessionStatus.INTERRUPTED else SessionStatus.CANCELLED
            val message = if (run.interrupted) "Android stopped the foreground service." else "Run cancelled."
            finish(run, status, message)
            throw cancel
        } catch (error: Exception) {
            finish(run, SessionStatus.FAILED, error.message ?: "Run failed.")
        }
    }

    fun cancel() {
        scope.launch { active?.job?.cancel() }
    }

    internal fun cancel(id: String) {
        active?.takeIf { it.id == id }?.job?.cancel()
    }

    internal fun interrupt(id: String) {
        val run = active?.takeIf { it.id == id } ?: return
        run.interrupted = true
        run.job.cancel()
    }

    internal fun foregroundReady(id: String) {
        active?.takeIf { it.id == id }?.ready?.complete(Unit)
    }

    internal fun foregroundFailed(id: String) {
        active?.takeIf { it.id == id }?.ready?.completeExceptionally(
            IllegalStateException("Android denied promotion to a foreground service."),
        )
    }

    suspend fun recent(): List<SessionSnapshot> = read { database.recent() }
    suspend fun events(id: String): List<SessionEvent> = read { database.events(id) }
    suspend fun checkpoint(id: String): String? = read { database.checkpoint(id) }

    private suspend fun <T> read(block: () -> T): T {
        restoration.await()
        return withContext(Dispatchers.IO) { block() }
    }

    internal suspend fun record(id: String, kind: String, text: String) {
        withContext(Dispatchers.IO) { database.append(id, kind, text) }
        withContext(Dispatchers.Main.immediate) { mutableRevision.value++ }
    }

    internal suspend fun saveCheckpoint(id: String, value: String) {
        withContext(Dispatchers.IO) { database.saveCheckpoint(id, value) }
        withContext(Dispatchers.Main.immediate) { mutableRevision.value++ }
    }

    internal suspend fun setActiveStatus(id: String, status: SessionStatus, message: String) {
        withContext(Dispatchers.Main.immediate) {
            statusLock.withLock {
                val current = mutableCurrent.value?.takeIf { it.id == id && it.status.active } ?: return@withLock
                val updated = current.copy(
                    status = status,
                    statusMessage = message,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                withContext(Dispatchers.IO) { database.setStatus(updated) }
                mutableCurrent.value = updated
                mutableRevision.value++
            }
        }
    }

    private suspend fun finish(run: ActiveRun, status: SessionStatus, message: String) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            statusLock.withLock {
                if (active != run) return@withLock
                val current = checkNotNull(mutableCurrent.value)
                val finished = current.copy(
                    status = status,
                    statusMessage = message.take(2_000),
                    updatedAtMillis = System.currentTimeMillis(),
                )
                val saved = runCatching { withContext(Dispatchers.IO) { database.setStatus(finished) } }
                active = null
                mutableCurrent.value = if (saved.isSuccess) finished else {
                    finished.copy(statusMessage = "${finished.statusMessage} Session status could not be saved.")
                }
                mutableRevision.value++
            }
        }
    }

    companion object {
        @Volatile private var instance: AgentSessions? = null

        fun get(context: Context): AgentSessions = instance ?: synchronized(this) {
            instance ?: AgentSessions(context.applicationContext).also { instance = it }
        }
    }
}

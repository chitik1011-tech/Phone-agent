package dev.phoneagent.session

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRunServiceTest {
    @Test
    fun runContinuesWhenActivityStopsAndCanBeCancelled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val sessions = AgentSessions.get(context)
        val started = CompletableDeferred<Unit>()
        val activityStopped = CompletableDeferred<Unit>()
        val wroteWhileStopped = CompletableDeferred<Unit>()
        val spec = SessionSpec("Exercise foreground execution", "test", "test", "ASK")
        ActivityScenario.launch<Activity>(launch).use { scenario ->
            runBlocking {
                val id = sessions.start(spec) {
                    started.complete(Unit)
                    activityStopped.await()
                    event("test", "Foreground work continued after the Activity stopped.")
                    wroteWhileStopped.complete(Unit)
                    awaitCancellation()
                }
                try {
                    withTimeout(15_000) { started.await() }
                    val rejected = runCatching {
                        sessions.start(spec) { error("Concurrent run must never execute") }
                    }
                    assertTrue(rejected.exceptionOrNull() is IllegalStateException)
                    scenario.moveToState(Lifecycle.State.CREATED)
                    activityStopped.complete(Unit)
                    withTimeout(10_000) { wroteWhileStopped.await() }
                    assertEquals(SessionStatus.RUNNING, sessions.current.value?.status)
                    assertEquals("test", sessions.events(id).single().kind)
                } finally {
                    sessions.cancel()
                    withTimeout(10_000) {
                        sessions.current.first { snapshot ->
                            snapshot != null && snapshot.id == id && !snapshot.status.active
                        }
                    }
                }
                assertEquals(SessionStatus.CANCELLED, sessions.current.value?.status)
            }
        }
    }
}

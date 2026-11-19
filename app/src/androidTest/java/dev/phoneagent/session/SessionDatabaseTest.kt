package dev.phoneagent.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun coldRestartPreservesCheckpointAndDoesNotRestartActiveSession() {
        val name = "sessions-test-${UUID.randomUUID()}.db"
        try {
            SessionDatabase(context, name).use { first ->
                first.create(session("pending", SessionStatus.PENDING))
                first.create(session("running", SessionStatus.RUNNING))
                first.create(session("approval", SessionStatus.WAITING_FOR_APPROVAL))
                first.create(session("done", SessionStatus.COMPLETED))
                first.append("running", "tool", "Test build output")
                first.saveCheckpoint("running", """{"step":2}""")
            }
            SessionDatabase(context, name).use { reopened ->
                val restored = reopened.restoreInterrupted().associate { it.id to it.status }
                assertEquals(
                    mapOf(
                        "pending" to SessionStatus.INTERRUPTED,
                        "running" to SessionStatus.INTERRUPTED,
                        "approval" to SessionStatus.INTERRUPTED,
                        "done" to SessionStatus.COMPLETED,
                    ),
                    restored,
                )
                assertEquals("""{"step":2}""", reopened.checkpoint("running"))
                assertEquals("Test build output", reopened.events("running").single().text)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun removesOldEventsAndCascadesSessionPruning() {
        SessionDatabase(context, null).use { database ->
            database.create(session("original", SessionStatus.COMPLETED))
            repeat(SessionDatabase.EVENT_LIMIT + 1) { index ->
                database.append("original", "tool", index.toString())
            }
            val retained = database.events("original")
            assertEquals(SessionDatabase.EVENT_LIMIT, retained.size)
            assertEquals("1", retained.first().text)
            assertEquals(SessionDatabase.EVENT_LIMIT.toString(), retained.last().text)

            database.saveCheckpoint("original", "old checkpoint")
            repeat(SessionDatabase.SESSION_LIMIT) { index ->
                database.create(session("new-$index", SessionStatus.COMPLETED, index.toLong() + 2))
            }
            assertEquals(SessionDatabase.SESSION_LIMIT, database.recent().size)
            assertNull(database.checkpoint("original"))
            assertEquals(emptyList<SessionEvent>(), database.events("original"))
        }
    }

    private fun session(id: String, status: SessionStatus, now: Long = 1L) = SessionSnapshot(
        id,
        SessionSpec("Run tests", "example", "test-model", "ASK"),
        status,
        status.name,
        now,
        now,
    )
}

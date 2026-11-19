package dev.phoneagent.session

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class SessionDatabase(
    context: Context,
    name: String? = "agent-sessions.db",
) : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY NOT NULL,
                task TEXT NOT NULL,
                project TEXT NOT NULL,
                model TEXT NOT NULL,
                mode TEXT NOT NULL,
                status TEXT NOT NULL,
                message TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                checkpoint TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE session_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX session_events_by_session ON session_events(session_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported session database migration: $oldVersion to $newVersion")
    }

    fun restoreInterrupted(): List<SessionSnapshot> {
        val active = SessionStatus.entries.filter(SessionStatus::active).map(SessionStatus::name)
        val placeholders = active.joinToString(",") { "?" }
        writableDatabase.update(
            "sessions",
            contentValuesForStatus(
                SessionStatus.INTERRUPTED,
                "Android stopped the process. Check saved results before starting a new run.",
            ),
            "status IN ($placeholders)",
            active.toTypedArray(),
        )
        return recent()
    }

    fun create(snapshot: SessionSnapshot) = transaction { db ->
        val values = contentValuesForStatus(
            snapshot.status,
            snapshot.statusMessage,
            snapshot.updatedAtMillis,
        ).apply {
            put("id", snapshot.id)
            put("task", snapshot.spec.task)
            put("project", snapshot.spec.project)
            put("model", snapshot.spec.model)
            put("mode", snapshot.spec.mode)
            put("created_at", snapshot.createdAtMillis)
        }
        db.insertOrThrow("sessions", null, values)
        db.execSQL(
            """
            DELETE FROM sessions WHERE id IN (
                SELECT id FROM sessions ORDER BY created_at DESC, rowid DESC
                LIMIT -1 OFFSET $SESSION_LIMIT
            )
            """.trimIndent(),
        )
    }

    fun setStatus(snapshot: SessionSnapshot) {
        val changed = writableDatabase.update(
            "sessions",
            contentValuesForStatus(snapshot.status, snapshot.statusMessage, snapshot.updatedAtMillis),
            "id = ?",
            arrayOf(snapshot.id),
        )
        check(changed == 1) { "Unknown session ${snapshot.id}" }
    }

    fun recent(): List<SessionSnapshot> = readableDatabase.query(
        "sessions",
        arrayOf("id", "task", "project", "model", "mode", "status", "message", "created_at", "updated_at"),
        null,
        null,
        null,
        null,
        "created_at DESC, rowid DESC",
        SESSION_LIMIT.toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.snapshot())
        }
    }

    fun append(sessionId: String, kind: String, text: String) = transaction { db ->
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("kind", kind.take(64))
            put(
                "text",
                if (text.length <= EVENT_TEXT_LIMIT) text else {
                    val marker = "\n[Event shortened in the session journal.]"
                    text.take(EVENT_TEXT_LIMIT - marker.length) + marker
                },
            )
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("session_events", null, values)
        db.execSQL(
            """
            DELETE FROM session_events WHERE id IN (
                SELECT id FROM session_events WHERE session_id = ?
                ORDER BY id DESC LIMIT -1 OFFSET $EVENT_LIMIT
            )
            """.trimIndent(),
            arrayOf(sessionId),
        )
    }

    fun events(sessionId: String): List<SessionEvent> = readableDatabase.query(
        "session_events",
        arrayOf("id", "kind", "text", "created_at"),
        "session_id = ?",
        arrayOf(sessionId),
        null,
        null,
        "id ASC",
        EVENT_LIMIT.toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(SessionEvent(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
            }
        }
    }

    fun saveCheckpoint(id: String, value: String) {
        require(value.length <= CHECKPOINT_LIMIT) { "Session checkpoint exceeds $CHECKPOINT_LIMIT characters" }
        val values = ContentValues().apply { put("checkpoint", value) }
        val changed = writableDatabase.update("sessions", values, "id = ?", arrayOf(id))
        check(changed == 1) { "Unknown session $id" }
    }

    fun checkpoint(id: String): String? = readableDatabase.query(
        "sessions",
        arrayOf("checkpoint"),
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun Cursor.snapshot() = SessionSnapshot(
        id = getString(0),
        spec = SessionSpec(task = getString(1), project = getString(2), model = getString(3), mode = getString(4)),
        status = SessionStatus.valueOf(getString(5)),
        statusMessage = getString(6),
        createdAtMillis = getLong(7),
        updatedAtMillis = getLong(8),
    )

    private fun transaction(action: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            action(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun contentValuesForStatus(
        status: SessionStatus,
        message: String,
        now: Long = System.currentTimeMillis(),
    ) = ContentValues().apply {
        put("status", status.name)
        put("message", message)
        put("updated_at", now)
    }

    companion object {
        const val SESSION_LIMIT = 25
        const val EVENT_LIMIT = 200
        const val EVENT_TEXT_LIMIT = 8_192
        const val CHECKPOINT_LIMIT = 300_000
    }
}

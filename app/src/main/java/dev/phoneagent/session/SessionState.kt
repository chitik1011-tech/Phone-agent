package dev.phoneagent.session

data class SessionSpec(
    val task: String,
    val project: String,
    val model: String,
    val mode: String,
)

enum class SessionStatus(val active: Boolean = false) {
    PENDING(true),
    RUNNING(true),
    WAITING_FOR_APPROVAL(true),
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

data class SessionSnapshot(
    val id: String,
    val spec: SessionSpec,
    val status: SessionStatus,
    val statusMessage: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class SessionEvent(
    val id: Long,
    val kind: String,
    val text: String,
    val createdAtMillis: Long,
)

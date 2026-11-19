package dev.phoneagent.agent

import org.json.JSONArray
import org.json.JSONObject

enum class AgentMode {
    ASK, AGENT, AUTONOMOUS, READ_ONLY
}

data class AgentOptions(
    val mode: AgentMode = AgentMode.ASK,
    val systemPrompt: String = "",
    val maxSteps: Int = 12,
    val maxSubagents: Int = 3,
    val contextChars: Int = 48_000,
    val autoCompact: Boolean = true,
)

data class ToolCall(val id: String, val name: String, val arguments: String) {
    override fun toString(): String = "ToolCall(id=$id, name=$name)"

    internal fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments))

    companion object {
        internal fun fromJson(json: JSONObject): ToolCall {
            val function = json.getJSONObject("function")
            return ToolCall(json.getString("id"), function.getString("name"), function.getString("arguments"))
        }
    }
}

data class AgentMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("role", role).apply {
        if (content.isNotEmpty() || toolCalls.isEmpty()) put("content", content)
        if (toolCalls.isNotEmpty()) put("tool_calls", JSONArray(toolCalls.map(ToolCall::toJson)))
        if (toolCallId != null) put("tool_call_id", toolCallId)
    }

    companion object {
        fun fromJson(json: JSONObject): AgentMessage {
            val calls = json.optJSONArray("tool_calls")
            return AgentMessage(
                role = json.getString("role"),
                content = json.text("content"),
                toolCalls = List(calls?.length() ?: 0) { ToolCall.fromJson(requireNotNull(calls).getJSONObject(it)) },
                toolCallId = json.text("tool_call_id").ifEmpty { null },
            )
        }
    }
}

data class AgentResult(
    val answer: String,
    val messages: List<AgentMessage>,
    val stepLimitReached: Boolean = false,
)

sealed interface AgentEvent {
    data class TextDelta(val text: String, val agentId: String = "main") : AgentEvent
    data class ToolStarted(val call: ToolCall, val agentId: String = "main") : AgentEvent
    data class ToolFinished(val call: ToolCall, val output: String, val agentId: String = "main") : AgentEvent
    data class Subagent(
        val id: String,
        val task: String,
        val state: SubagentState,
        val model: String,
        val detail: String = "",
    ) : AgentEvent
    data class Compacted(val summary: String) : AgentEvent
    data class Checkpoint(val messages: List<AgentMessage>) : AgentEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int, val agentId: String = "main") : AgentEvent
}

enum class SubagentState { RUNNING, COMPLETED, FAILED, CANCELLED }

interface ProjectTools {
    suspend fun list(path: String): String
    suspend fun read(path: String): String
    suspend fun search(path: String, query: String): String = searchProject(this, path, query)
    suspend fun write(path: String, text: String): String
    suspend fun edit(path: String, oldText: String, newText: String): String
    suspend fun command(command: String): String
}

interface AgentProvider {
    val model: String
    suspend fun complete(
        messages: List<AgentMessage>,
        tools: List<JSONObject>,
        onText: suspend (String) -> Unit,
    ): ModelTurn
}

data class ModelTurn(
    val message: AgentMessage,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

internal fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

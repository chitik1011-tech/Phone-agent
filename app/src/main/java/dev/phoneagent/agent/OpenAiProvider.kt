package dev.phoneagent.agent

import dev.phoneagent.providers.DEFAULT_OUTPUT_TOKENS
import dev.phoneagent.providers.OUTPUT_TOKEN_RANGE
import dev.phoneagent.providers.PROVIDER_CONNECT_TIMEOUT_SECONDS
import dev.phoneagent.providers.ReasoningEffort
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_FRAME_BYTES = 1_048_576L
private const val MAX_COMPLETION_CHARS = 262_144
private val loopbackHosts = setOf("127.0.0.1", "localhost", "::1")

data class ProviderConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val maxOutputTokens: Int = DEFAULT_OUTPUT_TOKENS,
    val reasoning: ReasoningEffort = ReasoningEffort.DEFAULT,
) {
    override fun toString(): String = "ProviderConfig(model=$model, reasoning=$reasoning)"
}

class OpenAiProvider(private val config: ProviderConfig) : AgentProvider {
    override val model: String = config.model.trim()
    private val endpoint = validateEndpoint(config.baseUrl)
    private val geminiCompatibleEndpoint = endpoint.host == "generativelanguage.googleapis.com"
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .apply { if (endpoint.host in loopbackHosts) proxy(Proxy.NO_PROXY) }
        .connectTimeout(PROVIDER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun models(): List<String> = exchange(request("models").get().build()) { body ->
        val data = readJson(body).getJSONArray("data")
        buildList {
            repeat(data.length()) {
                val id = data.getJSONObject(it).text("id")
                if (id.isNotBlank()) add(id)
            }
        }.distinct().sorted()
    }

    override suspend fun complete(
        messages: List<AgentMessage>,
        tools: List<JSONObject>,
        onText: suspend (String) -> Unit,
    ): ModelTurn {
        require(model.isNotEmpty()) { "Select a model before running an agent." }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", JSONArray(messages.map(AgentMessage::toJson)))
            .put("stream", true)
            .put(
                if (endpoint.host == "api.openai.com") "max_completion_tokens" else "max_tokens",
                config.maxOutputTokens.coerceIn(OUTPUT_TOKEN_RANGE),
            )
        if (endpoint.host == "api.openai.com") {
            payload.put("stream_options", JSONObject().put("include_usage", true))
        }
        if (tools.isNotEmpty()) {
            payload.put("tools", JSONArray(tools))
        }
        configureReasoning(payload, endpoint.host, config.reasoning, hasTools = tools.isNotEmpty())
        val request = request("chat/completions")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return exchange(request) { body ->
            if (body.contentType()?.subtype == "json") {
                parseFullResponse(readJson(body)).also { if (it.message.content.isNotEmpty()) onText(it.message.content) }
            } else {
                parseEventStream(body.source(), geminiCompatibleEndpoint, onText)
            }
        }
    }

    private fun request(path: String): Request.Builder {
        val url = endpoint.newBuilder().addPathSegments(path).build()
        return Request.Builder().url(url).apply {
            val key = config.apiKey.trim()
            require(key.length <= 4_096 && key.all { it.code in 33..126 }) { "Enter a valid API key." }
            if (key.isNotEmpty()) header("Authorization", "Bearer $key")
        }
    }

    private suspend fun <T> exchange(request: Request, read: suspend (ResponseBody) -> T): T =
        coroutineScope {
            val call = client.newCall(request)
            val cancelCall = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val detail = when (response.code) {
                                401, 403 -> "Check the API key and model access."
                                404 -> "Check the base URL and model."
                                429 -> "The provider rate limit or quota has been reached."
                                in 300..399 -> "Configure the final provider endpoint directly."
                                else -> "The provider rejected the request."
                            }
                            throw IOException("Provider HTTP ${response.code}. $detail")
                        }
                        read(response.body ?: throw IOException("The provider returned no response body."))
                    }
                }
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                throw failure
            } finally {
                cancelCall.cancel()
            }
        }
}

internal fun configureReasoning(payload: JSONObject, host: String, effort: ReasoningEffort, hasTools: Boolean) {
    if (host == "api.deepseek.com" && hasTools) {
        // Thinking with tools requires replay of reasoning_content, which the agent does not persist.
        payload.put("thinking", JSONObject().put("type", "disabled"))
        return
    }
    val value = effort.wireValue ?: return
    when (host) {
        "api.deepseek.com" -> {
            payload.put("thinking", JSONObject().put("type", "enabled"))
            payload.put("reasoning_effort", value)
        }
        "openrouter.ai" -> payload.put("reasoning", JSONObject().put("effort", value))
        "api.anthropic.com" -> Unit
        else -> payload.put("reasoning_effort", value)
    }
}

internal fun validateEndpoint(raw: String): HttpUrl {
    require(raw.length <= 2_048) { "The provider base URL is too long." }
    val url = raw.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Enter a valid provider base URL.")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "A provider base URL cannot contain credentials, a query, or a fragment."
    }
    require(url.isHttps || url.host in loopbackHosts) {
        "Use HTTPS for remote providers. HTTP is available for a loopback model server."
    }
    return url
}

private fun readJson(body: ResponseBody): JSONObject {
    val source = body.source()
    if (source.request(MAX_FRAME_BYTES + 1)) throw IOException("The provider response is too large.")
    return JSONObject(source.readUtf8())
}

private fun parseFullResponse(json: JSONObject): ModelTurn {
    if (json.has("error")) throw IOException("The provider returned an API error.")
    val choice = json.getJSONArray("choices").getJSONObject(0)
    checkFinishReason(choice.text("finish_reason"))
    val message = AgentMessage.fromJson(choice.getJSONObject("message"))
    if (message.role != "assistant") throw IOException("Unexpected provider response role.")
    if ((choice.text("finish_reason") == "tool_calls") != message.toolCalls.isNotEmpty()) {
        throw IOException("The provider returned inconsistent tool call state.")
    }
    return modelTurn(message, json.optJSONObject("usage"))
}

internal suspend fun parseEventStream(
    source: BufferedSource,
    geminiCompatibleEndpoint: Boolean = false,
    onText: suspend (String) -> Unit,
): ModelTurn {
    val response = StringBuilder()
    val calls = sortedMapOf<Int, ToolCallBuffer>()
    val event = StringBuilder()
    var finishReason = ""
    var usage: JSONObject? = null

    while (!source.exhausted()) {
        currentCoroutineContext().ensureActive()
        val line = source.readUtf8LineStrict(MAX_FRAME_BYTES)
        if (line.startsWith("data:")) {
            if (event.isNotEmpty()) event.append('\n')
            event.append(line.substring(5).removePrefix(" "))
            if (event.length > MAX_FRAME_BYTES) throw IOException("The provider event is too large.")
            continue
        }
        if (line.isNotEmpty() || event.isEmpty()) continue

        val frame = event.toString().trim()
        event.clear()
        if (frame == "[DONE]") break
        val data = JSONObject(frame)
        if (data.has("error")) throw IOException("The provider returned an API error.")
        data.optJSONObject("usage")?.let { usage = it }
        val choice = data.optJSONArray("choices")?.optJSONObject(0) ?: continue
        choice.text("finish_reason").takeIf(String::isNotEmpty)?.let { finishReason = it }
        val delta = choice.optJSONObject("delta") ?: continue
        val text = delta.text("content")
        if (text.isNotEmpty()) {
            response.append(text)
            if (response.length > MAX_COMPLETION_CHARS) throw IOException("The provider completion is too large.")
            onText(text)
        }
        val toolDeltas = delta.optJSONArray("tool_calls") ?: continue
        repeat(toolDeltas.length()) { index ->
            val part = toolDeltas.getJSONObject(index)
            val callIndex = if (geminiCompatibleEndpoint && !part.has("index")) index else part.getInt("index")
            if (callIndex !in 0..15) throw IOException("The provider returned too many tool calls.")
            calls.getOrPut(callIndex, ::ToolCallBuffer).append(part)
        }
    }
    if (finishReason.isEmpty()) throw IOException("The provider stream ended before completion.")
    checkFinishReason(finishReason)
    val callsOnGeminiStop = geminiCompatibleEndpoint && finishReason == "stop" && calls.isNotEmpty()
    if (!callsOnGeminiStop && (finishReason == "tool_calls") != calls.isNotEmpty()) {
        throw IOException("The provider returned inconsistent tool call state.")
    }
    val toolCalls = calls.values.map { it.build() }
    if (callsOnGeminiStop) toolCalls.forEach { JSONObject(it.arguments) }
    val message = AgentMessage("assistant", response.toString(), toolCalls)
    return modelTurn(message, usage)
}

private fun modelTurn(message: AgentMessage, usage: JSONObject?): ModelTurn = ModelTurn(
    message,
    usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 },
    usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 },
)

private fun checkFinishReason(reason: String) {
    if (reason != "stop" && reason != "tool_calls") {
        val detail = when (reason) {
            "length" -> "The model reached its output limit."
            "content_filter" -> "The provider filtered the completion."
            else -> "The provider did not return a complete answer."
        }
        throw IOException(detail)
    }
}

private class ToolCallBuffer {
    private var id = ""
    private val name = StringBuilder()
    private val arguments = StringBuilder()

    fun append(part: JSONObject) {
        val nextId = part.text("id")
        if (nextId.isNotEmpty()) {
            if (id.isNotEmpty() && id != nextId) throw IOException("The provider changed a tool call ID.")
            id = nextId
        }
        val function = part.optJSONObject("function") ?: return
        name.append(function.text("name"))
        arguments.append(function.text("arguments"))
        if (id.length > 128 || name.length > 128 || arguments.length > MAX_COMPLETION_CHARS) {
            throw IOException("A provider tool call exceeded its size limit.")
        }
    }

    fun build(): ToolCall {
        if (id.isBlank() || name.isBlank() || arguments.isBlank()) {
            throw IOException("The provider returned an incomplete tool call.")
        }
        return ToolCall(id, name.toString(), arguments.toString())
    }
}

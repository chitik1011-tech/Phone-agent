package dev.phoneagent.mcp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class McpException(
    message: String,
    val httpStatus: Int? = null,
    val rpcCode: Int? = null,
) : IOException(message)

internal data class McpCatalog(
    val protocolVersion: String,
    val tools: List<McpTool>,
    val unavailableTools: Int,
    val clientIdentity: Any,
)

internal class McpClient(server: McpServer, http: OkHttpClient = OkHttpClient()) {
    private val endpoint = server.endpoint()
    private val bearer = server.token.trim()
    private val enabled = server.enabled
    private val clientIdentity = Any()
    private val requestIds = AtomicInteger()
    private val client = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun discover(): McpCatalog {
        check(enabled) { "Enable this MCP server before connecting." }
        return try {
            listTools(Connection(MODERN_VERSION))
        } catch (failure: McpException) {
            if (failure.httpStatus !in setOf(400, 404, 405) ||
                failure.rpcCode in setOf(-32_020, -32_601)
            ) {
                throw failure
            }
            withLegacySession(::listTools)
        }
    }

    // The settings screen calls this only after the user confirms this exact tool and argument object.
    // Tool calls are never retried because the server may have performed a side effect.
    suspend fun callOnce(catalog: McpCatalog, tool: McpTool, arguments: JSONObject): JSONObject {
        check(enabled) { "Enable this MCP server before invoking a tool." }
        require(catalog.clientIdentity === clientIdentity && catalog.tools.any { it === tool }) {
            "Discover the tool from this server before invoking it."
        }
        val params = JSONObject().put("name", tool.name).put("arguments", arguments)
        val result = if (catalog.protocolVersion == MODERN_VERSION) {
            rpc("tools/call", params, Connection(MODERN_VERSION), tool.argumentHeaders(arguments)).result
        } else {
            withLegacySession { connection -> rpc("tools/call", params, connection).result }
        }
        if (result.optString("resultType") == "input_required") {
            throw McpException("The server requested an additional input that this client cannot provide.")
        }
        return result
    }

    private suspend fun listTools(connection: Connection): McpCatalog {
        val tools = linkedMapOf<String, McpTool>()
        val cursors = mutableSetOf<String>()
        var nextCursor: String? = null
        var unavailable = 0

        repeat(MAX_PAGES) {
            val params = JSONObject()
            nextCursor?.let { params.put("cursor", it) }
            val catalog = rpc("tools/list", params, connection).result
            val entries = catalog.optJSONArray("tools")
                ?: throw McpException("The MCP server omitted the tool catalog.")
            for (index in 0 until entries.length()) {
                if (tools.size + unavailable >= MAX_TOOLS) throw McpException("The MCP tool catalog is too large.")
                val raw = entries.optJSONObject(index)
                val tool = try {
                    raw?.let(::readMcpTool)
                } catch (_: McpException) {
                    null
                }
                if (tool == null) unavailable++ else tools[tool.name] = tool
            }
            nextCursor = (catalog.opt("nextCursor") as? String)?.takeIf(String::isNotEmpty)
            if (nextCursor == null) {
                return McpCatalog(connection.version, tools.values.toList(), unavailable, clientIdentity)
            }
            if (requireNotNull(nextCursor).length > 2048 || !cursors.add(requireNotNull(nextCursor))) {
                throw McpException("The MCP server returned an invalid catalog cursor.")
            }
        }
        throw McpException("The MCP tool catalog spans too many pages.")
    }

    private suspend fun <T> withLegacySession(block: suspend (Connection) -> T): T {
        val init = rpc(
            "initialize",
            JSONObject()
                .put("protocolVersion", LEGACY_VERSION)
                .put("clientInfo", clientInfo())
                .put("capabilities", JSONObject()),
            connection = null,
        )
        val protocol = init.result.optString("protocolVersion")
        if (protocol !in SUPPORTED_LEGACY) throw McpException("The MCP server negotiated an unsupported protocol revision.")
        val sessionId = init.sessionId
        if (sessionId != null && (sessionId.isEmpty() || sessionId.length > 512 || sessionId.any { it.code !in 33..126 })) {
            throw McpException("The MCP server returned an invalid session identifier.")
        }
        val connection = Connection(protocol, sessionId)
        try {
            notifyInitialized(connection)
            return block(connection)
        } finally {
            if (connection.sessionId != null) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(1_500) {
                        try {
                            exchange(request(connection).delete().build()) { Unit }
                        } catch (_: McpException) {
                            // Some servers expire sessions themselves or do not implement DELETE.
                        }
                    }
                }
            }
        }
    }

    private suspend fun notifyInitialized(connection: Connection) {
        val message = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        val request = request(connection).post(message.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        exchange(request) { response ->
            if (response.code != 202) throw McpException("The MCP server did not accept initialization.")
        }
    }

    private suspend fun rpc(
        method: String,
        params: JSONObject,
        connection: Connection?,
        headerParams: Map<String, String> = emptyMap(),
    ): RpcResponse {
        val id = requestIds.incrementAndGet()
        val request = request(connection)
        if (connection?.version == MODERN_VERSION) {
            params.put(
                "_meta",
                JSONObject()
                    .put("io.modelcontextprotocol/protocolVersion", MODERN_VERSION)
                    .put("io.modelcontextprotocol/clientInfo", clientInfo())
                    .put("io.modelcontextprotocol/clientCapabilities", JSONObject()),
            )
            request.header("Mcp-Method", method)
            if (method == "tools/call") {
                request.header("Mcp-Name", encodeMcpHeader(params.getString("name")))
                for ((header, value) in headerParams) request.header(header, value)
            }
        }
        val message = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        return exchange(request.post(message.toString().toRequestBody(JSON_MEDIA_TYPE)).build()) { response ->
            val body = response.body ?: throw McpException("The MCP server returned an empty response.")
            val type = body.contentType()?.let { "${it.type}/${it.subtype}" }
            val messageObject = when (type) {
                "application/json" -> readJson(body.source())
                "text/event-stream" -> readEvents(body.source(), id)
                else -> throw McpException("The MCP server returned an unsupported content type.")
            }
            if (!matchesId(messageObject, id)) throw McpException("The MCP server returned an unmatched response.")
            val error = messageObject.optJSONObject("error")
            if (error != null) {
                val code = error.optInt("code")
                throw McpException("The MCP server rejected the RPC request (code $code).", rpcCode = code)
            }
            RpcResponse(
                result = messageObject.optJSONObject("result")
                    ?: throw McpException("The MCP server omitted the RPC result."),
                sessionId = response.header("Mcp-Session-Id"),
            )
        }
    }

    private fun request(connection: Connection?): Request.Builder =
        Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json, text/event-stream")
            .apply {
                connection?.let { header("MCP-Protocol-Version", it.version) }
                connection?.sessionId?.let { header("Mcp-Session-Id", it) }
            }

    private suspend fun <T> exchange(request: Request, read: (Response) -> T): T {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(McpException("Could not reach the MCP server.")))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (!it.isSuccessful) throw mapFailure(it)
                            read(it)
                        }
                        continuation.resumeWith(Result.success(value))
                    } catch (failure: Exception) {
                        val safe = failure as? McpException ?: McpException("Could not read the MCP response.")
                        continuation.resumeWith(Result.failure(safe))
                    }
                }
            })
        }
    }

    private fun mapFailure(response: Response): McpException {
        val status = response.code
        val diagnostic = when (status) {
            401, 403 -> "The MCP server rejected authentication. Check the bearer token."
            404 -> "The MCP endpoint was not found. Check its exact URL."
            429 -> "The MCP server is rate-limiting requests."
            in 300..399 -> "The MCP server returned a redirect. Enter the final endpoint URL."
            else -> "The MCP server rejected the request."
        }
        val code = if (status in setOf(400, 404, 405)) {
            runCatching {
                val source = response.body?.source() ?: return@runCatching null
                (readJson(source).optJSONObject("error")?.opt("code") as? Number)?.toInt()
            }.getOrNull()
        } else {
            null
        }
        return McpException("$diagnostic HTTP $status.", status, code)
    }

    private data class Connection(val version: String, val sessionId: String? = null)
    private data class RpcResponse(val result: JSONObject, val sessionId: String?)

    private companion object {
        const val MODERN_VERSION = "2026-07-28"
        const val LEGACY_VERSION = "2025-11-25"
        const val MAX_TOOLS = 500
        const val MAX_PAGES = 8
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val SUPPORTED_LEGACY = setOf(LEGACY_VERSION, "2025-06-18", "2025-03-26")

        fun clientInfo(): JSONObject = JSONObject().put("name", "Phone Agent").put("version", "0.1.0")
    }
}

private const val MAX_REPLY_BYTES = 1024L * 1024

private fun readJson(source: BufferedSource): JSONObject {
    if (source.request(MAX_REPLY_BYTES + 1)) throw McpException("The MCP response is too large.")
    return parseMcpJson(source.readUtf8(), "The MCP server returned invalid JSON.")
}

private fun readEvents(source: BufferedSource, id: Int): JSONObject {
    val event = StringBuilder()
    var readChars = 0L
    while (!source.exhausted()) {
        val line = source.readUtf8LineStrict(MAX_REPLY_BYTES)
        readChars += line.length + 1
        if (readChars > MAX_REPLY_BYTES) throw McpException("The MCP event stream is too large.")
        if (line == "data" || line.startsWith("data:")) {
            if (event.isNotEmpty()) event.append('\n')
            event.append(line.substringAfter(':', "").removePrefix(" "))
        } else if (line.isEmpty() && event.isNotEmpty()) {
            val data = parseMcpJson(event.toString(), "The MCP server sent an invalid SSE event.")
            if (matchesId(data, id)) return data
            event.clear()
        }
    }
    throw McpException("The MCP event stream ended before its result.")
}

private fun matchesId(message: JSONObject, id: Int): Boolean {
    val returnedId = message.opt("id")
    return message.optString("jsonrpc") == "2.0" && returnedId is Number &&
        returnedId.toDouble() == id.toDouble() && !message.has("method")
}

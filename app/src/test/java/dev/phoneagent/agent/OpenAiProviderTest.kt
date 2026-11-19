package dev.phoneagent.agent

import dev.phoneagent.providers.ReasoningEffort
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {
    @Test
    fun assemblesTextAndFragmentedToolArgumentsFromServerSentEvents() = runBlocking {
        val buffer = Buffer().writeUtf8(
            """
            data: {"choices":[{"delta":{"content":"Reading "},"finish_reason":null}]}

            : heartbeat
            data: {"choices":[{"delta":{"content":"the file.","tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"pa"}}]},"finish_reason":null}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"build.gradle.kts\"}"}}]},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

            data: {"choices":[],"usage":{"prompt_tokens":15,"completion_tokens":9}}

            data: [DONE]

            """.trimIndent() + "\n",
        )
        val deltas = mutableListOf<String>()
        val turn = parseEventStream(buffer) { deltas.add(it) }
        assertEquals(listOf("Reading ", "the file."), deltas)
        assertEquals("Reading the file.", turn.message.content)
        assertEquals(ToolCall("call_1", "read_file", """{"path":"build.gradle.kts"}"""), turn.message.toolCalls.single())
        assertEquals(15, turn.inputTokens)
        assertEquals(9, turn.outputTokens)
    }

    @Test
    fun rejectsDisconnectedStreamingTurnBeforeToolExecution() {
        assertThrows(IOException::class.java) {
            runBlocking {
                parseEventStream(Buffer().writeUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")) {}
            }
        }
    }

    @Test
    fun requestsCatalogAndStreamsCompletionOverLoopbackHttp() = runBlocking {
        val received = AtomicReference<Pair<String?, JSONObject>>()
        LoopbackServer(requestCount = 2) { request, _, output ->
            when (request.methodAndPath) {
                "GET /v1/models" -> output.respond("application/json", """{"data":[{"id":"test-model"}]}""")
                "POST /v1/chat/completions" -> {
                    received.set(request.headers["authorization"] to JSONObject(request.body))
                    val response = """
                        data: {"choices":[{"delta":{"content":"Verified"},"finish_reason":null}]}

                        data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                    """.trimIndent() + "\n"
                    output.respond("text/event-stream", response)
                }
                else -> error("Unexpected request: ${request.methodAndPath}")
            }
        }.use { server ->
            val provider = OpenAiProvider(ProviderConfig(
                server.baseUrl, "test-model", "test-key", maxOutputTokens = 8_192, reasoning = ReasoningEffort.HIGH,
            ))
            assertEquals(listOf("test-model"), provider.models())
            val turn = provider.complete(listOf(AgentMessage("user", "Check transport")), emptyList()) {}
            assertEquals("Verified", turn.message.content)
            val (authentication, body) = received.get()
            assertEquals("Bearer test-key", authentication)
            assertEquals("test-model", body.getString("model"))
            assertEquals(8_192, body.getInt("max_tokens"))
            assertEquals("high", body.getString("reasoning_effort"))
            assertEquals("Check transport", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        }
    }

    @Test
    fun sendsTheDocumentedReasoningFieldsAndKeepsDeepSeekToolTurnsCompatible() {
        val openRouter = JSONObject()
        configureReasoning(openRouter, "openrouter.ai", ReasoningEffort.HIGH, hasTools = true)
        assertEquals("high", openRouter.getJSONObject("reasoning").getString("effort"))
        assertFalse(openRouter.has("reasoning_effort"))

        val directChat = JSONObject()
        configureReasoning(directChat, "api.deepseek.com", ReasoningEffort.MAX, hasTools = false)
        assertEquals("enabled", directChat.getJSONObject("thinking").getString("type"))
        assertEquals("max", directChat.getString("reasoning_effort"))

        val directTools = JSONObject()
        configureReasoning(directTools, "api.deepseek.com", ReasoningEffort.MAX, hasTools = true)
        assertEquals("disabled", directTools.getJSONObject("thinking").getString("type"))
        assertFalse(directTools.has("reasoning_effort"))
    }

    @Test
    fun cancellingAStreamClosesItsPendingNetworkRead() = runBlocking {
        val disconnected = CompletableDeferred<Boolean>()
        LoopbackServer(requestCount = 1) { request, input, output ->
            check(request.methodAndPath == "POST /v1/chat/completions")
            output.respond(
                "text/event-stream",
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                complete = false,
            )
            val connectionClosed = try {
                input.exhausted()
            } catch (_: SocketException) {
                true
            }
            disconnected.complete(connectionClosed)
        }.use { server ->
            withTimeout(4_000) {
                val provider = OpenAiProvider(ProviderConfig(server.baseUrl, "test-model"))
                val firstOutput = CompletableDeferred<String>()
                val job = launch {
                    provider.complete(listOf(AgentMessage("user", "Check cancellation")), emptyList()) {
                        firstOutput.complete(it)
                    }
                }
                assertEquals("partial", firstOutput.await())
                job.cancelAndJoin()
                assertTrue(disconnected.await())
            }
        }
    }

    @Test
    fun acceptsCompleteGeminiToolCallsWithStopOnlyForCompatibilityEndpoint() = runBlocking {
        val stream = """
            data: {"choices":[{"delta":{"tool_calls":[{"id":"g1","function":{"name":"read_file","arguments":"{}"}}]},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent() + "\n"
        val result = parseEventStream(Buffer().writeUtf8(stream), geminiCompatibleEndpoint = true) {}
        assertEquals(ToolCall("g1", "read_file", "{}"), result.message.toolCalls.single())
    }

    @Test
    fun checksProviderUrlsAndDoesNotPrintApiKey() {
        assertEquals("localhost", validateEndpoint("http://localhost:11434/v1").host)
        assertEquals("https://api.openai.com/v1", validateEndpoint("https://api.openai.com/v1/").toString())
        assertThrows(IllegalArgumentException::class.java) { validateEndpoint("http://example.org/v1") }
        assertThrows(IllegalArgumentException::class.java) { validateEndpoint("https://secret@example.org/v1") }
        assertThrows(IllegalArgumentException::class.java) { validateEndpoint("https://example.org/v1?token=abc") }
        assertFalse(ProviderConfig("https://provider.test", "test", "secret").toString().contains("secret"))
    }
}

private data class FixtureRequest(
    val methodAndPath: String,
    val headers: Map<String, String>,
    val body: String,
)

/** A real HTTP/1.1 loopback endpoint using only APIs available to Android unit-test compilation. */
private class LoopbackServer(
    requestCount: Int,
    respond: (FixtureRequest, BufferedSource, BufferedSink) -> Unit,
) : Closeable {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val failure = AtomicReference<Throwable>()
    val baseUrl = "http://127.0.0.1:${socket.localPort}/v1"
    private val worker = thread(isDaemon = true, name = "provider-http-fixture") {
        try {
            repeat(requestCount) {
                socket.accept().use { client ->
                    client.soTimeout = 8_000
                    val input = client.source().buffer()
                    respond(input.readRequest(), input, client.sink().buffer())
                }
            }
        } catch (error: Throwable) {
            if (!socket.isClosed) failure.set(error)
        }
    }

    override fun close() {
        socket.close()
        worker.join(1_000)
        check(!worker.isAlive) { "Loopback HTTP fixture failed to finish." }
        failure.get()?.let { throw AssertionError("Loopback HTTP fixture failed.", it) }
    }
}

private fun BufferedSource.readRequest(): FixtureRequest {
    val request = readUtf8LineStrict(8_192).substringBeforeLast(' ')
    val headers = buildMap {
        while (true) {
            val line = readUtf8LineStrict(8_192)
            if (line.isEmpty()) break
            put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
        }
    }
    val length = headers["content-length"]?.toLong() ?: 0
    check(length in 0..65_536) { "Unexpected HTTP request size." }
    return FixtureRequest(request, headers, readUtf8(length))
}

private fun BufferedSink.respond(contentType: String, body: String, complete: Boolean = true) {
    val bytes = body.encodeToByteArray()
    writeUtf8("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n")
    if (complete) writeUtf8("Content-Length: ${bytes.size}\r\n")
    writeUtf8("\r\n").write(bytes)
    flush()
}

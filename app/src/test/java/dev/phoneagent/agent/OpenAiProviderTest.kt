package dev.phoneagent.agent

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            val bytes = """{"data":[{"id":"test-model"}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/chat/completions") { exchange ->
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            received.set(authorization to request)
            val response = """
                data: {"choices":[{"delta":{"content":"Verified"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

            """.trimIndent() + "\n"
            val payload = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val provider = OpenAiProvider(ProviderConfig("http://127.0.0.1:${server.address.port}/v1", "test-model", "test-key"))
            assertEquals(listOf("test-model"), provider.models())
            val turn = provider.complete(listOf(AgentMessage("user", "Check transport")), emptyList()) {}
            assertEquals("Verified", turn.message.content)
            val (authentication, body) = received.get()
            assertEquals("Bearer test-key", authentication)
            assertEquals("test-model", body.getString("model"))
            assertEquals("Check transport", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancellingAStreamClosesItsPendingNetworkRead() = runBlocking {
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.write(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".toByteArray(),
                )
                exchange.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            withTimeout(4_000) {
                val provider = OpenAiProvider(ProviderConfig("http://127.0.0.1:${server.address.port}/v1", "test-model"))
                val firstOutput = CompletableDeferred<String>()
                val job = launch {
                    provider.complete(listOf(AgentMessage("user", "Check cancellation")), emptyList()) {
                        firstOutput.complete(it)
                    }
                }
                assertEquals("partial", firstOutput.await())
                job.cancelAndJoin()
            }
        } finally {
            release.countDown()
            server.stop(0)
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

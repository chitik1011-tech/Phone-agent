package dev.phoneagent.mcp

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class McpClientTest {
    private val server = McpServer(name = "Weather", url = "https://example.com/mcp", token = "test-token")

    @Test
    fun modernCatalogPaginatesAndApprovedCallReadsSse() = runBlocking {
        val observed = ConcurrentLinkedQueue<String>()
        val http = fake { request ->
            val message = request.json()
            val params = message.getJSONObject("params")
            val method = message.getString("method")
            assertEquals(method, request.header("Mcp-Method"))
            assertEquals("Bearer test-token", request.header("Authorization"))
            assertEquals("application/json, text/event-stream", request.header("Accept"))
            assertEquals("2026-07-28", request.header("MCP-Protocol-Version"))
            assertEquals("2026-07-28", params.getJSONObject("_meta").getString("io.modelcontextprotocol/protocolVersion"))
            assertEquals("https://example.com/mcp", request.url.toString())

            val id = message.getInt("id")
            if (method == "tools/list") {
                val next = params.optString("cursor")
                observed.add("list:$next")
                val result = JSONObject().put("tools", JSONArray().put(weatherTool(if (next.isEmpty()) "weather" else "forecast")))
                if (next.isEmpty()) result.put("nextCursor", "page-2")
                response(request, 200, rpc(id, result))
            } else {
                observed.add(method)
                assertEquals("weather", request.header("Mcp-Name"))
                assertEquals("=?base64?5YyX5Lqs?=", request.header("Mcp-Param-City"))
                assertEquals("北京", params.getJSONObject("arguments").getString("city"))
                val payload = rpc(id, JSONObject().put("content", JSONArray().put(
                    JSONObject().put("type", "text").put("text", "Sun"),
                )))
                response(
                    request,
                    200,
                    ": heartbeat\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: $payload\n\n",
                    "text/event-stream",
                )
            }
        }

        val client = McpClient(server, http)
        val catalog = client.discover()
        assertEquals(listOf("weather", "forecast"), catalog.tools.map { it.name })
        assertEquals(listOf("list:", "list:page-2"), observed.toList())
        val result = client.callOnce(catalog, catalog.tools.first(), mcpArguments("""{"city":"北京"}"""))
        assertEquals("Sun", result.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(listOf("list:", "list:page-2", "tools/call"), observed.toList())
    }

    @Test
    fun legacyConnectsWithSessionAndClosesAfterDiscoveryAndCall() = runBlocking {
        val methods = ConcurrentLinkedQueue<String>()
        var sessions = 0
        val client = McpClient(server, fake { request ->
            assertEquals("Bearer test-token", request.header("Authorization"))
            if (request.method == "DELETE") {
                methods.add("DELETE")
                assertEquals("session-$sessions", request.header("Mcp-Session-Id"))
                return@fake response(request, 204, "")
            }
            val message = request.json()
            val method = message.getString("method")
            methods.add(method)
            if (request.header("MCP-Protocol-Version") == "2026-07-28") {
                return@fake response(request, 400, "Server not initialized.", "text/plain")
            }
            if (method == "initialize") {
                assertNull(request.header("Mcp-Session-Id"))
                val id = message.getInt("id")
                return@fake response(
                    request, 200,
                    rpc(id, JSONObject().put("protocolVersion", "2025-11-25")),
                ).newBuilder().header("Mcp-Session-Id", "session-${++sessions}").build()
            }
            assertEquals("2025-11-25", request.header("MCP-Protocol-Version"))
            assertEquals("session-$sessions", request.header("Mcp-Session-Id"))
            if (method == "notifications/initialized") return@fake response(request, 202, "")
            val result = if (method == "tools/list") JSONObject().put("tools", JSONArray().put(weatherTool("weather")))
            else JSONObject().put("isError", false)
            response(request, 200, rpc(message.getInt("id"), result))
        })

        val catalog = client.discover()
        assertEquals("2025-11-25", catalog.protocolVersion)
        assertFalse(client.callOnce(catalog, catalog.tools.single(), JSONObject()).getBoolean("isError"))
        assertEquals(
            listOf("tools/list", "initialize", "notifications/initialized", "tools/list", "DELETE",
                "initialize", "notifications/initialized", "tools/call", "DELETE"),
            methods.toList(),
        )
    }

    @Test
    fun refusesUnauthorizedRequestsWithoutHandshaking() = runBlocking {
        val calls = ConcurrentLinkedQueue<String>()
        val client = McpClient(server, fake {
            calls.add(it.json().getString("method"))
            response(it, 401, """{"error":"token-echo-test-token"}""")
        })
        val failure = runCatching { client.discover() }.exceptionOrNull()
        assertTrue(failure is McpException)
        assertFalse(failure?.message.orEmpty().contains(server.token))
        assertEquals(listOf("tools/list"), calls.toList())
    }

    @Test
    fun untrustedEndpointAndArgumentsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { server.copy(token = "").endpoint() }
        assertThrows(IllegalArgumentException::class.java) { server.copy(url = "http://example.com/mcp").endpoint() }
        assertThrows(IllegalArgumentException::class.java) { server.copy(url = "https://example.com/mcp?k=key").endpoint() }
        assertThrows(McpException::class.java) { mcpArguments("[]") }
        assertThrows(McpException::class.java) { mcpArguments("{} {}") }
        assertThrows(McpException::class.java) { mcpArguments("{\"deep\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}") }
        assertEquals("test { braces }", mcpArguments("""{"result":"test { braces }"}""").getString("result"))
    }

    @Test
    fun rejectsInvalidHeaderAnnotations() {
        val invalid = weatherTool("weather").getJSONObject("inputSchema")
        invalid.getJSONObject("properties").getJSONObject("city").put("x-mcp-header", "Foo\r\nBar")
        assertThrows(McpException::class.java) {
            readMcpTool(JSONObject().put("name", "bad").put("inputSchema", invalid))
        }
        assertEquals("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=", encodeMcpHeader("=?base64?literal?="))
        assertEquals("Seattle", encodeMcpHeader("Seattle"))
    }

    private fun weatherTool(name: String) = JSONObject()
        .put("name", name)
        .put("inputSchema", JSONObject().put("type", "object").put(
            "properties",
            JSONObject().put("city", JSONObject().put("type", "string").put("x-mcp-header", "City")),
        ))

    private fun Request.json(): JSONObject =
        JSONObject(Buffer().also { sink -> requireNotNull(body).writeTo(sink) }.readUtf8())

    private fun rpc(id: Int, result: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    private fun fake(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()

    private fun response(request: Request, code: Int, body: String, type: String = "application/json"): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody(type.toMediaType()))
            .build()
}

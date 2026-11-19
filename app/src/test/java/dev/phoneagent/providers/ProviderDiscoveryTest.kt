package dev.phoneagent.providers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderDiscoveryTest {
    @Test
    fun catalogUsesSelectedEndpointAndCanParseMetadata() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://openrouter.ai/api/v1/models", request.url.toString())
            assertEquals("Bearer test-secret", request.header("Authorization"))
            assertEquals("GET", request.method)
            assertEquals(3_000, chain.connectTimeoutMillis())
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""
                    {"data":[
                      {"id":"provider/b","supported_parameters":[]},
                      {"id":"provider/a","name":"Agent model","supported_parameters":["tools","reasoning"],"top_provider":{"max_completion_tokens":16384}},
                      {"id":"provider/a"},
                      {"id":"local"},
                      {},
                      {"id":4}
                    ]}
                """.trimIndent().toResponseBody())
                .build()
        }.build()

        val models = ProviderDiscovery(client).models(
            ProviderSettings(preset = ProviderPreset.OPENROUTER, apiKey = "test-secret"),
        )
        assertEquals(
            listOf(
                ProviderModel("local", "local", null),
                ProviderModel("provider/a", "Agent model", true, true, 16_384),
                ProviderModel("provider/b", "provider/b", false, false),
            ),
            models,
        )
    }

    @Test
    fun claudeCatalogUsesOfficialVersionAndReadsDisplayName() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://api.anthropic.com/v1/models?limit=1000", request.url.toString())
            assertEquals("2026-10-21", request.header("anthropic-version"))
            assertEquals("Bearer test-claude-key", request.header("Authorization"))
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"data":[{"id":"claude-test","display_name":"Claude Test"}]}""".toResponseBody())
                .build()
        }.build()

        val models = ProviderDiscovery(client).models(
            ProviderSettings(preset = ProviderPreset.CLAUDE, apiKey = "test-claude-key"),
        )
        assertEquals(listOf(ProviderModel("claude-test", "Claude Test", null)), models)
    }

    @Test
    fun malformedCatalogUsesGenericErrorMessage() {
        val failure = assertThrows(IOException::class.java) { decodeModelCatalog("arbitrary-provider-error") }
        assertEquals("The provider did not return an OpenAI-compatible model catalog.", failure.message)
    }
}

package dev.phoneagent.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderSettingsTest {
    @Test
    fun urlPathPreservesProviderPrefixAndAppendsEndpointSegments() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            ProviderSettings(preset = ProviderPreset.GEMINI)
                .endpoint("chat", "completions").toString(),
        )
        assertEquals(
            "https://api.deepseek.com/models",
            ProviderSettings(preset = ProviderPreset.DEEPSEEK).endpoint("models").toString(),
        )
    }

    @Test
    fun cleartextIsLimitedToExactLoopbackHosts() {
        assertEquals("http://127.0.0.1:11434/v1/models", ProviderSettings(preset = ProviderPreset.OLLAMA)
            .endpoint("models").toString())
        assertEquals("http://[::1]:8080/v1/models", ProviderSettings(baseUrl = "http://[::1]:8080/v1/")
            .endpoint("models").toString())
        listOf("http://localhost.example.com/v1", "http://192.168.0.1/v1").forEach { baseUrl ->
            assertThrows(IllegalArgumentException::class.java) { ProviderSettings(baseUrl = baseUrl).endpoint() }
        }
    }

    @Test
    fun rejectsBaseUrlCredentialsAndSecretAppearancesInToString() {
        listOf("https://name:secret@example.com/v1", "https://example.com/v1?api_key=invalid",
            "https://example.com/v1#fragment").forEach { baseUrl ->
            assertThrows(IllegalArgumentException::class.java) { ProviderSettings(baseUrl = baseUrl).endpoint() }
        }
        val settings = ProviderSettings(model = "my-model", apiKey = "my-private-key")
        settings.validate()
        assertFalse(settings.toString().contains(settings.apiKey))
        assertThrows(IllegalArgumentException::class.java) {
            settings.copy(apiKey = "private\r\nkey").validate()
        }
    }
}

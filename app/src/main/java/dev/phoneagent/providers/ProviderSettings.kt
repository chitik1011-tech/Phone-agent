package dev.phoneagent.providers

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ProviderPreset(
    val label: String,
    val defaultBaseUrl: String,
    val keyRequired: Boolean,
) {
    OPENAI("OpenAI", "https://api.openai.com/v1", true),
    CLAUDE("Claude", "https://api.anthropic.com/v1", true),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", true),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", true),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", true),
    OLLAMA("Ollama", "http://127.0.0.1:11434/v1", false),
    CUSTOM("Custom", "", false),
}

data class ProviderSettings(
    val preset: ProviderPreset = ProviderPreset.OPENAI,
    val baseUrl: String = preset.defaultBaseUrl,
    val model: String = "",
    val apiKey: String = "",
    val timeoutSeconds: Int = 60,
    val maxOutputTokens: Int = 4096,
) {
    fun endpoint(vararg segments: String): HttpUrl {
        val base = baseUrl.trim().trimEnd('/')
        require(base.length <= MAX_URL_LENGTH) { "The base URL is too long." }
        val url = base.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid base URL.")
        require(url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() &&
            url.query == null && url.fragment == null
        ) {
            "Enter a base URL without credentials, a query, or a fragment."
        }
        require(url.isHttps || url.host in loopbackHosts) {
            "Use HTTPS for a remote model endpoint."
        }
        return url.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
    }

    fun validate(requireModel: Boolean = true) {
        endpoint()
        val key = apiKey.trim()
        require(!preset.keyRequired || key.isNotEmpty()) { "Enter an API key." }
        require(key.length <= 4096 && key.all { it.code in 33..126 }) {
            "Enter a valid API key without spaces or line breaks."
        }
        require(!requireModel || model.isNotBlank()) { "Choose a model or enter its identifier." }
        require(model.length <= 256 && model.none(Char::isISOControl)) {
            "Enter a valid model identifier."
        }
        require(timeoutSeconds in 10..600) { "Set the timeout between 10 and 600 seconds." }
        require(maxOutputTokens in 256..32_768) { "Set the output limit between 256 and 32768 tokens." }
    }

    override fun toString(): String = "ProviderSettings(preset=${preset.name}, credentials=redacted)"

    private companion object {
        const val MAX_URL_LENGTH = 2048
        val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")
    }
}

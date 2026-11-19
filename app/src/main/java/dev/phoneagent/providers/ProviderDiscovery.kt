package dev.phoneagent.providers

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class ProviderModel(
    val id: String,
    val title: String,
    val supportsTools: Boolean?,
    val supportsReasoning: Boolean? = null,
    val maxCompletionTokens: Int? = null,
)

class ProviderDiscovery(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun models(settings: ProviderSettings): List<ProviderModel> {
        settings.validate(requireModel = false)
        val catalog = settings.endpoint("models")
        val claude = catalog.host == "api.anthropic.com"
        val endpoint = if (claude) catalog.newBuilder().addQueryParameter("limit", "1000").build() else catalog
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .apply {
                val key = settings.apiKey.trim()
                if (key.isNotEmpty()) header("Authorization", "Bearer $key")
                if (claude) header("anthropic-version", "2026-10-21")
            }
            .get()
            .build()
        val call = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .apply { if (catalog.host in setOf("localhost", "127.0.0.1", "::1")) proxy(Proxy.NO_PROXY) }
            .connectTimeout(PROVIDER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
            .newCall(request)

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val failure = IOException("Could not reach the provider. Check its URL and network connection.")
                    continuation.resumeWith(Result.failure(failure))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(::parseModels) }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun parseModels(response: Response): List<ProviderModel> {
        if (!response.isSuccessful) {
            val problem = when (response.code) {
                401, 403 -> "The provider rejected the credentials"
                404 -> "The provider has no model catalog at this URL"
                429 -> "The provider is rate-limiting requests"
                in 300..399 -> "The provider returned a redirect. Enter its final URL"
                else -> "The provider returned an error"
            }
            throw IOException("$problem (HTTP ${response.code}).")
        }
        val source = response.body?.source() ?: throw IOException("The provider returned an empty response.")
        if (source.request(MAX_BYTES + 1)) throw IOException("The provider model catalog is too large.")
        return decodeModelCatalog(source.readUtf8())
    }

    private companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}

internal fun decodeModelCatalog(json: String): List<ProviderModel> {
    val data = try {
        JSONObject(json).getJSONArray("data")
    } catch (_: org.json.JSONException) {
        throw IOException("The provider did not return an OpenAI-compatible model catalog.")
    }
    return buildList {
        for (index in 0 until data.length()) {
            val model = data.optJSONObject(index) ?: continue
            val id = (model.opt("id") as? String)?.trim() ?: continue
            if (id.isEmpty() || id.length > 256 || id.any(Char::isISOControl)) continue
            val name = model.opt("name") as? String ?: model.opt("display_name") as? String
            val title = name?.trim()?.take(256).orEmpty().ifBlank { id }
            val parameters = model.optJSONArray("supported_parameters")?.let { values ->
                (0 until values.length()).map { values.optString(it) }.toSet()
            }
            add(ProviderModel(
                id, title, parameters?.contains("tools"),
                parameters?.let { "reasoning" in it || "reasoning_effort" in it },
                model.optJSONObject("top_provider")?.optInt("max_completion_tokens")
                    ?.takeIf { it >= OUTPUT_TOKEN_RANGE.first },
            ))
        }
    }.distinctBy(ProviderModel::id).sortedBy(ProviderModel::id)
}

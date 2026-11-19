package dev.phoneagent.providers

import android.content.Context
import org.json.JSONObject

class ProviderStore internal constructor(context: Context, slot: String) {
    constructor(context: Context) : this(context, "default")

    private val record = EncryptedRecord(
        context,
        fileName = "provider-settings.$slot.enc",
        keyAlias = "phone-agent.provider-settings.v1.$slot",
        maxRecordBytes = 16 * 1024,
    )
    internal val storageFile get() = record.storageFile

    fun load(): ProviderSettings {
        val saved = record.read() ?: return ProviderSettings()
        val json = JSONObject(saved)
        return ProviderSettings(
            preset = ProviderPreset.valueOf(json.getString("preset")),
            baseUrl = json.getString("baseUrl"),
            model = json.getString("model"),
            apiKey = json.getString("apiKey"),
            timeoutSeconds = json.getInt("timeoutSeconds"),
            maxOutputTokens = json.getInt("maxOutputTokens"),
        ).also { it.validate() }
    }

    fun save(settings: ProviderSettings) {
        settings.validate()
        val json = JSONObject()
            .put("preset", settings.preset.name)
            .put("baseUrl", settings.baseUrl.trim())
            .put("model", settings.model.trim())
            .put("apiKey", settings.apiKey.trim())
            .put("timeoutSeconds", settings.timeoutSeconds)
            .put("maxOutputTokens", settings.maxOutputTokens)
        record.write(json.toString())
    }

    fun clear() = record.clear()
}

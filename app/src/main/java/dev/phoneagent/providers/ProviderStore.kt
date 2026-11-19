package dev.phoneagent.providers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedModel(
    val id: String,
    val title: String = id,
    val maxOutputTokens: Int = DEFAULT_OUTPUT_TOKENS,
    val reasoning: ReasoningEffort = ReasoningEffort.DEFAULT,
    val supportsReasoning: Boolean? = null,
    val maxCompletionTokens: Int? = null,
) {
    val outputLimit: Int get() = minOf(maxCompletionTokens ?: OUTPUT_TOKEN_RANGE.last, OUTPUT_TOKEN_RANGE.last)
}

internal data class SavedConnection(
    val connection: ProviderSettings,
    val models: List<SavedModel>,
) {
    fun settings(id: String): ProviderSettings {
        val model = models.first { it.id == id }
        return connection.copy(model = id, maxOutputTokens = model.maxOutputTokens, reasoning = model.reasoning)
    }
}

internal data class ProviderLibrary(
    val active: ProviderPreset? = null,
    val connections: List<SavedConnection> = emptyList(),
) {
    fun connection(preset: ProviderPreset): SavedConnection? = connections.firstOrNull { it.connection.preset == preset }

    val selected: ProviderSettings?
        get() = active?.let(::connection)?.let { it.settings(it.connection.model) }
}

class ProviderStore internal constructor(context: Context, slot: String) {
    constructor(context: Context) : this(context, "default")

    private val record = EncryptedRecord(
        context,
        fileName = "provider-settings.$slot.enc",
        keyAlias = "phone-agent.provider-settings.v1.$slot",
        maxRecordBytes = 4 * 1024 * 1024,
    )
    internal val storageFile get() = record.storageFile

    fun load(): ProviderSettings = library().selected ?: ProviderSettings()

    internal fun library(): ProviderLibrary = synchronized(lock) {
        record.read()?.let(::decodeLibrary) ?: ProviderLibrary()
    }

    fun save(settings: ProviderSettings, catalog: List<ProviderModel> = emptyList()) = synchronized(lock) {
        settings.validate()
        val previous = library()
        val id = settings.model.trim()
        val models = previous.connection(settings.preset)
            ?.takeIf { it.connection.endpoint() == settings.endpoint() }?.models.orEmpty()
        val model = models.firstOrNull { it.id == id } ?: SavedModel(id)
        val catalogModel = catalog.firstOrNull { it.id == id }
        val cap = catalogModel?.maxCompletionTokens
        val requestedTokens = if (model !in models && cap != null) minOf(cap, OUTPUT_TOKEN_RANGE.last)
        else settings.maxOutputTokens
        val updated = model.copy(maxOutputTokens = requestedTokens, reasoning = settings.reasoning)
        val connection = SavedConnection(
            settings.copy(model = id, maxOutputTokens = DEFAULT_OUTPUT_TOKENS, reasoning = ReasoningEffort.DEFAULT),
            models.withModel(updated).withCatalog(catalog, settings.maxOutputTokens),
        )
        write(previous.replacing(connection, activate = true))
    }

    internal fun importModels(connection: ProviderSettings, catalog: List<ProviderModel>): ProviderLibrary =
        mutateConnection(connection) { saved ->
            val tokens = saved.settings(saved.connection.model).maxOutputTokens
            saved.copy(models = saved.models.withCatalog(catalog, tokens))
        }

    internal fun select(connection: ProviderSettings, model: SavedModel): ProviderLibrary =
        mutateConnection(connection, activate = true) { saved ->
            val id = model.id.trim()
            val existing = saved.models.firstOrNull { it.id == id }
            val updated = (existing ?: model).copy(
                id = id,
                maxOutputTokens = minOf(model.maxOutputTokens, existing?.outputLimit ?: model.outputLimit),
                reasoning = model.reasoning,
            )
            saved.connection.copy(model = id, maxOutputTokens = updated.maxOutputTokens).validate()
            saved.copy(connection = saved.connection.copy(model = id), models = saved.models.withModel(updated))
        }

    internal fun remove(preset: ProviderPreset) = synchronized(lock) {
        val previous = library()
        val remaining = previous.connections.filterNot { it.connection.preset == preset }
        if (remaining.isEmpty()) record.clear()
        else write(ProviderLibrary(previous.active.takeUnless { it == preset } ?: remaining.first().connection.preset, remaining))
    }

    fun clear() = synchronized(lock) { record.clear() }

    private fun mutateConnection(
        connection: ProviderSettings,
        activate: Boolean = false,
        update: (SavedConnection) -> SavedConnection,
    ): ProviderLibrary = synchronized(lock) {
        val previous = library()
        val saved = previous.connection(connection.preset)
            ?.takeIf { it.connection.endpoint() == connection.endpoint() }
            ?: error("This provider connection changed. Open the model list again.")
        previous.replacing(update(saved), activate).also(::write)
    }

    private fun write(library: ProviderLibrary) {
        val data = library.connections.map { (connection, models) ->
            JSONObject().put("preset", connection.preset.name)
                .put("baseUrl", connection.baseUrl.trim())
                .put("apiKey", connection.apiKey.trim())
                .put("model", connection.model)
                .put("models", JSONArray(models.map { model ->
                    JSONObject().put("id", model.id).put("title", model.title)
                        .put("maxOutputTokens", model.maxOutputTokens)
                        .put("reasoning", model.reasoning.name).put("supportsReasoning", model.supportsReasoning)
                        .put("maxCompletionTokens", model.maxCompletionTokens)
                }))
        }
        record.write(JSONObject().put("active", library.active?.name).put("connections", JSONArray(data)).toString())
    }

    private companion object {
        val lock = Any()
    }
}

private fun List<SavedModel>.withModel(model: SavedModel): List<SavedModel> =
    if (none { it.id == model.id }) this + model else map { if (it.id == model.id) model else it }

private fun List<SavedModel>.withCatalog(catalog: List<ProviderModel>, defaultTokens: Int): List<SavedModel> {
    if (catalog.isEmpty()) return this
    val merged = associateBy(SavedModel::id).toMutableMap()
    for (entry in catalog) {
        val previous = merged[entry.id]
        val cap = entry.maxCompletionTokens ?: previous?.maxCompletionTokens
        val limit = minOf(cap ?: OUTPUT_TOKEN_RANGE.last, OUTPUT_TOKEN_RANGE.last)
        val tokens = previous?.maxOutputTokens ?: if (cap == null) defaultTokens else limit
        merged[entry.id] = SavedModel(
            entry.id, entry.title, minOf(tokens, limit), previous?.reasoning ?: ReasoningEffort.DEFAULT,
            entry.supportsReasoning ?: previous?.supportsReasoning, cap,
        )
    }
    return merged.values.toList()
}

private fun ProviderLibrary.replacing(connection: SavedConnection, activate: Boolean): ProviderLibrary {
    val preset = connection.connection.preset
    val updated = if (connection(preset) == null) connections + connection
    else connections.map { if (it.connection.preset == preset) connection else it }
    return ProviderLibrary(if (activate) preset else active, updated)
}

internal fun decodeLibrary(saved: String): ProviderLibrary {
    val json = JSONObject(saved)
    val connections = json.optJSONArray("connections")
    if (connections == null) {
        val old = decodeConnection(json)
        val model = SavedModel(old.model, maxOutputTokens = json.optInt("maxOutputTokens", DEFAULT_OUTPUT_TOKENS))
        return ProviderLibrary(old.preset, listOf(SavedConnection(old, listOf(model))))
    }
    val decoded = List(connections.length()) { index ->
        val item = connections.getJSONObject(index)
        val connection = decodeConnection(item)
        val rawModels = item.getJSONArray("models")
        val models = List(rawModels.length()) { modelIndex ->
            val model = rawModels.getJSONObject(modelIndex)
            SavedModel(
                id = model.getString("id"),
                title = model.getString("title"),
                maxOutputTokens = model.getInt("maxOutputTokens"),
                reasoning = ReasoningEffort.valueOf(model.getString("reasoning")),
                supportsReasoning = model.opt("supportsReasoning") as? Boolean,
                maxCompletionTokens = model.optInt("maxCompletionTokens").takeIf { it >= OUTPUT_TOKEN_RANGE.first },
            )
        }
        SavedConnection(connection, models)
    }
    return ProviderLibrary(ProviderPreset.valueOf(json.getString("active")), decoded)
}

private fun decodeConnection(json: JSONObject): ProviderSettings = ProviderSettings(
    preset = ProviderPreset.valueOf(json.getString("preset")),
    baseUrl = json.getString("baseUrl"),
    model = json.getString("model"),
    apiKey = json.getString("apiKey"),
).also { it.validate() }

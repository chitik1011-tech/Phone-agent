package dev.phoneagent.providers

private val standardEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)

internal fun reasoningLevels(
    settings: ProviderSettings,
    model: SavedModel,
    hasTools: Boolean,
): List<ReasoningEffort> {
    val host = settings.endpoint().host
    val id = model.id.substringAfterLast('/').removePrefix("models/").lowercase()
    val levels = when {
        model.supportsReasoning == false || host == "api.anthropic.com" -> emptyList()
        host == "api.deepseek.com" -> if (hasTools) emptyList()
        else listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX)
        host == "generativelanguage.googleapis.com" -> when {
            id.startsWith("gemini-4-neon") || id.startsWith("gemini-4-hydrogen") ->
                standardEfforts + ReasoningEffort.EXTRA_HIGH
            id.startsWith("gemini-3") && "pro" in id -> listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH)
            id.startsWith("gemini-2.5") || id.startsWith("gemini-3") || id.startsWith("gemini-4") -> standardEfforts
            else -> emptyList()
        }
        host == "api.openai.com" -> when {
            id.startsWith("gpt-5.2") || id.startsWith("gpt-5.3") || id.startsWith("gpt-5.4") ||
                id.startsWith("gpt-5.5") || id.startsWith("gpt-5.6") -> standardEfforts + ReasoningEffort.EXTRA_HIGH
            id.startsWith("gpt-5") || id.startsWith("o3") || id.startsWith("o4") -> standardEfforts
            else -> emptyList()
        }
        host == "openrouter.ai" -> standardEfforts + ReasoningEffort.EXTRA_HIGH
        else -> standardEfforts
    }
    return listOf(ReasoningEffort.DEFAULT) + levels
}

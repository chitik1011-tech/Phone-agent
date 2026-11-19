package dev.phoneagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.phoneagent.access.AccessDiagnostics
import dev.phoneagent.agent.AgentMode
import dev.phoneagent.ui.AppTheme
import dev.phoneagent.ui.MonoPanel
import dev.phoneagent.ui.ScreenTitle
import kotlin.math.roundToInt

@Composable
internal fun SettingsContent(
    page: String,
    prefs: AppPreferences,
    revision: Int,
    onChanged: () -> Unit,
    openProviders: () -> Unit,
    openAgents: () -> Unit,
    openPermissions: () -> Unit,
    openMcp: () -> Unit,
    back: () -> Unit,
    openTerminal: () -> Unit,
    requestNotifications: () -> Unit,
    requestLanguage: (String) -> Unit,
    languageTag: String,
) {
    val labels = LocalContext.current
    val appTheme = remember(revision) { prefs.theme }
    val currentMode = remember(revision) { prefs.mode }
    val haptics = remember(revision) { prefs.haptics }
    val animations = remember(revision) { prefs.animations }
    val compact = remember(revision) { prefs.compact }
    val maxSubagents = remember(revision) { prefs.subagents }
    val maxSteps = remember(revision) { prefs.steps }
    val contextSize = remember(revision) { prefs.contextChars }
    val promptProfile = remember(revision) { prefs.promptProfile }
    var prompt by remember { mutableStateOf(prefs.prompt) }
    var reviewerModel by remember { mutableStateOf(prefs.reviewerModel) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (page != "MAIN") TextButton(onClick = back) { Text(labels.getString(R.string.all_settings)) }
        when (page) {
            "AGENTS" -> {
                ScreenTitle(labels.getString(R.string.agent_preferences), labels.getString(R.string.agent_tool_limits))
                MonoPanel {
                    Text(labels.getString(R.string.autonomy), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mode == currentMode,
                                onClick = { prefs.mode = mode; onChanged() },
                                label = { Text(labels.getString(when (mode) {
                                    AgentMode.ASK -> R.string.agent_mode_ask
                                    AgentMode.AGENT -> R.string.agent_mode_agent
                                    AgentMode.AUTONOMOUS -> R.string.agent_mode_autonomous
                                    AgentMode.READ_ONLY -> R.string.agent_mode_read_only
                                })) },
                            )
                        }
                    }
                    Text(when (currentMode) {
                        AgentMode.ASK -> labels.getString(R.string.explain_ask)
                        AgentMode.AGENT -> labels.getString(R.string.explain_agent)
                        AgentMode.AUTONOMOUS -> labels.getString(R.string.explain_auto)
                        AgentMode.READ_ONLY -> labels.getString(R.string.explain_read_only)
                    })
                    Text(labels.getString(R.string.review_limit, maxSubagents))
                    Slider(
                        value = maxSubagents.toFloat(), valueRange = 0f..8f, steps = 7,
                        onValueChange = { prefs.subagents = it.roundToInt(); onChanged() },
                    )
                    OutlinedTextField(
                        value = reviewerModel,
                        onValueChange = { reviewerModel = it.take(200); prefs.reviewerModel = reviewerModel },
                        label = { Text(labels.getString(R.string.reviewer_model)) },
                        supportingText = { Text(labels.getString(R.string.reviewer_model_help)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(labels.getString(R.string.turn_limit, maxSteps))
                    Slider(
                        value = maxSteps.toFloat(), valueRange = 2f..40f, steps = 37,
                        onValueChange = { prefs.steps = it.roundToInt(); onChanged() },
                    )
                }
                MonoPanel {
                    Text(labels.getString(R.string.context_memory), style = MaterialTheme.typography.titleMedium)
                    Text(labels.getString(R.string.context_target, contextSize / 1000))
                    Slider(
                        value = contextSize.toFloat(), valueRange = 16_000f..100_000f,
                        onValueChange = { prefs.contextChars = it.roundToInt(); onChanged() },
                    )
                    Text(labels.getString(R.string.auto_compact))
                    Switch(checked = compact, onCheckedChange = { prefs.compact = it; onChanged() })
                    Text(labels.getString(R.string.prompt_profile))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PromptProfile.entries.forEach { entry ->
                            FilterChip(
                                selected = entry == promptProfile,
                                onClick = { prefs.promptProfile = entry; onChanged() },
                                label = { Text(labels.getString(entry.labelResource)) },
                            )
                        }
                    }
                    if (promptProfile.instruction.isNotBlank()) Text(promptProfile.instruction)
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it.take(12_000); prefs.prompt = prompt },
                        label = { Text(labels.getString(R.string.custom_instructions)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            }
            "PERMISSIONS" -> {
                ScreenTitle(labels.getString(R.string.permissions), labels.getString(R.string.permission_help))
                MonoPanel {
                    Text(labels.getString(R.string.normal_access), style = MaterialTheme.typography.titleLarge)
                    Text(labels.getString(R.string.normal_access_help))
                    OutlinedButton(onClick = openTerminal) { Text(labels.getString(R.string.termux_settings)) }
                }
                AccessDiagnostics()
            }
            else -> {
                ScreenTitle(labels.getString(R.string.nav_settings), labels.getString(R.string.settings_hint))
                MonoPanel {
                    Text(labels.getString(R.string.language), style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "" to R.string.language_system,
                            "en" to R.string.language_english,
                            "ru" to R.string.language_russian,
                        ).forEach { (tag, label) ->
                            FilterChip(
                                selected = languageTag == tag,
                                onClick = { if (languageTag != tag) requestLanguage(tag) },
                                label = { Text(labels.getString(label)) },
                            )
                        }
                    }
                }
                MonoPanel {
                    Text(labels.getString(R.string.ai_models), style = MaterialTheme.typography.titleLarge)
                    Text(labels.getString(R.string.provider_setup_help))
                    OutlinedButton(onClick = openProviders) { Text(labels.getString(R.string.manage_providers)) }
                }
                MonoPanel {
                    Text(labels.getString(R.string.agents_context), style = MaterialTheme.typography.titleLarge)
                    Text(labels.getString(R.string.agents_help))
                    OutlinedButton(onClick = openAgents) { Text(labels.getString(R.string.agent_preferences)) }
                }
                MonoPanel {
                    Text(labels.getString(R.string.terminal_permissions), style = MaterialTheme.typography.titleLarge)
                    Text(labels.getString(R.string.terminal_permissions_help))
                    OutlinedButton(onClick = openPermissions) { Text(labels.getString(R.string.permissions)) }
                }
                MonoPanel {
                    Text(labels.getString(R.string.background), style = MaterialTheme.typography.titleLarge)
                    Text(labels.getString(R.string.background_help))
                    OutlinedButton(onClick = requestNotifications) { Text(labels.getString(R.string.enable_notifications)) }
                }
                MonoPanel {
                    Text(labels.getString(R.string.advanced), style = MaterialTheme.typography.titleLarge)
                    Text(labels.getString(R.string.advanced_help))
                    OutlinedButton(onClick = openMcp) { Text(labels.getString(R.string.mcp_servers)) }
                }
                MonoPanel {
                    Text(labels.getString(R.string.appearance), style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = theme == appTheme,
                                onClick = { prefs.theme = theme; onChanged() },
                                label = { Text(when (theme) {
                                    AppTheme.SYSTEM -> labels.getString(R.string.theme_system)
                                    AppTheme.OLED -> labels.getString(R.string.theme_oled)
                                    AppTheme.LIGHT -> labels.getString(R.string.theme_light)
                                }) },
                            )
                        }
                    }
                    Text(labels.getString(R.string.navigation_animations))
                    Switch(checked = animations, onCheckedChange = { prefs.animations = it; onChanged() })
                    Text(labels.getString(R.string.navigation_haptics))
                    Switch(checked = haptics, onCheckedChange = { prefs.haptics = it; onChanged() })
                }
            }
        }
    }
}

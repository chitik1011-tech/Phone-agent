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
) {
    val appTheme = remember(revision) { prefs.theme }
    val currentMode = remember(revision) { prefs.mode }
    val haptics = remember(revision) { prefs.haptics }
    val animations = remember(revision) { prefs.animations }
    val compact = remember(revision) { prefs.compact }
    val maxSubagents = remember(revision) { prefs.subagents }
    val maxSteps = remember(revision) { prefs.steps }
    val contextSize = remember(revision) { prefs.contextChars }
    var prompt by remember { mutableStateOf(prefs.prompt) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (page != "MAIN") TextButton(onClick = back) { Text("All settings") }
        when (page) {
            "AGENTS" -> {
                ScreenTitle("Agent preferences", "Choose limits and how each task can use tools.")
                MonoPanel {
                    Text("Autonomy", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgentMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mode == currentMode,
                                onClick = { prefs.mode = mode; onChanged() },
                                label = { Text(mode.name.replace('_', ' ')) },
                            )
                        }
                    }
                    Text(when (currentMode) {
                        AgentMode.ASK -> "Review file edits and shell commands before they run."
                        AgentMode.AGENT -> "Allow file edits in the selected project and review each shell command."
                        AgentMode.AUTONOMOUS -> "Allow project edits and independent reviewers. Each shell command needs approval."
                        AgentMode.READ_ONLY -> "Only list and read project files."
                    })
                    Text("Maximum review subagents · $maxSubagents")
                    Slider(
                        value = maxSubagents.toFloat(), valueRange = 0f..8f, steps = 7,
                        onValueChange = { prefs.subagents = it.roundToInt(); onChanged() },
                    )
                    Text("Maximum model turns · $maxSteps")
                    Slider(
                        value = maxSteps.toFloat(), valueRange = 2f..40f, steps = 37,
                        onValueChange = { prefs.steps = it.roundToInt(); onChanged() },
                    )
                }
                MonoPanel {
                    Text("Context & memory", style = MaterialTheme.typography.titleMedium)
                    Text("Context target · ${contextSize / 1000}k characters")
                    Slider(
                        value = contextSize.toFloat(), valueRange = 16_000f..100_000f,
                        onValueChange = { prefs.contextChars = it.roundToInt(); onChanged() },
                    )
                    Text("Automatic context compression")
                    Switch(checked = compact, onCheckedChange = { prefs.compact = it; onChanged() })
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it.take(12_000); prefs.prompt = prompt },
                        label = { Text("Custom system instructions") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            }
            "PERMISSIONS" -> {
                ScreenTitle("Permissions", "Manage Android folder access and Termux.")
                MonoPanel {
                    Text("Normal access", style = MaterialTheme.typography.titleLarge)
                    Text("Open a folder to grant project access through Android. Termux requests a separate run-command permission.")
                    OutlinedButton(onClick = openTerminal) { Text("Termux settings") }
                }
                AccessDiagnostics()
            }
            else -> {
                ScreenTitle("Settings", "Configure your workspace.")
                MonoPanel {
                    Text("AI & models", style = MaterialTheme.typography.titleLarge)
                    Text("Connect a hosted or local endpoint and select a model.")
                    OutlinedButton(onClick = openProviders) { Text("Manage providers") }
                }
                MonoPanel {
                    Text("Agents & context", style = MaterialTheme.typography.titleLarge)
                    Text("Task modes, model turns, reviewers and system instructions.")
                    OutlinedButton(onClick = openAgents) { Text("Agent preferences") }
                }
                MonoPanel {
                    Text("Terminal & permissions", style = MaterialTheme.typography.titleLarge)
                    Text("Connect Termux and manage project access.")
                    OutlinedButton(onClick = openPermissions) { Text("Permissions") }
                }
                MonoPanel {
                    Text("Background", style = MaterialTheme.typography.titleLarge)
                    Text("Notifications keep active tasks visible when you leave the app.")
                    OutlinedButton(onClick = requestNotifications) { Text("Enable notifications") }
                }
                MonoPanel {
                    Text("Advanced", style = MaterialTheme.typography.titleLarge)
                    Text("Connect MCP servers, browse tools, and approve individual calls.")
                    OutlinedButton(onClick = openMcp) { Text("MCP servers") }
                }
                MonoPanel {
                    Text("Appearance", style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = theme == appTheme,
                                onClick = { prefs.theme = theme; onChanged() },
                                label = { Text(when (theme) {
                                    AppTheme.SYSTEM -> "System"
                                    AppTheme.OLED -> "OLED black"
                                    AppTheme.LIGHT -> "Light"
                                }) },
                            )
                        }
                    }
                    Text("Navigation animations")
                    Switch(checked = animations, onCheckedChange = { prefs.animations = it; onChanged() })
                    Text("Navigation haptics")
                    Switch(checked = haptics, onCheckedChange = { prefs.haptics = it; onChanged() })
                }
            }
        }
    }
}

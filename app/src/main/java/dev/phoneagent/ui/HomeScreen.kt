package dev.phoneagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    projectName: String?,
    termuxStatus: String,
    providerStatus: String,
    onOpenProject: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenProviders: () -> Unit,
    onNewTask: () -> Unit,
    onStartOnboarding: (() -> Unit)? = null,
    recentTaskTitle: String? = null,
    recentTaskStatus: String? = null,
    onOpenAgents: (() -> Unit)? = null,
) {
    ScrollableScreen {
        ScreenTitle("Phone Agent", "Android development workspace")
        MonoPanel {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(projectName ?: "Open a project", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (projectName == null) {
                        "Choose a project folder to edit sources and work with an agent."
                    } else {
                        "Continue editing or give the agent a task."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = onOpenProject) {
                    Text(if (projectName == null) "Choose folder" else "Open workspace")
                }
                if (projectName != null) {
                    OutlinedButton(onClick = onNewTask) { Text("New agent task") }
                }
            }
        }
        if (recentTaskTitle != null) {
            SettingGroup("Latest task") {
                SettingRow(
                    title = recentTaskTitle,
                    description = recentTaskStatus,
                    onClick = onOpenAgents,
                    leading = { DestinationIcon(Destination.AGENTS) },
                )
            }
        }
        SettingGroup("Connections") {
            SettingRow(
                "Termux",
                termuxStatus,
                onClick = onOpenTerminal,
                leading = { DestinationIcon(Destination.TERMINAL) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                "Model provider",
                providerStatus,
                onClick = onOpenProviders,
                leading = { DestinationIcon(Destination.SETTINGS) },
            )
        }
        if (onStartOnboarding != null) {
            TextButton(onClick = onStartOnboarding) { Text("Setup guide") }
        }
    }
}

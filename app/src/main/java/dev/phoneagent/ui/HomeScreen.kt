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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.phoneagent.R

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
    val labels = LocalContext.current
    ScrollableScreen {
        ScreenTitle(labels.getString(R.string.about_app), labels.getString(R.string.android_workspace))
        MonoPanel {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(projectName ?: labels.getString(R.string.open_a_project), style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (projectName == null) {
                        labels.getString(R.string.home_project_empty)
                    } else {
                        labels.getString(R.string.home_project_ready)
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
                    Text(if (projectName == null) labels.getString(R.string.choose_folder) else labels.getString(R.string.open_workspace))
                }
                if (projectName != null) {
                    OutlinedButton(onClick = onNewTask) { Text(labels.getString(R.string.new_agent_task)) }
                }
            }
        }
        if (recentTaskTitle != null) {
            SettingGroup(labels.getString(R.string.latest_task)) {
                SettingRow(
                    title = recentTaskTitle,
                    description = recentTaskStatus,
                    onClick = onOpenAgents,
                    leading = { DestinationIcon(Destination.AGENTS) },
                )
            }
        }
        SettingGroup(labels.getString(R.string.connections)) {
            SettingRow(
                "Termux",
                termuxStatus,
                onClick = onOpenTerminal,
                leading = { DestinationIcon(Destination.TERMINAL) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(
                labels.getString(R.string.model_provider),
                providerStatus,
                onClick = onOpenProviders,
                leading = { DestinationIcon(Destination.SETTINGS) },
            )
        }
        if (onStartOnboarding != null) {
            TextButton(onClick = onStartOnboarding) { Text(labels.getString(R.string.setup_guide)) }
        }
    }
}

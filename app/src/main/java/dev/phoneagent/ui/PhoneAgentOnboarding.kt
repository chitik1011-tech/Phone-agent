package dev.phoneagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class SetupStep(val title: String, val description: String) {
    WELCOME("Phone Agent", "Your AI-powered Android IDE."),
    PROVIDER(
        "Connect a model",
        "Choose an endpoint and model for agent requests. You can continue setup and configure a provider later.",
    ),
    TERMUX(
        "Connect Termux",
        "Grant command access in Android, then set allow-external-apps=true in Termux and test the connection.",
    ),
    PERMISSION(
        "Project access",
        "Normal mode uses the Android folder picker to give the editor access to the project you choose.",
    ),
    AGENTS(
        "Agent preferences",
        "Review available execution modes and configure how the agent can use project tools.",
    ),
    PROJECT(
        "Open your first project",
        "Select a project folder to inspect its source files and start a task.",
    ),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneAgentOnboarding(
    onFinish: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenTermux: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenPermissions: (() -> Unit)? = null,
    onOpenAgentPreferences: (() -> Unit)? = null,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val step = SetupStep.entries[page]
    val lastPage = SetupStep.entries.lastIndex
    val action = when (step) {
        SetupStep.WELCOME -> null
        SetupStep.PROVIDER -> "Open model settings" to onOpenProviders
        SetupStep.TERMUX -> "Open terminal setup" to onOpenTermux
        SetupStep.PERMISSION -> onOpenPermissions?.let { "Open permissions" to it }
        SetupStep.AGENTS -> onOpenAgentPreferences?.let { "Open agent settings" to it }
        SetupStep.PROJECT -> "Choose folder" to onOpenProject
    }
    BackHandler(enabled = page > 0) { page-- }
    ScrollableScreen(
        Modifier.background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LinearProgressIndicator(
            progress = { (page + 1f) / SetupStep.entries.size },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
            label = "setting up Phone Agent",
        ) { activeStep ->
            ScreenTitle(activeStep.title, activeStep.description)
        }
        if (action != null) {
            OutlinedButton(onClick = action.second) { Text(action.first) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = { if (page == lastPage) onFinish() else page++ }) {
                Text(if (page == lastPage) "Open IDE" else "Continue")
            }
            if (page == 0) {
                TextButton(onClick = onFinish) { Text("Skip setup") }
            } else {
                TextButton(onClick = { page-- }) { Text("Back") }
            }
        }
    }
}

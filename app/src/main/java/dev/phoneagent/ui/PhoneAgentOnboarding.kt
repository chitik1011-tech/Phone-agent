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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.phoneagent.R

private enum class SetupStep(val title: Int, val description: Int) {
    WELCOME(R.string.about_app, R.string.setup_welcome),
    PROVIDER(
        R.string.setup_model,
        R.string.setup_model_hint,
    ),
    TERMUX(
        R.string.setup_termux,
        R.string.setup_termux_hint,
    ),
    PERMISSION(
        R.string.setup_access,
        R.string.setup_access_hint,
    ),
    AGENTS(
        R.string.agent_preferences,
        R.string.setup_agents_hint,
    ),
    PROJECT(
        R.string.setup_project,
        R.string.setup_project_hint,
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
    val labels = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    val step = SetupStep.entries[page]
    val lastPage = SetupStep.entries.lastIndex
    val action = when (step) {
        SetupStep.WELCOME -> null
        SetupStep.PROVIDER -> labels.getString(R.string.open_model_settings) to onOpenProviders
        SetupStep.TERMUX -> labels.getString(R.string.open_terminal_setup) to onOpenTermux
        SetupStep.PERMISSION -> onOpenPermissions?.let { labels.getString(R.string.open_permissions) to it }
        SetupStep.AGENTS -> onOpenAgentPreferences?.let { labels.getString(R.string.open_agent_settings) to it }
        SetupStep.PROJECT -> labels.getString(R.string.choose_folder) to onOpenProject
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
            ScreenTitle(labels.getString(activeStep.title), labels.getString(activeStep.description))
        }
        if (action != null) {
            OutlinedButton(onClick = action.second) { Text(action.first) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = { if (page == lastPage) onFinish() else page++ }) {
                Text(if (page == lastPage) labels.getString(R.string.open_ide) else labels.getString(R.string.continue_setup))
            }
            if (page == 0) {
                TextButton(onClick = onFinish) { Text(labels.getString(R.string.skip_setup)) }
            } else {
                TextButton(onClick = { page-- }) { Text(labels.getString(R.string.back)) }
            }
        }
    }
}

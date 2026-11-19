package dev.phoneagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.phoneagent.termux.TermuxRuntime
import dev.phoneagent.workspace.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URI

private const val TERMUX_HOME = "/data/data/com.termux/files/home"

@Composable
internal fun ProjectCreation(
    runtime: TermuxRuntime,
    clone: Boolean,
    onDismiss: () -> Unit,
    onCreated: (WorkspaceRef) -> Unit,
) {
    val labels = LocalContext.current
    var name by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (clone) labels.getString(R.string.clone_repository) else labels.getString(R.string.new_termux_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (clone) {
                    OutlinedTextField(
                        value = remote, onValueChange = { remote = it },
                        label = { Text(labels.getString(R.string.git_clone_url)) },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(labels.getString(R.string.termux_new_folder)) },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                )
                if (clone) {
                    TextButton(enabled = !busy, onClick = {
                        runCatching { runtime.launchInteractiveCommand("gh auth login --hostname github.com --web") }
                            .onFailure { status = it.message ?: "Could not open GitHub CLI login." }
                    }) { Text(labels.getString(R.string.git_cli_signin)) }
                    Text(labels.getString(R.string.git_private_help), style = MaterialTheme.typography.bodySmall)
                }
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && name.isNotBlank() && (!clone || remote.isNotBlank()),
                onClick = {
                    busy = true
                    status = ""
                    scope.launch {
                        try {
                            val directory = name.trim()
                            require(directory.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) {
                                "Use up to 64 letters, digits, dots, underscores or hyphens."
                            }
                            val command = if (clone) {
                                val uri = URI(remote.trim())
                                require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                                    uri.rawPath.orEmpty().length > 1
                                ) { "Use a repository HTTPS URL without credentials." }
                                val quoted = "'" + uri.toASCIIString().replace("'", "'\"'\"'") + "'"
                                "GIT_TERMINAL_PROMPT=0 git -c protocol.file.allow=never clone -- $quoted '$directory'"
                            } else {
                                "mkdir -- '$directory'"
                            }
                            val result = runtime.run(command, TERMUX_HOME)
                            check(result.error == null) { result.error.orEmpty() }
                            check(result.exitCode == 0) {
                                result.stderr.trim().takeLast(1500).ifBlank { "Termux exit code ${result.exitCode}." }
                            }
                            onCreated(termuxWorkspace("$TERMUX_HOME/$directory"))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            status = failure.message ?: "Could not create the project."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) labels.getString(R.string.working) else if (clone) labels.getString(R.string.clone) else labels.getString(R.string.create)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(labels.getString(R.string.cancel)) }
        },
    )
}

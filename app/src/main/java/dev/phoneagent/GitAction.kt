package dev.phoneagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

internal enum class GitAction(val label: String, val prompt: String? = null) {
    FETCH("Fetch"),
    PULL("Pull"),
    PUSH("Push"),
    BRANCH("New branch", "Branch name"),
    COMMIT("Commit staged", "Commit message");

    fun command(raw: String): String {
        val text = raw.trim()
        val quoted = "'" + text.replace("'", "'\"'\"'") + "'"
        val suffix = when (this) {
            FETCH -> "fetch"
            PULL -> "pull --ff-only"
            PUSH -> "push"
            BRANCH -> {
                require(text.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,119}"))) {
                    "Enter a branch name using letters, digits, dots, slashes, underscores or hyphens."
                }
                "switch -c $quoted"
            }
            COMMIT -> {
                require(text.length in 1..300 && text.none(Char::isISOControl)) { "Enter a short commit message." }
                "commit -m $quoted"
            }
        }
        return "GIT_TERMINAL_PROMPT=0 git -c core.fsmonitor=false --no-pager $suffix"
    }
}

@Composable
internal fun GitActionConfirmation(
    action: GitAction,
    location: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember(action) { mutableStateOf("") }
    var error by remember(action) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(location, style = MaterialTheme.typography.labelMedium)
                Text(when (action) {
                    GitAction.FETCH -> "Download objects and refs from the configured remote."
                    GitAction.PULL -> "Update the current branch only if it can fast-forward."
                    GitAction.PUSH -> "Send the current branch to its configured remote and upstream."
                    GitAction.BRANCH -> "Create and switch to a new local branch."
                    GitAction.COMMIT -> "Create a local commit containing the changes already staged in Git."
                })
                action.prompt?.let { label ->
                    OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(label) }, singleLine = true)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching { action.command(input) }
                    .onSuccess(onConfirm)
                    .onFailure { error = it.message ?: "Invalid Git arguments." }
            }) { Text(action.label) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

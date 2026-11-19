package dev.phoneagent

import androidx.compose.ui.platform.LocalContext
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

internal enum class GitAction(val labelResource: Int, val promptResource: Int? = null) {
    FETCH(R.string.git_fetch),
    PULL(R.string.git_pull),
    PUSH(R.string.git_push),
    BRANCH(R.string.git_branch, R.string.git_branch_name),
    COMMIT(R.string.git_commit, R.string.git_commit_message);

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
    val labels = LocalContext.current
    var input by remember(action) { mutableStateOf("") }
    var error by remember(action) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(labels.getString(action.labelResource)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(location, style = MaterialTheme.typography.labelMedium)
                Text(when (action) {
                    GitAction.FETCH -> labels.getString(R.string.git_explain_fetch)
                    GitAction.PULL -> labels.getString(R.string.git_explain_pull)
                    GitAction.PUSH -> labels.getString(R.string.git_explain_push)
                    GitAction.BRANCH -> labels.getString(R.string.git_explain_branch)
                    GitAction.COMMIT -> labels.getString(R.string.git_explain_commit)
                })
                action.promptResource?.let { label ->
                    OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(labels.getString(label)) }, singleLine = true)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching { action.command(input) }
                    .onSuccess(onConfirm)
                    .onFailure { error = it.message ?: "Invalid Git arguments." }
            }) { Text(labels.getString(action.labelResource)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(labels.getString(R.string.cancel)) } },
    )
}

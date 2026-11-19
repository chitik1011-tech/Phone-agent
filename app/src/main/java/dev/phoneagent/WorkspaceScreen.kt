package dev.phoneagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.phoneagent.ui.CodeEditor
import dev.phoneagent.ui.MonoPanel
import dev.phoneagent.ui.ScreenTitle
import dev.phoneagent.workspace.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class View { FILES, EDITOR, GIT }

@Composable
internal fun WorkspaceScreen(project: Workspace?, agentActive: Boolean, onChoose: () -> Unit) {
    if (project == null) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenTitle("Workspace")
            MonoPanel {
                Text("Choose a project folder to browse and edit files.")
                Button(onClick = onChoose) { Text("Open project") }
            }
        }
        return
    }
    val location = project.ref.location
    var view by rememberSaveable(location) { mutableStateOf(View.FILES) }
    var dir by rememberSaveable(location) { mutableStateOf("") }
    var opened by rememberSaveable(location) { mutableStateOf<String?>(null) }
    var original by rememberSaveable(location) { mutableStateOf("") }
    var draft by rememberSaveable(location) { mutableStateOf("") }
    var newFile by rememberSaveable(location) { mutableStateOf(false) }
    var name by rememberSaveable(location) { mutableStateOf("") }
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var entries by remember(location) { mutableStateOf(emptyList<String>()) }
    var loadingList by remember(location) { mutableStateOf(true) }
    var error by remember(location) { mutableStateOf("") }
    var busy by remember(location) { mutableStateOf(false) }
    var revision by remember(location) { mutableIntStateOf(0) }
    var gitOutput by remember(location) { mutableStateOf("") }
    var gitAction by remember(location) { mutableStateOf<GitAction?>(null) }
    var search by rememberSaveable(location) { mutableStateOf("") }
    var pendingNew by rememberSaveable(location) { mutableStateOf(false) }
    val dirty = opened != null && (draft != original || pendingNew)
    val scope = rememberCoroutineScope()

    fun guard(action: () -> Unit) {
        if (dirty) discard = action else action()
    }

    fun runWorkspace(action: suspend () -> Unit) {
        busy = true
        error = ""
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "The workspace operation failed."
            } finally {
                busy = false
            }
        }
    }

    fun openFile(path: String) = guard {
        runWorkspace {
            val content = project.read(path)
            opened = path
            original = content
            draft = content
            pendingNew = false
            view = View.EDITOR
        }
    }

    LaunchedEffect(project, dir, revision) {
        loadingList = true
        entries = emptyList()
        try {
            entries = project.list(dir.ifEmpty { "." }).lineSequence().filter(String::isNotBlank).toList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not list this folder."
        } finally {
            loadingList = false
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle(project.ref.title, if (project.ref.termux) "Termux project" else "Android folder") {
            TextButton(enabled = !busy, onClick = { guard(onChoose) }) { Text("Switch") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(View.FILES, View.EDITOR, View.GIT).forEach { item ->
                FilterChip(
                    selected = item == view,
                    onClick = { view = item; error = "" },
                    label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }
        if (error.isNotEmpty()) SelectionContainer { Text(error, color = MaterialTheme.colorScheme.error) }
        when (view) {
            View.FILES -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        enabled = dir.isNotBlank() && !busy,
                        onClick = { dir = dir.substringBeforeLast('/', ""); search = "" },
                    ) { Text("Parent") }
                    TextButton(onClick = { revision++ }) { Text("Refresh") }
                    TextButton(enabled = !agentActive && !busy, onClick = {
                        guard { name = ""; newFile = true }
                    }) { Text("New file") }
                }
                Text(dir.ifBlank { "." }, style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, singleLine = true,
                    label = { Text("Filter this folder") }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.weight(1f)) {
                    if (loadingList) item { Text("Loading files…") }
                    else if (entries.isEmpty()) item { Text("Folder is empty.") }
                    items(entries.filter { it.contains(search, ignoreCase = true) }) { entry ->
                        val directory = entry.endsWith('/')
                        val path = listOf(dir, entry.removeSuffix("/")).filter(String::isNotBlank).joinToString("/")
                        TextButton(
                            enabled = !busy,
                            onClick = { if (directory) { dir = path; search = "" } else openFile(path) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(entry, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
            View.EDITOR -> {
                if (opened == null) Text("Select a file in the Files tab.")
                else {
                    Text(opened.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    CodeEditor(
                        value = draft, onValueChange = { draft = it }, fileName = opened.orEmpty(),
                        modifier = Modifier.weight(1f).fillMaxWidth(), readOnly = agentActive || busy,
                    )
                    Row(
                        Modifier.padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(enabled = dirty && !agentActive && !busy, onClick = {
                            val file = requireNotNull(opened)
                            runWorkspace {
                                if (!pendingNew) {
                                    check(project.read(file) == original) {
                                        "The file changed on disk. Reopen it before saving."
                                    }
                                    if (original.isEmpty()) project.write(file, draft) else project.edit(file, original, draft)
                                } else {
                                    val parent = file.substringBeforeLast('/', ".")
                                    val existing = project.list(parent).lineSequence().map { it.removeSuffix("/") }
                                    check(file.substringAfterLast('/') !in existing) { "The file already exists." }
                                    project.write(file, draft)
                                }
                                original = draft
                                pendingNew = false
                                revision++
                            }
                        }) { Text("Save") }
                        if (dirty) TextButton(enabled = !busy, onClick = {
                            draft = original
                            if (pendingNew) { opened = null; pendingNew = false; view = View.FILES }
                        }) { Text("Discard") }
                    }
                }
            }
            View.GIT -> {
                if (!project.ref.termux) Text("Open the repository's Termux path to use Git.")
                else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Status", "Diff", "History", "Branches", "Worktrees").forEach { operation ->
                            OutlinedButton(
                                enabled = !busy, onClick = { runWorkspace { gitOutput = project.git(operation) } },
                            ) { Text(operation) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GitAction.entries.forEach { action ->
                            TextButton(enabled = !busy && !agentActive, onClick = { gitAction = action }) {
                                Text(action.label)
                            }
                        }
                    }
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(
                            gitOutput.ifBlank { "Choose a Git view." },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    gitAction?.let { action ->
        GitActionConfirmation(action, project.ref.location, onDismiss = { gitAction = null }) { command ->
            gitAction = null
            runWorkspace {
                gitOutput = project.command(command)
                revision++
            }
        }
    }

    if (newFile) {
        AlertDialog(
            onDismissRequest = { newFile = false },
            title = { Text("New file") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("File name") },
                )
            },
            confirmButton = {
                Button(enabled = name.isNotBlank() && '/' !in name, onClick = {
                    val path = listOf(dir, name.trim()).filter(String::isNotBlank).joinToString("/")
                    try {
                        dev.phoneagent.workspace.pathParts(path)
                        opened = path
                        original = ""
                        draft = ""
                        pendingNew = true
                        newFile = false
                        view = View.EDITOR
                    } catch (failure: IllegalArgumentException) {
                        error = failure.message.orEmpty()
                        newFile = false
                    }
                }) { Text("Create in editor") }
            },
            dismissButton = { TextButton(onClick = { newFile = false }) { Text("Cancel") } },
        )
    }

    discard?.let { action ->
        AlertDialog(
            onDismissRequest = { discard = null },
            title = { Text("Discard unsaved changes?") },
            confirmButton = {
                TextButton(onClick = {
                    draft = original
                    if (pendingNew) { opened = null; pendingNew = false }
                    discard = null
                    action()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { discard = null }) { Text("Keep editing") } },
        )
    }
}

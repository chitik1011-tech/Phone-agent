package dev.phoneagent.termux

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

@Composable
fun TerminalScreen(runtime: TermuxRuntime, defaultWorkDir: String = "") {
    val scope = rememberCoroutineScope()
    var installed by remember(runtime) { mutableStateOf(runtime.status()) }
    var directory by rememberSaveable(defaultWorkDir) { mutableStateOf(defaultWorkDir) }
    var bridge by remember(runtime) { mutableStateOf<BridgeClient?>(null) }
    var tabs by remember(runtime) { mutableStateOf<List<TerminalTab>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember(runtime) { mutableStateOf(false) }
    var message by remember(runtime) { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        installed = runtime.status()
        if (!it) message = "Termux permission was denied."
    }

    fun updateSessions(connection: BridgeClient, sessions: List<TerminalTab>) {
        bridge = connection
        tabs = sessions
        selected = selected?.takeIf { id -> sessions.any { it.id == id } } ?: sessions.firstOrNull()?.id
    }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                message = failure.message ?: "Termux operation failed."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(runtime, installed.permissionGranted) {
        if (bridge == null) {
            val existing = runtime.resumeBridge()
            if (existing != null) {
                try {
                    updateSessions(existing, existing.list())
                } catch (failure: IOException) {
                    message = failure.message ?: "Termux connection was lost."
                }
            }
        }
    }

    val connection = bridge
    val active = tabs.firstOrNull { it.id == selected }
    Column(Modifier.fillMaxSize()) {
        if (connection == null) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Termux", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when {
                        !installed.installed -> "Termux is not installed."
                        !installed.supportsPrivateCommandResults -> "Update Termux to 0.118 or later for private command results."
                        else -> "Installed • ${installed.versionName.orEmpty()}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Open Termux once. Set allow-external-apps=true in ~/.termux/termux.properties, run " +
                        "termux-reload-settings, and install Python with pkg install python. " +
                        "Connecting installs the bridge in Termux home.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        if (!runtime.launchTermux()) message = "Install Termux to continue."
                    }) { Text("Open Termux") }
                    TextButton(onClick = { installed = runtime.status() }) { Text("Refresh") }
                    if (installed.supportsPrivateCommandResults && !installed.permissionGranted) {
                        TextButton(onClick = { permission.launch(TermuxRuntime.PERMISSION) }) {
                            Text("Grant permission")
                        }
                    }
                }
                if (installed.supportsPrivateCommandResults && installed.permissionGranted) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(enabled = !busy, onClick = {
                            perform {
                                val newBridge = runtime.connectBridge()
                                updateSessions(newBridge, newBridge.list())
                            }
                        }) { Text(if (busy) "Connecting…" else "Connect") }
                        TextButton(enabled = !busy, onClick = {
                            perform {
                                val probe = runtime.probe()
                                message = probe.error ?: if (probe.exitCode == 0) {
                                    if (probe.stdout.lines().any { it.endsWith("/python3") }) {
                                        "RUN_COMMAND works and Python is available."
                                    } else {
                                        "RUN_COMMAND works. Install Python in Termux to connect."
                                    }
                                } else {
                                    probe.stderr.ifBlank { "Termux probe exited with ${probe.exitCode}." }
                                }
                            }
                        }) { Text("Test connection") }
                    }
                }
            }
        } else {
            if (active != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (tab in tabs) {
                        FilterChip(
                            selected = tab.id == selected,
                            onClick = { selected = tab.id; message = null },
                            label = { Text(tab.directory.substringAfterLast('/').ifEmpty { "/" }) },
                        )
                    }
                    TextButton(enabled = !busy, onClick = { selected = null }) { Text("New tab") }
                    TextButton(enabled = !busy, onClick = {
                        perform {
                            connection.close(active.id)
                            val updated = connection.list()
                            selected = null
                            updateSessions(connection, updated)
                        }
                    }) { Text("Close tab") }
                }
            } else {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tabs.isNotEmpty()) {
                        TextButton(onClick = { selected = tabs.first().id }) { Text("Back to terminal") }
                    }
                    OutlinedTextField(
                        value = directory,
                        onValueChange = { directory = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Termux directory") },
                        supportingText = { Text("Leave blank to open Termux home.") },
                        singleLine = true,
                    )
                    Button(enabled = !busy, onClick = {
                        perform {
                            val new = connection.open(directory.ifBlank { "" })
                            updateSessions(connection, connection.list())
                            selected = new.id
                        }
                    }) { Text("Open terminal") }
                }
            }
            if (active != null) {
                Text(
                    active.directory,
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
                key(active.id) {
                    TerminalPane(
                        client = connection,
                        tab = active,
                        onMessage = { message = it },
                        modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 180.dp),
                    )
                }
            }
        }
        message?.let {
            Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            if (connection != null) {
                TextButton(enabled = !busy, onClick = {
                    perform {
                        val newBridge = runtime.connectBridge()
                        updateSessions(newBridge, newBridge.list())
                    }
                }) { Text("Reconnect") }
            }
        }
    }
}

@Composable
private fun TerminalPane(
    client: BridgeClient,
    tab: TerminalTab,
    onMessage: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var view by remember { mutableStateOf<TerminalWebView?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var fontSize by rememberSaveable { mutableIntStateOf(14) }
    var selectedModifier by remember { mutableStateOf("") }
    val terminal = view
    val cursor = remember(terminal) { mutableLongStateOf(0L) }

    LaunchedEffect(terminal, fontSize) {
        if (terminal != null && withTimeoutOrNull(6_000) { terminal.ready.await() } != null) {
            terminal.font(fontSize)
        }
    }
    LaunchedEffect(terminal) {
        terminal?.modifier?.collect { selectedModifier = it }
    }
    LaunchedEffect(client, terminal, lifecycle) {
        if (terminal == null) return@LaunchedEffect
        if (withTimeoutOrNull(6_000) { terminal.ready.await() } == null) {
            onMessage("The terminal emulator did not load. Check Android System WebView.")
            return@LaunchedEffect
        }
        var ended = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (ended) return@repeatOnLifecycle
            coroutineScope {
                val refresh = Channel<Unit>(Channel.CONFLATED)
                val input = launch {
                    for (text in terminal.input) {
                        try {
                            client.write(tab.id, text)
                            refresh.trySend(Unit)
                        } catch (failure: IOException) {
                            onMessage(failure.message ?: "The terminal did not accept input.")
                        }
                    }
                }
                val resize = launch {
                    terminal.viewport.collect { size ->
                        if (size != null) {
                            try {
                                client.resize(tab.id, size.rows, size.columns)
                            } catch (failure: IOException) {
                                onMessage(failure.message ?: "Unable to resize the terminal.")
                            }
                        }
                    }
                }
                try {
                    var retries = 0
                    var wait = 120L
                    while (isActive) {
                        val chunk = try {
                            client.poll(tab.id, cursor.longValue)
                        } catch (failure: IOException) {
                            ended = (failure as? BridgeFailure)?.missing == true
                            if (ended || ++retries > 3) {
                                onMessage(failure.message ?: "Termux connection was lost.")
                                break
                            }
                            delay(400L * retries)
                            continue
                        }
                        retries = 0
                        if (chunk.dropped) {
                            terminal.resetTerminal()
                            onMessage("Earlier terminal output expired from the scrollback.")
                        }
                        cursor.longValue = chunk.offset
                        if (chunk.encodedBytes.isNotEmpty()) terminal.write(chunk.encodedBytes)
                        if (chunk.exitCode != null) {
                            ended = true
                            onMessage("Shell exited with code ${chunk.exitCode}.")
                            break
                        }
                        wait = if (chunk.encodedBytes.isEmpty()) (wait * 3 / 2).coerceAtMost(750) else 80
                        if (withTimeoutOrNull(wait) { refresh.receive() } != null) wait = 80
                    }
                } finally {
                    input.cancel()
                    resize.cancel()
                    refresh.cancel()
                }
            }
        }
    }

    Column(modifier) {
        AndroidView(
            factory = {
                TerminalWebView(it, onMessage).also { webView -> view = webView }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onRelease = { it.release() },
        )
        if (searching) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Find in terminal") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    terminal?.search(query) { if (!it) onMessage("No matching terminal output.") }
                }) { Text("Next") }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((label, code) in TERMINAL_KEYS) {
                TextButton(onClick = { terminal?.terminalKey(code) }) {
                    Text(if (selectedModifier == code) "$label ✓" else label)
                }
            }
            TextButton(onClick = {
                terminal?.copy { text -> clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal selection", text)) }
            }) { Text("Copy") }
            TextButton(onClick = {
                val item = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                item?.text?.let { terminal?.paste(it.toString()) }
            }) { Text("Paste") }
            TextButton(onClick = { searching = !searching }) { Text("Find") }
            TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(10) }) { Text("A−") }
            TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(26) }) { Text("A+") }
        }
    }
}

private val TERMINAL_KEYS = listOf(
    "Ctrl" to "Ctrl", "Alt" to "Alt", "Esc" to "Esc", "Tab" to "Tab", "↑" to "Up",
    "↓" to "Down", "←" to "Left", "→" to "Right", "Home" to "Home", "End" to "End",
    "^C" to "Interrupt",
)

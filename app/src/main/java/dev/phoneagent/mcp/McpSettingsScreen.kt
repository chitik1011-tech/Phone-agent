package dev.phoneagent.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun McpSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { McpStore(context) }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(emptyList<McpServer>()) }
    var draft by remember { mutableStateOf(McpServer()) }
    var connection by remember { mutableStateOf<DiscoveredConnection?>(null) }
    var status by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var showOutput by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var reveal by remember { mutableStateOf(false) }
    var editingTool by remember { mutableStateOf<McpTool?>(null) }
    var pending by remember { mutableStateOf<PendingToolCall?>(null) }
    var removing by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { reveal = false }

    fun update(server: McpServer) {
        draft = server
        connection = null
        status = ""
        output = ""
    }

    fun background(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                operation()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: McpException) {
                status = failure.message ?: "The MCP operation failed."
            } catch (failure: IllegalArgumentException) {
                status = failure.message ?: "Check the MCP settings."
            } catch (failure: IllegalStateException) {
                status = failure.message ?: "The MCP connection is unavailable."
            } catch (_: Exception) {
                status = "Could not complete the MCP operation."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(store) {
        try {
            saved = withContext(Dispatchers.IO) { store.all() }
            saved.firstOrNull()?.let { draft = it }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            status = "Could not open saved MCP servers."
        } finally {
            busy = false
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("MCP", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Connect a Streamable HTTP server using its bearer token. Discover tools and approve each manual call.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (server in saved) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            reveal = false
                            update(server)
                        },
                    ) { Text(if (server.id == draft.id) "${server.name} · selected" else server.name) }
                }
                if (saved.isNotEmpty()) TextButton(enabled = !busy, onClick = {
                    reveal = false
                    update(McpServer())
                }) { Text("Add server") }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { update(draft.copy(name = it.take(80))) },
                    enabled = !busy,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.url,
                    onValueChange = { update(draft.copy(url = it.take(2048), token = "")) },
                    enabled = !busy,
                    label = { Text("Streamable HTTP endpoint") },
                    supportingText = { Text("Enter the full endpoint URL. Remote servers require HTTPS.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.token,
                    onValueChange = { update(draft.copy(token = it.take(4096))) },
                    enabled = !busy,
                    label = { Text("Bearer token") },
                    trailingIcon = {
                        TextButton(enabled = !busy, onClick = { reveal = !reveal }) {
                            Text(if (reveal) "Hide" else "Show")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(
                        checked = draft.enabled,
                        enabled = !busy,
                        onCheckedChange = { update(draft.copy(enabled = it)) },
                    )
                    Text("Enabled")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            val candidate = draft
                            background {
                                saved = withContext(Dispatchers.IO) { store.save(candidate) }
                                draft = saved.first { it.id == candidate.id }
                                reveal = false
                                status = "MCP server saved."
                            }
                        },
                    ) { Text("Save server") }
                    if (saved.any { it.id == draft.id }) {
                        TextButton(enabled = !busy, onClick = { removing = true }) { Text("Remove") }
                    }
                }
                OutlinedButton(
                    enabled = !busy && draft.enabled,
                    onClick = {
                        val candidate = draft
                        background {
                            connection = null
                            output = ""
                            status = "Discovering tools…"
                            val client = McpClient(candidate)
                            val catalog = client.discover()
                            connection = DiscoveredConnection(candidate, client, catalog)
                            status = "${catalog.tools.size} tools · MCP ${catalog.protocolVersion}"
                            if (catalog.unavailableTools > 0) status += " · ${catalog.unavailableTools} invalid entries"
                        }
                    },
                ) { Text("Discover tools") }
                if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (output.isNotEmpty()) TextButton(onClick = { showOutput = true }) { Text("View result") }
            }
        }

        connection?.let { connected ->
            items(connected.catalog.tools, key = { it.name }) { tool ->
                val title = connected.server.redact(tool.title)
                val description = connected.server.redact(tool.description)
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { editingTool = tool }) { Text("Prepare call") }
                }
            }
        }
    }

    val tool = editingTool
    val connected = connection
    if (tool != null && connected != null) {
        ToolArgumentsDialog(
            tool = tool,
            server = connected.server,
            close = { editingTool = null },
            prepare = { arguments ->
                tool.argumentHeaders(arguments)
                editingTool = null
                pending = PendingToolCall(connected, tool, arguments)
            },
        )
    }

    pending?.let { request ->
        val server = request.connection.server
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Run ${server.redact(request.tool.name)}?") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("This sends these arguments to ${server.name} at ${server.endpoint().host}. The tool can change external data.")
                    Text(server.redact(request.arguments.toString(2)), fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    background {
                        output = ""
                        status = "Calling ${server.redact(request.tool.name)}…"
                        val result = request.connection.client.callOnce(
                            request.connection.catalog,
                            request.tool,
                            request.arguments,
                        )
                        val body = server.redact(result.toString(2))
                        output = body.take(MAX_DISPLAY_CHARS) + if (body.length > MAX_DISPLAY_CHARS) "\n[output truncated]" else ""
                        showOutput = true
                        status = if (result.optBoolean("isError")) "The tool reported an error." else "The tool returned a result."
                    }
                }) { Text("Run once") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    if (showOutput && output.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showOutput = false },
            title = { Text("Tool result") },
            text = {
                Text(
                    output,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { showOutput = false }) { Text("Close") } },
        )
    }

    if (removing) {
        val server = draft
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove this MCP server?") },
            text = { Text("This deletes its saved endpoint and bearer token from Phone Agent.") },
            confirmButton = {
                Button(onClick = {
                    removing = false
                    background {
                        saved = withContext(Dispatchers.IO) { store.remove(server.id) }
                        reveal = false
                        update(saved.firstOrNull() ?: McpServer())
                    }
                }) { Text("Remove server") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToolArgumentsDialog(
    tool: McpTool,
    server: McpServer,
    close: () -> Unit,
    prepare: (JSONObject) -> Unit,
) {
    var arguments by remember(tool) { mutableStateOf("{}") }
    var error by remember(tool) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(server.redact(tool.title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Input schema")
                Text(
                    server.redact(tool.schema.toString(2)).take(6000),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = arguments,
                    onValueChange = {
                        arguments = it.take(24 * 1024)
                        error = ""
                    },
                    label = { Text("JSON arguments") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    prepare(mcpArguments(arguments))
                } catch (failure: McpException) {
                    error = failure.message ?: "Check the tool arguments."
                }
            }) { Text("Review call") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

private data class DiscoveredConnection(val server: McpServer, val client: McpClient, val catalog: McpCatalog)
private data class PendingToolCall(val connection: DiscoveredConnection, val tool: McpTool, val arguments: JSONObject)

private fun McpServer.redact(value: String): String = value.replace(token.trim(), "[redacted]")
private const val MAX_DISPLAY_CHARS = 16_000

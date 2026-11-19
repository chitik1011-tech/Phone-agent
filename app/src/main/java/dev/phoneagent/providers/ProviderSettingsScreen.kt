package dev.phoneagent.providers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import java.io.IOException

@Composable
fun ProviderSettingsScreen(
    store: ProviderStore,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {},
    onOpenCodexTerminal: (() -> Unit)? = null,
    onCodexLogin: (() -> Unit)? = null,
    onCheckCodexStatus: (suspend () -> String)? = null,
    onOpenCodex: (() -> Unit)? = null,
) {
    val discovery = remember { ProviderDiscovery() }
    val scope = rememberCoroutineScope()
    var settings by remember(store) { mutableStateOf(ProviderSettings()) }
    var timeoutText by remember(store) { mutableStateOf("60") }
    var tokenText by remember(store) { mutableStateOf("4096") }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(emptyList<ProviderModel>()) }
    var showModels by remember { mutableStateOf(false) }
    var codexStatus by remember { mutableStateOf("") }
    var checkingCodex by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { showKey = false }

    fun resetCatalog() {
        models = emptyList()
        status = ""
    }

    fun currentSettings(): ProviderSettings = settings.copy(
        timeoutSeconds = timeoutText.toIntOrNull()
            ?: throw IllegalArgumentException("Enter the timeout in seconds."),
        maxOutputTokens = tokenText.toIntOrNull()
            ?: throw IllegalArgumentException("Enter the output token limit."),
    )

    LaunchedEffect(store) {
        try {
            settings = withContext(Dispatchers.IO) { store.load() }
            timeoutText = settings.timeoutSeconds.toString()
            tokenText = settings.maxOutputTokens.toString()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            status = "Could not open the saved connection. Remove it or save new settings."
        } finally {
            busy = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI & Models", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect the workspace agent to a provider or an OpenAI-compatible local server.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderPreset.entries.forEach { preset ->
                FilterChip(
                    selected = preset == settings.preset,
                    enabled = !busy,
                    onClick = {
                        if (preset != settings.preset) {
                            settings = settings.copy(
                                preset = preset,
                                baseUrl = preset.defaultBaseUrl,
                                model = "",
                                apiKey = "",
                            )
                            showKey = false
                            resetCatalog()
                        }
                    },
                    label = { Text(preset.label) },
                )
            }
        }
        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { url ->
                if (url != settings.baseUrl) {
                    settings = settings.copy(baseUrl = url.take(2048), apiKey = "")
                    showKey = false
                    resetCatalog()
                }
            },
            enabled = !busy,
            label = { Text("Base URL") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            supportingText = { Text("Use HTTPS for remote servers. Local servers can use loopback HTTP.") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.apiKey,
            onValueChange = {
                settings = settings.copy(apiKey = it.take(4096))
                resetCatalog()
            },
            enabled = !busy,
            label = { Text(if (settings.preset.keyRequired) "API key" else "API key (optional)") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }, enabled = !busy) {
                    Text(if (showKey) "Hide" else "Show")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.model,
            onValueChange = { settings = settings.copy(model = it.take(256)) },
            enabled = !busy,
            label = { Text("Model identifier") },
            supportingText = { Text("Choose a chat model with tool calling for workspace tasks.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                status = "Checking the provider…"
                scope.launch {
                    try {
                        models = discovery.models(currentSettings())
                        status = if (models.isEmpty()) "The provider returned an empty model catalog." else
                            "Connection verified. ${models.size} models available."
                        showModels = models.isNotEmpty()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: IllegalArgumentException) {
                        status = failure.message ?: "Check the connection settings."
                    } catch (failure: IOException) {
                        status = failure.message ?: "The connection check failed."
                    } catch (_: Exception) {
                        status = "Could not read the provider response."
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text("Check connection and list models")
        }
        if (models.isNotEmpty()) TextButton(onClick = { showModels = true }) { Text("Choose model") }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { timeoutText = it.filter(Char::isDigit).take(3) },
                enabled = !busy,
                label = { Text("Timeout (s)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = tokenText,
                onValueChange = { tokenText = it.filter(Char::isDigit).take(5) },
                enabled = !busy,
                label = { Text("Output tokens") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val candidate = currentSettings()
                            candidate.validate()
                            withContext(Dispatchers.IO) { store.save(candidate) }
                            showKey = false
                            status = "Connection settings saved."
                            onChanged()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: IllegalArgumentException) {
                            status = failure.message ?: "Check the connection settings."
                        } catch (_: Exception) {
                            status = "Could not save the connection in secure storage."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Save connection") }
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.clear() }
                            settings = ProviderSettings()
                            timeoutText = settings.timeoutSeconds.toString()
                            tokenText = settings.maxOutputTokens.toString()
                            showKey = false
                            models = emptyList()
                            status = "Connection removed."
                            onChanged()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            status = "Could not remove the saved connection."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Remove") }
        }
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (onCodexLogin != null || onCheckCodexStatus != null || onOpenCodex != null || onOpenCodexTerminal != null) {
            Text("ChatGPT / Codex CLI", style = MaterialTheme.typography.titleLarge)
            Text(
                "Use the Codex CLI in Termux with your ChatGPT account. Install the CLI and enable device-code sign-in in your ChatGPT security settings first. The workspace agent uses the connection above.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onCodexLogin != null) {
                OutlinedButton(
                    onClick = {
                        codexStatus = ""
                        onCodexLogin()
                    },
                    enabled = !checkingCodex,
                ) { Text("Sign in with ChatGPT") }
            }
            if (onCheckCodexStatus != null) {
                TextButton(
                    enabled = !checkingCodex,
                    onClick = {
                        checkingCodex = true
                        codexStatus = "Checking the Codex CLI sign-in…"
                        scope.launch {
                            try {
                                codexStatus = onCheckCodexStatus()
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                codexStatus = "Could not check the Codex CLI sign-in."
                            } finally {
                                checkingCodex = false
                            }
                        }
                    },
                ) { Text("Check Codex sign-in") }
            }
            if (codexStatus.isNotEmpty()) Text(codexStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onOpenCodex != null) TextButton(onClick = onOpenCodex) { Text("Open Codex CLI") }
            if (onOpenCodexTerminal != null) {
                Text("To sign in or check your account manually, run codex login --device-auth or codex login status.")
                TextButton(onClick = onOpenCodexTerminal) { Text("Open Termux") }
            }
        }
    }

    if (showModels) {
        ModelCatalogDialog(
            models = models,
            onDismiss = { showModels = false },
            onSelect = { model ->
                settings = settings.copy(model = model.id)
                showModels = false
            },
        )
    }
}

@Composable
private fun ModelCatalogDialog(
    models: List<ProviderModel>,
    onDismiss: () -> Unit,
    onSelect: (ProviderModel) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(models, search) {
        models.filter { model ->
            model.id.contains(search, ignoreCase = true) || model.title.contains(search, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search models") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(filtered, key = { it.id }) { model ->
                        TextButton(
                            onClick = { onSelect(model) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(model.title)
                                if (model.title != model.id) {
                                    Text(model.id, style = MaterialTheme.typography.bodySmall)
                                }
                                when (model.supportsTools) {
                                    true -> Text("Tool calling listed", style = MaterialTheme.typography.bodySmall)
                                    false -> Text("Tool calling unavailable", style = MaterialTheme.typography.bodySmall)
                                    null -> Unit
                                }
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) Text("No matching models.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.phoneagent.R
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProviderSettingsScreen(
    store: ProviderStore,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {},
    onConnectionSaved: () -> Unit = {},
    onOpenCodexTerminal: (() -> Unit)? = null,
    onCodexLogin: (() -> Unit)? = null,
    onCheckCodexStatus: (suspend () -> String)? = null,
    onOpenCodex: (() -> Unit)? = null,
) {
    val labels = LocalContext.current
    val discovery = remember { ProviderDiscovery() }
    val scope = rememberCoroutineScope()
    var settings by remember(store) { mutableStateOf(ProviderSettings()) }
    var library by remember(store) { mutableStateOf(ProviderLibrary()) }
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

    fun selectModel(id: String) {
        val existing = library.connection(settings.preset)?.takeIf { saved ->
            runCatching { saved.connection.endpoint() == settings.endpoint() }.getOrDefault(false)
        }?.models?.firstOrNull { it.id == id }
        settings = settings.copy(
            model = id,
            maxOutputTokens = existing?.maxOutputTokens ?: DEFAULT_OUTPUT_TOKENS,
            reasoning = existing?.reasoning ?: ReasoningEffort.DEFAULT,
        )
    }

    LaunchedEffect(store) {
        try {
            library = withContext(Dispatchers.IO) { store.library() }
            settings = library.selected ?: ProviderSettings()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            status = labels.getString(R.string.provider_load_failed)
        } finally {
            busy = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(labels.getString(R.string.provider_screen), style = MaterialTheme.typography.headlineMedium)
        Text(
            labels.getString(R.string.provider_connection_hint),
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
                            val saved = library.connection(preset)
                            settings = saved?.settings(saved.connection.model) ?: ProviderSettings(preset = preset)
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
                    settings = settings.copy(baseUrl = url.take(2048), apiKey = "", model = "", reasoning = ReasoningEffort.DEFAULT)
                    showKey = false
                    resetCatalog()
                }
            },
            enabled = !busy,
            label = { Text(labels.getString(R.string.base_url)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            supportingText = { Text(labels.getString(R.string.endpoint_help)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.apiKey,
            onValueChange = {
                settings = settings.copy(apiKey = it.take(4096))
                resetCatalog()
            },
            enabled = !busy,
            label = { Text(if (settings.preset.keyRequired) labels.getString(R.string.api_key) else labels.getString(R.string.api_key_optional)) },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }, enabled = !busy) {
                    Text(if (showKey) labels.getString(R.string.hide) else labels.getString(R.string.show))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.model,
            onValueChange = { selectModel(it.take(256)) },
            enabled = !busy,
            label = { Text(labels.getString(R.string.model_identifier)) },
            supportingText = { Text(labels.getString(R.string.model_tools_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                status = labels.getString(R.string.provider_checking)
                scope.launch {
                    try {
                        models = discovery.models(settings)
                        status = if (models.isEmpty()) labels.getString(R.string.chat_model_catalog_empty) else
                            labels.getString(R.string.provider_verified, models.size)
                        showModels = models.isNotEmpty()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: IllegalArgumentException) {
                        status = failure.message ?: labels.getString(R.string.provider_check_settings)
                    } catch (failure: IOException) {
                        status = failure.message ?: labels.getString(R.string.provider_check_failed)
                    } catch (_: Exception) {
                        status = labels.getString(R.string.provider_read_failed)
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text(labels.getString(R.string.test_models))
        }
        if (models.isNotEmpty()) TextButton(onClick = { showModels = true }) { Text(labels.getString(R.string.provider_choose_model)) }

        Text(labels.getString(R.string.provider_connect_timeout), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val candidate = settings
                            library = withContext(Dispatchers.IO) {
                                store.save(candidate, models)
                                store.library()
                            }
                            showKey = false
                            status = labels.getString(R.string.provider_saved)
                            onChanged()
                            onConnectionSaved()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: IllegalArgumentException) {
                            status = failure.message ?: labels.getString(R.string.provider_check_settings)
                        } catch (_: Exception) {
                            status = labels.getString(R.string.provider_save_failed)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(labels.getString(R.string.save_connection)) }
            TextButton(
                enabled = !busy && library.connection(settings.preset) != null,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            library = withContext(Dispatchers.IO) {
                                store.remove(settings.preset)
                                store.library()
                            }
                            settings = library.selected ?: ProviderSettings()
                            showKey = false
                            models = emptyList()
                            status = labels.getString(R.string.provider_removed)
                            onChanged()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            status = labels.getString(R.string.provider_remove_failed)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(labels.getString(R.string.remove)) }
        }
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (onCodexLogin != null || onCheckCodexStatus != null || onOpenCodex != null || onOpenCodexTerminal != null) {
            Text("ChatGPT / Codex CLI", style = MaterialTheme.typography.titleLarge)
            Text(
                labels.getString(R.string.codex_cli_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onCodexLogin != null) {
                OutlinedButton(
                    onClick = {
                        codexStatus = ""
                        onCodexLogin()
                    },
                    enabled = !checkingCodex,
                ) { Text(labels.getString(R.string.chatgpt_sign_in)) }
            }
            if (onCheckCodexStatus != null) {
                TextButton(
                    enabled = !checkingCodex,
                    onClick = {
                        checkingCodex = true
                        codexStatus = labels.getString(R.string.codex_checking)
                        scope.launch {
                            try {
                                codexStatus = onCheckCodexStatus()
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                codexStatus = labels.getString(R.string.codex_check_failed)
                            } finally {
                                checkingCodex = false
                            }
                        }
                    },
                ) { Text(labels.getString(R.string.check_codex)) }
            }
            if (codexStatus.isNotEmpty()) Text(codexStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onOpenCodex != null) TextButton(onClick = onOpenCodex) { Text(labels.getString(R.string.open_codex)) }
            if (onOpenCodexTerminal != null) {
                Text(labels.getString(R.string.codex_manually))
                TextButton(onClick = onOpenCodexTerminal) { Text(labels.getString(R.string.open_termux)) }
            }
        }
    }

    if (showModels) {
        ModelCatalogDialog(
            models = models,
            onDismiss = { showModels = false },
            onSelect = { model ->
                selectModel(model.id)
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
    val labels = LocalContext.current
    var search by remember { mutableStateOf("") }
    val filtered = remember(models, search) {
        models.filter { model ->
            model.id.contains(search, ignoreCase = true) || model.title.contains(search, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(labels.getString(R.string.choose_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(labels.getString(R.string.search_models)) },
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
                                    true -> Text(labels.getString(R.string.tools_listed), style = MaterialTheme.typography.bodySmall)
                                    false -> Text(labels.getString(R.string.tools_unavailable), style = MaterialTheme.typography.bodySmall)
                                    null -> Unit
                                }
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) Text(labels.getString(R.string.no_matching_models))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(labels.getString(R.string.close)) } },
    )
}

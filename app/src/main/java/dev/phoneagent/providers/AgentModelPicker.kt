package dev.phoneagent.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.phoneagent.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AgentModelPicker(
    store: ProviderStore,
    connection: ProviderSettings,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val labels = LocalContext.current
    val discovery = remember { ProviderDiscovery() }
    val scope = rememberCoroutineScope()
    var modelId by remember(connection) { mutableStateOf(connection.model) }
    var query by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(emptyList<ProviderModel>()) }
    var catalogFailed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(connection) {
        try {
            models = discovery.models(connection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalogFailed = true
        } finally {
            loading = false
        }
    }

    val visibleModels = remember(models, query) {
        models.filter { it.id.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.choose_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(connection.preset.label, style = MaterialTheme.typography.labelLarge)
                if (loading) Text(stringResource(R.string.chat_loading_models))
                if (catalogFailed) Text(stringResource(R.string.chat_model_catalog_failed))
                if (!loading && !catalogFailed && models.isEmpty()) Text(stringResource(R.string.chat_model_catalog_empty))
                if (models.isNotEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.search_models)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                        items(visibleModels, key = { it.id }) { candidate ->
                            TextButton(
                                onClick = { modelId = candidate.id; error = "" },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !saving,
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(if (candidate.id == modelId) "✓ ${candidate.title}" else candidate.title)
                                    if (candidate.id != candidate.title) {
                                        Text(candidate.id, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it.take(256); error = "" },
                    label = { Text(stringResource(R.string.model_identifier)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && modelId.isNotBlank() && modelId.none(Char::isISOControl),
                onClick = {
                    saving = true
                    val selected = modelId.trim()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.save(connection.copy(model = selected)) }
                            onSaved()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = labels.getString(R.string.chat_model_save_failed)
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

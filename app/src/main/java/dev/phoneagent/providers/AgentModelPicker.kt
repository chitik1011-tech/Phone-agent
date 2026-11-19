package dev.phoneagent.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phoneagent.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentModelPicker(
    store: ProviderStore,
    library: ProviderLibrary,
    hasProject: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    val labels = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember { ProviderDiscovery() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var options by remember { mutableStateOf<ModelChoice?>(null) }
    var catalogMenu by remember { mutableStateOf(false) }
    var manualMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val selected = library.selected
    val visible = remember(library, query) {
        library.connections.flatMap { provider ->
            provider.models.map { ModelChoice(provider.connection, it) }
        }.filter { (provider, model) ->
            model.id.contains(query, ignoreCase = true) || model.title.contains(query, ignoreCase = true) ||
                provider.preset.label.contains(query, ignoreCase = true)
        }.sortedBy { it.model.title.lowercase() }
    }

    fun import(connection: ProviderSettings) {
        busy = true
        status = labels.getString(R.string.chat_loading_models)
        scope.launch {
            try {
                val catalog = discovery.models(connection)
                if (catalog.isEmpty()) {
                    status = labels.getString(R.string.chat_model_catalog_empty)
                } else {
                    withContext(Dispatchers.IO) { store.importModels(connection, catalog) }
                    status = labels.getString(R.string.models_imported, catalog.size)
                    onChanged()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = labels.getString(R.string.chat_model_catalog_failed)
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
    ) {
        val detail = options
        if (detail != null) {
            ModelOptions(
                choice = detail,
                hasProject = hasProject,
                busy = busy,
                status = status,
                onBack = { options = null; status = "" },
                onSelect = { model ->
                    busy = true
                    status = ""
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.select(detail.connection, model) }
                            onChanged()
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = labels.getString(R.string.chat_model_save_failed)
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding().padding(bottom = 12.dp),
            ) {
                item {
                    Column(
                        Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.saved_models), style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it.take(256) },
                            label = { Text(stringResource(R.string.search_models)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box {
                                TextButton(enabled = !busy, onClick = { catalogMenu = true }) {
                                    Text(stringResource(R.string.model_import))
                                }
                                DropdownMenu(expanded = catalogMenu, onDismissRequest = { catalogMenu = false }) {
                                    library.connections.forEach { provider ->
                                        val connection = provider.connection
                                        DropdownMenuItem(text = { Text(connection.preset.label) }, onClick = {
                                            catalogMenu = false
                                            import(connection)
                                        })
                                    }
                                }
                            }
                            Box {
                                TextButton(enabled = !busy, onClick = { manualMenu = true }) {
                                    Text(stringResource(R.string.model_manual_add))
                                }
                                DropdownMenu(expanded = manualMenu, onDismissRequest = { manualMenu = false }) {
                                    library.connections.forEach { provider ->
                                        val connection = provider.connection
                                        DropdownMenuItem(text = { Text(connection.preset.label) }, onClick = {
                                            manualMenu = false
                                            status = ""
                                            options = ModelChoice(connection, SavedModel(id = ""), manual = true)
                                        })
                                    }
                                }
                            }
                            TextButton(enabled = !busy, onClick = { onDismiss(); onOpenProviders() }) {
                                Text(stringResource(R.string.manage_providers))
                            }
                        }
                        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                        if (visible.isEmpty()) Text(stringResource(R.string.no_matching_models))
                    }
                }
                items(visible, key = { "${it.connection.preset.name}:${it.model.id}" }) { choice ->
                    val model = choice.model
                    val current = selected != null && selected.preset == choice.connection.preset && selected.model == model.id
                    ListItem(
                        headlineContent = { Text(model.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (model.title == model.id) choice.connection.preset.label
                                else "${choice.connection.preset.label} · ${model.id}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = if (current) {
                            { Text(stringResource(R.string.current_model), color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        modifier = Modifier.clickable(enabled = !busy) { status = ""; options = choice },
                    )
                }
            }
        }
    }
}

private data class ModelChoice(val connection: ProviderSettings, val model: SavedModel, val manual: Boolean = false)

@Composable
private fun ModelOptions(
    choice: ModelChoice,
    hasProject: Boolean,
    busy: Boolean,
    status: String,
    onBack: () -> Unit,
    onSelect: (SavedModel) -> Unit,
) {
    var id by remember(choice) { mutableStateOf(choice.model.id) }
    var tokens by remember(choice) { mutableStateOf(choice.model.maxOutputTokens.toString()) }
    var effort by remember(choice) { mutableStateOf(choice.model.reasoning) }
    val activeModel = choice.model.copy(id = id.trim())
    val levels = remember(choice, id, hasProject) { reasoningLevels(choice.connection, activeModel, hasProject) }
    val activeEffort = if (effort in levels) effort else ReasoningEffort.DEFAULT
    val tokenCount = tokens.toIntOrNull()
    val tokenRange = OUTPUT_TOKEN_RANGE.first..activeModel.outputLimit
    val validId = activeModel.id.isNotBlank() && activeModel.id.none(Char::isISOControl)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TextButton(enabled = !busy, onClick = onBack) { Text(stringResource(R.string.saved_models)) }
            Text(
                if (choice.manual) stringResource(R.string.model_manual_add) else choice.model.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(choice.connection.preset.label, style = MaterialTheme.typography.bodySmall)
        }
        if (choice.manual) item {
            OutlinedTextField(
                value = id, onValueChange = { id = it.take(256) },
                label = { Text(stringResource(R.string.model_identifier)) },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(stringResource(R.string.model_reasoning), style = MaterialTheme.typography.titleMedium)
            if (levels.size == 1) {
                Text(stringResource(R.string.model_reasoning_default_only), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { level ->
                    FilterChip(
                        selected = level == activeEffort,
                        enabled = !busy,
                        onClick = { effort = level },
                        label = { Text(stringResource(level.labelResource())) },
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = tokens,
                onValueChange = { tokens = it.filter { digit -> digit in '0'..'9' }.take(5) },
                label = { Text(stringResource(R.string.output_tokens)) },
                supportingText = { Text(stringResource(R.string.model_token_range, tokenRange.first, tokenRange.last)) },
                isError = tokens.isNotEmpty() && (tokenCount == null || tokenCount !in tokenRange),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            )
            TextButton(enabled = !busy, onClick = { tokens = tokenRange.last.toString() }) {
                Text(stringResource(R.string.model_max_tokens))
            }
        }
        item {
            Text(stringResource(R.string.provider_connect_timeout), style = MaterialTheme.typography.bodySmall)
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error)
            Button(
                enabled = !busy && validId && tokenCount != null && tokenCount in tokenRange,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onSelect(activeModel.copy(
                        title = if (choice.manual) activeModel.id else choice.model.title,
                        reasoning = if (levels.size == 1) effort else activeEffort,
                        maxOutputTokens = checkNotNull(tokenCount),
                    ))
                },
            ) { Text(stringResource(R.string.model_use)) }
        }
    }
}

internal fun ReasoningEffort.labelResource(): Int = when (this) {
    ReasoningEffort.DEFAULT -> R.string.model_reasoning_default
    ReasoningEffort.LOW -> R.string.model_reasoning_low
    ReasoningEffort.MEDIUM -> R.string.model_reasoning_medium
    ReasoningEffort.HIGH -> R.string.model_reasoning_high
    ReasoningEffort.EXTRA_HIGH -> R.string.model_reasoning_extra_high
    ReasoningEffort.MAX -> R.string.model_reasoning_max
}

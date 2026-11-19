package dev.phoneagent

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phoneagent.agent.AgentEvent
import dev.phoneagent.agent.AgentMessage
import dev.phoneagent.agent.AgentOptions
import dev.phoneagent.agent.AgentRunner
import dev.phoneagent.agent.OpenAiProvider
import dev.phoneagent.agent.ProviderConfig
import dev.phoneagent.providers.AgentModelPicker
import dev.phoneagent.providers.ProviderSettings
import dev.phoneagent.providers.ProviderStore
import dev.phoneagent.session.AgentSessions
import dev.phoneagent.session.SessionEvent
import dev.phoneagent.session.SessionRun
import dev.phoneagent.session.SessionSnapshot
import dev.phoneagent.session.SessionSpec
import dev.phoneagent.session.SessionStatus
import dev.phoneagent.ui.ScreenTitle
import dev.phoneagent.workspace.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Composable
internal fun AgentCenter(
    sessions: AgentSessions,
    project: Workspace?,
    providers: ProviderStore,
    prefs: AppPreferences,
    providerRevision: Int,
    onModelChanged: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val labels = LocalContext.current
    val location = project?.ref?.location.orEmpty()
    var task by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var fresh by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current by sessions.current.collectAsStateWithLifecycle()
    val revision by sessions.revision.collectAsStateWithLifecycle()
    val history by produceState(emptyList<SessionSnapshot>(), revision) {
        value = sessions.recent()
    }
    val connection by produceState<ProviderSettings?>(null, providers, providerRevision) {
        value = withContext(Dispatchers.IO) {
            runCatching { providers.load().also { it.validate(requireModel = false) } }.getOrNull()
        }
    }
    val session = if (fresh) null else {
        selected?.let { id -> history.firstOrNull { it.id == id } }
            ?: current?.takeIf { it.spec.project == location }
    }
    val sessionId = session?.id
    val transcript by produceState(ChatTranscript(), sessionId, revision) {
        value = if (session == null) ChatTranscript() else {
            val messages = sessions.checkpoint(session.id)?.let { runCatching { readCheckpoint(it) }.getOrNull() }.orEmpty()
            val currentPrompt = messages.indexOfLast { it.role == "user" && it.content == session.spec.task }
            val earlier = if (currentPrompt < 0) emptyList() else {
                messages.take(currentPrompt).filter {
                    it.role in setOf("user", "assistant") && it.content.isNotBlank()
                }
            }
            ChatTranscript(session.id, earlier, sessions.events(session.id))
        }
    }
    val shown = transcript.takeIf { it.sessionId == sessionId } ?: ChatTranscript()
    val active = current?.status?.active == true
    val logScroll = rememberLazyListState()
    val answer = shown.events.lastOrNull { it.kind == "answer" }?.text
        ?: shown.events.filter { it.kind == "output" }.joinToString("") { it.text }

    fun submit() {
        if (busy || active || task.isBlank()) return
        val instruction = task.trim()
        val resume = session?.takeIf { it.spec.project == location }
        val options = AgentOptions(
            mode = prefs.mode,
            systemPrompt = prefs.systemPrompt,
            maxSteps = prefs.steps,
            maxSubagents = prefs.subagents,
            contextChars = prefs.contextChars,
            autoCompact = prefs.compact,
        )
        val reviewerModel = prefs.reviewerModel
        busy = true
        error = ""
        scope.launch {
            try {
                val providerConfig = withContext(Dispatchers.IO) {
                    val config = providers.load()
                    config.validate()
                    ProviderConfig(
                        config.baseUrl, config.model, config.apiKey, config.timeoutSeconds, config.maxOutputTokens,
                    )
                }
                val provider = OpenAiProvider(providerConfig)
                val reviewer = reviewerModel.takeIf { it.isNotBlank() && it != provider.model }
                    ?.let { OpenAiProvider(providerConfig.copy(model = it)) }
                val messages = resume?.let { sessions.checkpoint(it.id)?.let(::readCheckpoint) }.orEmpty()
                sessions.start(SessionSpec(instruction, location, provider.model, options.mode.name)) {
                    runCodingAgent(project, provider, reviewer, instruction, options, messages)
                }
                task = ""
                selected = null
                fresh = false
                showHistory = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: labels.getString(R.string.chat_start_failed)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(sessionId, shown.earlier.size, answer.length, showDetails, shown.events.size, showHistory) {
        if (!showHistory) {
            val last = logScroll.layoutInfo.totalItemsCount - 1
            if (last >= 0) logScroll.scrollToItem(last)
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle(stringResource(R.string.agent_chat), project?.ref?.title ?: stringResource(R.string.chat_no_project))
        val configured = connection
        OutlinedButton(
            onClick = { if (configured == null) onOpenSettings() else pickerOpen = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val model = configured?.model?.takeIf(String::isNotBlank)
            Text(
                model?.let { stringResource(R.string.chat_model_value, it) } ?: stringResource(R.string.choose_model),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(stringResource(if (showHistory) R.string.agent_chat else R.string.chat_history))
            }
            TextButton(
                enabled = !active && !busy,
                onClick = {
                    fresh = true
                    selected = null
                    showHistory = false
                    error = ""
                },
            ) { Text(stringResource(R.string.new_chat)) }
            TextButton(enabled = !active && !busy, onClick = onOpenProject) {
                Text(stringResource(if (project == null) R.string.open_project else R.string.change_project))
            }
        }
        if (showHistory) {
            LazyColumn(Modifier.weight(1f)) {
                items(history, key = { it.id }) { item ->
                    TextButton(
                        onClick = { selected = item.id; fresh = false; showHistory = false },
                    ) {
                        Text("${item.spec.task.take(90)} · ${stringResource(item.status.labelResource())}")
                    }
                }
                if (history.isEmpty()) item { Text(stringResource(R.string.chat_no_history)) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = logScroll,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown.earlier) { message -> ChatBubble(message.content, message.role == "user") }
                if (session == null) {
                    item {
                        val hint = if (configured?.model.isNullOrBlank()) R.string.choose_model_to_chat else R.string.chat_empty
                        Text(stringResource(hint), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    item { ChatBubble(session.spec.task, mine = true) }
                    if (answer.isNotBlank()) item { ChatBubble(answer, mine = false) }
                    item {
                        Text(
                            "${session.spec.model} · ${stringResource(session.status.labelResource())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (session.status == SessionStatus.FAILED) {
                            Text(session.statusMessage, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    val details = shown.events.filterNot { it.kind == "output" || it.kind == "answer" }
                    if (details.isNotEmpty()) {
                        item {
                            TextButton(onClick = { showDetails = !showDetails }) {
                                Text(stringResource(if (showDetails) R.string.chat_hide_activity else R.string.chat_show_activity))
                            }
                        }
                        if (showDetails) items(details, key = { it.id }) { event ->
                            SelectionContainer {
                                Column {
                                    Text(event.kind, style = MaterialTheme.typography.labelMedium)
                                    Text(event.text, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (selected != null && session?.spec?.project != location) {
            Text(stringResource(R.string.chat_other_project), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = task,
            onValueChange = { task = it.take(24_000) },
            label = { Text(stringResource(R.string.chat_message)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 4,
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
            if (active) OutlinedButton(onClick = sessions::cancel) { Text(stringResource(R.string.chat_stop)) }
            else Button(
                onClick = { if (configured == null) onOpenSettings() else submit() },
                enabled = !busy && task.isNotBlank(),
            ) { Text(stringResource(if (busy) R.string.chat_starting else R.string.chat_send)) }
        }
    }

    if (pickerOpen && connection != null) {
        AgentModelPicker(
            store = providers,
            connection = checkNotNull(connection),
            onDismiss = { pickerOpen = false },
            onSaved = { pickerOpen = false; onModelChanged() },
        )
    }
}

private data class ChatTranscript(
    val sessionId: String? = null,
    val earlier: List<AgentMessage> = emptyList(),
    val events: List<SessionEvent> = emptyList(),
)

private fun readCheckpoint(raw: String): List<AgentMessage> {
    val saved = JSONArray(raw)
    return List(saved.length()) { index -> AgentMessage.fromJson(saved.getJSONObject(index)) }
}

@StringRes
internal fun SessionStatus.labelResource(): Int = when (this) {
    SessionStatus.PENDING -> R.string.session_pending
    SessionStatus.RUNNING -> R.string.session_running
    SessionStatus.WAITING_FOR_APPROVAL -> R.string.session_waiting
    SessionStatus.COMPLETED -> R.string.session_completed
    SessionStatus.FAILED -> R.string.session_failed
    SessionStatus.CANCELLED -> R.string.session_cancelled
    SessionStatus.INTERRUPTED -> R.string.session_interrupted
}

@Composable
private fun ChatBubble(message: String, mine: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            modifier = Modifier.widthIn(max = if (mine) 304.dp else 660.dp),
            shape = MaterialTheme.shapes.large,
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            SelectionContainer {
                Text(message, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

private suspend fun SessionRun.runCodingAgent(
    project: Workspace?,
    provider: OpenAiProvider,
    reviewer: OpenAiProvider?,
    instruction: String,
    options: AgentOptions,
    messages: List<AgentMessage>,
): String {
    val output = StringBuilder()
    var lastFlush = System.nanoTime()

    suspend fun flush() {
        if (output.isEmpty()) return
        event("output", output.toString())
        output.clear()
        lastFlush = System.nanoTime()
    }

    val result = AgentRunner(provider, project, reviewer ?: provider).run(
        task = instruction,
        options = options,
        history = messages,
        approve = { call ->
            if (project == null || (call.name == "execute_command" && !project.ref.termux)) {
                false
            } else {
                waitingForApproval(true)
                try {
                    ApprovalGate.request(call, project.ref)
                } finally {
                    waitingForApproval(false)
                }
            }
        },
        onEvent = { change ->
            if (change !is AgentEvent.TextDelta) flush()
            when (change) {
                is AgentEvent.TextDelta -> if (change.agentId == "main") {
                    output.append(change.text)
                    if (output.length >= 4096 || System.nanoTime() - lastFlush > 450_000_000) flush()
                }
                is AgentEvent.ToolStarted -> event("Tool started", "${change.agentId} · ${change.call.name}")
                is AgentEvent.ToolFinished -> event(
                    "Tool result", "${change.agentId} · ${change.call.name}\n${change.output.take(4000)}",
                )
                is AgentEvent.Subagent -> event(
                    "Reviewer · ${change.state.name.lowercase()}",
                    "${change.id} · ${change.model}\n${change.task}\n${change.detail.take(4000)}",
                )
                is AgentEvent.Compacted -> event("Context summary", change.summary)
                is AgentEvent.Checkpoint -> checkpoint(JSONArray(change.messages.map { it.toJson() }).toString())
                is AgentEvent.Usage -> event("Token usage", "${change.inputTokens} in · ${change.outputTokens} out")
            }
        },
    )
    flush()
    check(!result.stepLimitReached) { "The agent stopped at its step limit. Use Continue session to proceed." }
    return result.answer
}

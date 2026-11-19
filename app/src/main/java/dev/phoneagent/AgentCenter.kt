package dev.phoneagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phoneagent.agent.AgentEvent
import dev.phoneagent.agent.AgentMessage
import dev.phoneagent.agent.AgentOptions
import dev.phoneagent.agent.AgentRunner
import dev.phoneagent.agent.OpenAiProvider
import dev.phoneagent.agent.ProviderConfig
import dev.phoneagent.providers.ProviderStore
import dev.phoneagent.session.AgentSessions
import dev.phoneagent.session.SessionEvent
import dev.phoneagent.session.SessionRun
import dev.phoneagent.session.SessionSnapshot
import dev.phoneagent.session.SessionSpec
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
    onOpenProject: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var task by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current by sessions.current.collectAsStateWithLifecycle()
    val revision by sessions.revision.collectAsStateWithLifecycle()
    val history by produceState(emptyList<SessionSnapshot>(), revision) {
        value = sessions.recent()
    }
    val session = selected?.let { id -> history.firstOrNull { it.id == id } } ?: current
    val events by produceState(emptyList<SessionEvent>(), session?.id, revision) {
        value = session?.id?.let { sessions.events(it) }.orEmpty()
    }
    val active = current?.status?.active == true
    val logScroll = rememberLazyListState()

    fun submit(resume: SessionSnapshot? = null) {
        if (project == null || busy || active || task.isBlank()) return
        val instruction = task.trim()
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
                val messages = resume?.let {
                    require(it.spec.project == project.ref.location) { "Open the session's original project first." }
                    val raw = sessions.checkpoint(it.id) ?: error("This session has no saved context.")
                    val saved = JSONArray(raw)
                    List(saved.length()) { index -> AgentMessage.fromJson(saved.getJSONObject(index)) }
                }.orEmpty()
                sessions.start(SessionSpec(instruction, project.ref.location, provider.model, options.mode.name)) {
                    runCodingAgent(project, provider, reviewer, instruction, options, messages)
                }
                task = ""
                selected = null
                showHistory = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not start the agent."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(events.size, showHistory) {
        if (events.isNotEmpty() && !showHistory) logScroll.scrollToItem(events.lastIndex)
    }

    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenTitle("Agents", project?.ref?.title ?: "Choose a project to run coding tasks")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showHistory = !showHistory }) {
                Text(if (showHistory) "Current session" else "History")
            }
            TextButton(onClick = onOpenSettings) { Text("Models") }
            if (project == null) TextButton(onClick = onOpenProject) { Text("Open project") }
        }
        if (showHistory) {
            LazyColumn(Modifier.weight(1f)) {
                items(history, key = { it.id }) { item ->
                    TextButton(onClick = { selected = item.id; showHistory = false }) {
                        Text("${item.spec.task.take(90)} · ${item.status.name.lowercase().replace('_', ' ')}")
                    }
                }
                if (history.isEmpty()) item { Text("No saved sessions.") }
            }
        } else {
            session?.let {
                Text(it.spec.task, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text("${it.spec.model} · ${it.statusMessage}", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = logScroll,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(events, key = { it.id }) { event ->
                    SelectionContainer {
                        if (event.kind == "output") Text(event.text)
                        else Column {
                            Text(
                                event.kind, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(event.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (events.isEmpty()) item {
                    Text("Describe a change, a bug, or a build task.")
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(
            value = task,
            onValueChange = { task = it.take(24_000) },
            label = { Text(if (selected != null) "Follow-up or new task" else "Task") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )
        Row(
            Modifier.padding(bottom = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active) OutlinedButton(onClick = sessions::cancel) { Text("Stop agent") }
            else {
                val enabled = !busy && project != null && task.isNotBlank()
                Button(onClick = { submit() }, enabled = enabled) { Text(if (busy) "Starting…" else "Run task") }
                if (session?.spec?.project == project?.ref?.location && session != null) {
                    OutlinedButton(onClick = { submit(session) }, enabled = enabled) { Text("Continue session") }
                }
            }
        }
    }
}

private suspend fun SessionRun.runCodingAgent(
    project: Workspace,
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
            if (call.name == "execute_command" && !project.ref.termux) {
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

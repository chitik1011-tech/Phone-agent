package dev.phoneagent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phoneagent.mcp.McpSettingsScreen
import dev.phoneagent.providers.ProviderSettingsScreen
import dev.phoneagent.providers.ProviderStore
import dev.phoneagent.providers.checkCodexCliStatus
import dev.phoneagent.session.AgentSessions
import dev.phoneagent.termux.TerminalScreen
import dev.phoneagent.termux.TermuxRuntime
import dev.phoneagent.ui.Destination
import dev.phoneagent.ui.HomeScreen
import dev.phoneagent.ui.PhoneAgentOnboarding
import dev.phoneagent.ui.PhoneAgentShell
import dev.phoneagent.ui.PhoneAgentTheme
import dev.phoneagent.workspace.WorkspaceRef
import dev.phoneagent.workspace.workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PhoneAgentApp() }
    }
}

private enum class SettingsPage { MAIN, PROVIDERS, AGENTS, PERMISSIONS, MCP }

@Composable
private fun PhoneAgentApp() {
    val context = LocalContext.current.applicationContext
    val preferences = remember { AppPreferences(context) }
    val providers = remember { ProviderStore(context) }
    val runtime = remember { TermuxRuntime(context) }
    val sessions = remember { AgentSessions.get(context) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf(Destination.HOME) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    var onboarding by rememberSaveable { mutableStateOf(preferences.onboarding) }
    var setupInProgress by rememberSaveable { mutableStateOf(preferences.onboarding) }
    val onboardingState = rememberSaveableStateHolder()
    var showProjects by rememberSaveable { mutableStateOf(false) }
    var showTermuxPath by rememberSaveable { mutableStateOf(false) }
    var createProject by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var termuxPath by rememberSaveable { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    var providerRevision by remember { mutableIntStateOf(0) }
    var prefsRevision by remember { mutableIntStateOf(0) }
    var project by remember { mutableStateOf(preferences.workspace) }
    val selectedProject = remember(project) { project?.let { workspace(context, runtime, it) } }
    val session by sessions.current.collectAsStateWithLifecycle()
    val approval by ApprovalGate.pending.collectAsStateWithLifecycle()
    val providerLabel = androidx.compose.runtime.produceState("Not configured", providerRevision) {
        value = withContext(Dispatchers.IO) {
            runCatching { providers.load().model.ifBlank { "Not configured" } }.getOrDefault("Check settings")
        }
    }.value

    fun message(text: String) { scope.launch { snack.showSnackbar(text) } }

    fun finishOnboarding() {
        onboarding = false
        setupInProgress = false
        preferences.onboarding = false
    }

    fun openSettings(page: SettingsPage) {
        onboarding = false
        settingsPage = page
        screen = Destination.SETTINGS
    }

    fun openProject(ref: WorkspaceRef) {
        preferences.rememberWorkspace(ref)
        project = ref
        showProjects = false
        showTermuxPath = false
        finishOnboarding()
        screen = Destination.WORKSPACE
    }

    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                openProject(documentWorkspace(uri))
            }.onFailure { message(it.message ?: "Could not open the folder.") }
        }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message(if (granted) "Notifications enabled." else "Notification permission was declined.")
    }

    fun openTermux() {
        onboarding = false
        screen = Destination.TERMINAL
    }

    fun launchCodex() {
        runCatching { runtime.launchInteractiveCommand("codex login --device-auth") }
            .onFailure { message(it.message ?: "Could not open Codex in Termux.") }
    }

    val selection = remember(prefsRevision) { preferences.theme }
    val animations = remember(prefsRevision) { preferences.animations }
    val haptics = remember(prefsRevision) { preferences.haptics }
    BackHandler(!onboarding && (setupInProgress || screen != Destination.HOME)) {
        if (setupInProgress) onboarding = true
        else if (screen == Destination.SETTINGS && settingsPage != SettingsPage.MAIN) {
            settingsPage = SettingsPage.MAIN
        } else screen = Destination.HOME
    }

    PhoneAgentTheme(selection) {
        if (onboarding) {
            onboardingState.SaveableStateProvider("setup") {
                PhoneAgentOnboarding(
                    onFinish = ::finishOnboarding,
                    onOpenProviders = { openSettings(SettingsPage.PROVIDERS) },
                    onOpenTermux = ::openTermux,
                    onOpenProject = { showProjects = true },
                    onOpenPermissions = { openSettings(SettingsPage.PERMISSIONS) },
                    onOpenAgentPreferences = { openSettings(SettingsPage.AGENTS) },
                )
            }
        } else {
            PhoneAgentShell(
                destination = screen,
                onNavigate = {
                    if (it == Destination.SETTINGS && screen != it) settingsPage = SettingsPage.MAIN
                    screen = it
                },
                animateNavigation = animations,
                haptics = haptics,
            ) { destination ->
                when (destination) {
                    Destination.HOME -> HomeScreen(
                        projectName = project?.title,
                        termuxStatus = runtime.status().let {
                            when {
                                !it.installed -> "Install Termux"
                                !it.permissionGranted -> "Permission required"
                                else -> "Installed · ${it.versionName ?: "version unknown"}"
                            }
                        },
                        providerStatus = providerLabel,
                        onOpenProject = {
                            if (project == null) showProjects = true else screen = Destination.WORKSPACE
                        },
                        onOpenTerminal = ::openTermux,
                        onOpenProviders = { openSettings(SettingsPage.PROVIDERS) },
                        onNewTask = { screen = Destination.AGENTS },
                        onStartOnboarding = { setupInProgress = true; onboarding = true },
                        recentTaskTitle = session?.spec?.task,
                        recentTaskStatus = session?.statusMessage,
                        onOpenAgents = { screen = Destination.AGENTS },
                    )
                    Destination.WORKSPACE -> WorkspaceScreen(
                        selectedProject, session?.status?.active == true, { showProjects = true },
                    )
                    Destination.TERMINAL -> TerminalScreen(
                        runtime, project?.takeIf { it.termux }?.location.orEmpty(),
                    )
                    Destination.AGENTS -> AgentCenter(
                        sessions, selectedProject, providers, preferences,
                        onOpenProject = { showProjects = true },
                        onOpenSettings = { openSettings(SettingsPage.PROVIDERS) },
                    )
                    Destination.SETTINGS -> when (settingsPage) {
                        SettingsPage.PROVIDERS -> Column(Modifier.fillMaxSize()) {
                            TextButton(onClick = { settingsPage = SettingsPage.MAIN }) { Text("All settings") }
                            ProviderSettingsScreen(
                                providers, Modifier.weight(1f),
                                onChanged = { providerRevision++ },
                                onCodexLogin = ::launchCodex,
                                onOpenCodexTerminal = { runtime.launchTermux() },
                                onCheckCodexStatus = { checkCodexCliStatus(runtime) },
                                onOpenCodex = {
                                    runCatching {
                                        runtime.launchInteractiveCommand(
                                            "codex", project?.takeIf { it.termux }?.location.orEmpty(),
                                        )
                                    }.onFailure { message(it.message ?: "Could not start Codex in Termux.") }
                                },
                            )
                        }
                        SettingsPage.MCP -> Column(Modifier.fillMaxSize()) {
                            TextButton(onClick = { settingsPage = SettingsPage.MAIN }) { Text("All settings") }
                            McpSettingsScreen(Modifier.weight(1f))
                        }
                        else -> SettingsContent(
                            page = settingsPage.name,
                            prefs = preferences,
                            revision = prefsRevision,
                            onChanged = { prefsRevision++ },
                            openProviders = { settingsPage = SettingsPage.PROVIDERS },
                            openAgents = { settingsPage = SettingsPage.AGENTS },
                            openPermissions = { settingsPage = SettingsPage.PERMISSIONS },
                            openMcp = { settingsPage = SettingsPage.MCP },
                            back = { settingsPage = SettingsPage.MAIN },
                            openTerminal = ::openTermux,
                            requestNotifications = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    message("This Android version manages notifications in system settings.")
                                }
                            },
                        )
                    }
                }
            }
        }

        SnackbarHost(snack, Modifier.padding(horizontal = 18.dp, vertical = 56.dp))

        if (showProjects) {
            AlertDialog(
                onDismissRequest = { showProjects = false },
                title = { Text("Open project") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Open an Android folder or a private Termux project.")
                        TextButton(onClick = { showProjects = false; createProject = false }) {
                            Text("New Termux project")
                        }
                        TextButton(onClick = { showProjects = false; createProject = true }) {
                            Text("Clone repository")
                        }
                        preferences.folderHistory.forEach { previous ->
                            TextButton(onClick = { openProject(previous) }) { Text(previous.title) }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showProjects = false; openFolder.launch(null) }) { Text("Android folder") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showProjects = false
                        termuxPath = project?.takeIf { it.termux }?.location.orEmpty()
                        showTermuxPath = true
                    }) { Text("Termux path") }
                },
            )
        }

        createProject?.let { clone ->
            ProjectCreation(runtime, clone, { createProject = null }) { ref ->
                createProject = null
                openProject(ref)
            }
        }

        if (showTermuxPath) {
            AlertDialog(
                onDismissRequest = { if (!opening) showTermuxPath = false },
                title = { Text("Termux project path") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Use the full path printed by pwd in the project. File operations require Python in Termux.")
                        OutlinedTextField(
                            value = termuxPath,
                            onValueChange = { termuxPath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Project directory") },
                            placeholder = { Text("/data/data/com.termux/files/home/project") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(enabled = !opening && termuxPath.isNotBlank(), onClick = {
                        opening = true
                        scope.launch {
                            try {
                                val ref = termuxWorkspace(termuxPath)
                                workspace(context, runtime, ref).list(".")
                                openProject(ref)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                message(error.message ?: "Could not open the Termux directory.")
                            } finally {
                                opening = false
                            }
                        }
                    }) { Text(if (opening) "Checking…" else "Open") }
                },
                dismissButton = { TextButton(enabled = !opening, onClick = { showTermuxPath = false }) { Text("Cancel") } },
            )
        }

        approval?.let { request ->
            val body = remember(request) {
                runCatching { JSONObject(request.call.arguments).toString(2) }
                    .getOrDefault(request.call.arguments)
            }
            AlertDialog(
                onDismissRequest = { request.answer.complete(false) },
                title = { Text("Approve ${request.call.name.replace('_', ' ')}?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(request.project.location, style = MaterialTheme.typography.labelMedium)
                        if (request.call.name == "execute_command") {
                            Text("This command runs with the same access as your Termux shell.")
                        }
                        SelectionContainer { Text(body, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = { Button(onClick = { request.answer.complete(true) }) { Text("Allow once") } },
                dismissButton = { TextButton(onClick = { request.answer.complete(false) }) { Text("Deny") } },
            )
        }
    }
}

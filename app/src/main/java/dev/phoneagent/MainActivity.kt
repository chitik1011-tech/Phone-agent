package dev.phoneagent

import android.Manifest
import android.content.Intent
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
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
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val tag = AppPreferences(newBase).languageTag
        val localized = if (Build.VERSION.SDK_INT < 33 && tag.isNotEmpty()) {
            val configuration = Configuration(newBase.resources.configuration)
            configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
            newBase.createConfigurationContext(configuration)
        } else newBase
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PhoneAgentApp(this) }
    }
}

private enum class SettingsPage { MAIN, PROVIDERS, AGENTS, PERMISSIONS, MCP }

@Composable
private fun PhoneAgentApp(activity: ComponentActivity) {
    val labels = LocalContext.current
    val context = activity.applicationContext
    val preferences = remember { AppPreferences(context) }
    val providers = remember { ProviderStore(context) }
    val runtime = remember { TermuxRuntime(context) }
    val sessions = remember { AgentSessions.get(context) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf(Destination.AGENTS) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    var providerSetupFromChat by rememberSaveable { mutableStateOf(false) }
    var onboarding by rememberSaveable { mutableStateOf(false) }
    var setupInProgress by rememberSaveable { mutableStateOf(false) }
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
    val providerLabel = androidx.compose.runtime.produceState(labels.getString(R.string.not_configured), providerRevision) {
        value = withContext(Dispatchers.IO) {
            runCatching { providers.load().model.ifBlank { labels.getString(R.string.not_configured) } }.getOrDefault(labels.getString(R.string.check_settings))
        }
    }.value

    fun message(text: String) { scope.launch { snack.showSnackbar(text) } }

    fun finishOnboarding() {
        onboarding = false
        setupInProgress = false
    }

    fun openSettings(page: SettingsPage, fromChat: Boolean = false) {
        onboarding = false
        providerSetupFromChat = fromChat
        settingsPage = page
        screen = Destination.SETTINGS
    }

    fun backFromProviders() {
        if (providerSetupFromChat) {
            providerSetupFromChat = false
            screen = Destination.AGENTS
        }
        settingsPage = SettingsPage.MAIN
    }

    fun openProject(ref: WorkspaceRef) {
        val next = if (!onboarding && screen == Destination.AGENTS) Destination.AGENTS else Destination.WORKSPACE
        preferences.rememberWorkspace(ref)
        project = ref
        showProjects = false
        showTermuxPath = false
        finishOnboarding()
        screen = next
    }

    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                openProject(documentWorkspace(uri))
            }.onFailure { message(it.message ?: labels.getString(R.string.could_not_open_folder)) }
        }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message(if (granted) labels.getString(R.string.notifications_enabled) else labels.getString(R.string.notifications_declined))
    }

    fun openTermux() {
        onboarding = false
        screen = Destination.TERMINAL
    }

    fun launchCodex() {
        runCatching { runtime.launchInteractiveCommand("codex login --device-auth") }
            .onFailure { message(it.message ?: labels.getString(R.string.no_codex_open)) }
    }

    val selection = remember(prefsRevision) { preferences.theme }
    val animations = remember(prefsRevision) { preferences.animations }
    val haptics = remember(prefsRevision) { preferences.haptics }
    BackHandler(!onboarding && (setupInProgress || screen != Destination.AGENTS)) {
        if (setupInProgress) onboarding = true
        else if (screen == Destination.SETTINGS && settingsPage == SettingsPage.PROVIDERS) backFromProviders()
        else if (screen == Destination.SETTINGS && settingsPage != SettingsPage.MAIN) {
            settingsPage = SettingsPage.MAIN
        } else screen = Destination.AGENTS
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
                    if (it == Destination.SETTINGS && screen != it) {
                        settingsPage = SettingsPage.MAIN
                        providerSetupFromChat = false
                    }
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
                                !it.installed -> labels.getString(R.string.install_termux)
                                !it.permissionGranted -> labels.getString(R.string.permission_required)
                                else -> labels.getString(R.string.termux_installed, it.versionName ?: labels.getString(R.string.termux_version_unknown))
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
                        recentTaskStatus = session?.let { labels.getString(it.status.labelResource()) },
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
                        providerRevision = providerRevision,
                        onModelChanged = { providerRevision++ },
                        onOpenProject = { showProjects = true },
                        onOpenSettings = { openSettings(SettingsPage.PROVIDERS, fromChat = true) },
                    )
                    Destination.SETTINGS -> when (settingsPage) {
                        SettingsPage.PROVIDERS -> Column(Modifier.fillMaxSize()) {
                            TextButton(onClick = ::backFromProviders) {
                                Text(labels.getString(if (providerSetupFromChat) R.string.agent_chat else R.string.all_settings))
                            }
                            ProviderSettingsScreen(
                                providers, Modifier.weight(1f),
                                onChanged = { providerRevision++ },
                                onConnectionSaved = { if (providerSetupFromChat) backFromProviders() },
                                onCodexLogin = ::launchCodex,
                                onOpenCodexTerminal = { runtime.launchTermux() },
                                onCheckCodexStatus = { checkCodexCliStatus(runtime) },
                                onOpenCodex = {
                                    runCatching {
                                        runtime.launchInteractiveCommand(
                                            "codex", project?.takeIf { it.termux }?.location.orEmpty(),
                                        )
                                    }.onFailure { message(it.message ?: labels.getString(R.string.no_codex_start)) }
                                },
                            )
                        }
                        SettingsPage.MCP -> Column(Modifier.fillMaxSize()) {
                            TextButton(onClick = { settingsPage = SettingsPage.MAIN }) { Text(labels.getString(R.string.all_settings)) }
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
                            requestLanguage = { tag ->
                                preferences.languageTag = tag
                                if (Build.VERSION.SDK_INT >= 33) {
                                    activity.getSystemService(LocaleManager::class.java)
                                        .applicationLocales = LocaleList.forLanguageTags(tag)
                                } else {
                                    activity.recreate()
                                }
                            },
                            languageTag = if (Build.VERSION.SDK_INT >= 33) {
                                activity.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
                            } else preferences.languageTag,
                            requestNotifications = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    message(labels.getString(R.string.notifications_system))
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
                title = { Text(labels.getString(R.string.open_project)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(labels.getString(R.string.project_dialog_help))
                        TextButton(onClick = { showProjects = false; createProject = false }) {
                            Text(labels.getString(R.string.new_termux_project))
                        }
                        TextButton(onClick = { showProjects = false; createProject = true }) {
                            Text(labels.getString(R.string.clone_repository))
                        }
                        preferences.folderHistory.forEach { previous ->
                            TextButton(onClick = { openProject(previous) }) { Text(previous.title) }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showProjects = false; openFolder.launch(null) }) { Text(labels.getString(R.string.android_folder)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showProjects = false
                        termuxPath = project?.takeIf { it.termux }?.location.orEmpty()
                        showTermuxPath = true
                    }) { Text(labels.getString(R.string.termux_path)) }
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
                title = { Text(labels.getString(R.string.termux_project_path)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(labels.getString(R.string.termux_directory_help))
                        OutlinedTextField(
                            value = termuxPath,
                            onValueChange = { termuxPath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(labels.getString(R.string.project_directory)) },
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
                                message(error.message ?: labels.getString(R.string.termux_open_failed))
                            } finally {
                                opening = false
                            }
                        }
                    }) { Text(if (opening) labels.getString(R.string.checking) else labels.getString(R.string.open)) }
                },
                dismissButton = { TextButton(enabled = !opening, onClick = { showTermuxPath = false }) { Text(labels.getString(R.string.cancel)) } },
            )
        }

        approval?.let { request ->
            val body = remember(request) {
                runCatching { JSONObject(request.call.arguments).toString(2) }
                    .getOrDefault(request.call.arguments)
            }
            AlertDialog(
                onDismissRequest = { request.answer.complete(false) },
                title = { Text(labels.getString(R.string.approve_operation, request.call.name.replace('_', ' '))) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(request.project.location, style = MaterialTheme.typography.labelMedium)
                        if (request.call.name == "execute_command") {
                            Text(labels.getString(R.string.permission_termux_shell))
                        }
                        SelectionContainer { Text(body, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = { Button(onClick = { request.answer.complete(true) }) { Text(labels.getString(R.string.allow_once)) } },
                dismissButton = { TextButton(onClick = { request.answer.complete(false) }) { Text(labels.getString(R.string.deny)) } },
            )
        }
    }
}

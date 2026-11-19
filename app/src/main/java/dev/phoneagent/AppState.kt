package dev.phoneagent

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.phoneagent.agent.AgentMode
import dev.phoneagent.agent.ToolCall
import dev.phoneagent.workspace.WorkspaceRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("phone_agent", Context.MODE_PRIVATE)

    var workspace: WorkspaceRef?
        get() {
            val location = prefs.getString("project_location", null) ?: return null
            return WorkspaceRef(
                location,
                prefs.getString("project_title", null) ?: location.substringAfterLast('/'),
                prefs.getBoolean("project_termux", false),
            )
        }
        set(value) {
            prefs.edit().putString("project_location", value?.location)
                .putString("project_title", value?.title)
                .putBoolean("project_termux", value?.termux ?: false).apply()
        }

    var folderHistory: List<WorkspaceRef>
        get() {
            val history = JSONArray(prefs.getString("projects", "[]"))
            return List(history.length()) { index ->
                val entry = history.getJSONObject(index)
                WorkspaceRef(entry.getString("location"), entry.getString("title"), entry.getBoolean("termux"))
            }
        }
        private set(value) {
            val items = JSONArray(value.map {
                JSONObject().put("location", it.location).put("title", it.title).put("termux", it.termux)
            })
            prefs.edit().putString("projects", items.toString()).apply()
        }

    fun rememberWorkspace(project: WorkspaceRef) {
        workspace = project
        folderHistory = (listOf(project) + folderHistory.filterNot { it.location == project.location }).take(8)
    }

    var mode
        get() = enumValue("mode", AgentMode.ASK)
        set(value) { setText("mode", value.name) }

    var onboarding
        get() = !prefs.getBoolean("onboarded", false)
        set(value) { prefs.edit().putBoolean("onboarded", !value).apply() }

    var subagents
        get() = prefs.getInt("subagents", 2)
        set(value) { prefs.edit().putInt("subagents", value.coerceIn(0, 8)).apply() }

    var compact
        get() = prefs.getBoolean("compact", true)
        set(value) { prefs.edit().putBoolean("compact", value).apply() }

    var steps
        get() = prefs.getInt("steps", 12)
        set(value) { prefs.edit().putInt("steps", value.coerceIn(2, 40)).apply() }

    var contextChars
        get() = prefs.getInt("context_chars", 48_000)
        set(value) { prefs.edit().putInt("context_chars", value.coerceIn(16_000, 100_000)).apply() }

    var prompt
        get() = prefs.getString("prompt", "").orEmpty()
        set(value) { setText("prompt", value.take(12_000)) }

    var theme
        get() = enumValue("theme", dev.phoneagent.ui.AppTheme.SYSTEM)
        set(value) { setText("theme", value.name) }

    var haptics
        get() = prefs.getBoolean("haptics", true)
        set(value) { prefs.edit().putBoolean("haptics", value).apply() }

    var animations
        get() = prefs.getBoolean("animations", true)
        set(value) { prefs.edit().putBoolean("animations", value).apply() }

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == prefs.getString(key, null) } ?: default

    private fun setText(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}

internal fun documentWorkspace(uri: Uri): WorkspaceRef {
    val id = DocumentsContract.getTreeDocumentId(uri)
    val title = id.substringAfterLast('/').substringAfterLast(':').ifBlank { "Project folder" }
    return WorkspaceRef(uri.toString(), title, termux = false)
}

internal fun termuxWorkspace(rawPath: String): WorkspaceRef {
    val path = rawPath.trim().trimEnd('/')
    val roots = listOf("/data/data/com.termux/files/home", "/data/user/0/com.termux/files/home", "/storage/emulated/0", "/sdcard")
    val root = roots.firstOrNull { path.startsWith("$it/") }
        ?: error("Use a project subfolder under Termux home or internal shared storage.")
    val relative = path.removePrefix("$root/")
    require(relative.split('/').all { it.isNotBlank() && it != "." && it != ".." && !it.startsWith('.') }
        && path.length <= 512 && path.none(Char::isISOControl)) { "Choose a specific project directory." }
    return WorkspaceRef(path, relative.substringAfterLast('/'), termux = true)
}

internal data class Approval(
    val call: ToolCall,
    val project: WorkspaceRef,
    val answer: CompletableDeferred<Boolean> = CompletableDeferred(),
)

internal object ApprovalGate {
    val pending = MutableStateFlow<Approval?>(null)

    suspend fun request(call: ToolCall, project: WorkspaceRef): Boolean {
        val request = Approval(call, project)
        check(pending.compareAndSet(null, request)) { "There is already an approval pending." }
        return try {
            request.answer.await()
        } finally {
            pending.compareAndSet(request, null)
        }
    }
}

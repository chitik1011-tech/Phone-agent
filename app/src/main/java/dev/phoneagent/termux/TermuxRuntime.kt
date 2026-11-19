package dev.phoneagent.termux

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TermuxStatus(
    val installed: Boolean,
    val versionName: String?,
    val permissionGranted: Boolean,
) {
    val supportsPrivateCommandResults: Boolean
        get() {
            val segments = versionName?.substringBefore('-')?.split('.') ?: return false
            val major = segments.getOrNull(0)?.toIntOrNull() ?: return false
            val minor = segments.getOrNull(1)?.toIntOrNull() ?: return false
            return major > 0 || major == 0 && minor >= 118
        }
}

data class CommandResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val error: String? = null,
    val truncated: Boolean = false,
)

class TermuxRuntime(context: Context) {
    private val app = context.applicationContext
    private val bridgeLock = Mutex()
    private val bridgeSettings = app.getSharedPreferences("termux_bridge", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    fun status(): TermuxStatus {
        val info = try {
            app.packageManager.getPackageInfo(PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return TermuxStatus(
            installed = info != null,
            versionName = info?.versionName,
            permissionGranted = app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED,
        )
    }

    fun launchTermux(): Boolean {
        val intent = app.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun launchInteractiveCommand(command: String, workDir: String = "") {
        requireConnection()
        requireCommand(command)
        require(workDir.isEmpty() || validDirectory(workDir))
        val script = """
            if [ -n "${'$'}1" ]; then cd -- "${'$'}1" || exit 125; fi
            exec "$BASH" -c "${'$'}2"
        """.trimIndent()
        val intent = commandIntent(BASH, arrayOf("-c", script, "phone-agent", workDir, command), false)
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        checkNotNull(app.startService(intent)) { "Termux RUN_COMMAND service could not start." }
    }

    suspend fun run(
        command: String,
        workDir: String,
        timeoutMs: Long = 120_000,
    ): CommandResult {
        require(validDirectory(workDir)) {
            "Enter an absolute Termux project directory."
        }
        requireCommand(command)
        require(timeoutMs in 1_000..3_600_000)
        val seconds = ((timeoutMs + 999) / 1000).toString() + "s"
        val script = """
            cd -- "${'$'}1" || exit 125
            exec "$BIN/timeout" -k 3s "${'$'}2" "$BASH" -c "${'$'}3"
        """.trimIndent()
        return execute(BASH, arrayOf("-c", script, "phone-agent", workDir, seconds, command), timeoutMs + 6_000)
    }

    suspend fun probe(): CommandResult = execute(
        BASH, arrayOf("-c", "printf 'termux-ready\\n'; command -v python3 || true"), 10_000,
    )

    internal suspend fun resumeBridge(): BridgeClient? {
        val state = status()
        if (!state.permissionGranted || !state.supportsPrivateCommandResults) return null
        return cachedBridge()?.takeIf { it.isReachable() }
    }

    internal suspend fun connectBridge(): BridgeClient = bridgeLock.withLock {
        requireConnection()
        cachedBridge()?.let { client ->
            if (client.isReachable()) return@withLock client
        }
        val source = withContext(Dispatchers.IO) {
            app.assets.open("termux/phone_agent_bridge.py").bufferedReader().use { it.readText() }
        }
        val result = execute(
            "$BIN/python3",
            arrayOf("-c", "import sys; bridge_source = sys.argv.pop(1); exec(bridge_source)", source, "--provision"),
            12_000,
        )
        check(result.error == null && result.exitCode == 0) {
            "Bridge startup failed. Check Termux RUN_COMMAND and install Python with pkg install python."
        }
        val config = runCatching { JSONObject(result.stdout) }.getOrElse {
            error("Termux returned an invalid bridge response.")
        }
        check(config.optInt("version") == BridgeClient.VERSION) { "Unsupported bridge protocol." }
        val client = BridgeClient(config.getInt("port"), config.getString("key"))
        check(client.isReachable()) { "The Termux bridge stopped before connecting." }
        withContext(Dispatchers.IO) {
            bridgeSettings.edit().putString(BRIDGE_CONFIG, config.toString()).commit()
        }
        client
    }

    private fun cachedBridge(): BridgeClient? {
        val raw = bridgeSettings.getString(BRIDGE_CONFIG, null) ?: return null
        return runCatching {
            val config = JSONObject(raw)
            check(config.optInt("version") == BridgeClient.VERSION)
            BridgeClient(config.getInt("port"), config.getString("key"))
        }.getOrNull()
    }

    private suspend fun execute(path: String, args: Array<String>, timeoutMs: Long): CommandResult {
        try {
            requireConnection()
        } catch (exception: IllegalStateException) {
            return CommandResult(error = exception.message)
        }
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<CommandResult>()
        TermuxResultReceiver.pending[id] = result
        val callbackIntent = Intent(app, TermuxResultReceiver::class.java)
            .setData(Uri.parse("phoneagent-termux://result/$id"))
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(app, 0, callbackIntent, flags)
        try {
            val intent = commandIntent(path, args, true)
            intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
            val started = try {
                app.startService(intent)
            } catch (exception: RuntimeException) {
                return CommandResult(error = "Termux service could not start (${exception.javaClass.simpleName}).")
            }
            if (started == null) return CommandResult(error = "Termux RUN_COMMAND service is unavailable.")
            return withTimeoutOrNull(timeoutMs) { result.await() }
                ?: CommandResult(error = "Termux did not return a result. Check allow-external-apps and the Termux session.")
        } finally {
            TermuxResultReceiver.pending.remove(id)
            callback.cancel()
        }
    }

    private fun requireConnection() {
        val state = status()
        check(state.installed) { "Install Termux and open it once to finish setup." }
        check(state.supportsPrivateCommandResults) { "Termux 0.118 or later is required for private command results." }
        check(state.permissionGranted) { "Grant the Termux RUN_COMMAND permission." }
    }

    private fun requireCommand(command: String) {
        require(command.isNotBlank() && '\u0000' !in command && command.toByteArray().size <= MAX_COMMAND_BYTES) {
            "Enter a shell command no longer than 64 KiB."
        }
    }

    private fun validDirectory(directory: String): Boolean =
        directory.startsWith('/') && '\u0000' !in directory && directory.toByteArray().size <= 4096

    private fun commandIntent(path: String, args: Array<String>, background: Boolean) =
        Intent("com.termux.RUN_COMMAND").apply {
            setClassName(PACKAGE, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            if (background) putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", "0")
        }

    companion object {
        const val PACKAGE = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val BIN = "/data/data/com.termux/files/usr/bin"
        private const val BASH = "$BIN/bash"
        private const val BRIDGE_CONFIG = "connection"
        private const val MAX_COMMAND_BYTES = 64 * 1024
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val resultId = intent.data?.lastPathSegment ?: return
        val target = pending.remove(resultId) ?: return
        val bundle = intent.getBundleExtra("result")
        if (bundle == null) {
            target.complete(CommandResult(error = "Termux did not supply a result bundle."))
            return
        }
        val stdout = bundle.getString("stdout").orEmpty()
        val stderr = bundle.getString("stderr").orEmpty()
        val errorCode = bundle.getInt("err", Activity.RESULT_OK)
        val error = if (errorCode == Activity.RESULT_OK) {
            null
        } else {
            bundle.getString("errmsg")?.takeIf { it.isNotBlank() } ?: "Termux error $errorCode."
        }
        target.complete(
            CommandResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,
                error = error,
                truncated = (bundle.getString("stdout_original_length")?.toIntOrNull() ?: 0) > stdout.length ||
                    (bundle.getString("stderr_original_length")?.toIntOrNull() ?: 0) > stderr.length,
            ),
        )
    }

    companion object {
        internal val pending = ConcurrentHashMap<String, CompletableDeferred<CommandResult>>()
    }
}

package dev.phoneagent.providers

import dev.phoneagent.termux.TermuxRuntime

suspend fun checkCodexCliStatus(runtime: TermuxRuntime): String {
    val termux = runtime.status()
    if (!termux.installed) return "Install Termux and the Codex CLI to connect your account."
    if (!termux.permissionGranted) return "Grant permission for RUN_COMMAND in Terminal settings first."

    // Codex reports its active login mode on stderr. Return only a classified exit code to Android.
    val command = """
        command -v codex >/dev/null 2>&1 || exit 44
        status=$(codex login status 2>&1)
        result=$?
        if [ "${'$'}result" -eq 0 ]; then
            case "${'$'}status" in
                *"Logged in using ChatGPT"*) exit 0 ;;
                *) exit 42 ;;
            esac
        fi
        case "${'$'}status" in
            *"Not logged in"*) exit 43 ;;
            *) exit 45 ;;
        esac
    """.trimIndent()
    val result = runtime.run(command, workDir = "/data/data/com.termux/files/home", timeoutMs = 15_000)
    if (result.error != null) return "Could not reach Termux. Check the connection in Terminal settings."
    return when (result.exitCode) {
        0 -> "Codex CLI reports a ChatGPT sign-in."
        42 -> "Codex CLI reports an active connection using another sign-in method."
        43 -> "Codex CLI is signed out."
        44 -> "Codex CLI is unavailable on the Termux PATH."
        else -> "Codex CLI could not check its sign-in. Open Termux for details."
    }
}

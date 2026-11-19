# Phone Agent

Native Android IDE for local projects, an interactive Termux terminal, and API-backed coding agents. Supports Android 10 or later. The first development preview targets Android 15, including the OnePlus Ace 5.

**Project maintainer / Автор проекта** [@chitik1011-tech](https://github.com/chitik1011-tech).

Мобильная IDE для Android со встроенным терминалом Termux и ИИ-агентами для работы с кодом. Написана на Kotlin с Jetpack Compose.

## AI coding agent on phone

Phone Agent is a mobile IDE for Android with a code editor, a built-in Termux terminal, and AI coding agents. If you want **Codex on phone**, you can open the Codex CLI in Termux and sign in with your ChatGPT account once the CLI is installed.

Exploring **OpenCode** or **Google Antigravity** for mobile coding? Phone Agent gives you a native Android workspace for editing project files, running terminal commands, and working with an AI coding agent.

## Build

Install JDK 17 and the Android SDK platform and build-tools for API 35, then run:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
python3 -m unittest discover -s tools/termux
python3 -m unittest discover -s tools/workspace
```

The **Android** workflow publishes a preview after its checks pass. Set the four `PHONE_AGENT_*` signing secrets shown in the workflow to sign future previews with your release key.

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. To execute Android instrumented tests, connect a test device or emulator and run `./gradlew :app:connectedDebugAndroidTest`.

## First task

1. In **Workspace**, choose an Android folder or enter the exact path to a Termux project. An Android folder is a Storage Access Framework grant. To run builds, select the actual Termux project path.
2. In **Terminal**, open the setup screen, allow Termux external commands, grant RUN_COMMAND, and verify the connection. The embedded PTY bridge requires Termux 0.118 or later and Python in Termux. No root access is needed.
3. In **Settings → AI & models**, add a provider and test model discovery. Choose a model that supports tool calling. A hosted model receives the project text and tool results used in the session.
4. In **Agents**, describe a change. For a first project, try asking the agent to read a small text file, change one line, and run a corresponding test. Review the exact command and working directory before approving it.
5. Use **History** to view saved runs. To continue a stopped or interrupted run, reopen the original project and enter a follow-up. The app does not automatically replay a command when Android kills a process.

## Operating boundaries

- Interactive Termux uses the Termux app's user permissions. Scoped file tools check relative paths and reject common credential paths. Shell commands require individual approval because a Termux process can access more than the current project.
- File edits in Android folder grants follow the storage provider's write semantics. Termux project writes use atomic replacement. Text file operations support UTF-8 source files up to 20 KB.
- Independent reviewers use their own requests and context. Review tasks can read project files, and the main agent handles changes. A step limit bounds each run.
- Stopping an approved shell command closes its authenticated connection. The Termux bridge stops the command process group on disconnect or timeout. Short file helpers still use RUN_COMMAND with a bounded timeout. Interactive terminal tabs have their own interrupt and stop controls.
- The ChatGPT option opens and checks the official Codex CLI login in Termux. The native agent uses its separately configured API endpoint.

See [device checks](docs/device-checks.md) for verification on Android and ColorOS.

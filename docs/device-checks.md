# Phone Agent device checks

Run these on Android 15 / ColorOS 15 on the OnePlus Ace 5. Record Android version, Termux version, launcher APK version, and test outcome. Android install, Termux and ColorOS measurements remain pending until a compatible device is available.

| Area | Reproducible check |
|---|---|
| Install and launch | Install the debug APK using Android's installer or `adb install -r`. Open it in dark and light themes. Proceed through onboarding and rotate the device on the terminal and editor screens. |
| Android workspace | Pick an Android folder, open a text file, save an edit, relaunch, and reopen the same project. Verify the file with another editor. Revoke the grant in Android and check the reported error. |
| Termux permission | Disable `allow-external-apps`, leave RUN_COMMAND permission denied, and check the diagnosis. Enable it, grant permission, and test again. |
| Termux files | Run `pkg install python git`, create a project under Termux home, and open the path reported by `pwd`. Check a file edit in Termux, including an executable file whose mode should survive. |
| PTY | Open two terminal tabs. In one, run `printf '\\033[38;2;200;100;50mtruecolor\\033[0m\\n'`; in the second run `cat`, type UTF-8, and use Ctrl+C. Rotate and run `stty size`; verify both tabs and history. Reopen the terminal screen and reconnect. |
| Command results | Ask for `printf hello; printf error >&2; exit 7` in a test project. Approve once. Check separate stdout/stderr and exit code 7 in the log. Deny another command and verify it never ran. |
| Agent | Use a tool-capable provider model. Ask the agent to read and edit a small project file and run the project's tests. In READ_ONLY mode request the same edit and verify the file stays intact. |
| Review subagents | Ask for two independent reviews of a project issue. Check distinct IDs, overlapping work, and real reports. Ask a reviewer to modify a file and verify edits are unavailable. |
| Shell cancellation | Approve a build or an agent command that starts a child, then press Stop while it runs. Verify the process group exits in Termux and that an existing interactive terminal tab continues working. Repeat with Android Force stop, then reopen Phone Agent and check session recovery. |
| Persistence | Start a slow task, press Home, lock the screen, then unlock and return. Check actual running state or an interruption. Force stop, restart, and verify the session records interruption without rerunning an approved command. |
| Provider & credentials | Save a temporary API key, reopen settings, test model discovery, then delete the key. Run the Keystore instrumentation tests. Use an actual Termux Codex install for the CLI login and account-status paths. |
| Git | Open an existing Termux git repository. Compare status, diff, history, and branch lists against the same commands in Termux. |
| Background notification | Grant Android notification permission, start an agent, press Home, and stop the run from its notification. Repeat after declining permission and check the state on reopening the app. |
| MCP | Add a test Streamable HTTP server in Settings, verify tool discovery, open a read-only tool, and review its full arguments before sending. Check denied calls do not reach the server and invalid credentials produce an error. |
| Privilege diagnostics | Verify Shizuku stopped/running state, grant/deny app permission, and test binder disconnect. With a supported root manager, deny and then grant the fixed root UID diagnostic. |

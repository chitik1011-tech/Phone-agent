This preview improves cancellation for approved Termux shell commands. It keeps the existing interactive PTY and its terminal tabs separate from agent commands.

- Stopping an agent closes the command connection. The bridge terminates the command's process group on disconnect or timeout.
- Command results include separate stdout, stderr, an exit code, and a flag when either stream exceeds its 32 KiB capture limit.
- The app does not replay a submitted command if its connection is lost. A shell command that detaches into a separate process group can outlive the original group.

Install the attached APK on Android 10 or later. Termux 0.118 or later, Python in Termux, `allow-external-apps=true`, and the Android RUN_COMMAND permission are required for the bridge. A debug APK can be updated in place only by an APK signed with the same certificate.

The process-group tests run against real Linux child processes. Android delivery to Termux and the full OnePlus Ace 5 / ColorOS force-stop scenario still need a physical-device check. See `docs/device-checks.md`.

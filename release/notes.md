First Android development preview. This build uses Android's debug signing key unless release signing was configured.

- Native workspace for Android folder grants and private Termux project directories.
- Termux command results and an interactive local PTY terminal with multiple tabs.
- OpenAI-compatible agent tool calls, explicit shell approvals, and independent read-only review agents.
- Project editor, Git views and operations, encrypted provider settings, and a persisted agent session journal.
- Manual MCP tool discovery and invocation over Streamable HTTP, and Root/Shizuku access diagnostics.
- OLED and light monochrome themes.

Install the attached APK on Android 10 or later. To use the embedded terminal, install Termux, enable `allow-external-apps=true` in `~/.termux/termux.properties`, grant Phone Agent the RUN_COMMAND permission, and install Python in Termux. See the manual checks in `docs/device-checks.md`.

This is a development preview. Console and agent features require a working Termux install or an API endpoint where applicable. Termux permissions, ColorOS background behavior, and live provider accounts are device checks. ChatGPT authorization in this version signs in to the supported Codex CLI in Termux. Review subagents are read-only. Advanced LSP, GitHub pull-request workflows, and project device integration remain in development.

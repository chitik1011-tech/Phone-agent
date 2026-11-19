# Phone Agent development

- Preserve Android sandbox and user-granted project boundaries. Keep interactive Termux and automated project file access separate.
- Require confirmation for agent shell commands and privileged operations. Never pass provider credentials to Termux or repository files.
- Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and list device checks separately. Record any unverified integration in the release notes.
- Keep APKs and signing credentials out of Git.

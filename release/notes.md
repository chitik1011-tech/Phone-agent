This preview addresses a ColorOS installer report that the APK contains no signature files. The APK now includes v1 JAR signature files alongside its v2 and v3 signatures. CI verifies all three schemes before publication.

The app still targets Android 15 and supports Android 10 or later. The OnePlus Ace 5 installer needs a physical-device check. Android requires matching certificates for in-place updates from earlier builds. This preview uses the CI debug key unless release signing is configured.

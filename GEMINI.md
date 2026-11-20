# SimpMusic-HeyJarvis Project Rules

## Testing Guardrails
- **Always test on mute:** Before running any live desktop playback or Hey Jarvis voice test on Windows, mute system audio, and restore the original mute state immediately afterward.
- **Single-track playback invariant:** Voice play commands must stop/cancel any active track load or precache job and deduplicate queue entries by `videoId` so only one track plays at a time.

## Build & Process Hygiene
- **Zero leftover Java/JDK:** Do not leave a downloaded portable JDK (such as `.tmp-jdk21`) or running Gradle daemons behind. Always pass `--no-daemon`, delete the temporary JDK directory after building, and verify `tasklist /FI "IMAGENAME eq java.exe"` reports no running Java processes.

## Git & Release Safety
- **Never push to upstream:** `origin` points to `maxrave-dev/SimpMusic`. Always push commits and publish releases only to `myfork` (`hreyner7-collab/SimpMusic-HeyJarvis`, branch `main`).
- **Keep Windows and Android in sync:** Whenever publishing a new release, make sure both the Windows Desktop ZIP (`SimpMusic-HeyJarvis-Windows.zip`) and the Android APK (`SimpMusic-HeyJarvis-v2.6.0-Android.apk`) include the latest updates.

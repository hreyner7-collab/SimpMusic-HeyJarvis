---
name: simpmusic-build-release
description: >-
  Use this skill when building the SimpMusic-HeyJarvis Windows desktop app or
  Android APK, packaging releases, or publishing updates to
  hreyner7-collab/SimpMusic-HeyJarvis.
---

# SimpMusic-HeyJarvis Build & Release Workflow

## 1. Provision Temporary Portable Temurin JDK 21
1. Download Temurin 21 zip from Adoptium to `C:\Users\henry\Downloads\.tmp-jdk21`.
2. Ensure `gradle/gradle-daemon-jvm.properties` has `toolchainVendor=MICROSOFT` commented out.
3. For Android builds, write `sdk.dir=C:\\Users\\henry\\AppData\\Local\\Android\\Sdk` to `local.properties`.

## 2. Run Gradle Builds (PowerShell-Safe Quoting)
Always quote `-D` and `-P` flags in PowerShell so PowerShell does not split on dots, and always pass `--no-daemon`:

```powershell
$env:JAVA_HOME = "C:\Users\henry\Downloads\.tmp-jdk21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# Desktop distributable
& .\gradlew.bat ":desktopApp:createDistributable" "--no-daemon" `
  "-Dorg.gradle.java.home=C:\Users\henry\Downloads\.tmp-jdk21" `
  "-Porg.gradle.java.installations.paths=C:\Users\henry\Downloads\.tmp-jdk21" `
  "-Porg.gradle.java.installations.auto-detect=false"

# Android APK
& .\gradlew.bat ":androidApp:assembleDebug" "--no-daemon" `
  "-Dorg.gradle.java.home=C:\Users\henry\Downloads\.tmp-jdk21" `
  "-Porg.gradle.java.installations.paths=C:\Users\henry\Downloads\.tmp-jdk21" `
  "-Porg.gradle.java.installations.auto-detect=false"
```

## 3. Clean Up Temporary JDK & Verify Zero Java Processes
```powershell
Get-Process -Name java, javaw -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Remove-Item "C:\Users\henry\Downloads\.tmp-jdk21" -Recurse -Force -ErrorAction SilentlyContinue
tasklist /FI "IMAGENAME eq java.exe"
```
Confirm output is `INFO: No tasks are running which match the specified criteria.`

## 4. Publish to `myfork` (Never `origin`)
- Push source to `myfork` (`https://github.com/hreyner7-collab/SimpMusic-HeyJarvis.git`, branch `main`).
- Publish both `SimpMusic-HeyJarvis-Windows.zip` and `SimpMusic-HeyJarvis-v2.6.0-Android.apk` using `gh release create --repo hreyner7-collab/SimpMusic-HeyJarvis`.

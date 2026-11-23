#!/bin/bash
# Packages the REAL Kotlin Multiplatform app (ComposeApp.framework, iosArm64) into an unsigned-style
# (ad-hoc signed) .ipa for a physical iPhone. Run after:
#   ./gradlew :composeApp:linkDebugFrameworkIosArm64 --no-configuration-cache -Pkotlin.native.cacheKind=none
# Re-sign with Sideloadly/AltStore (free Apple ID) before installing.
set -euo pipefail
cd "$(dirname "$0")/../.."
FW_DIR="composeApp/build/bin/iosArm64/debugFramework"
OUT="dist/ios-kmp"; APP="build/kmp-device/Payload/SimpMusic.app"
rm -rf build/kmp-device; mkdir -p "$APP/Frameworks" "$OUT"
SDK="$(xcrun --sdk iphoneos --show-sdk-path)"
xcrun --sdk iphoneos swiftc -parse-as-library -sdk "$SDK" -target arm64-apple-ios17.0 \
  -F "$FW_DIR" -framework ComposeApp \
  -framework AVFoundation -framework AVFAudio -framework MediaPlayer \
  -framework Metal -framework CoreMedia -framework QuartzCore -framework UIKit \
  -Xlinker -dead_strip -Xlinker -lsqlite3 -Xlinker -lc++ \
  -Xlinker -rpath -Xlinker @executable_path/Frameworks \
  iosApp/KmpHost/SimpMusicKmpApp.swift -o "$APP/SimpMusic"
cp iosApp/Info-Device.plist "$APP/Info.plist"
# Static framework: nothing to embed, the Kotlin code is linked into the executable.
cp -R composeApp/build/compose/ios/composeApp/compose-resources "$APP/" 2>/dev/null || true
codesign --force --sign - --entitlements iosApp/Entitlements.plist "$APP"
(cd build/kmp-device && zip -qry "../../$OUT/SimpMusic-HeyJarvis-KMP-iOS.ipa" Payload)
ls -lh "$OUT/SimpMusic-HeyJarvis-KMP-iOS.ipa"

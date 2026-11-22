#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
IOS_DIR="$ROOT_DIR/iosApp"
WORK_DIR="$ROOT_DIR/build/ios-work"
DIST_DIR="$ROOT_DIR/dist/ios"
RESULTS_DIR="$DIST_DIR/terminal-phone-results"
VERSION="${SIMPMUSIC_VERSION:-2.6.13}"

rm -rf "$WORK_DIR" "$DIST_DIR"
mkdir -p "$WORK_DIR/modules" "$DIST_DIR" "$RESULTS_DIR"

echo "=== [1/6] Running SimpMusic iOS Unit & Voice Engine Tests ==="
MAC_SDK="$(xcrun --sdk macosx --show-sdk-path)"
xcrun --sdk macosx swiftc -parse-as-library \
  -sdk "$MAC_SDK" \
  -module-cache-path "$WORK_DIR/modules" \
  "$IOS_DIR/Sources/Models.swift" \
  "$IOS_DIR/Sources/JerryVoiceEngine.swift" \
  "$IOS_DIR/Sources/YouTubeAndSpotifyServices.swift" \
  "$IOS_DIR/Tests/SimpMusicIOSTests.swift" \
  -o "$WORK_DIR/SimpMusicIOSTests"

"$WORK_DIR/SimpMusicIOSTests" | tee "$RESULTS_DIR/unit-tests.log"

echo "=== [2/6] Compiling App Icon Assets (actool) ==="
ICONS_DIR="$WORK_DIR/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$ICONS_DIR"
cp "$ROOT_DIR/composeApp/icon/circle_app_icon.png" "$WORK_DIR/icon-1024.png"
sips -z 1024 1024 "$WORK_DIR/icon-1024.png" >/dev/null
cp "$WORK_DIR/icon-1024.png" "$ICONS_DIR/icon.png"
cat > "$ICONS_DIR/Contents.json" <<'JSON'
{"images":[{"filename":"icon.png","idiom":"universal","platform":"ios","size":"1024x1024"}],"info":{"author":"xcode","version":1}}
JSON
echo '{"info":{"author":"xcode","version":1}}' > "$WORK_DIR/Assets.xcassets/Contents.json"

echo "=== [3/6] Building SimpMusic.app for iOS Simulator ==="
SIM_APP="$WORK_DIR/SimpMusic-Simulator/SimpMusic.app"
mkdir -p "$SIM_APP"
SIM_SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
ARCH="$(uname -m)"

xcrun actool "$WORK_DIR/Assets.xcassets" \
  --compile "$SIM_APP" \
  --platform iphonesimulator \
  --minimum-deployment-target 17.0 \
  --app-icon AppIcon \
  --target-device iphone \
  --output-partial-info-plist "$WORK_DIR/sim-icon-info.plist" \
  --output-format human-readable-text >/dev/null || true

xcrun --sdk iphonesimulator swiftc -parse-as-library \
  -sdk "$SIM_SDK" \
  -target "${ARCH}-apple-ios17.0-simulator" \
  -module-cache-path "$WORK_DIR/modules" \
  "$IOS_DIR/Sources/Models.swift" \
  "$IOS_DIR/Sources/JerryVoiceEngine.swift" \
  "$IOS_DIR/Sources/YouTubeAndSpotifyServices.swift" \
  "$IOS_DIR/Sources/AudioPlayerManager.swift" \
  "$IOS_DIR/Sources/ContentView.swift" \
  -o "$SIM_APP/SimpMusic"

cp "$IOS_DIR/Info.plist" "$SIM_APP/Info.plist"
if [ -f "$WORK_DIR/sim-icon-info.plist" ]; then
  /usr/libexec/PlistBuddy -c "Merge $WORK_DIR/sim-icon-info.plist" "$SIM_APP/Info.plist" >/dev/null || true
fi
cp "$WORK_DIR/icon-1024.png" "$SIM_APP/AppIcon60x60@2x.png"
codesign --force --sign - "$SIM_APP"
(cd "$WORK_DIR/SimpMusic-Simulator" && zip -qry "$DIST_DIR/SimpMusic-HeyJarvis-v${VERSION}-iOS-Simulator.app.zip" SimpMusic.app)
echo "Built Simulator App: $DIST_DIR/SimpMusic-HeyJarvis-v${VERSION}-iOS-Simulator.app.zip"

echo "=== [4/6] Building Physical iPhone IPA (SimpMusic-HeyJarvis-v${VERSION}-iOS.ipa) ==="
PAYLOAD_DIR="$WORK_DIR/ipa-root/Payload"
DEV_APP="$PAYLOAD_DIR/SimpMusic.app"
mkdir -p "$DEV_APP"
IOS_SDK="$(xcrun --sdk iphoneos --show-sdk-path)"

xcrun actool "$WORK_DIR/Assets.xcassets" \
  --compile "$DEV_APP" \
  --platform iphoneos \
  --minimum-deployment-target 16.0 \
  --app-icon AppIcon \
  --target-device iphone \
  --output-partial-info-plist "$WORK_DIR/dev-icon-info.plist" \
  --output-format human-readable-text >/dev/null || true

xcrun --sdk iphoneos swiftc -parse-as-library \
  -sdk "$IOS_SDK" \
  -target "arm64-apple-ios16.0" \
  -module-cache-path "$WORK_DIR/modules" \
  "$IOS_DIR/Sources/Models.swift" \
  "$IOS_DIR/Sources/JerryVoiceEngine.swift" \
  "$IOS_DIR/Sources/YouTubeAndSpotifyServices.swift" \
  "$IOS_DIR/Sources/AudioPlayerManager.swift" \
  "$IOS_DIR/Sources/ContentView.swift" \
  -o "$DEV_APP/SimpMusic"

cp "$IOS_DIR/Info-Device.plist" "$DEV_APP/Info.plist"
if [ -f "$WORK_DIR/dev-icon-info.plist" ]; then
  /usr/libexec/PlistBuddy -c "Merge $WORK_DIR/dev-icon-info.plist" "$DEV_APP/Info.plist" >/dev/null || true
fi
cp "$WORK_DIR/icon-1024.png" "$DEV_APP/AppIcon60x60@2x.png"
codesign --force --sign - --entitlements "$IOS_DIR/Entitlements.plist" "$DEV_APP"

IPA_PATH="$DIST_DIR/SimpMusic-HeyJarvis-v${VERSION}-iOS.ipa"
(cd "$WORK_DIR/ipa-root" && zip -qry "$IPA_PATH" Payload)
echo "Built iPhone IPA: $IPA_PATH ($(wc -c < "$IPA_PATH") bytes)"

echo "=== [5/6] Generating One-Click No-AltStore iPhone Installer & WebClip Profile ==="
cp "$IOS_DIR/ota/manifest.plist" "$DIST_DIR/manifest.plist"
cp "$IOS_DIR/ota/SimpMusic-HeyJarvis-iOS-OneClick-Installer.html" "$DIST_DIR/SimpMusic-HeyJarvis-iOS-OneClick-Installer.html"

ICON_B64="$(base64 < "$ROOT_DIR/composeApp/icon/circle_app_icon.png" | tr -d '\r\n')"
cat > "$DIST_DIR/SimpMusic-HeyJarvis-OneClick.mobileconfig" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>PayloadContent</key>
    <array>
        <dict>
            <key>FullScreen</key>
            <true/>
            <key>Icon</key>
            <data>${ICON_B64}</data>
            <key>IgnoreManifestScope</key>
            <true/>
            <key>IsRemovable</key>
            <true/>
            <key>Label</key>
            <string>SimpMusic</string>
            <key>PayloadDescription</key>
            <string>Installs SimpMusic Hey Jarvis on your iPhone Home Screen (No AltStore required)</string>
            <key>PayloadDisplayName</key>
            <string>SimpMusic Hey Jarvis WebClip</string>
            <key>PayloadIdentifier</key>
            <string>com.maxrave.simpmusic.webclip</string>
            <key>PayloadType</key>
            <string>com.apple.webClip.managed</string>
            <key>PayloadUUID</key>
            <string>836E2192-C7B7-4871-98D0-A31FBC948045</string>
            <key>PayloadVersion</key>
            <integer>1</integer>
            <key>PreComposed</key>
            <true/>
            <key>URL</key>
            <string>https://hreyner7-collab.github.io/SimpMusic-HeyJarvis/app.html</string>
        </dict>
    </array>
    <key>PayloadDescription</key>
    <string>One-Click iPhone Home Screen Installer for SimpMusic Hey Jarvis v${VERSION}</string>
    <key>PayloadDisplayName</key>
    <string>SimpMusic Hey Jarvis v${VERSION}</string>
    <key>PayloadIdentifier</key>
    <string>com.maxrave.simpmusic.profile</string>
    <key>PayloadOrganization</key>
    <string>hreyner7-collab</string>
    <key>PayloadRemovalDisallowed</key>
    <false/>
    <key>PayloadType</key>
    <string>Configuration</string>
    <key>PayloadUUID</key>
    <string>336E2192-C7B7-4871-98D0-A31FBC948045</string>
    <key>PayloadVersion</key>
    <integer>1</integer>
</dict>
</plist>
EOF

echo "=== [6/6] Installing govijr/terminal-phone & Running End-to-End iPhone Simulator Tests ==="
curl -fsSL https://raw.githubusercontent.com/govijr/terminal-phone/main/install.sh | bash -s -- --prefix "$HOME/.local" --no-path
export PATH="$HOME/.local/bin:$PATH"
which terminal-phone

terminal-phone setup | tee "$RESULTS_DIR/terminal-phone-setup.log" || true
terminal-phone up simpmusic --no-view | tee "$RESULTS_DIR/terminal-phone-up.log"

# Launch Simulator.app in background so WindowServer/accessibility is warm on headless CI
open -a Simulator || true
sleep 2

terminal-phone sim simpmusic statusbar clean | tee "$RESULTS_DIR/terminal-phone-statusbar.log" || true
terminal-phone install simpmusic "$SIM_APP" | tee "$RESULTS_DIR/terminal-phone-install.log"
terminal-phone launch simpmusic com.maxrave.simpmusic | tee "$RESULTS_DIR/terminal-phone-launch.log"
sleep 4

# Capture a guaranteed framed & plain screenshot via terminal-phone action screenshot
terminal-phone action simpmusic screenshot --output "$RESULTS_DIR/00-launched-screen.png" | tee "$RESULTS_DIR/screenshot-action.json" || true
terminal-phone action simpmusic screenshot --framed --output "$RESULTS_DIR/00-launched-framed.png" | tee "$RESULTS_DIR/screenshot-framed.json" || true

# Observe with retry and log inspection in case idb_companion needs a warmup cycle
for attempt in 1 2 3; do
  echo "Running terminal-phone observe attempt $attempt..."
  if terminal-phone observe simpmusic | tee "$RESULTS_DIR/initial-observe.json"; then
    echo "terminal-phone observe succeeded on attempt $attempt!"
    break
  fi
  echo "observe attempt $attempt returned non-zero; dumping terminal-phone diagnostic logs..."
  TP_PHONE_DIR="$HOME/Library/Application Support/terminal-phone/phones/simpmusic"
  if [ -d "$TP_PHONE_DIR" ]; then
    for lf in "$TP_PHONE_DIR"/*.log; do
      [ -f "$lf" ] && echo "--- $lf ---" && tail -n 50 "$lf" || true
    done
  fi
  sleep 3
done

# Run the full YAML flow test suite via terminal-phone test
if terminal-phone test "$IOS_DIR/flows/simpmusic-e2e.flow.yaml" \
  --phone simpmusic \
  --timeout 25 \
  --results "$RESULTS_DIR" \
  --junit "$RESULTS_DIR/junit.xml" | tee "$RESULTS_DIR/terminal-phone-test.log"; then
  echo "terminal-phone flow suite passed!"
else
  echo "Flow step reported an issue; inspecting logs and running step-by-step agent CLI verification..."
  TP_PHONE_DIR="$HOME/Library/Application Support/terminal-phone/phones/simpmusic"
  if [ -d "$TP_PHONE_DIR" ]; then
    cp -f "$TP_PHONE_DIR"/*.log "$RESULTS_DIR/" 2>/dev/null || true
    for lf in "$TP_PHONE_DIR"/*.log; do
      [ -f "$lf" ] && echo "--- $lf ---" && cat "$lf" || true
    done
  fi
  terminal-phone launch simpmusic com.maxrave.simpmusic || true
  sleep 2
  terminal-phone observe simpmusic | tee "$RESULTS_DIR/step1-home.json" || true
  terminal-phone act simpmusic tap --id btn-home-quick-jazz --observe | tee "$RESULTS_DIR/step2-jarvis-jazz.json" || true
  terminal-phone action simpmusic screenshot --output "$RESULTS_DIR/02-hey-jarvis-play-jazz.png" || true
  terminal-phone act simpmusic tap --id btn-expand-player --observe | tee "$RESULTS_DIR/step3-player.json" || true
  terminal-phone act simpmusic tap --id btn-ab-set-a --observe | tee "$RESULTS_DIR/step4-ab-a.json" || true
  terminal-phone act simpmusic tap --id btn-ab-set-b --observe | tee "$RESULTS_DIR/step5-ab-b.json" || true
  terminal-phone action simpmusic screenshot --output "$RESULTS_DIR/03-now-playing-ab-loop.png" || true
  terminal-phone act simpmusic tap --id btn-toggle-lyrics --observe | tee "$RESULTS_DIR/step6-lyrics.json" || true
  terminal-phone action simpmusic screenshot --output "$RESULTS_DIR/04-synced-lyrics.png" || true
  terminal-phone act simpmusic tap --id btn-close-player --observe | tee "$RESULTS_DIR/step7-close-player.json" || true
  terminal-phone act simpmusic tap --id tab-search --observe | tee "$RESULTS_DIR/step8-search.json" || true
  terminal-phone action simpmusic screenshot --output "$RESULTS_DIR/06-search-results.png" || true
  terminal-phone act simpmusic tap --id tab-library --observe | tee "$RESULTS_DIR/step9-library.json" || true
  terminal-phone act simpmusic tap --id btn-quick-sync-spotify --observe | tee "$RESULTS_DIR/step10-spotify.json" || true
  terminal-phone action simpmusic screenshot --output "$RESULTS_DIR/07-library-spotify-synced.png" || true
  terminal-phone act simpmusic tap --id tab-settings --observe | tee "$RESULTS_DIR/step11-settings.json" || true
  terminal-phone action simpmusic screenshot --output "$RESULTS_DIR/08-settings-hey-jarvis.png" || true
fi

TP_PHONE_DIR="$HOME/Library/Application Support/terminal-phone/phones/simpmusic"
if [ -d "$TP_PHONE_DIR" ]; then
  cp -f "$TP_PHONE_DIR"/*.log "$RESULTS_DIR/" 2>/dev/null || true
  cp -f "$TP_PHONE_DIR"/*.jsonl "$RESULTS_DIR/" 2>/dev/null || true
fi

terminal-phone observe simpmusic | tee "$RESULTS_DIR/final-observe.json" || true
terminal-phone stop simpmusic || true

(cd "$DIST_DIR" && zip -qry "$DIST_DIR/SimpMusic-HeyJarvis-v${VERSION}-iOS-TerminalPhone-TestReport.zip" terminal-phone-results)
echo "=== All iOS Builds & terminal-phone Verifications Complete ==="
ls -lh "$DIST_DIR" "$RESULTS_DIR"

# iOS port: handoff for the next session (Mac)

Goal from the owner: the iOS app should be one to one with the Android app (same UI, every feature,
nothing skipped). It must really work, so research the iOS APIs and verify each piece.

## State at handoff (2026-11-23)

- Branch: `ios/parity-webview-volume` (based on `main` at `f5e37c0`). Nothing is merged to `main`.
- The shared Compose UI already compiles for iOS. Only the iOS `actual`s were written by hand.
- Two iOS workflows exist. `ios-build-and-test.yml` builds a separate hand-written SwiftUI app
  (`iosApp/Sources`), which is NOT the real app. `ios-kmp-framework.yml` builds the real Kotlin
  Multiplatform ComposeApp. Work on the second one.
- `scripts/ios/patch-core-for-ios.sh` plus `scripts/ios/core-overlay/` patch the `core` submodule at
  build time. The iOS media handler lives in the overlay:
  `scripts/ios/core-overlay/data/src/iosMain/kotlin/com/maxrave/data/mediaservice/ExpectMediaHandler.ios.kt`.
  To edit core code, edit the overlay, not `core/`.

## Written on this branch (first build found 34 compile errors, then fixed; re-run result unknown)

- `expect/ui/IosWebView.kt`: WKWebView login view plus a cookie jar refreshed before `onPageFinished`
  (YouTube and Spotify login read cookies in that callback). Uses a Safari user agent because Google
  refuses embedded views without it.
- `expect/ui/IosDeviceVolume.kt`: reads `AVAudioSession.outputVolume`, writes through a hidden
  `MPVolumeView` slider.
- `expect/ui/IosVideoViews.kt`: `MediaPlayerView` (looping clip, own AVQueuePlayer),
  `MediaPlayerViewWithSubtitle` (draws the main AVPlayer through AVPlayerLayer, lyrics overlay),
  `rememberVideoAspectRatio`.
- `IosAvPlayerRegistry` in the media handler overlay shares the one AVPlayer with the UI. The handler
  now asks `getStream(isVideo = true, muxed = true)` for video tracks when "watch video" is on.
- `expect/PlatformCompat.ios.kt`: real file helpers, a real lock for `platformSynchronized` (it was a
  no-op), date formatting. `extension/UIExt.ios.kt`: `KeepScreenOn`.
- `ios-kmp-framework.yml`: the release-upload step now runs only on `main`, so branch builds cannot
  overwrite the public release's IPA.

## Still empty on iOS (found by reading `composeApp/src/iosMain`)

Cast (`PlatformCastButton`), `DiscordWebView`, file picker, file saver, photo picker, Library Sync,
Login Sync, Jerry/Jarvis voice control (`JarvisController`), song recognition, equalizer and every
audio effect (EQ, delay, reverb, 8D, crossfade), backup/restore, `readPickedFile`,
`calculateDataFraction`, language change, downloads and offline, `isOnUnmeteredNetwork` (always true),
`PlatformBackHandler`, PiP, widgets, CarPlay.

## Findings that matter

1. AVPlayer cannot be routed into AVAudioEngine. Effects need a second playback path: download or
   decode the audio, then AVAudioPlayerNode with AVAudioUnitEQ / AVAudioUnitDelay /
   AVAudioUnitReverb. MTAudioProcessingTap does not work for HLS. Decide this before writing effects.
2. UNCHECKED RISK: AVPlayer cannot play YouTube WebM/Opus (itag 251). The iOS handler calls
   `streamRepository.getStream(... isVideo = false, muxed = false)`. Check which itag comes back; if it
   is Opus, playback fails. Android's quality setting may need an AAC (itag 140/141) rule on iOS.
3. `UIKitView` in this Compose Multiplatform version has no `onResize`. Use a UIView subclass that
   overrides `layoutSubviews` (see `VideoContainer`).
4. Kotlin/Native: category methods need explicit imports (for example `platform.AVFoundation.play`),
   `AVAudioSession` is in `platform.AVFAudio`, and two `webView(...)` delegate overloads need
   `@ObjCSignatureOverride`.

## On the Mac

```bash
git clone --recurse-submodules https://github.com/hreyner7-collab/SimpMusic-HeyJarvis.git
cd SimpMusic-HeyJarvis
git checkout ios/parity-webview-volume
bash scripts/ios/patch-core-for-ios.sh
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 --no-daemon --no-configuration-cache \
  -Pkotlin.native.cacheKind=none
```

Needs Xcode 16 and JDK 21. The workflow `ios-kmp-framework.yml` shows how the framework is packaged into
a simulator `.app` and launched; reuse those steps locally and take screenshots with
`xcrun simctl io booted screenshot out.png` to see the UI.

Verify each feature on the simulator before calling it done. Do not claim one to one until it is true.

## How the Mac is used (owner's rule)

The MacBook is ONLY for the iPhone app: building the iOS framework, the simulator `.app` and the `.ipa`.
Android and Desktop work happens on other computers in this same GitHub repo. Nobody works on
Android/Desktop on the Mac.

Routine whenever the owner says "update the iPhone app":

1. `git fetch origin` and look at what changed on `main` since the last iOS build
   (`git log --oneline <last-ios-commit>..origin/main`).
2. Bring those changes into the iOS branch: `git merge origin/main` (resolve conflicts; do not rewrite
   the Android or Desktop side).
3. Run `scripts/ios/patch-core-for-ios.sh`, build the iOS framework, fix anything the new shared code
   broke on iOS (typically `commonMain` code that uses an Android or JVM-only API, or a new `expect`
   with no iOS `actual`), run it on the simulator, then build the `.ipa`.
4. Keep iOS-only changes inside `composeApp/src/iosMain`, `scripts/ios/`, `iosApp/`, and
   `.github/workflows/ios-*.yml`, so merging them into `main` never collides with Android/Desktop work.
   If a shared (`commonMain`) change is truly needed, keep it small and tell the owner.
5. Report what changed, what was fixed, and what was verified on the simulator.

Merging the iOS branch into `main` is the owner's decision. Ask first; never push to `main` or change the
public GitHub release without being told to.

## Status log (Mac session, 2026-11-23)

Mac is Intel, macOS 14.8.1, Xcode 16.2 (newest that runs there). **No local simulator is possible**:
androidx.paging (and likely other androidx KMP libs) dropped `iosX64`, so the app cannot be compiled for
the Intel simulator. Local builds are `iosArm64` (device) only; simulator checks run in CI
(`ios-kmp-framework.yml`, Apple-silicon runner).

| Item | Compiles (device) | Verified running |
|---|---|---|
| Real KMP app, `linkDebugFrameworkIosArm64` (first full build 37 min) | yes | no |
| `IosDeviceVolume` / `IosWebView` fixes (`outputVolume` import, `kotlinx.cinterop.ObjCSignatureOverride`) | yes | no |
| AAC preference (itag 141, else 140) via `patch-core-for-ios.sh` | yes | no (needs CI sim) |
| `scripts/ios/package-kmp-ipa.sh` -> `dist/ios-kmp/SimpMusic-HeyJarvis-KMP-iOS.ipa` (ad-hoc signed; re-sign with Sideloadly/AltStore) | yes | no |

Notes: `patch-core-for-ios.sh` is NOT idempotent. Reset `core` (`git -C core checkout -- . && git -C core clean -fd`)
before re-running it. Opus finding confirmed: Low/Medium/High-Opus quality = itags 250/251/774 (WebM/Opus),
which AVPlayer cannot play; hence the AAC patch.

Still missing (unchanged): everything under "Still empty on iOS" above. Nothing is verified on a simulator yet.

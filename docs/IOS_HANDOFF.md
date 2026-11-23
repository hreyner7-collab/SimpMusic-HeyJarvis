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

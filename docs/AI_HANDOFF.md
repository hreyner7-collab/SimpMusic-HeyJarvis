# SimpMusic Hey Jarvis: handoff for another AI

Repo: hreyner7-collab/SimpMusic-HeyJarvis (fork of maxrave-dev/SimpMusic). Latest release: v2.6.25 on branch `release/2.6.25`.
Never push to `main`; each release is a `release/<version>` branch plus a `v<version>-heyjarvis` GitHub release.
Read `CLAUDE.md` first: it is the architecture guide.

## How to rebuild
- `core/` is a git submodule (maxrave-dev/core, pinned 8413a257). The author's changes to it are NOT in the submodule:
  apply every file in `patches/core/` (see `scripts/apply-core-patches.sh`). The 0002 patch was generated with
  `git diff --binary` after `git add -N`, and has not yet been test-applied on a clean checkout.
- Windows desktop: JDK 21 (a Temurin copy is used as `JAVA_HOME`), `gradlew :desktopApp:createDistributable --no-daemon`,
  then `scripts/windows/build_installer_exe.ps1 -AppDir <build>\SimpMusic -IconPath composeApp\icon\circle_app_icon.ico -Version X -OutputExe <exe>`.
- Release: asset must be named `SimpMusic-HeyJarvis-Setup.exe`; the in-app updater (`AppUpdater.jvm.kt`, `UPDATE_REPO_LATEST_API`)
  downloads it from the latest release when its version is higher. Version lives in `gradle/libs.versions.toml` (version-name / version-code).
- No secrets are in the repo. The user sets their own AI key in Settings > AI (Custom OpenAI; for Groq use base URL
  `https://api.groq.com/openai/v1`, model `openai/gpt-oss-120b`).

## What was done in this project (newest first)
- 2.6.25: DJ speaks only during a DJ session (started from the DJ tile / screen); music is paused while the DJ talks and resumed after;
  the DJ ring button in the Desktop play bar shows only while a DJ session is active and opens the DJ screen.
- 2.6.24: DJ ring button added to the play bar. Custom OpenAI base URL gets a trailing slash automatically (a missing slash dropped `/v1`).
- 2.6.23: Mic input in the DJ screen (Windows speech recognition), DJ tile on Home, DJ result when searching "dj", friendlier AI errors.
- 2.6.16 to 2.6.22: Spotify-style DJ (Library card, own DJ screen, blue tile with green ring, mixes of favourites / forgotten / fresh songs,
  "Ask DJ" box, chips, Piper offline neural voice (Lessac) with Windows system voice fallback), stereo widener audio effect.
- 2.6.15: Spotify library sync through the user's own Spotify developer app (PKCE login) to avoid rate-limit errors.
- 2.6.14: Faster song start (hedged stream lookups, warm-up cache, TIDAL pause after 429).
- 2.6.13 and earlier: playback-error diagnostics for the "Time out ... ERROR_UNKNOWN" bug (root cause never proven; network resets
  `tls: IO error -10054` seen in logs, possibly VPN/antivirus), in-app updater from GitHub releases.

## Known gaps
- iOS: files exist on branch `ios/parity-webview-volume` and `docs/IOS_HANDOFF.md`; never confirmed to build or run. Missing: effects,
  Cast/AirPlay, library sync, DJ voice, pickers, backup, downloads. macOS CI runners were often unavailable.
- mpv `af-command simpDjSweep ... failed` warnings flood the log around crossfades (playback still works).
- DJ play-bar button and pause-while-speaking were built but not verified on screen.
- Old "speak every N songs" DJ setting is now unused.
- The Android DJ mic is hidden (no speech-recognition permission flow yet); Android has no DJ play-bar button.

## Working style the user wants
- Plain, short answers. The user types quickly with typos: infer intent.
- Do the testing yourself on the PC; publish only when the user says "Publish it".
- Never enter or hardcode API keys. The user once pasted a Groq key in chat: treat it as leaked and tell them to rotate it.

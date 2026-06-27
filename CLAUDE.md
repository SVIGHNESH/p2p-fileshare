# P2P File Sharing App — CLAUDE.md

## Project Overview
A cross-platform peer-to-peer file sharing app built in Java. Supports Linux, Windows, and Android. Uses a hybrid P2P architecture with a central tracker for peer discovery and direct peer-to-peer file transfer.

## Module Structure
```
p2p-fileshare/
├── core/        # Shared Java library (used by all platforms)
├── tracker/     # Standalone tracker server (runs on PC/server)
├── desktop/     # JavaFX desktop app (Linux/Windows)
└── android/     # Android app (API 26+)
```

## Build System
- **Desktop/Tracker**: Do NOT use Gradle — use the manual build scripts instead. Java 25/26 is incompatible with Gradle 8/9.
- **Android**: Uses Gradle with AGP 8.3.2, Gradle 9.3.0. Run from inside `android/` directory.

## Build Commands
```bash
# Build core + tracker + desktop (run from project root)
./scripts/build.sh

# Package core into android/libs/p2p-core.jar (run after build.sh)
./scripts/build-android-libs.sh

# Run tracker
./scripts/run-tracker.sh [port]

# Run desktop app
./scripts/run-desktop.sh

# Build Android APK (run from android/)
cd android && ./gradlew assembleDebug

# Install APK via adb
~/Android/Sdk/platform-tools/adb install -r android/build/outputs/apk/debug/P2PShare-debug.apk
```

## Key Architecture Decisions

### TLS
- `TLSHelper` generates a self-signed PKCS12 keystore at runtime
- On desktop: stored at `~/.p2pshare/keystore.p12`
- On Android: stored at `context.getFilesDir()/p2pshare/keystore.p12`
- **Android does NOT have `keytool`** — keystore is generated via `KeyPairGenerator` in Java
- **Android filesystem root `/` is read-only** — always use `context.getFilesDir()` for storage
- `TLSHelper.init(File)` must be called before `PeerServer` starts. On Android, `AppState.init()` does this.

### Networking
- Tracker port: 9000
- Peer server port: 9001
- UDP multicast discovery: group `230.0.0.1`, port 9002
- All network calls must run on background threads on Android (not main thread)

### Android-Specific
- `AppState.init()` is called from `TransferService.onCreate()` on a background thread
- `TransferService` requires `FOREGROUND_SERVICE_DATA_SYNC` permission (Android 14+)
- Gson is bundled inside `android/libs/p2p-core.jar` — do NOT add `gson` as a separate dependency in `android/build.gradle` (causes duplicate class error)
- Color state lists go in `res/color/`, not `res/values/`
- Use `android:textColorHint` not `android:hintTextColor` in XML layouts

### Desktop
- JavaFX 21 is modular — must use `--module-path` and `--add-modules`, not `-cp`
- Dark theme: background `#0f0f13`, surface `#16161f`, accent `#7c6ef7`

## Dependencies
- **Gson 2.10.1** — JSON serialization for protocol messages
- **JavaFX 21** — desktop UI (from `~/.m2` Maven local cache)
- **AndroidX AppCompat 1.6.1**, **Material 1.11.0**, **Fragment 1.6.2** — Android UI
- `android.useAndroidX=true` and `android.enableJetifier=true` must be in `android/gradle.properties`

## Common Pitfalls
- Always run `./scripts/build.sh` then `./scripts/build-android-libs.sh` before building the Android APK, otherwise `android/libs/p2p-core.jar` will be stale or missing
- The `android/` directory is its own Gradle root project — run `./gradlew` from inside `android/`, not from the project root
- `android/libs/` is gitignored — regenerate with `build-android-libs.sh` after cloning
- The APK output name is `P2PShare-debug.apk`, not `android-debug.apk`

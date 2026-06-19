# P2P Share — Secure LAN File Sharing

A cross-platform peer-to-peer file sharing app for **Windows, Linux, and Android**.  
Built entirely in Java with a shared core engine and platform-specific UIs.

---

## Architecture

```
p2p-fileshare/
├── core/      Pure Java 11 — transfer engine, chunking, TLS, discovery
├── tracker/   Standalone tracker server (runnable JAR)
├── desktop/   JavaFX app — Windows & Linux
└── android/   Android app — Java + Material UI
```

---

## How It Works

1. **One person** on the network runs the **Tracker** (like a bulletin board — it just keeps a list of who has what).
2. Everyone else opens the **desktop or Android app**.
3. The app **auto-discovers** the tracker on the same Wi-Fi (no IP typing needed).
4. You can **search** for files others are sharing and **download** them directly — peer to peer.
5. All transfers are **encrypted with TLS 1.3** and verified with **SHA-256 checksums**.

---

## Prerequisites

- Java 17+ (for desktop and tracker)
- Android Studio (for Android app)
- Gradle 8

---

## Running the Tracker (one machine per group)

```bash
cd p2p-fileshare
./gradlew :tracker:jar
java -jar tracker/build/libs/tracker-1.0.0.jar
# Optional: specify a custom port
java -jar tracker/build/libs/tracker-1.0.0.jar 9000
```

The tracker starts broadcasting its presence over UDP multicast — all peers on the same Wi-Fi will find it automatically within seconds.

---

## Running the Desktop App (Windows / Linux)

```bash
./gradlew :desktop:run
```

Or build a packaged installer:
```bash
# Linux .deb / .AppImage
./gradlew :desktop:jar
jpackage --input desktop/build/libs \
         --main-jar desktop-1.0.0.jar \
         --main-class com.p2p.desktop.Main \
         --name "P2P Share" \
         --type deb   # or: app-image, exe (on Windows)
```

---

## Building the Android App

1. Open the `android/` folder in **Android Studio**
2. Sync Gradle
3. Connect a device or start an emulator
4. Click **Run**

> The Android app requires Android 8.0+ (API 26+)

---

## Features

| Feature | Status |
|---------|--------|
| Peer discovery via UDP multicast | ✅ |
| File search across the network | ✅ |
| Parallel multi-peer downloads | ✅ |
| Resumable downloads (crash-safe) | ✅ |
| TLS 1.3 encryption (auto, no setup) | ✅ |
| SHA-256 chunk verification | ✅ |
| Desktop app (Windows + Linux) | ✅ |
| Android app | ✅ |
| Heartbeat / dead peer eviction | ✅ |

---

## LAN-Only Note

Direct TCP connections require both peers to be on the **same local network** (same Wi-Fi or same LAN). Mobile devices on cellular data sit behind carrier NAT and cannot be reached directly — this is a known limitation of direct P2P without a relay server.

**For demos and academic use: connect both devices to the same Wi-Fi.**

---

## Project Modules

### `core/` — Shared Engine
- `Protocol.java` — all message types (JSON)
- `FileChunker.java` — splits files into 512 KB chunks
- `FileReassembler.java` — reassembles + resume state
- `PeerServer.java` — TLS server, serves chunks on request
- `DownloadManager.java` — parallel multi-peer downloader
- `TrackerClient.java` — register/query the tracker
- `UDPDiscovery.java` — LAN multicast auto-discovery
- `TLSHelper.java` — generates self-signed cert at startup

### `tracker/` — Index Server
- `TrackerServer.java` — accepts registrations, answers queries
- `PeerRegistry.java` — in-memory peer/file index with heartbeat

### `desktop/` — JavaFX App
- 4 tabs: **Search**, **Downloads**, **My Files**, **Settings**
- Dark theme, progress bars, live download updates
- File picker for adding files to share

### `android/` — Android App
- Bottom navigation: Search / Downloads / My Files / Settings
- Foreground `TransferService` keeps transfers alive in background
- Same dark theme as desktop

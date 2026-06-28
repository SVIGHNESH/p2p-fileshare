# P2P File-Share - Improvement Roadmap

A prioritized set of improvement ideas from a full codebase scan across `core`, `tracker`, `desktop`, and `android`.
Each item is framed as: what exists now (with `file:line`), why it matters, and the proposed change.
Nothing here has been implemented yet; this is a planning document.

## How to read this

Priority legend:

- **P0** - correctness or compliance bug; breaks core functionality or silently produces bad results.
- **P1** - robustness, security, or reliability gap that bites under real use (failures, concurrency, leaks).
- **P2** - scalability, UX, or significant maintainability improvement.
- **P3** - polish, cleanups, lower-risk nits.

The overall architecture is sound.
The highest-leverage work is concentrated in the transfer engine, security, and the Android storage/permission model.

---

## Tier 0 - The highest-leverage fixes

These five give the most value per unit of work.
They are the difference between the P2P core being a demo and being robust.

### T0.1 - Stop doing a TLS handshake per chunk (P1, performance)

- **Now:** `DownloadManager.downloadChunk` opens a fresh TLS socket for every 512 KB chunk (`core/.../transfer/DownloadManager.java:140`), and `PeerServer` serves exactly one request per connection then closes it (`core/.../transfer/PeerServer.java:52`).
- **Problem:** A full TLS handshake per chunk is the single biggest throughput killer in the system.
- **Fix:** Keep one TLS connection open per peer and pipeline many chunk requests over it, using length-prefixed framing so multiple request/response pairs can share a socket.

### T0.2 - Fix the download thread-pool deadlock (P0, concurrency)

- **Now:** One `Executors.newFixedThreadPool(8)` runs both the per-file coordinator (which blocks on `f.get()`) and the per-chunk workers (`core/.../transfer/DownloadManager.java:51,55,96,112`).
- **Problem:** Start 8 downloads and all 8 threads sit as coordinators blocking on `get()`, leaving zero threads to actually fetch chunks.
This is a classic pool-starvation deadlock.
- **Fix:** Separate the coordinator pool from the chunk-worker pool, or move to `Executors.newVirtualThreadPerTaskExecutor()` on Java 25 and let it scale naturally.

### T0.3 - Add peer failover and per-chunk retry (P1, reliability)

- **Now:** Chunks are round-robin pinned to peers via `task.peers.get(i % task.peers.size())` (`core/.../transfer/DownloadManager.java:93`), with no retry or failover.
- **Problem:** If one peer is down, every chunk assigned to it fails permanently and the whole download goes FAILED, even when other healthy peers have the file.
- **Fix:** Use a shared work-queue of missing chunks that any worker pulls from, requeue a failed chunk to a different healthy peer, and track a per-peer failure counter to stop using dead peers.

### T0.4 - Verify the whole file, not just chunk presence (P0, correctness)

- **Now:** `FileChunker.sha256OfFile` exists but is never called; completion only checks that every chunk slot is filled (`core/.../transfer/DownloadManager.java:119`), never against the advertised `FileInfo.checksum`.
- **Problem:** A self-consistent but wrong set of chunks passes as "complete."
Related: the desktop shares files with an empty checksum when hashing fails (`desktop/.../AppState.java:87`), which downloaders then "verify" against.
- **Fix:** After reassembly, verify the full-file SHA-256 against the advertised checksum and re-fetch on mismatch.
Skip or visibly flag files that cannot be hashed instead of advertising an empty checksum.

### T0.5 - Make TLS actually authenticate (P1, security)

- **Now:** `TLSHelper` uses a trust-all `X509TrustManager` plus a single shared bundled certificate with a hardcoded password (`core/.../crypto/TLSHelper.java:18,43-49`).
- **Problem:** You get encryption but zero authentication.
Any LAN attacker presents the same public cert and performs a trivial man-in-the-middle.
The tracker is plaintext TCP with no auth, so anyone can `UNREGISTER` or spoof any peer, and discovery is unauthenticated (the `DISCOVERY_MAGIC` constant is defined but never used), so a rogue `TRACKER:port` broadcast hijacks the whole LAN.
- **Fix:** Move to per-peer keypairs with trust-on-first-use pinning (identity derived from the public key), run the tracker over TLS as well, and authenticate `UNREGISTER`/`HEARTBEAT` by connection identity rather than payload-claimed IP.

---

## Transfer engine (core)

### TE.1 - Pin UTF-8 and use bounded framing (P1)

- **Now:** Sockets use `PrintWriter`/`InputStreamReader` with the platform-default charset, and `downloadChunk` reads the header one byte at a time until `\n` (`core/.../transfer/DownloadManager.java:141-152`, `PeerServer.java:49`).
- **Problem:** Default charset can mismatch between platforms, and byte-at-a-time reads are slow and unbounded.
- **Fix:** Pin UTF-8 everywhere, buffer reads, and cap the header line length.

### TE.2 - Validate chunk index on the server (P1)

- **Now:** `PeerServer` passes `req.chunkIndex` straight to `FileChunker.readChunk` (`core/.../transfer/PeerServer.java:67`).
- **Problem:** A negative or out-of-range index makes `Math.min(CHUNK_SIZE, file.length() - offset)` negative, producing `NegativeArraySizeException`; the connection just drops silently.
- **Fix:** Validate the index range and return a structured error response.

### TE.3 - Fix the shared-folder path-prefix check (P1, security)

- **Now:** The traversal guard is `file.getCanonicalPath().startsWith(sharedFolder.getCanonicalPath())` (`core/.../transfer/PeerServer.java:61`).
- **Problem:** Without a trailing separator, a sibling directory such as `share-secret` passes the `share` guard.
- **Fix:** Compare against the canonical shared-folder path plus `File.separator`.

### TE.4 - Make chunk writes and resume-state durable and concurrent (P2)

- **Now:** `FileReassembler.writeChunk` opens and closes a `RandomAccessFile` per chunk and is `synchronized`, so all writes serialize on the monitor during disk I/O (`core/.../chunking/FileReassembler.java:17-24`); `saveState()` rewrites the entire `.meta` file on every chunk with no atomicity (`:61-67`).
- **Problem:** Writes serialize under contention, and a crash mid-`.meta`-write corrupts resume state.
- **Fix:** Hold one `FileChannel` open and use positioned writes (thread-safe without a global lock); write `.meta` atomically via temp file plus rename, and only periodically.

### TE.5 - Throttle and marshal progress callbacks (P2)

- **Now:** `notifyProgress` fires once per chunk from pool threads (`core/.../transfer/DownloadManager.java:103,166`).
- **Problem:** UI callbacks run off the UI thread and can flood with one update per chunk.
- **Fix:** Throttle progress updates and document that callbacks are invoked off-thread so UI layers marshal them.

### TE.6 - Cancel chunk sub-tasks on cancel (P2)

- **Now:** `cancel()` cancels only the coordinator future; already-submitted chunk futures keep running (`core/.../transfer/DownloadManager.java:59-62`).
- **Fix:** Track per-task chunk futures and cancel them too.

### TE.7 - Harden UDP discovery (P2)

- **Now:** Discovery announcements are unauthenticated, `DISCOVERY_MAGIC` is unused (the code uses `ANNOUNCE_PREFIX = "TRACKER:"` instead), `joinGroup(group)` is the deprecated form with no `NetworkInterface`, and sockets do not set `SO_REUSEADDR` (`core/.../discovery/UDPDiscovery.java:21,53-66,78-81`).
- **Problem:** Anyone can announce a tracker and hijack peers; on multi-homed hosts the wrong interface may be used; two peers on one host can collide on the discovery port.
- **Fix:** Authenticate or sign announcements (use the magic constant at minimum), specify the network interface, and set address reuse.

---

## Tracker

### TR.1 - Reuse one executor instead of one pool per connection (P0)

- **Now:** `Executors.newCachedThreadPool().submit(...)` is called inside the accept loop and never shut down (`tracker/.../TrackerServer.java:55`).
- **Problem:** A new `ThreadPoolExecutor` is allocated per connection and leaks non-daemon threads, defeating pooling and keeping the JVM from exiting cleanly; a cached pool is also unbounded under bursts.
- **Fix:** Create one executor as a field (virtual-thread-per-task is ideal here) and drain it in `stop()`.

### TR.2 - Trust the socket IP, not the payload (P0)

- **Now:** IP correction handles only `null` and `0.0.0.0` for REGISTER (`tracker/.../TrackerServer.java:85`), and UNREGISTER (`:91`) and HEARTBEAT (`:105`) apply no correction at all.
- **Problem:** Peers' `detectLanIp()` falls back to `127.0.1.1` on Linux and Android sends the literal `"unknown"`, so the tracker stores and serves loopback/unknown addresses and downloaders cannot connect.
This also closes the registry-spoofing hole.
- **Fix:** Always derive the peer IP from the accepted socket's source address and ignore the claimed `ip`; keep the listening port from the payload (the TCP source port is ephemeral).

### TR.3 - Guard against a null filename poisoning queries (P0)

- **Now:** `findPeersWithFile` calls `f.name.equalsIgnoreCase(filename)` with no null check (`tracker/.../PeerRegistry.java:47`).
- **Problem:** One peer registering a `FileInfo` with `name == null` makes every query throw an NPE inside the stream, failing discovery for all callers.
- **Fix:** Reject or drop blank/null names at register time and guard defensively in the predicate.

### TR.4 - Add read timeouts and input caps (P1, DoS)

- **Now:** `handleClient` never sets a socket timeout and uses unbounded `readLine()`; `RegisterRequest.files` and `PeerRegistry.peers` have no caps (`tracker/.../TrackerServer.java:62-68`, `PeerRegistry.java:13`).
- **Problem:** A client that sends no newline pins a handler forever (slowloris); a huge line or millions of file entries exhausts memory.
- **Fix:** Set a read timeout, read with a bounded buffer, and cap files-per-peer, filename length, and total tracked peers.

### TR.5 - Return structured ERROR on bad input (P1)

- **Now:** Gson deserializes an unknown `type` to `null`, the `switch` NPEs, it is caught, and the client gets no response (`tracker/.../TrackerServer.java:71-76`).
- **Problem:** Clients cannot distinguish rejection from a crash; the protocol's `ERROR` type is never sent for bad input.
- **Fix:** Validate message, type, and field ranges, and return a structured `ERROR` on any parse or validation failure.

### TR.6 - Run the tracker over TLS and bind mutating ops to identity (P1, security)

- **Now:** Plain `ServerSocket` (`tracker/.../TrackerServer.java:28`); UNREGISTER takes ip:port from the payload, so any client can deregister any peer.
- **Fix:** Reuse `TLSHelper` for the tracker socket and bind UNREGISTER/HEARTBEAT to the connection identity.

### TR.7 - Add an inverted index and stale-peer eviction (P2, scalability)

- **Now:** `findPeersWithFile` and `getAllFilenames` are O(peers x files) (`tracker/.../PeerRegistry.java:42-50,72-78`); there is no eviction.
- **Fix:** Maintain a `Map<lowercased filename, Set<peerKey>>` updated on register/unregister/evict, and evict peers that miss their keepalive window.

### TR.8 - Wire real heartbeats instead of full re-registration (P2)

- **Now:** No client sends HEARTBEAT; peers re-`register` their entire checksummed file list every 30s, which `register` handles as a full replace (`desktop/.../AppState.java:133-137`, `tracker/.../PeerRegistry.java:25-30`).
- **Problem:** Keepalive bandwidth and re-index work scale with file count; the lightweight HEARTBEAT path already in the protocol is dead.
- **Fix:** Send lightweight HEARTBEATs for keepalive and reserve REGISTER for actual list changes; consider incremental add/remove-file operations.

### TR.9 - Tracker polish (P3)

- Catch `IOException` specifically in the accept loop and back off on repeated failures to avoid busy-spin on `EMFILE` (`tracker/.../TrackerServer.java:52-59`).
- Log only on new peers and real file-list changes; demote periodic re-registers to debug (`tracker/.../PeerRegistry.java:28`).
- Use a structured key or bracket IPv6 literals instead of `ip + ":" + port` (`tracker/.../PeerRegistry.java:26`).
- Validate the port arg and catch bind failures in `main()` (`tracker/.../TrackerServer.java:120-123`).

---

## Security (cross-cutting summary)

These items also appear under their modules; collected here for a single security view.

- **No peer authentication** - trust-all TLS plus one shared cert (`core/.../crypto/TLSHelper.java:43-49`).
See T0.5.
- **Plaintext, unauthenticated tracker** - anyone can unregister or spoof peers (`tracker/.../TrackerServer.java:28,89-92`).
See TR.2, TR.6.
- **Unauthenticated discovery** - rogue tracker hijack; magic constant unused (`core/.../discovery/UDPDiscovery.java:21`).
See TE.7.
- **Path-prefix bypass** - sibling-directory escape (`core/.../transfer/PeerServer.java:61`).
See TE.3.
- **No request size limits** - memory-exhaustion DoS on tracker and peer servers.
See TR.4.
- **Android backs up the TLS private key** - `allowBackup="true"` with the keystore in `getFilesDir()` (`android/.../AndroidManifest.xml:20`).
See AN.8.

---

## Android

The app is largely non-functional on real devices as-is; the storage and permission gaps are the root causes.

### AN.1 - Fix the shared-folder storage model (P0)

- **Now:** Raw `File` I/O against `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/P2PShare` with no runtime storage permission held; `WRITE_EXTERNAL_STORAGE` is capped at `maxSdkVersion=29` (`android/.../AppState.java:59-61`, `AndroidManifest.xml:10-11`).
- **Problem:** Denied on API 26-28 (dangerous permission never requested) and blocked by scoped storage on API 30+, so `listFiles`, `mkdirs`, and `FileOutputStream` fail across essentially every supported level.
- **Fix:** Move the shared folder to `context.getExternalFilesDir(DIRECTORY_DOWNLOADS)`, which needs no permission and works on all levels; update `res/xml/file_paths.xml` to an `external-files-path` entry so `FileProvider.getUriForFile` does not throw.

### AN.2 - Build the runtime-permission layer (P0)

- **Now:** `POST_NOTIFICATIONS` is declared but never requested (`android/.../AndroidManifest.xml:17`); there is no `requestPermissions`/`registerForActivityResult` anywhere.
- **Problem:** On Android 13+ the foreground-service notification is suppressed until granted.
- **Fix:** Request `POST_NOTIFICATIONS` from `MainActivity` on Android 13+ via `registerForActivityResult(ActivityResultContracts.RequestPermission())`.

### AN.3 - Guard against detached-fragment crashes (P1)

- **Now:** Background completions call `requireActivity().runOnUiThread(...)` with no `isAdded()` guard (`android/.../ui/SearchFragment.java:69,110`, `SettingsFragment.java:51`, `DownloadsFragment.java:90-103`).
- **Problem:** Navigating away mid-transfer throws `IllegalStateException: Fragment not attached to a context`.
- **Fix:** Guard every post with `if (!isAdded()) return;`, and hoist transfer/search state into a `ViewModel` so results survive detach.

### AN.4 - Stop leaking the Activity through download callbacks (P1)

- **Now:** `DownloadTask` objects live in the process-lifetime `AppState` singleton, and their callbacks capture `requireActivity()` and fragment views (`android/.../ui/SearchFragment.java:169-179`, `DownloadsFragment.java:90-103`).
- **Problem:** The Activity and its view hierarchy are pinned for the life of the process (persistent leak in SearchFragment).
- **Fix:** Have the UI observe task state via a `ViewModel`/listener registry cleared in `onDestroyView`; at minimum, null the callbacks in `onDestroyView`/`onPause`.

### AN.5 - Move file hashing off the main thread (P1, ANR)

- **Now:** `SharedFragment.onActivityResult:72` calls `refreshSharedFiles()` directly on the main thread when the picked file already exists; that method SHA-256-hashes every shared file (`android/.../AppState.java:165-184`).
- **Fix:** Run it on a background thread like the other call sites; give `refreshSharedFiles()` an internal executor and cache checksums by `(path, size, lastModified)`.

### AN.6 - Release the MulticastLock on shutdown (P1, battery)

- **Now:** The lock is released only on a successful discovery and not in `shutdown()` (`android/.../AppState.java:103-115,143-148`).
- **Problem:** If discovery never succeeds, multicast filtering stays disabled until process death, draining battery.
- **Fix:** Release the lock (guarded by `isHeld()`) in `shutdown()` and once a tracker is configured manually.

### AN.7 - Make shared state thread-safe (P1)

- **Now:** `AppState.get()` is an unsynchronized check-then-act called from the service init thread and the UI thread; `sharedFiles` (`ArrayList`) is mutated on background threads while read on the UI thread; `isConnected` is non-`volatile` (`android/.../AppState.java:23-26,166-176`).
- **Fix:** Synchronize `get()` or initialize eagerly in an `Application` subclass; use `CopyOnWriteArrayList` (or hand out snapshots) for `sharedFiles`; mark `isConnected` `volatile`.

### AN.8 - Foreground-service and manifest correctness (P2)

- Pass the explicit type to `startForeground(id, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` (`android/.../service/TransferService.java:20`).
- Plan for the Android 15 ~6h/day `dataSync` cap; evaluate `connectedDevice`/`specialUse` for a continuously serving node.
- Add a content `PendingIntent` to the notification and wire `updateNotification` (currently dead code with zero callers) to real progress (`android/.../service/TransferService.java:48-60`).
- Make `init()` idempotent so `START_STICKY` redelivery does not double-bind the peer port (`android/.../service/TransferService.java:21,26`).
- Set `allowBackup="false"` (or exclude the keystore dir) to avoid exporting the TLS private key (`android/.../AndroidManifest.xml:20`).
- Remove the unused `READ_MEDIA_*` permissions; SAF already grants per-URI access (`android/.../AndroidManifest.xml:12-14`).

### AN.9 - Android UI maintainability (P3)

- Replace the full teardown/rebuild card lists with `RecyclerView` adapters (the dependency is already declared but unused); the current raw-pixel sizing is not density-scaled and renders differently per DPI.
- Migrate the deprecated `startActivityForResult`/`onActivityResult` to `registerForActivityResult`.
- Cache fragment instances instead of recreating them on every tab tap (`android/.../MainActivity.java:30-33`).
- Add capped exponential backoff to the forever-retrying discovery loop and stop it once connected.

---

## Desktop (JavaFX)

### DT.1 - Do not block the FX thread during startup (P0)

- **Now:** `AppState.init()` runs TLS keygen, a LAN-IP probe, full-folder SHA-256, and up to three blocking 5s tracker registrations on the FX thread before `stage.show()` (`desktop/.../App.java:21,52`, `AppState.java:95-146`).
- **Problem:** The window is blank and frozen on launch until all of it finishes.
- **Fix:** Show the stage first, run `init()` on a background thread, and marshal only state writes back via `Platform.runLater`.

### DT.2 - Stop mutating the scene graph off-thread (P0)

- **Now:** The discovery callback writes `trackerHost`/`trackerPort` properties bound bidirectionally to live TextFields, on the discovery thread (`desktop/.../AppState.java:155-170`).
- **Fix:** Wrap the property writes in `Platform.runLater` or expose an `AppState` method that marshals them.

### DT.3 - Fix the DownloadTask callback collision (P0)

- **Now:** `DownloadTask`'s single-Consumer callbacks are assigned by the SearchView button, then synchronously overwritten by the DownloadsView card when `state.downloads.add(task)` fires the list listener (`desktop/.../ui/SearchView.java:246-257`, `DownloadsView.java:132,138,154`).
- **Problem:** The search button is stuck on "Starting..." forever, and `refreshSharedFiles()` in the button's `onComplete` never runs, so a finished download does not appear under "My Files" without a manual refresh.
- **Fix:** Support multiple listeners (a fan-out list in `DownloadManager.notifyProgress`), or have views observe task state via the list rather than owning the callback.

### DT.4 - Make refreshSharedFiles non-blocking (P1)

- **Now:** `refreshSharedFiles()` hashes every file and does a blocking register on the FX thread, invoked from Sharing refresh/add/remove and Settings browse (`desktop/.../AppState.java:180-186`, `ui/SharingView.java:56,150,168`, `SettingsView.java:203`).
- **Fix:** Run hashing and register on a background thread; apply `sharedFiles.setAll(...)` on the FX thread.

### DT.5 - Validate the port field and report real save results (P1)

- **Now:** `Integer.parseInt` runs on the unvalidated free-text port field (`desktop/.../AppState.java:109,161,175`), and the Save button shows "Saved!" unconditionally while `reconnect()` runs on another thread whose outcome is never checked (`ui/SettingsView.java:261-275`).
- **Fix:** Restrict the field with a `TextFormatter`, handle parse errors, and drive the Save button from the actual `isConnected` outcome.

### DT.6 - Orderly shutdown and lifecycle (P1)

- **Now:** Cleanup relies solely on a Runtime shutdown hook; there is no `Application.stop()`, the `UDPDiscovery` instance is never stopped, and `scheduleDiscovery` re-spawns a non-daemon thread every 15s with no stop signal (`desktop/.../AppState.java:128,139-145,155-170`).
- **Fix:** Override `App.stop()`, give discovery a stop flag, and make the discovery thread a daemon.

### DT.7 - Surface security and integrity failures (P1)

- **Now:** TLS init failure is swallowed to `System.err` while Settings shows "Encryption: Always On" (`desktop/.../AppState.java:96-97`, `ui/SettingsView.java:236-244`), and files that fail hashing are shared with an empty checksum (`AppState.java:87-89`).
- **Fix:** Surface a security/connection error state in the UI, and skip or flag unhashable files.

### DT.8 - Desktop maintainability (P2/P3)

- Avoid `ConcurrentModificationException` by snapshotting `sharedFiles` on the FX thread before background register/serialization (`desktop/.../AppState.java:135,162,177,184`).
- Add a dedicated `autoDiscover` boolean preference instead of inferring it from an empty `trackerHost` (`ui/SettingsView.java:127-128`).
- Either wire `myDisplayName` into the protocol or remove the misleading "visible to others" copy (`ui/SettingsView.java:217`, `core/.../tracker/TrackerClient.java:28-29`).
- Extract duplicated `fileIcon()`/`formatSize()` helpers into a shared util (three/two divergent copies across views).
- Move inline `-fx-` style strings and hardcoded hex colors into `app.css` style classes.
- Use a shared named `ExecutorService` instead of `new Thread()` per action; add request coalescing/cancellation to Search and Browse.
- Own file/directory choosers with the real window instead of a throwaway `new Stage()` (`ui/SettingsView.java:199`, `SharingView.java:163`).

---

## Build, test, and CI

### BT.1 - Make build.sh fail on compile errors (P0)

- **Now:** `javac ... 2>&1 | grep -v "^Note:" || true` with `set -e` but no `set -o pipefail` (`scripts/build.sh:2,49,59,83`).
- **Problem:** A broken compile still prints "compiled" and the script proceeds to package stale `.class` files; the exit code lies.
- **Fix:** Add `set -o pipefail`, drop `|| true`, and check `PIPESTATUS[0]` so the build aborts on error.

### BT.2 - Add tests and CI (P1)

- **Now:** No `src/test`, no `*Test.java`, no CI config anywhere.
- **Problem:** Chunking, reassembly/resume, checksum verification, and the JSON protocol are exactly the logic that breaks silently on refactors.
- **Fix:** Add JUnit tests around `core` (chunker/reassembler round-trip, protocol serialization, checksum-mismatch handling), and a CI workflow that runs `build.sh`, `build-android-libs.sh`, Android `assembleDebug`, and the tests on a pinned JDK.

### BT.3 - Fix the JavaFX dependency story (P1)

- **Now:** gson is read from `~/.gradle/caches` but JavaFX from `~/.m2`, and the printed remedy `./gradlew :desktop:dependencies` never populates `~/.m2` (`scripts/build.sh:5-6,34-36`).
- **Problem:** A fresh clone following the printed instructions keeps failing the JavaFX check.
- **Fix:** Resolve both dependencies from one source of truth, ideally vendored repo-local jars, and make the remedy match the directory the script actually probes.

### BT.4 - Make the desktop build cross-platform (P1)

- **Now:** JavaFX classifiers are hardcoded `-linux.jar` (`scripts/build.sh:9-14`, `run-desktop.sh:17-22`), while the README claims Windows support.
- **Fix:** Detect the OS and select the `win`/`mac`/`mac-aarch64`/`linux` classifier, or document the Linux-only limitation.

### BT.5 - Align the Java language level (P1)

- **Now:** `build.sh` compiles every module with `--release 17`, but the Gradle configs and Android declare Java 11; `build-android-libs.sh` packs the Java 17 `core` bytecode into the Java 11 Android module (`scripts/build.sh:46,56,78`, `android/build.gradle`).
- **Problem:** A real class-file-version mismatch on the live Android path.
- **Fix:** Pick one language level (Java 11 if Android needs it) and enforce it in both the scripts and Gradle.

### BT.6 - Produce a shippable desktop artifact (P2)

- **Now:** `build.sh` makes a fat `tracker.jar` but no desktop jar; `run-desktop.sh` runs from the exploded build tree, and the README's `jpackage` flow depends on the broken Gradle path (`scripts/build.sh:62-72`, `run-desktop.sh:31`, `README.md:58-67`).
- **Fix:** Assemble a desktop fat jar the same way as `tracker.jar` (bundling the OS-correct JavaFX modules), then optionally feed it to `jpackage`.

### BT.7 - Single-source the version and put it in the protocol (P2)

- **Now:** Version strings disagree across `build.gradle` (1.0.0), `android/build.gradle` (1.0), and the README, and there is no version in the wire protocol.
- **Fix:** Single-source the version, stamp it into artifact names and a `Protocol` constant, and surface it in the handshake for compatibility checks.

### BT.8 - Fix the README and consolidate the build system (P2)

- **Now:** Every desktop/tracker command in the README is the Gradle path the project told everyone not to use; there are two Gradle roots with mismatched wrapper versions (9.0.0 vs 9.3.0).
- **Fix:** Rewrite the README to the actual scripts, drop the dead `include 'android'` from the root `settings.gradle`, and align wrapper versions.
Optionally pin the Gradle daemon JDK and declare a Java toolchain to retire the manual scripts entirely.

### BT.9 - Build-script polish (P3)

- Use `mktemp` for the source-list files instead of fixed `/tmp/p2p_*_sources.txt` paths (`scripts/build.sh:45,55,77`).
- Keep the manual scripts and Gradle dependency sets in sync (e.g. `ikonli` is declared in `desktop/build.gradle` but absent from the script classpaths).
- Gate ANSI colors on `[ -t 1 ]` for clean CI/log output.

---

## Observability and cross-cutting maintainability

- **Adopt a logging framework** (SLF4J or `java.util.logging`) with levels, timestamps, and simple counters, replacing `System.out`/`System.err` throughout; the tracker currently logs a registration line every 30s per peer, burying real events.
- **Add protocol versioning** so peers and tracker can detect incompatible builds.
- **De-duplicate shared helpers** (`fileIcon`, `formatSize`) across desktop and Android into a common util.
- **Centralize background work** behind shared named executors instead of `new Thread()` per action.

---

## Suggested sequencing

1. Transfer-engine cluster (T0.1-T0.4): biggest impact, contained to `core`, turns the P2P core from a demo into something robust.
2. Build safety and tests (BT.1, BT.2): stop silent build failures and lock in the core logic before refactoring.
3. Android storage and permissions (AN.1, AN.2): unblocks the Android app on real devices.
4. Desktop threading and the callback-collision bug (DT.1-DT.3): fixes the most visible desktop defects.
5. Security pass (T0.5, TR.2, TR.6, TE.3): move from encryption-only to authenticated, spoof-resistant.
6. Tracker reliability and scalability (TR.1, TR.4, TR.7, TR.8).
7. Remaining P2/P3 polish across modules.

package com.p2p.desktop;

import com.p2p.core.chunking.FileChunker;
import com.p2p.core.discovery.UDPDiscovery;
import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.FileInfo;
import com.p2p.core.tracker.TrackerClient;
import com.p2p.core.transfer.DownloadManager;
import com.p2p.core.transfer.PeerServer;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/** Singleton holding all runtime state for the desktop app. */
public class AppState {

    private static AppState instance;
    public static AppState get() {
        if (instance == null) instance = new AppState();
        return instance;
    }

    private AppState() {
        // DT.8: keep the background-thread mirror of autoDiscover in sync with the FX-bound property,
        // persist the choice immediately, and apply it to the live connection. Registered here (not at
        // the field) so it never fires during field initialization — only on a genuine user change.
        autoDiscover.addListener((obs, was, now) -> onAutoDiscoverChanged(now));
        // Keep the off-thread displayName mirror in lockstep with the FX-bound property (the Settings
        // field binds bidirectionally to it). The next keep-alive/refresh re-register then advertises
        // the new name; tracker eviction is 90s, so a rename propagates well within one keep-alive cycle.
        myDisplayName.addListener((obs, was, now) -> displayName = now);
    }

    private final Preferences prefs = Preferences.userNodeForPackage(AppState.class);

    // Persisted settings
    public final StringProperty trackerHost = new SimpleStringProperty(prefs.get("trackerHost", ""));
    public final StringProperty trackerPort = new SimpleStringProperty(prefs.get("trackerPort", "9000"));

    /**
     * DT.8: whether to auto-discover a tracker on the LAN (true) or connect only to the manually
     * configured {@link #trackerHost} (false). Previously auto-discovery was <i>inferred</i> from
     * {@code trackerHost} being blank, so the Settings auto-discover toggle was cosmetic — turning it
     * "off" persisted nothing and the app kept auto-discovering, the same "Settings UI lies about its
     * state" class of bug fixed in DT.5 and DT.7. This is now an explicit, persisted preference that
     * the connection logic actually honors. On upgrade (no {@code autoDiscover} key yet) we default to
     * {@code true} rather than inferring from {@code trackerHost}: the OLD code persisted trackerHost on
     * EVERY successful auto-connect (the discovery callback and the localhost fast-path both did
     * {@code trackerHost.set(...); savePrefs()}), so a non-blank saved host is just as likely to be
     * auto-discovery pollution as a deliberate manual entry. Keying the default off it would wrongly
     * flip existing auto-discovery users (the recommended path) to manual mode and strip their discovery
     * fallback, leaving them "Not connected" if the tracker's IP changed. There is no reliable
     * "manual was chosen" signal in old prefs, so default-on is the lower-harm choice: a genuine manual
     * user gets auto-discovery once (their host is preserved, and auto likely finds the same LAN tracker)
     * and can toggle it off, whereas the auto-discovery population keeps working untouched.
     */
    public final BooleanProperty autoDiscover =
            new SimpleBooleanProperty(prefs.getBoolean("autoDiscover", true));

    /**
     * Background-thread source of truth for {@link #autoDiscover}: the JavaFX property must be touched
     * on the FX thread, so the init/discovery/reconnect paths (all off-thread) branch on this volatile
     * mirror, kept in lockstep by the listener registered in the constructor.
     */
    private volatile boolean autoDiscoverEnabled = autoDiscover.get();
    public final StringProperty sharedFolderPath = new SimpleStringProperty(
            prefs.get("sharedFolder", System.getProperty("user.home") + "/P2PShare"));
    public final StringProperty myDisplayName = new SimpleStringProperty(
            prefs.get("displayName", System.getProperty("user.name")));

    /**
     * Background-thread mirror of {@link #myDisplayName}: the JavaFX property must be read on the FX
     * thread, so the off-thread register sites (init, keep-alive, refresh, reconnect, discovery) read
     * this {@code volatile} snapshot instead. Kept in lockstep by a listener registered in the
     * constructor (same pattern as {@link #autoDiscoverEnabled} and {@link #myIp}). This is the
     * cosmetic nickname advertised to the tracker so other peers see "shared by &lt;name&gt;"; the
     * tracker sanitizes it before relaying.
     */
    private volatile String displayName = myDisplayName.get();

    // Runtime components
    public TrackerClient trackerClient;
    public PeerServer peerServer;
    public DownloadManager downloadManager;
    public UDPDiscovery discovery;

    public final ObservableList<DownloadManager.DownloadTask> downloads = FXCollections.observableArrayList();
    public final ObservableList<FileInfo> sharedFiles = FXCollections.observableArrayList();

    /**
     * DT.7: real TLS health surfaced to the UI. Optimistically {@code true} (TLS init is the very
     * first thing {@link #initBlocking} does, so the window is true within milliseconds), and flipped
     * to {@code false} if {@code TLSHelper.init()} throws. Previously that failure was swallowed to
     * {@code System.err} while the Settings card kept claiming "Encryption: Always On" — a security
     * lie. When this is {@code false} the peer server never binds (it needs the TLS context), so no
     * peer transfers happen at all. Written ONLY on the FX thread (via {@link #runFx}).
     */
    public final BooleanProperty securityOk = new SimpleBooleanProperty(true);

    /** DT.7: the TLS init error message, shown alongside the "Encryption unavailable" state. */
    public final StringProperty securityDetail = new SimpleStringProperty("");

    /**
     * DT.7: names of shared-folder files that could NOT be hashed (e.g. unreadable) and so were
     * silently dropped from the advertised list by {@link #buildFileList}. Surfaced as a warning
     * banner in the Sharing view so the user knows a file they expected to share isn't being shared.
     * FX-bound — written ONLY on the FX thread.
     */
    public final ObservableList<String> unshareableFiles = FXCollections.observableArrayList();

    /**
     * UI-facing connection flag, written ONLY on the FX thread (via {@link #runFx}).
     * Background code must never branch on this — it reads the authoritative
     * {@link #connected} flag instead, since a marshalled set may not have run yet.
     */
    public final BooleanProperty isConnected = new SimpleBooleanProperty(false);

    /** Authoritative connection state read/written by background threads. */
    private volatile boolean connected = false;

    /**
     * DT.6: set once {@link #shutdown()} has run so the auto-discovery retry loop stops
     * rescheduling itself and a second cleanup pass (App.stop() and the shutdown hook can
     * both fire) becomes a no-op.
     */
    private volatile boolean shuttingDown = false;

    /**
     * DT.8: guards against running two concurrent UDP discovery listen loops. Set true while a loop is
     * active so {@link #startDiscoveryLoop()} is idempotent (init and a runtime auto-discover toggle can
     * both ask to start one), and cleared when the loop stops (connected, shutting down, or
     * auto-discover turned off).
     */
    private volatile boolean discoveryLoopActive = false;

    /**
     * The peer-server port actually advertised to the tracker, or {@code -1} when the peer server is
     * not serving. Set from {@link PeerServer#getBoundPort()} once (and only if) {@code start()}
     * succeeds. {@code volatile} because it is written on the background init thread and read by the
     * keep-alive / discovery / reconnect / refresh threads.
     *
     * <p>Single source of truth for "what port do we tell the tracker, and may we register at all?".
     * Every registration site guards on {@code servingPort > 0}. Before this, init/keepalive/refresh/
     * reconnect/discovery all advertised the hardcoded {@link Protocol#DEFAULT_PEER_PORT} (9001)
     * regardless of whether the peer server bound, so a TLS-init failure (the server needs the TLS
     * context, so it never binds) left the desktop registering an unreachable 9001 — poisoning the
     * tracker so every downloader was handed a dead address and failed to connect. The desktop now
     * binds an ephemeral port (port 0) and advertises the real bound one; the only remaining bind
     * failure, with the port OS-assigned, is a TLS failure, already surfaced as "Encryption
     * unavailable" via {@link #securityOk}.
     */
    private volatile int servingPort = -1;

    /**
     * Network-facing LAN IP. {@code volatile} because it is set on the background
     * init thread and later read by the keep-alive / discovery / reconnect threads.
     */
    public volatile String myIp = "unknown";

    /** FX-thread display mirror of {@link #myIp} for the status bar. */
    public final StringProperty myIpDisplay = new SimpleStringProperty("detecting...");

    public void savePrefs() {
        prefs.put("trackerHost", trackerHost.get());
        prefs.put("trackerPort", trackerPort.get());
        prefs.put("sharedFolder", sharedFolderPath.get());
        prefs.put("displayName", myDisplayName.get());
        prefs.putBoolean("autoDiscover", autoDiscover.get());
    }

    /** Outcome of a manual {@link #reconnect()}, so the Settings Save button can report the
     *  real result instead of unconditionally flashing "Saved!" (DT.5). */
    public enum ReconnectResult {
        /** A manual tracker was configured and the registration succeeded. */
        CONNECTED,
        /** A manual tracker was configured but could not be reached. */
        UNREACHABLE,
        /** No manual tracker is set (auto-discovery mode); nothing to verify synchronously. */
        AUTO_DISCOVER
    }

    /**
     * Parse the user-entered tracker port (DT.5). The Settings field is digit-restricted by a
     * {@code TextFormatter}, but a blank field or a stale/hand-edited preference could still hold a
     * blank or out-of-range value, so this is defensive: blank or anything outside 1..65535 falls
     * back to the default 9000 instead of throwing. Previously a non-numeric value made
     * {@code Integer.parseInt} throw inside {@link #reconnect()} on the Save worker thread — the
     * worker died silently while the button still reported success.
     */
    static int parseTrackerPort(String raw) {
        if (raw == null || raw.isBlank()) return 9000;
        try {
            int p = Integer.parseInt(raw.trim());
            if (p >= 1 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {}
        return 9000;
    }

    /** Determine the real LAN IP via a connected UDP socket (no packets sent). */
    private String detectLanIp() {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 53);
            String ip = s.getLocalAddress().getHostAddress();
            if (ip != null && !ip.startsWith("127.") && !ip.equals("0.0.0.0")) return ip;
        } catch (Exception ignored) {}
        try { return InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception e) { return "127.0.0.1"; }
    }

    public File getSharedFolder() {
        File f = new File(sharedFolderPath.get());
        f.mkdirs();
        return f;
    }

    public List<FileInfo> buildFileList() {
        return buildFileList(null);
    }

    /**
     * Build the advertised file list. Files that cannot be hashed are skipped (T0.4: never advertise
     * an empty checksum — a downloader would "verify" any bytes against it and accept corruption) and,
     * if {@code skippedOut} is non-null, their names are collected there so the caller can surface
     * them in the UI (DT.7) instead of dropping them silently.
     */
    public List<FileInfo> buildFileList(List<String> skippedOut) {
        List<FileInfo> files = new ArrayList<>();
        File folder = getSharedFolder();
        File[] listed = folder.listFiles();
        if (listed == null) return files;
        for (File f : listed) {
            if (f.isFile() && !f.getName().endsWith(".meta")) {
                int chunks = FileChunker.getTotalChunks(f.length());
                String checksum;
                try { checksum = FileChunker.sha256OfFile(f); }
                catch (Exception e) {
                    System.err.println("[AppState] Skipping unhashable shared file: "
                            + f.getName() + " (" + e.getMessage() + ")");
                    if (skippedOut != null) skippedOut.add(f.getName());
                    continue;
                }
                files.add(new FileInfo(f.getName(), f.length(), chunks, checksum));
            }
        }
        return files;
    }

    /**
     * Register with the (already-targeted) tracker as a download source — but ONLY when the peer
     * server is actually listening ({@code servingPort > 0}). Returns {@code false} without touching
     * the network when we are not serving, so a TLS-init failure never advertises an unreachable
     * address to the tracker (the poisoning bug this method exists to prevent). The caller is
     * responsible for any {@code trackerClient.setTracker(...)} target switch before calling.
     */
    private boolean registerWithTracker(List<FileInfo> files) {
        if (servingPort <= 0 || trackerClient == null) return false;
        // Advertise the cosmetic display nickname so other peers' UIs can show "shared by <name>"
        // (DT.8: the Settings "visible to others on the network" copy is now truthful — the name
        // actually travels). Read the volatile mirror, never the FX property, off-thread.
        return trackerClient.register(myIp, servingPort, files, displayName);
    }

    /**
     * DT.1: non-blocking entry point. All of startup — TLS keygen, the LAN-IP
     * probe, full-folder SHA-256 hashing, the peer-server bind, and up to three
     * blocking tracker registrations — runs on a background daemon thread so the
     * FX thread is free to paint the window immediately. Only writes to live
     * JavaFX state are marshalled back via {@link #runFx}.
     */
    public void init() {
        Thread t = new Thread(this::initBlocking, "appstate-init");
        t.setDaemon(true);
        t.start();
    }

    private void initBlocking() {
        try {
            // DT.7: surface a TLS failure instead of swallowing it. If keygen/keystore load throws,
            // the peer server below never binds (it needs this context), so no encrypted transfers can
            // happen — the UI must stop claiming "Encryption: Always On".
            try {
                com.p2p.core.crypto.TLSHelper.init();
                runFx(() -> { securityOk.set(true); securityDetail.set(""); });
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                System.err.println("[AppState] TLS init failed: " + msg);
                runFx(() -> { securityOk.set(false); securityDetail.set(msg); });
            }

            myIp = detectLanIp();
            final String ip = myIp;
            runFx(() -> myIpDisplay.set(ip));

            final List<String> skipped = new ArrayList<>();
            final List<FileInfo> files = buildFileList(skipped);
            runFx(() -> { sharedFiles.setAll(files); unshareableFiles.setAll(skipped); });

            // Bind an ephemeral peer port (0 -> OS-assigned) and advertise the REAL bound port. A
            // fixed 9001 collides when two peers share a host and, more importantly, was advertised
            // even when the bind failed. servingPort is the single source of truth: -1 until/unless
            // start() succeeds, so every register site below declines to advertise an unreachable
            // address. The only bind failure left, with the port OS-assigned, is a TLS failure —
            // already surfaced as "Encryption unavailable" via securityOk.
            peerServer = new PeerServer(0, this::getSharedFolder);
            boolean serverUp = peerServer.start();
            servingPort = serverUp ? peerServer.getBoundPort() : -1;

            downloadManager = new DownloadManager();

            int tPort = parseTrackerPort(trackerPort.get());
            trackerClient = new TrackerClient(trackerHost.get(), tPort);

            // Use the local `files` snapshot (not the ObservableList, which the FX
            // thread is concurrently populating) and a local boolean for control
            // flow — never the marshalled isConnected property, whose set() may not
            // have run yet. registerWithTracker() is a no-op when servingPort <= 0, so a
            // peer server that never bound stays off the tracker instead of poisoning it.
            //
            // DT.8: branch on the explicit autoDiscover preference instead of inferring the mode from a
            // blank trackerHost. In manual mode we register with the configured tracker ONLY — no
            // localhost fallback and no discovery loop — so an unreachable manual host fails honestly
            // rather than silently auto-discovering some other tracker. In auto mode we ignore the
            // manual host entirely, try the same-machine tracker as a fast path, then fall back to UDP
            // discovery. The discovered/localhost address is deliberately NOT written back into
            // trackerHost: that property is the user's manual config and the manual field reflects it,
            // so auto-discovery no longer pollutes it (which was the root of the inference bug).
            boolean ok = false;
            if (autoDiscoverEnabled) {
                trackerClient.setTracker("127.0.0.1", tPort);
                ok = registerWithTracker(files);
            } else if (!trackerHost.get().isBlank()) {
                ok = registerWithTracker(files);
            }

            connected = ok;
            final boolean fOk = ok;
            runFx(() -> isConnected.set(fOk));

            // Auto-discover the tracker only in auto mode and only when we can actually serve: if the
            // peer server isn't up there's nothing to register, so don't spin a discovery loop that
            // could only poison a tracker.
            if (!ok && serverUp && autoDiscoverEnabled) {
                startDiscoveryLoop();
            }

            // Re-register periodically so the tracker doesn't evict us (90s timeout).
            keepAlive.scheduleAtFixedRate(() -> {
                if (connected) registerWithTracker(new ArrayList<>(sharedFiles));
            }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);

            // DT.6: a Runtime shutdown hook covers abnormal termination (SIGTERM / Ctrl-C). The
            // normal window-close path runs App.stop() -> shutdown() instead; shutdown() is idempotent
            // so the two never double-clean.
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "appstate-shutdown-hook"));
        } catch (Exception e) {
            // Don't let a bind/registration failure die silently on the background thread. (The TLS
            // failure path that DT.7 surfaces is handled at its own try/catch above; this is the
            // catch-all for the rest of startup.)
            System.err.println("[AppState] init failed: " + e.getMessage());
        }
    }

    /**
     * DT.6: orderly shutdown. Before this the only cleanup was a Runtime shutdown hook, and it relied
     * on JavaFX's implicit-exit calling {@code System.exit()} to fire that hook — an implementation
     * detail, not the documented lifecycle. That hook also never stopped the {@link UDPDiscovery}
     * instance, so the non-daemon {@code udp-discovery} listener (relaunched every 15s while
     * disconnected) and the {@link PeerServer} accept loop survived only because {@code System.exit}
     * force-terminated them. If implicit-exit ever stopped calling {@code System.exit} (e.g. the app
     * embedded so it is not the last window), those non-daemon threads would keep the JVM alive — a
     * latent hang. {@code App.stop()} is the documented place to clean up; it runs on the FX thread and
     * here explicitly stops every long-lived component (discovery included) so teardown no longer
     * depends on {@code System.exit}.
     *
     * <p>Idempotent (guarded by {@link #shuttingDown}) because both {@code App.stop()} and the shutdown
     * hook can fire — the hook still runs for SIGTERM/Ctrl-C, where there is no App.stop().
     */
    public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        System.out.println("[AppState] Shutting down — stopping discovery, peer server and downloads.");
        // Stop discovery first so its retry loop cannot relaunch a listener mid-teardown.
        try { if (discovery != null) discovery.stop(); } catch (Exception ignored) {}
        // Unregister under the exact port we advertised (servingPort); if we never served
        // (servingPort <= 0) we were never registered, so there is nothing to unregister.
        try { if (connected && trackerClient != null && servingPort > 0) trackerClient.unregister(myIp, servingPort); } catch (Exception ignored) {}
        try { if (peerServer != null) peerServer.stop(); } catch (Exception ignored) {}        // releases the accept thread
        try { if (downloadManager != null) downloadManager.shutdown(); } catch (Exception ignored) {}
        keepAlive.shutdownNow();
        sharedFilesExecutor.shutdownNow();
        savePrefs();
    }

    /** Marshal a state write back onto the JavaFX application thread. */
    private void runFx(Runnable r) {
        javafx.application.Platform.runLater(r);
    }

    private final java.util.concurrent.ScheduledExecutorService keepAlive =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tracker-keepalive");
                t.setDaemon(true);
                return t;
            });

    /**
     * DT.4: serializes the shared-file refresh (folder scan + SHA-256 of every file +
     * blocking tracker register) onto a single background daemon thread so the FX thread
     * is never blocked on disk hashing or network I/O. Single-threaded so rapid successive
     * refreshes (e.g. several Add File clicks) apply in order rather than racing.
     */
    private final java.util.concurrent.ExecutorService sharedFilesExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "shared-files-refresh");
                t.setDaemon(true);
                return t;
            });

    /**
     * DT.8: starts the UDP auto-discovery retry loop, or no-ops if one is already running. Idempotent
     * (guarded by {@link #discoveryLoopActive}) because both startup and a runtime auto-discover toggle
     * can ask to start it. Caller must already be in auto-discover mode and able to serve.
     */
    private synchronized void startDiscoveryLoop() {
        if (discoveryLoopActive || shuttingDown) return;
        if (discovery == null) discovery = new UDPDiscovery();
        discoveryLoopActive = true;
        System.out.println("[AppState] Auto-discovering a tracker on the local network...");
        scheduleDiscovery(discovery);
    }

    /** DT.8: stops the auto-discovery loop so the app stops trying to find a tracker (toggle → off). */
    private void stopDiscoveryLoop() {
        if (!discoveryLoopActive) return;
        discoveryLoopActive = false;
        System.out.println("[AppState] Auto-discovery disabled — no longer searching for a tracker.");
        try { if (discovery != null) discovery.stop(); } catch (Exception ignored) {}
    }

    private void scheduleDiscovery(UDPDiscovery disc) {
        // The callback fires on the discovery thread (DT.2): keep the blocking network calls here, but
        // marshal the isConnected write onto the FX thread. Registration goes through
        // registerWithTracker(), so the advertised port is always the real bound one. DT.8: the
        // discovered tracker address is set on trackerClient (so re-registers reach it) but is NOT
        // written into the trackerHost property — that property is the user's manual config and
        // auto-discovery must not overwrite it (the inference bug). The loop keeps retrying only while
        // disconnected, not shutting down, and still in auto-discover mode; otherwise it winds down.
        disc.listenForTracker(found -> {
            if (found != null) {
                String[] parts = found.split(":");
                trackerClient.setTracker(parts[0], Integer.parseInt(parts[1]));
                boolean ok = registerWithTracker(new ArrayList<>(sharedFiles));
                connected = ok;
                runFx(() -> isConnected.set(ok));
            }
            if (!connected && !shuttingDown && autoDiscoverEnabled) {
                scheduleDiscovery(disc);
            } else {
                discoveryLoopActive = false;
            }
        });
    }

    /**
     * DT.8: applies a runtime change to {@link #autoDiscover} (the Settings switch, or a manual-host
     * edit that flips it off). Persists the choice immediately and reconfigures the live connection so
     * the toggle is honest, not cosmetic: turning auto-discovery ON kicks off the same-machine attempt
     * + discovery loop when we're not already connected, and turning it OFF stops the loop so the app
     * genuinely stops searching. The fired-on-the-FX-thread listener does the persist inline (cheap) and
     * the network work on a short-lived daemon thread. A pre-init change (no peer server yet) only needs
     * the persist — the choice is then honored when {@link #initBlocking} runs.
     */
    private void onAutoDiscoverChanged(boolean enabled) {
        autoDiscoverEnabled = enabled;
        prefs.putBoolean("autoDiscover", enabled);
        if (peerServer == null || trackerClient == null || shuttingDown) return;
        Thread t = new Thread(() -> {
            if (enabled) {
                if (!connected && servingPort > 0) {
                    trackerClient.setTracker("127.0.0.1", parseTrackerPort(trackerPort.get()));
                    if (registerWithTracker(new ArrayList<>(sharedFiles))) {
                        connected = true;
                        runFx(() -> isConnected.set(true));
                    } else {
                        startDiscoveryLoop();
                    }
                }
            } else {
                stopDiscoveryLoop();
            }
        }, "auto-discover-apply");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Re-register with the manually-configured tracker and report the real outcome (DT.5).
     * Called off the FX thread (Settings "Save" spawns a worker), so the {@code isConnected}
     * write is marshalled back. Returns {@link ReconnectResult#AUTO_DISCOVER} when no manual
     * host is set (auto-discovery owns the connection then, so there is nothing to verify
     * synchronously here), and otherwise CONNECTED/UNREACHABLE per the registration result —
     * letting the caller give honest feedback instead of an unconditional "Saved!".
     */
    public ReconnectResult reconnect() {
        // DT.8: in auto-discover mode there is no manual host to verify — the discovery loop owns the
        // connection — so report AUTO_DISCOVER. (Previously this only checked for a blank host.)
        if (trackerClient == null || autoDiscoverEnabled || trackerHost.get().isBlank())
            return ReconnectResult.AUTO_DISCOVER;
        // If the peer server never bound (servingPort <= 0 — a TLS failure, already shown as the
        // "Encryption unavailable" card right here in Settings), registering would advertise an
        // unreachable address, so don't. There is nothing to connect as a source.
        if (servingPort <= 0) return ReconnectResult.UNREACHABLE;
        int tPort = parseTrackerPort(trackerPort.get());
        trackerClient.setTracker(trackerHost.get(), tPort);
        boolean ok = registerWithTracker(new ArrayList<>(sharedFiles));
        connected = ok;
        runFx(() -> isConnected.set(ok));
        return ok ? ReconnectResult.CONNECTED : ReconnectResult.UNREACHABLE;
    }

    /**
     * DT.4: rescans and re-hashes the shared folder, then re-registers with the tracker —
     * all off the FX thread (was previously run inline by Sharing add/remove/refresh, the
     * Settings folder browse, and the download-complete callback, blocking the UI on
     * full-folder SHA-256 + a blocking register). Only the {@link #sharedFiles} update is
     * marshalled back via {@link #runFx}. Safe to call from the FX thread.
     */
    public void refreshSharedFiles() {
        sharedFilesExecutor.submit(() -> {
            // Disk scan + SHA-256 of every file happen here, off the FX thread.
            final List<String> skipped = new ArrayList<>();
            final List<FileInfo> files = buildFileList(skipped);
            // Mutate the FX-bound collections only on the FX thread. unshareableFiles (DT.7) feeds the
            // Sharing-view warning banner so a file that can't be hashed isn't dropped silently.
            runFx(() -> { sharedFiles.setAll(files); unshareableFiles.setAll(skipped); });
            // Background control flow branches on the authoritative `connected` flag, not the
            // FX-bound isConnected property (whose marshalled set may not have run yet). Register
            // with the freshly built local snapshot — never the live ObservableList the FX thread
            // is concurrently populating — avoiding the ConcurrentModificationException DT.8 flags.
            if (connected) {
                registerWithTracker(files);
            }
        });
    }
}

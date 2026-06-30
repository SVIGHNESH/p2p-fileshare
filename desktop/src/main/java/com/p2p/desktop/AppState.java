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

    private final Preferences prefs = Preferences.userNodeForPackage(AppState.class);

    // Persisted settings
    public final StringProperty trackerHost = new SimpleStringProperty(prefs.get("trackerHost", ""));
    public final StringProperty trackerPort = new SimpleStringProperty(prefs.get("trackerPort", "9000"));
    public final StringProperty sharedFolderPath = new SimpleStringProperty(
            prefs.get("sharedFolder", System.getProperty("user.home") + "/P2PShare"));
    public final StringProperty myDisplayName = new SimpleStringProperty(
            prefs.get("displayName", System.getProperty("user.name")));

    // Runtime components
    public TrackerClient trackerClient;
    public PeerServer peerServer;
    public DownloadManager downloadManager;
    public UDPDiscovery discovery;

    public final ObservableList<DownloadManager.DownloadTask> downloads = FXCollections.observableArrayList();
    public final ObservableList<FileInfo> sharedFiles = FXCollections.observableArrayList();

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
        List<FileInfo> files = new ArrayList<>();
        File folder = getSharedFolder();
        File[] listed = folder.listFiles();
        if (listed == null) return files;
        for (File f : listed) {
            if (f.isFile() && !f.getName().endsWith(".meta")) {
                int chunks = FileChunker.getTotalChunks(f.length());
                String checksum;
                // T0.4: never advertise an empty checksum — a downloader would "verify"
                // any bytes against it and accept corruption. Skip files we cannot hash.
                try { checksum = FileChunker.sha256OfFile(f); }
                catch (Exception e) {
                    System.err.println("[AppState] Skipping unhashable shared file: "
                            + f.getName() + " (" + e.getMessage() + ")");
                    continue;
                }
                files.add(new FileInfo(f.getName(), f.length(), chunks, checksum));
            }
        }
        return files;
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
            try { com.p2p.core.crypto.TLSHelper.init(); }
            catch (Exception e) { System.err.println("[AppState] TLS init failed: " + e.getMessage()); }

            myIp = detectLanIp();
            final String ip = myIp;
            runFx(() -> myIpDisplay.set(ip));

            final List<FileInfo> files = buildFileList();
            runFx(() -> sharedFiles.setAll(files));

            int port = Protocol.DEFAULT_PEER_PORT;
            peerServer = new PeerServer(port, this::getSharedFolder);
            peerServer.start();

            downloadManager = new DownloadManager();

            int tPort = parseTrackerPort(trackerPort.get());
            trackerClient = new TrackerClient(trackerHost.get(), tPort);

            // Use the local `files` snapshot (not the ObservableList, which the FX
            // thread is concurrently populating) and a local boolean for control
            // flow — never the marshalled isConnected property, whose set() may not
            // have run yet.
            boolean ok = false;
            String discoveredHost = null;
            if (!trackerHost.get().isBlank()) {
                ok = trackerClient.register(myIp, port, files);
            }

            // Try localhost first (common case: tracker and desktop on same machine)
            if (!ok) {
                trackerClient.setTracker("127.0.0.1", tPort);
                if (trackerClient.register(myIp, port, files)) {
                    discoveredHost = "127.0.0.1";
                    ok = true;
                }
            }

            connected = ok;
            final boolean fOk = ok;
            final String fHost = discoveredHost;
            runFx(() -> {
                if (fHost != null) trackerHost.set(fHost);
                isConnected.set(fOk);
                if (fHost != null) savePrefs();
            });

            // Auto-discover tracker if not configured — retry until found or manually connected
            if (!ok) {
                discovery = new UDPDiscovery();
                scheduleDiscovery(discovery, port);
            }

            // Re-register periodically so the tracker doesn't evict us (90s timeout).
            keepAlive.scheduleAtFixedRate(() -> {
                if (connected && trackerClient != null) {
                    trackerClient.register(myIp, port, new ArrayList<>(sharedFiles));
                }
            }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);

            // DT.6: a Runtime shutdown hook covers abnormal termination (SIGTERM / Ctrl-C). The
            // normal window-close path runs App.stop() -> shutdown() instead; shutdown() is idempotent
            // so the two never double-clean.
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "appstate-shutdown-hook"));
        } catch (Exception e) {
            // DT.7 (surfacing this in the UI) is out of scope; at minimum don't let
            // a bind/TLS failure die silently on the background thread.
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
        int port = peerServer != null ? peerServer.getPort() : Protocol.DEFAULT_PEER_PORT;
        try { if (connected && trackerClient != null) trackerClient.unregister(myIp, port); } catch (Exception ignored) {}
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

    private void scheduleDiscovery(UDPDiscovery disc, int peerPort) {
        // The callback fires on the discovery thread (DT.2): keep the blocking
        // network calls here, but marshal every write to live UI state — the
        // trackerHost/trackerPort properties are bound bidirectionally to the
        // Settings text fields — onto the FX thread.
        disc.listenForTracker(found -> {
            if (found != null) {
                String[] parts = found.split(":");
                trackerClient.setTracker(parts[0], Integer.parseInt(parts[1]));
                boolean ok = trackerClient.register(myIp, peerPort, new ArrayList<>(sharedFiles));
                connected = ok;
                runFx(() -> {
                    trackerHost.set(parts[0]);
                    trackerPort.set(parts[1]);
                    isConnected.set(ok);
                    savePrefs();
                });
            } else if (!connected && !shuttingDown) {
                // Timed out, still not connected — keep retrying. A stale tracker
                // host saved from a previous network must not stop discovery.
                // (DT.6: but stop retrying once shutdown has begun so the JVM can exit.)
                scheduleDiscovery(disc, peerPort);
            }
        });
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
        if (trackerClient == null || trackerHost.get().isBlank()) return ReconnectResult.AUTO_DISCOVER;
        int port = peerServer != null ? peerServer.getPort() : Protocol.DEFAULT_PEER_PORT;
        int tPort = parseTrackerPort(trackerPort.get());
        trackerClient.setTracker(trackerHost.get(), tPort);
        boolean ok = trackerClient.register(myIp, port, new ArrayList<>(sharedFiles));
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
            final List<FileInfo> files = buildFileList();
            // Mutate the FX-bound ObservableList only on the FX thread.
            runFx(() -> sharedFiles.setAll(files));
            // Background control flow branches on the authoritative `connected` flag, not the
            // FX-bound isConnected property (whose marshalled set may not have run yet). Register
            // with the freshly built local snapshot — never the live ObservableList the FX thread
            // is concurrently populating — avoiding the ConcurrentModificationException DT.8 flags.
            if (connected && trackerClient != null) {
                int port = peerServer != null ? peerServer.getPort() : Protocol.DEFAULT_PEER_PORT;
                trackerClient.register(myIp, port, files);
            }
        });
    }
}

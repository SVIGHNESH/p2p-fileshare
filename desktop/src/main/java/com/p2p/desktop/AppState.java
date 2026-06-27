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

    public final BooleanProperty isConnected = new SimpleBooleanProperty(false);
    public String myIp = "unknown";

    public void savePrefs() {
        prefs.put("trackerHost", trackerHost.get());
        prefs.put("trackerPort", trackerPort.get());
        prefs.put("sharedFolder", sharedFolderPath.get());
        prefs.put("displayName", myDisplayName.get());
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
                try { checksum = FileChunker.sha256OfFile(f); }
                catch (Exception e) { checksum = ""; }
                files.add(new FileInfo(f.getName(), f.length(), chunks, checksum));
            }
        }
        return files;
    }

    public void init() {
        try { com.p2p.core.crypto.TLSHelper.init(); }
        catch (Exception e) { System.err.println("[AppState] TLS init failed: " + e.getMessage()); }

        myIp = detectLanIp();

        sharedFiles.setAll(buildFileList());

        int port = Protocol.DEFAULT_PEER_PORT;
        peerServer = new PeerServer(port, this::getSharedFolder);
        peerServer.start();

        downloadManager = new DownloadManager();

        int tPort = Integer.parseInt(trackerPort.get().isBlank() ? "9000" : trackerPort.get());
        trackerClient = new TrackerClient(trackerHost.get(), tPort);

        if (!trackerHost.get().isBlank()) {
            isConnected.set(trackerClient.register(myIp, port, sharedFiles));
        }

        // Try localhost first (common case: tracker and desktop on same machine)
        if (!isConnected.get()) {
            trackerClient.setTracker("127.0.0.1", tPort);
            if (trackerClient.register(myIp, port, sharedFiles)) {
                trackerHost.set("127.0.0.1");
                isConnected.set(true);
                savePrefs();
            }
        }

        // Auto-discover tracker if not configured — retry until found or manually connected
        if (!isConnected.get()) {
            discovery = new UDPDiscovery();
            scheduleDiscovery(discovery, port);
        }

        // Re-register periodically so the tracker doesn't evict us (90s timeout).
        keepAlive.scheduleAtFixedRate(() -> {
            if (isConnected.get() && trackerClient != null) {
                trackerClient.register(myIp, port, new ArrayList<>(sharedFiles));
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (trackerClient != null) trackerClient.unregister(myIp, port);
            if (peerServer != null) peerServer.stop();
            if (downloadManager != null) downloadManager.shutdown();
            keepAlive.shutdownNow();
            savePrefs();
        }));
    }

    private final java.util.concurrent.ScheduledExecutorService keepAlive =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tracker-keepalive");
                t.setDaemon(true);
                return t;
            });

    private void scheduleDiscovery(UDPDiscovery disc, int peerPort) {
        disc.listenForTracker(found -> {
            if (found != null) {
                String[] parts = found.split(":");
                trackerHost.set(parts[0]);
                trackerPort.set(parts[1]);
                trackerClient.setTracker(parts[0], Integer.parseInt(parts[1]));
                isConnected.set(trackerClient.register(myIp, peerPort, sharedFiles));
                savePrefs();
            } else if (!isConnected.get()) {
                // Timed out, still not connected — keep retrying. A stale tracker
                // host saved from a previous network must not stop discovery.
                scheduleDiscovery(disc, peerPort);
            }
        });
    }

    public void reconnect() {
        if (trackerHost.get().isBlank()) return;
        int port = peerServer != null ? peerServer.getPort() : Protocol.DEFAULT_PEER_PORT;
        int tPort = Integer.parseInt(trackerPort.get().isBlank() ? "9000" : trackerPort.get());
        trackerClient.setTracker(trackerHost.get(), tPort);
        isConnected.set(trackerClient.register(myIp, port, sharedFiles));
    }

    public void refreshSharedFiles() {
        sharedFiles.setAll(buildFileList());
        if (isConnected.get()) {
            int port = peerServer != null ? peerServer.getPort() : Protocol.DEFAULT_PEER_PORT;
            trackerClient.register(myIp, port, sharedFiles);
        }
    }
}

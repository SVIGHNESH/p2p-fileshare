package com.p2p.desktop;

import com.p2p.core.chunking.FileChunker;
import com.p2p.core.discovery.UDPDiscovery;
import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.FileInfo;
import com.p2p.core.tracker.TrackerClient;
import com.p2p.core.transfer.DownloadManager;
import com.p2p.core.transfer.PeerServer;
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

    public boolean isConnected = false;
    public String myIp = "unknown";

    public void savePrefs() {
        prefs.put("trackerHost", trackerHost.get());
        prefs.put("trackerPort", trackerPort.get());
        prefs.put("sharedFolder", sharedFolderPath.get());
        prefs.put("displayName", myDisplayName.get());
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
        try { myIp = InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception e) { myIp = "127.0.0.1"; }

        sharedFiles.setAll(buildFileList());

        int port = Protocol.DEFAULT_PEER_PORT;
        peerServer = new PeerServer(port, this::getSharedFolder);
        peerServer.start();

        downloadManager = new DownloadManager();

        int tPort = Integer.parseInt(trackerPort.get().isBlank() ? "9000" : trackerPort.get());
        trackerClient = new TrackerClient(trackerHost.get(), tPort);

        if (!trackerHost.get().isBlank()) {
            isConnected = trackerClient.register(myIp, port, sharedFiles);
        }

        // Auto-discover tracker if not configured
        if (!isConnected) {
            discovery = new UDPDiscovery();
            discovery.listenForTracker(found -> {
                if (found != null) {
                    String[] parts = found.split(":");
                    trackerHost.set(parts[0]);
                    trackerPort.set(parts[1]);
                    int tp = Integer.parseInt(parts[1]);
                    trackerClient.setTracker(parts[0], tp);
                    isConnected = trackerClient.register(myIp, port, sharedFiles);
                    savePrefs();
                }
            });
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (trackerClient != null) trackerClient.unregister(myIp, port);
            if (peerServer != null) peerServer.stop();
            if (downloadManager != null) downloadManager.shutdown();
            savePrefs();
        }));
    }

    public void refreshSharedFiles() {
        sharedFiles.setAll(buildFileList());
        if (isConnected) {
            int port = peerServer != null ? peerServer.getPort() : Protocol.DEFAULT_PEER_PORT;
            trackerClient.register(myIp, port, sharedFiles);
        }
    }
}

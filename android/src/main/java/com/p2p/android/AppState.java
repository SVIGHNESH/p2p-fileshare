package com.p2p.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import com.p2p.core.chunking.FileChunker;
import com.p2p.core.discovery.UDPDiscovery;
import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.FileInfo;
import com.p2p.core.tracker.TrackerClient;
import com.p2p.core.transfer.DownloadManager;
import com.p2p.core.transfer.PeerServer;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class AppState {

    private static AppState instance;
    public static AppState get(Context ctx) {
        if (instance == null) instance = new AppState(ctx.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;

    public String trackerHost;
    public int trackerPort = Protocol.DEFAULT_TRACKER_PORT;
    public String sharedFolderPath;
    public String myIp = "unknown";

    // Simple wrapper so SettingsFragment can call .get()/.set() like desktop AppState
    public static class StringHolder {
        private String value;
        public StringHolder(String v) { this.value = v; }
        public String get() { return value; }
        public void set(String v) { this.value = v; }
    }
    public final StringHolder myDisplayName = new StringHolder(System.getProperty("user.name", "User"));

    public TrackerClient trackerClient;
    public PeerServer peerServer;
    public DownloadManager downloadManager = new DownloadManager();
    public UDPDiscovery discovery;
    public boolean isConnected = false;

    public final List<DownloadManager.DownloadTask> downloads = new ArrayList<>();
    public final List<FileInfo> sharedFiles = new ArrayList<>();

    private AppState(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("p2pshare", Context.MODE_PRIVATE);
        this.trackerHost = prefs.getString("trackerHost", "");
        this.trackerPort = prefs.getInt("trackerPort", Protocol.DEFAULT_TRACKER_PORT);
        this.sharedFolderPath = prefs.getString("sharedFolder",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        + "/P2PShare");
    }

    public void init() {
        try { myIp = InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception e) { myIp = "127.0.0.1"; }

        getSharedFolder().mkdirs();
        refreshSharedFiles();

        peerServer = new PeerServer(Protocol.DEFAULT_PEER_PORT, this::getSharedFolder);
        peerServer.start();

        trackerClient = new TrackerClient(trackerHost, trackerPort);

        if (!trackerHost.isEmpty()) {
            isConnected = trackerClient.register(myIp, Protocol.DEFAULT_PEER_PORT, sharedFiles);
        }

        if (!isConnected) {
            discovery = new UDPDiscovery();
            discovery.listenForTracker(found -> {
                if (found != null) {
                    String[] parts = found.split(":");
                    trackerHost = parts[0];
                    trackerPort = Integer.parseInt(parts[1]);
                    trackerClient.setTracker(trackerHost, trackerPort);
                    isConnected = trackerClient.register(myIp, Protocol.DEFAULT_PEER_PORT, sharedFiles);
                    savePrefs();
                }
            });
        }
    }

    public void shutdown() {
        if (trackerClient != null) trackerClient.unregister(myIp, Protocol.DEFAULT_PEER_PORT);
        if (peerServer != null) peerServer.stop();
        if (downloadManager != null) downloadManager.shutdown();
    }

    public File getSharedFolder() {
        return new File(sharedFolderPath);
    }

    public void refreshSharedFiles() {
        sharedFiles.clear();
        File folder = getSharedFolder();
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && !f.getName().endsWith(".meta")) {
                int chunks = FileChunker.getTotalChunks(f.length());
                String checksum;
                try { checksum = FileChunker.sha256OfFile(f); }
                catch (Exception e) { checksum = ""; }
                sharedFiles.add(new FileInfo(f.getName(), f.length(), chunks, checksum));
            }
        }
        if (isConnected && trackerClient != null) {
            trackerClient.register(myIp, Protocol.DEFAULT_PEER_PORT, sharedFiles);
        }
    }

    public void savePrefs() {
        prefs.edit()
                .putString("trackerHost", trackerHost)
                .putInt("trackerPort", trackerPort)
                .putString("sharedFolder", sharedFolderPath)
                .apply();
    }
}

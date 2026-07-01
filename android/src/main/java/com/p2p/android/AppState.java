package com.p2p.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import com.p2p.core.chunking.FileChunker;
import com.p2p.core.crypto.TLSHelper;
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
        try { TLSHelper.init(new java.io.File(context.getFilesDir(), "p2pshare")); }
        catch (Exception e) { android.util.Log.e("P2PShare", "TLS init failed", e); }

        myIp = detectLanIp();

        getSharedFolder().mkdirs();
        refreshSharedFiles();

        peerServer = new PeerServer(Protocol.DEFAULT_PEER_PORT, this::getSharedFolder);
        peerServer.start();

        trackerClient = new TrackerClient(trackerHost, trackerPort);

        if (!trackerHost.isEmpty()) {
            isConnected = trackerClient.register(myIp, Protocol.DEFAULT_PEER_PORT, sharedFiles, myDisplayName.get());
        }

        if (!isConnected) {
            acquireMulticastLock();
            discovery = new UDPDiscovery();
            startDiscovery();
        }

        // Re-register periodically so the tracker doesn't evict us (90s timeout).
        keepAlive.scheduleAtFixedRate(() -> {
            if (isConnected && trackerClient != null) {
                trackerClient.register(myIp, Protocol.DEFAULT_PEER_PORT, new ArrayList<>(sharedFiles), myDisplayName.get());
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    /**
     * Android's Wi-Fi hardware drops multicast packets unless an app holds a
     * MulticastLock. Without this, UDP tracker discovery never receives the
     * tracker's announcement. Requires CHANGE_WIFI_MULTICAST_STATE permission.
     */
    private void acquireMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) return;
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                    context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            multicastLock = wifi.createMulticastLock("p2pshare-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Exception e) {
            android.util.Log.e("P2PShare", "MulticastLock failed", e);
        }
    }

    /** Listen for the tracker, retrying until found. Handles network changes. */
    private void startDiscovery() {
        discovery.listenForTracker(found -> {
            if (found != null) {
                String[] parts = found.split(":");
                trackerHost = parts[0];
                trackerPort = Integer.parseInt(parts[1]);
                trackerClient.setTracker(trackerHost, trackerPort);
                isConnected = trackerClient.register(myIp, Protocol.DEFAULT_PEER_PORT, sharedFiles, myDisplayName.get());
                savePrefs();
                if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
            } else if (!isConnected) {
                // Timed out without finding a tracker — keep retrying. A stale
                // tracker host saved from a previous network must not stop discovery.
                startDiscovery();
            }
        });
    }

    private final java.util.concurrent.ScheduledExecutorService keepAlive =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tracker-keepalive");
                t.setDaemon(true);
                return t;
            });

    public void shutdown() {
        keepAlive.shutdownNow();
        if (trackerClient != null) trackerClient.unregister(myIp, Protocol.DEFAULT_PEER_PORT);
        if (peerServer != null) peerServer.stop();
        if (downloadManager != null) downloadManager.shutdown();
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
                // T0.4: never advertise an empty checksum — a downloader would "verify"
                // any bytes against it and accept corruption. Skip files we cannot hash.
                try { checksum = FileChunker.sha256OfFile(f); }
                catch (Exception e) {
                    System.err.println("[AppState] Skipping unhashable shared file: "
                            + f.getName() + " (" + e.getMessage() + ")");
                    continue;
                }
                sharedFiles.add(new FileInfo(f.getName(), f.length(), chunks, checksum));
            }
        }
        if (isConnected && trackerClient != null) {
            // Network call must not run on the main thread (would throw NetworkOnMainThreadException).
            final List<FileInfo> snapshot = new ArrayList<>(sharedFiles);
            final String name = myDisplayName.get();
            new Thread(() -> trackerClient.register(myIp, Protocol.DEFAULT_PEER_PORT, snapshot, name)).start();
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

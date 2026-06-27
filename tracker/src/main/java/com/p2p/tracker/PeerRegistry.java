package com.p2p.tracker;

import com.p2p.core.protocol.Protocol.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PeerRegistry {

    private static final long HEARTBEAT_TIMEOUT_MS = 90_000;

    // TR.4: bound the registry's memory footprint. A single peer cannot register more than
    // MAX_FILES_PER_PEER files or a name longer than MAX_FILENAME_LENGTH, and the tracker stops
    // admitting brand-new peers past MAX_PEERS. These also feed TrackerServer's request line cap,
    // which is derived from them so a legitimate max-files register is never rejected as oversized.
    public static final int MAX_PEERS = 10_000;
    public static final int MAX_FILES_PER_PEER = 2048;
    public static final int MAX_FILENAME_LENGTH = 512;

    private final ConcurrentHashMap<String, PeerRecord> peers = new ConcurrentHashMap<>();

    public static class PeerRecord {
        public final PeerInfo info;
        public volatile long lastSeen;

        public PeerRecord(PeerInfo info) {
            this.info = info;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    /**
     * Registers (or replaces) a peer. Returns {@code false} only when the registry is at its
     * {@link #MAX_PEERS} capacity and this is a brand-new peer — updates to an already-known peer
     * are always accepted so a connected peer never gets locked out by a full table.
     */
    public boolean register(PeerInfo peer) {
        String key = peer.ip + ":" + peer.port;
        // TR.3: drop null entries and null/blank-named files at register time so a single peer
        // cannot poison the registry — an unguarded f.name.equalsIgnoreCase() in findPeersWithFile
        // would otherwise NPE and break discovery for every querying client.
        // TR.4: also cap files-per-peer and filename length to bound the registry footprint.
        peer.files = sanitizeFiles(peer.files);
        // TR.4: stop admitting new peers past the cap (existing peers may still update in place).
        if (!peers.containsKey(key) && peers.size() >= MAX_PEERS) {
            System.out.println("[Registry] Rejected new peer (registry full): " + key);
            return false;
        }
        peers.put(key, new PeerRecord(peer));
        System.out.printf("[Registry] Registered %s with %d file(s)%n",
                key, peer.files != null ? peer.files.size() : 0);
        return true;
    }

    private static List<FileInfo> sanitizeFiles(List<FileInfo> files) {
        if (files == null) return null;
        List<FileInfo> clean = new ArrayList<>();
        for (FileInfo f : files) {
            if (f == null || f.name == null || f.name.isBlank()) continue;
            if (f.name.length() > MAX_FILENAME_LENGTH) continue; // TR.4: drop absurdly long names
            clean.add(f);
            if (clean.size() >= MAX_FILES_PER_PEER) break;       // TR.4: cap files per peer
        }
        return clean;
    }

    public void unregister(String ip, int port) {
        peers.remove(ip + ":" + port);
        System.out.printf("[Registry] Unregistered %s:%d%n", ip, port);
    }

    public void heartbeat(String ip, int port) {
        PeerRecord r = peers.get(ip + ":" + port);
        if (r != null) r.lastSeen = System.currentTimeMillis();
    }

    public List<PeerInfo> findPeersWithFile(String filename) {
        if (filename == null) return new ArrayList<>();
        long now = System.currentTimeMillis();
        return peers.values().stream()
                .filter(r -> now - r.lastSeen < HEARTBEAT_TIMEOUT_MS)
                .filter(r -> r.info.files != null &&
                        r.info.files.stream()
                                .anyMatch(f -> f != null && f.name != null && f.name.equalsIgnoreCase(filename)))
                .map(r -> r.info)
                .collect(Collectors.toList());
    }

    public List<PeerInfo> getAllActivePeers() {
        long now = System.currentTimeMillis();
        return peers.values().stream()
                .filter(r -> now - r.lastSeen < HEARTBEAT_TIMEOUT_MS)
                .map(r -> r.info)
                .collect(Collectors.toList());
    }

    /** Remove peers that haven't sent a heartbeat in 90 seconds. */
    public void evictStalePeers() {
        long now = System.currentTimeMillis();
        peers.entrySet().removeIf(e -> {
            boolean stale = now - e.getValue().lastSeen > HEARTBEAT_TIMEOUT_MS;
            if (stale) System.out.println("[Registry] Evicted stale peer: " + e.getKey());
            return stale;
        });
    }

    public int getPeerCount() { return peers.size(); }

    public Set<String> getAllFilenames() {
        Set<String> names = new HashSet<>();
        peers.values().forEach(r -> {
            if (r.info.files != null) r.info.files.forEach(f -> {
                if (f != null && f.name != null && !f.name.isBlank()) names.add(f.name);
            });
        });
        return names;
    }
}

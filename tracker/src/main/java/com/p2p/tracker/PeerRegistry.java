package com.p2p.tracker;

import com.p2p.core.protocol.Protocol.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PeerRegistry {

    private static final long HEARTBEAT_TIMEOUT_MS = 90_000;

    private final ConcurrentHashMap<String, PeerRecord> peers = new ConcurrentHashMap<>();

    public static class PeerRecord {
        public final PeerInfo info;
        public volatile long lastSeen;

        public PeerRecord(PeerInfo info) {
            this.info = info;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    public void register(PeerInfo peer) {
        String key = peer.ip + ":" + peer.port;
        peers.put(key, new PeerRecord(peer));
        System.out.printf("[Registry] Registered %s with %d file(s)%n",
                key, peer.files != null ? peer.files.size() : 0);
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
        long now = System.currentTimeMillis();
        return peers.values().stream()
                .filter(r -> now - r.lastSeen < HEARTBEAT_TIMEOUT_MS)
                .filter(r -> r.info.files != null &&
                        r.info.files.stream().anyMatch(f -> f.name.equalsIgnoreCase(filename)))
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
            if (r.info.files != null) r.info.files.forEach(f -> names.add(f.name));
        });
        return names;
    }
}

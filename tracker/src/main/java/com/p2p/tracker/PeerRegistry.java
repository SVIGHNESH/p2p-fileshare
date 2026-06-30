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
    // T0.5/TR.4: a legitimate key fingerprint is a 64-char hex SHA-256; cap generously and drop
    // anything longer so a peer cannot smuggle an unbounded string through the relayed keyId field.
    public static final int MAX_KEYID_LENGTH = 128;
    // The cosmetic displayName is payload-claimed (forgeable, like the keyId), so it is bounded too;
    // a nickname has no reason to exceed this and the tracker truncates anything longer.
    public static final int MAX_DISPLAYNAME_LENGTH = 64;

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
        peer.keyId = sanitizeKeyId(peer.keyId);
        peer.displayName = sanitizeDisplayName(peer.displayName);
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

    /** T0.5: drop a null/blank or over-long keyId so only a plausible fingerprint is relayed. */
    private static String sanitizeKeyId(String keyId) {
        if (keyId == null || keyId.isBlank() || keyId.length() > MAX_KEYID_LENGTH) return null;
        return keyId;
    }

    /**
     * Clean a payload-claimed display nickname before it is stored and relayed to other peers'
     * UIs. The tracker is the trust boundary here (TR.2): the name is forgeable and rendered in
     * remote UIs, so this is a <i>content</i> sanitizer, not just a length cap — it strips control
     * characters and any embedded newline/tab so a crafted name cannot break the layout of a result
     * card or smuggle a second visual line, collapses internal whitespace runs to single spaces, trims
     * the ends, then caps the length. A name that is null, empty, or whitespace-/control-only after
     * cleaning becomes {@code null} (advertise no name) rather than an empty string.
     */
    static String sanitizeDisplayName(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder(Math.min(name.length(), MAX_DISPLAYNAME_LENGTH));
        boolean pendingSpace = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // Treat every ISO control char (newline, tab, CR, NUL, …) and any whitespace as a space,
            // then collapse runs so a crafted name cannot inject a second visual line into a card.
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                pendingSpace = sb.length() > 0; // never lead with a space
                continue;
            }
            if (pendingSpace) {
                if (sb.length() + 1 >= MAX_DISPLAYNAME_LENGTH) break; // no room for "<space><char>"
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(c);
            if (sb.length() >= MAX_DISPLAYNAME_LENGTH) break;
        }
        String cleaned = sb.toString();
        return cleaned.isEmpty() ? null : cleaned;
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

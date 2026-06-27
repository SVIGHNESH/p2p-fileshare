package com.p2p.tracker;

import com.p2p.core.protocol.Protocol.FileInfo;
import com.p2p.core.protocol.Protocol.PeerInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the tracker's registry-poisoning fix (TR.3).
 *
 * The original bug: {@code findPeersWithFile} and {@code getAllFilenames} dereferenced
 * {@code f.name} with no null check, so a single peer registering a {@code FileInfo} with a
 * null name made every query throw an NPE inside the stream — breaking discovery for all
 * callers. These tests pin both halves of the fix: null/blank-named entries are dropped at
 * register time, and the query predicates are null-safe defensively.
 */
class PeerRegistryTest {

    private static FileInfo named(String name) {
        return new FileInfo(name, 100, 1, "checksum");
    }

    @Test
    void registerDropsNullAndBlankNamedFiles() {
        PeerRegistry registry = new PeerRegistry();
        // A null list element, a null name, a blank name, and one genuine file.
        List<FileInfo> files = new ArrayList<>(Arrays.asList(
                null, named(null), named("   "), named("movie.mp4")));
        registry.register(new PeerInfo("10.0.0.5", 9001, files));

        Set<String> names = registry.getAllFilenames();
        assertEquals(Set.of("movie.mp4"), names,
                "only the genuinely-named file survives registration");
    }

    @Test
    void findPeersWithFileDoesNotThrowOnPoisonedRegistry() {
        PeerRegistry registry = new PeerRegistry();
        List<FileInfo> files = new ArrayList<>(Arrays.asList(named(null), named("doc.pdf")));
        registry.register(new PeerInfo("10.0.0.6", 9001, files));

        // The query that used to NPE now resolves cleanly to the one good file.
        List<PeerInfo> hits = registry.findPeersWithFile("doc.pdf");
        assertEquals(1, hits.size());
        assertEquals("10.0.0.6", hits.get(0).ip);

        // A name that only the dropped poison "had" simply yields no peers — no crash.
        assertTrue(registry.findPeersWithFile("anything-else").isEmpty());
    }

    @Test
    void findPeersWithFileToleratesNullQuery() {
        PeerRegistry registry = new PeerRegistry();
        registry.register(new PeerInfo("10.0.0.7", 9001,
                new ArrayList<>(List.of(named("a.txt")))));
        assertTrue(registry.findPeersWithFile(null).isEmpty(),
                "a null query filename must return empty, not NPE");
    }

    @Test
    void registerHandlesNullFileListAndIsMatchedCaseInsensitively() {
        PeerRegistry registry = new PeerRegistry();
        registry.register(new PeerInfo("10.0.0.8", 9001, null)); // null file list is allowed
        registry.register(new PeerInfo("10.0.0.9", 9001,
                new ArrayList<>(List.of(named("Report.PDF")))));

        assertEquals(2, registry.getPeerCount());
        assertEquals(1, registry.findPeersWithFile("report.pdf").size(),
                "lookup stays case-insensitive after the null-guard");
        assertFalse(registry.getAllFilenames().isEmpty());
    }

    // ── TR.4: registry footprint caps ─────────────────────────────────────────

    @Test
    void registerCapsFilesPerPeer() {
        PeerRegistry registry = new PeerRegistry();
        List<FileInfo> files = new ArrayList<>();
        for (int i = 0; i < PeerRegistry.MAX_FILES_PER_PEER + 50; i++) {
            files.add(named("file" + i + ".bin")); // distinct names so getAllFilenames doesn't dedup
        }
        registry.register(new PeerInfo("10.1.0.1", 9001, files));

        assertEquals(PeerRegistry.MAX_FILES_PER_PEER, registry.getAllFilenames().size(),
                "a peer cannot register more than the per-peer file cap");
    }

    @Test
    void registerDropsOverLongFilenames() {
        PeerRegistry registry = new PeerRegistry();
        String tooLong = "a".repeat(PeerRegistry.MAX_FILENAME_LENGTH + 1);
        String atLimit = "b".repeat(PeerRegistry.MAX_FILENAME_LENGTH);
        registry.register(new PeerInfo("10.1.0.2", 9001,
                new ArrayList<>(Arrays.asList(named(tooLong), named(atLimit), named("ok.txt")))));

        Set<String> names = registry.getAllFilenames();
        assertEquals(Set.of(atLimit, "ok.txt"), names,
                "names over the length cap are dropped; one exactly at the cap survives");
    }

    @Test
    void registerCapsTotalPeersButStillAllowsUpdatesToKnownPeers() {
        PeerRegistry registry = new PeerRegistry();
        for (int i = 0; i < PeerRegistry.MAX_PEERS; i++) {
            String ip = "10." + (i >>> 16 & 0xFF) + "." + (i >>> 8 & 0xFF) + "." + (i & 0xFF);
            assertTrue(registry.register(new PeerInfo(ip, 9001, null)),
                    "every peer up to the cap registers");
        }
        assertEquals(PeerRegistry.MAX_PEERS, registry.getPeerCount());

        // A brand-new peer beyond the cap is refused...
        assertFalse(registry.register(new PeerInfo("172.16.0.1", 9001, null)),
                "a new peer past the cap is rejected");
        assertEquals(PeerRegistry.MAX_PEERS, registry.getPeerCount());

        // ...but an already-known peer may still update in place (never lock out a live peer).
        PeerInfo existing = new PeerInfo("10.0.0.0", 9001,
                new ArrayList<>(List.of(named("update.bin"))));
        assertTrue(registry.register(existing),
                "an existing peer can re-register even when the table is full");
        assertEquals(1, registry.findPeersWithFile("update.bin").size());
    }
}

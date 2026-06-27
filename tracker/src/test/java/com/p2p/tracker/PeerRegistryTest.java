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
}

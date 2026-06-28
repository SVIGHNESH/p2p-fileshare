package com.p2p.core.transfer;

import com.p2p.core.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards PeerServer's request-validation hardening (TE.2, TE.3).
 *
 * These exercise the pure validation helpers directly (no TLS socket), which is the exact
 * locus of both bugs: the path-prefix guard and the chunk-index range check. A real-socket
 * E2E test is currently blocked because the bundled keystore is absent on a clean checkout,
 * so TLSHelper.init() cannot start a PeerServer (see notes).
 */
class PeerServerTest {

    // ── TE.3: path-traversal guard ───────────────────────────────────────────

    @Test
    void resolvesFileInsideSharedFolder(@TempDir Path tmp) throws IOException {
        File share = Files.createDirectory(tmp.resolve("share")).toFile();
        File ok = new File(share, "ok.txt");
        Files.writeString(ok.toPath(), "hello");

        File resolved = PeerServer.resolveSharedFile(share, "ok.txt");
        assertNotNull(resolved, "an existing file inside the shared folder must resolve");
        assertTrue(resolved.exists());
    }

    @Test
    void rejectsSiblingDirectoryTraversal(@TempDir Path tmp) throws IOException {
        // The sibling file MUST exist, or file.exists() short-circuits and the reject would
        // pass for the wrong reason - the bug is only reachable when the traversal target is real.
        File share = Files.createDirectory(tmp.resolve("share")).toFile();
        File sibling = Files.createDirectory(tmp.resolve("share-secret")).toFile();
        Files.writeString(new File(sibling, "secret.txt").toPath(), "top secret");

        // The old bare startsWith(sharedFolder.getCanonicalPath()) returned this file:
        // "…/share-secret/secret.txt".startsWith("…/share") is true. The trailing-separator
        // guard rejects it.
        assertNull(PeerServer.resolveSharedFile(share, "../share-secret/secret.txt"),
                "a sibling directory sharing the folder-name prefix must not be served");
    }

    @Test
    void rejectsMissingNullAndEmptyNames(@TempDir Path tmp) throws IOException {
        File share = Files.createDirectory(tmp.resolve("share")).toFile();
        assertNull(PeerServer.resolveSharedFile(share, "does-not-exist.txt"));
        assertNull(PeerServer.resolveSharedFile(share, null));
        assertNull(PeerServer.resolveSharedFile(share, ""));
    }

    @Test
    void rejectsDirectoryAsFile(@TempDir Path tmp) throws IOException {
        File share = Files.createDirectory(tmp.resolve("share")).toFile();
        Files.createDirectory(share.toPath().resolve("subdir"));
        assertNull(PeerServer.resolveSharedFile(share, "subdir"),
                "a directory inside the shared folder is not a servable chunked file");
    }

    // ── TE.2: chunk-index range check ────────────────────────────────────────

    @Test
    void acceptsInRangeChunkIndices() {
        // 2*CHUNK_SIZE + 1 byte file => 3 chunks (indices 0,1,2).
        long size = 2L * Protocol.CHUNK_SIZE + 1;
        assertTrue(PeerServer.isValidChunkIndex(0, size));
        assertTrue(PeerServer.isValidChunkIndex(1, size));
        assertTrue(PeerServer.isValidChunkIndex(2, size), "last chunk index must be accepted");
    }

    @Test
    void rejectsOutOfRangeChunkIndices() {
        long size = 2L * Protocol.CHUNK_SIZE + 1; // 3 chunks
        // Too-large would crash readChunk with NegativeArraySizeException; negative would crash
        // the RandomAccessFile seek. Both previously dropped the connection silently.
        assertFalse(PeerServer.isValidChunkIndex(3, size), "one past the last chunk must be rejected");
        assertFalse(PeerServer.isValidChunkIndex(-1, size));
        assertFalse(PeerServer.isValidChunkIndex(Integer.MIN_VALUE, size));
        assertFalse(PeerServer.isValidChunkIndex(Integer.MAX_VALUE, size));
    }

    @Test
    void rejectsAnyChunkIndexForEmptyFile() {
        // getTotalChunks(0) == 0, so an empty file exposes no valid chunk.
        assertFalse(PeerServer.isValidChunkIndex(0, 0));
    }
}

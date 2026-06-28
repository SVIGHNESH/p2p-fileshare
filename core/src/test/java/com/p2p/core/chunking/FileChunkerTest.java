package com.p2p.core.chunking;

import com.p2p.core.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Hashing and chunk-math tests for {@link FileChunker}. */
class FileChunkerTest {

    // Known SHA-256 vectors (FIPS 180-2 / RFC 6234 examples).
    private static final String SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String SHA256_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void sha256MatchesKnownVectors() {
        assertEquals(SHA256_ABC, FileChunker.sha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(SHA256_EMPTY, FileChunker.sha256(new byte[0]));
    }

    @Test
    void verifyChunkAcceptsMatchAndRejectsMismatch() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        assertTrue(FileChunker.verifyChunk(data, FileChunker.sha256(data)));
        assertFalse(FileChunker.verifyChunk(data, SHA256_EMPTY));
    }

    @Test
    void getTotalChunksRoundsUp() {
        int chunk = Protocol.CHUNK_SIZE;
        assertEquals(0, FileChunker.getTotalChunks(0));
        assertEquals(1, FileChunker.getTotalChunks(1));
        assertEquals(1, FileChunker.getTotalChunks(chunk));
        assertEquals(2, FileChunker.getTotalChunks(chunk + 1));
        assertEquals(3, FileChunker.getTotalChunks(3L * chunk));
        assertEquals(4, FileChunker.getTotalChunks(3L * chunk + 1));
    }

    @Test
    void sha256OfFileMatchesInMemoryDigest(@TempDir Path dir) throws Exception {
        byte[] data = patternBytes(50_000);
        File f = dir.resolve("data.bin").toFile();
        Files.write(f.toPath(), data);
        assertEquals(FileChunker.sha256(data), FileChunker.sha256OfFile(f));
    }

    @Test
    void readChunkReturnsExactBytesAndShorterFinalChunk(@TempDir Path dir) throws Exception {
        int chunk = Protocol.CHUNK_SIZE;
        int tail = 12_345;
        byte[] data = patternBytes(2 * chunk + tail); // 3 chunks; last is partial
        File f = dir.resolve("three-chunks.bin").toFile();
        Files.write(f.toPath(), data);

        assertEquals(3, FileChunker.getTotalChunks(data.length));

        byte[] first = FileChunker.readChunk(f, 0);
        assertEquals(chunk, first.length);
        assertArrayEquals(slice(data, 0, chunk), first);

        byte[] last = FileChunker.readChunk(f, 2);
        assertEquals(tail, last.length);
        assertArrayEquals(slice(data, 2 * chunk, tail), last);
    }

    /** Deterministic, non-trivial content so byte-exactness is actually exercised. */
    private static byte[] patternBytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + 7);
        return b;
    }

    private static byte[] slice(byte[] src, int offset, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, offset, out, 0, len);
        return out;
    }
}

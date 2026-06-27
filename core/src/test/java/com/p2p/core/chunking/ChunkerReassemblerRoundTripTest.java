package com.p2p.core.chunking;

import com.p2p.core.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end round-trip of the chunk/reassemble pipeline, plus the resume
 * (.meta) state machine. This is the logic most likely to break silently when
 * the transfer engine is refactored, so it is verified at the byte level.
 */
class ChunkerReassemblerRoundTripTest {

    @Test
    void chunksReassembleIntoAByteIdenticalFile(@TempDir Path dir) throws Exception {
        byte[] data = patternBytes(2 * Protocol.CHUNK_SIZE + 12_345); // 3 chunks, partial tail
        File source = writeFile(dir, "source.bin", data);
        File out = dir.resolve("out.bin").toFile();

        int total = FileChunker.getTotalChunks(data.length);
        FileReassembler r = new FileReassembler(out, total);

        for (int i = 0; i < total; i++) {
            r.writeChunk(i, FileChunker.readChunk(source, i));
        }
        r.close(); // flush + release the write channel before reading the file back

        assertTrue(r.isComplete());
        assertEquals(total, r.receivedCount());
        assertEquals(1.0, r.getProgress(), 0.0);
        assertEquals(0, r.missingChunks().length);
        assertArrayEquals(data, Files.readAllBytes(out.toPath()));
        assertEquals(FileChunker.sha256OfFile(source), FileChunker.sha256OfFile(out));
    }

    @Test
    void outOfOrderWritesStillReconstructTheFile(@TempDir Path dir) throws Exception {
        byte[] data = patternBytes(3 * Protocol.CHUNK_SIZE);
        File source = writeFile(dir, "source.bin", data);
        File out = dir.resolve("out.bin").toFile();

        int total = FileChunker.getTotalChunks(data.length);
        FileReassembler r = new FileReassembler(out, total);

        for (int i : new int[]{2, 0, 1}) {
            r.writeChunk(i, FileChunker.readChunk(source, i));
        }
        r.close();

        assertTrue(r.isComplete());
        assertArrayEquals(data, Files.readAllBytes(out.toPath()));
    }

    @Test
    void missingChunksReportsTheGaps(@TempDir Path dir) throws Exception {
        byte[] data = patternBytes(3 * Protocol.CHUNK_SIZE);
        File source = writeFile(dir, "source.bin", data);
        File out = dir.resolve("out.bin").toFile();

        FileReassembler r = new FileReassembler(out, 3);
        r.writeChunk(0, FileChunker.readChunk(source, 0));
        r.writeChunk(2, FileChunker.readChunk(source, 2));
        r.close();

        assertFalse(r.isComplete());
        assertEquals(2, r.receivedCount());
        assertArrayEquals(new int[]{1}, r.missingChunks());
        assertTrue(r.isChunkReceived(0));
        assertFalse(r.isChunkReceived(1));
    }

    @Test
    void stateSurvivesSaveAndReloadSoDownloadsResume(@TempDir Path dir) throws Exception {
        byte[] data = patternBytes(3 * Protocol.CHUNK_SIZE);
        File source = writeFile(dir, "source.bin", data);
        File out = dir.resolve("out.bin").toFile();

        FileReassembler first = new FileReassembler(out, 3);
        first.writeChunk(0, FileChunker.readChunk(source, 0));
        first.writeChunk(2, FileChunker.readChunk(source, 2));
        first.saveState();
        first.close(); // release the write channel before the resumed instance opens its own

        FileReassembler resumed = FileReassembler.loadState(out);
        assertNotNull(resumed);
        assertEquals(3, resumed.getTotalChunks());
        assertTrue(resumed.isChunkReceived(0));
        assertFalse(resumed.isChunkReceived(1));
        assertTrue(resumed.isChunkReceived(2));
        assertArrayEquals(new int[]{1}, resumed.missingChunks());

        // Finish the download on the resumed instance and confirm correctness. The partial file
        // written by `first` (chunks 0 and 2) must survive the resumed instance opening the channel —
        // a truncating open would zero it and this byte-identical assertion would fail.
        resumed.writeChunk(1, FileChunker.readChunk(source, 1));
        resumed.close();
        assertTrue(resumed.isComplete());
        assertArrayEquals(data, Files.readAllBytes(out.toPath()));

        resumed.deleteMetaFile();
        assertNull(FileReassembler.loadState(out), "meta file should be gone after delete");
    }

    @Test
    void loadStateReturnsNullWhenNoMetaExists(@TempDir Path dir) throws Exception {
        File out = dir.resolve("never-started.bin").toFile();
        assertNull(FileReassembler.loadState(out));
    }

    private static File writeFile(Path dir, String name, byte[] data) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.write(f.toPath(), data);
        return f;
    }

    private static byte[] patternBytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + 7);
        return b;
    }
}

package com.p2p.core.chunking;

import com.p2p.core.protocol.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TE.4 guards for the durable, concurrent reassembler:
 * <ul>
 *   <li>many threads writing distinct chunks at once (via the absolute-position channel write,
 *       with no global I/O lock) still produce a byte-identical file;</li>
 *   <li>{@code saveState} publishes the {@code .meta} atomically — it leaves no {@code .meta.tmp}
 *       behind and {@code loadState} round-trips the exact bitmap, including across a re-save.</li>
 * </ul>
 */
class FileReassemblerConcurrencyTest {

    @Test
    void concurrentChunkWritesProduceAByteIdenticalFile(@TempDir Path dir) throws Exception {
        // 6 full chunks + a partial tail, so offsets and the short final write are both exercised.
        byte[] data = patternBytes(6 * Protocol.CHUNK_SIZE + 4_321);
        File source = writeFile(dir, "source.bin", data);
        File out = dir.resolve("out.bin").toFile();

        int total = FileChunker.getTotalChunks(data.length);
        FileReassembler r = new FileReassembler(out, total);

        // Fire every chunk write off at the same instant for maximum contention on the channel.
        CyclicBarrier startLine = new CyclicBarrier(total);
        List<Thread> threads = new ArrayList<>(total);
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    byte[] chunk = FileChunker.readChunk(source, idx);
                    startLine.await();
                    r.writeChunk(idx, chunk);
                } catch (Throwable e) {
                    synchronized (errors) { errors.add(e); }
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        r.close();

        assertTrue(errors.isEmpty(), () -> "concurrent writes threw: " + errors);
        assertTrue(r.isComplete());
        assertArrayEquals(data, Files.readAllBytes(out.toPath()),
                "concurrent positioned writes must reassemble the exact source bytes");
    }

    @Test
    void saveStateIsAtomicAndLeavesNoTempFile(@TempDir Path dir) throws Exception {
        byte[] data = patternBytes(3 * Protocol.CHUNK_SIZE);
        File source = writeFile(dir, "source.bin", data);
        File out = dir.resolve("out.bin").toFile();

        FileReassembler r = new FileReassembler(out, 3);
        r.writeChunk(0, FileChunker.readChunk(source, 0));
        r.writeChunk(2, FileChunker.readChunk(source, 2));
        r.saveState();

        File tmp = new File(dir.toFile(), "out.bin.meta.tmp");
        assertFalse(tmp.exists(), "the atomic rename must leave no .meta.tmp behind");

        FileReassembler loaded = FileReassembler.loadState(out);
        assertNotNull(loaded);
        assertTrue(loaded.isChunkReceived(0));
        assertFalse(loaded.isChunkReceived(1));
        assertTrue(loaded.isChunkReceived(2));

        // Re-save after another chunk: the rename must replace the existing .meta cleanly.
        r.writeChunk(1, FileChunker.readChunk(source, 1));
        r.saveState();
        r.close();
        assertFalse(tmp.exists());

        FileReassembler reloaded = FileReassembler.loadState(out);
        assertNotNull(reloaded);
        assertTrue(reloaded.isComplete(), "a re-saved .meta must reflect the newer bitmap");
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

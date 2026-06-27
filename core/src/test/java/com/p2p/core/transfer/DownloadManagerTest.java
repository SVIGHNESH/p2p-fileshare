package com.p2p.core.transfer;

import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.PeerInfo;
import com.p2p.core.transfer.DownloadManager.DownloadState;
import com.p2p.core.transfer.DownloadManager.DownloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for T0.2: a single shared thread pool that runs both the
 * per-file coordinators (which block on {@code Future.get()}) and the per-chunk
 * workers starves itself once the number of concurrent downloads reaches the pool
 * size — every thread sits as a blocked coordinator and no thread is left to fetch
 * chunks. With a coordinator pool separate from the chunk-worker pool, the same
 * workload completes promptly.
 *
 * The test injects an in-memory {@link DownloadManager.ChunkFetcher} so the
 * orchestration is exercised with zero sockets/TLS, and starts more concurrent
 * downloads than the chunk-worker pool size to make the old starvation
 * deterministic rather than timing-dependent.
 */
class DownloadManagerTest {

    /** Comfortably above the internal chunk-worker pool size (8) so the old single-pool design deadlocks. */
    private static final int CONCURRENT_DOWNLOADS = 16;
    private static final int CHUNKS_PER_FILE = 3;

    @Test
    void manyConcurrentDownloadsDoNotDeadlock(@TempDir Path tmp) throws Exception {
        // Deterministic source content per file; the fetcher serves chunk slices of it.
        Map<String, byte[]> sources = new HashMap<>();
        for (int f = 0; f < CONCURRENT_DOWNLOADS; f++) {
            sources.put("file-" + f, makeContent("file-" + f, CHUNKS_PER_FILE));
        }

        DownloadManager.ChunkFetcher fetcher = (peer, filename, chunkIndex) -> {
            byte[] content = sources.get(filename);
            long offset = (long) chunkIndex * Protocol.CHUNK_SIZE;
            int len = (int) Math.min(Protocol.CHUNK_SIZE, content.length - offset);
            byte[] slice = new byte[len];
            System.arraycopy(content, (int) offset, slice, 0, len);
            return slice;
        };

        DownloadManager dm = new DownloadManager(fetcher);
        CountDownLatch done = new CountDownLatch(CONCURRENT_DOWNLOADS);
        AtomicInteger errors = new AtomicInteger();
        List<DownloadTask> tasks = new ArrayList<>();
        List<PeerInfo> peers = List.of(new PeerInfo("127.0.0.1", 9001, new ArrayList<>()));

        try {
            for (int f = 0; f < CONCURRENT_DOWNLOADS; f++) {
                String name = "file-" + f;
                File out = tmp.resolve(name).toFile();
                DownloadTask task = new DownloadTask(name, sources.get(name).length, out, peers);
                // Count down in BOTH terminal callbacks so a silent FAILED can't masquerade as success.
                task.onComplete = t -> done.countDown();
                task.onError = t -> { errors.incrementAndGet(); done.countDown(); };
                tasks.add(task);
                dm.download(task);
            }

            boolean finished = done.await(15, TimeUnit.SECONDS);
            assertTrue(finished,
                    "Downloads did not all terminate within 15s — the coordinator pool starved the chunk workers (T0.2 deadlock).");
            assertEquals(0, errors.get(), "No download should have failed");

            for (DownloadTask task : tasks) {
                assertEquals(DownloadState.COMPLETE, task.state, task.filename + " should be COMPLETE");
                assertArrayEquals(sources.get(task.filename),
                        Files.readAllBytes(task.outputFile.toPath()),
                        task.filename + " bytes should match the source");
            }
        } finally {
            dm.shutdown();
        }
    }

    /** Builds {@code chunks} chunks worth of deterministic bytes (last chunk is short). */
    private static byte[] makeContent(String seed, int chunks) {
        int size = (chunks - 1) * Protocol.CHUNK_SIZE + 137; // last chunk deliberately partial
        byte[] data = new byte[size];
        int h = seed.hashCode();
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + h);
        }
        return data;
    }
}

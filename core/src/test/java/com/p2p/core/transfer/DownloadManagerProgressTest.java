package com.p2p.core.transfer;

import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.PeerInfo;
import com.p2p.core.transfer.DownloadManager.DownloadState;
import com.p2p.core.transfer.DownloadManager.DownloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TE.5 guard: the per-chunk progress callback is throttled to at most one call per advancing
 * whole percentage point, so a fast multi-chunk download no longer fires one off-thread UI
 * callback per 512 KB chunk.
 *
 * <p>The invariant tested is scale-independent and needs no magic-number bound: every
 * {@code DOWNLOADING} callback must report a DISTINCT integer percent. Without the throttle, a
 * {@code TOTAL_CHUNKS}-chunk download fires one callback per chunk and percents repeat (e.g. three
 * chunks all at 0%, then several at 1%), so the distinct-percent assertion fails; with it, the
 * percents strictly increase 1..100 and the assertion holds. A single peer (one worker) makes the
 * sequence fully deterministic, and {@code expectedChecksum == null} skips the read-back so the
 * sparse ~125 MB output file is never hashed.
 */
class DownloadManagerProgressTest {

    /** Enough chunks that throttling is unambiguous: 250 chunks collapse to at most 100 callbacks. */
    private static final int TOTAL_CHUNKS = 250;

    @Test
    void perChunkProgressCallbacksAreThrottledToWholePercents(@TempDir Path tmp) throws Exception {
        // In-memory fetcher: the bytes are irrelevant (no checksum, no read-back), so serve a tiny
        // slice per chunk. The reassembler writes it at chunkIndex * CHUNK_SIZE, yielding a sparse file.
        DownloadManager.ChunkFetcher fetcher = (peer, filename, chunkIndex) -> new byte[]{1, 2, 3, 4};
        DownloadManager dm = new DownloadManager(fetcher);

        // Record the integer percent reported by every DOWNLOADING-state progress callback, and
        // separately whether a COMPLETE callback arrived at exactly 100%.
        List<Integer> downloadingPercents = new CopyOnWriteArrayList<>();
        AtomicReference<Double> completeProgress = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        long fileSize = (long) TOTAL_CHUNKS * Protocol.CHUNK_SIZE; // ceil(fileSize/CHUNK_SIZE) == TOTAL_CHUNKS
        File out = tmp.resolve("big.bin").toFile();
        List<PeerInfo> onePeer = List.of(new PeerInfo("127.0.0.1", 9001, new ArrayList<>()));
        DownloadTask task = new DownloadTask("big.bin", fileSize, out, onePeer, null);

        task.onProgressUpdate = t -> {
            if (t.state == DownloadState.DOWNLOADING) {
                downloadingPercents.add((int) (t.progress * 100));
            }
        };
        task.onComplete = t -> {
            completed.set(true);
            completeProgress.set(t.progress);
            done.countDown();
        };
        task.onError = t -> done.countDown();

        try {
            dm.download(task);
            assertTrue(done.await(30, TimeUnit.SECONDS), "download did not terminate in time");

            assertEquals(DownloadState.COMPLETE, task.state, "download should complete");
            assertTrue(completed.get(), "onComplete must fire even though per-chunk updates are throttled");
            assertEquals(1.0, completeProgress.get(), 0.0, "the terminal callback must report 100%");

            int callbacks = downloadingPercents.size();
            assertTrue(callbacks > 0, "at least one progress update should fire");

            // The throttle contract: every DOWNLOADING callback advanced the whole-percent figure,
            // so no two callbacks share a percent. This is exactly what fails without the throttle.
            Set<Integer> distinct = new HashSet<>(downloadingPercents);
            assertEquals(callbacks, distinct.size(),
                    "every throttled progress callback must report a distinct percent (got duplicates: "
                            + downloadingPercents + ")");

            // And throttling actually reduced the flood: far fewer callbacks than chunks fetched.
            assertTrue(callbacks < TOTAL_CHUNKS,
                    "throttled callbacks (" + callbacks + ") must be fewer than chunks (" + TOTAL_CHUNKS + ")");

            // Percents must be monotonically increasing (single worker => strictly ordered).
            for (int i = 1; i < downloadingPercents.size(); i++) {
                assertTrue(downloadingPercents.get(i) > downloadingPercents.get(i - 1),
                        "percents must strictly increase: " + downloadingPercents);
            }
            assertFalse(distinct.contains(0), "0% is not emitted by the throttle (it starts at 0)");
        } finally {
            dm.shutdown();
        }
    }
}

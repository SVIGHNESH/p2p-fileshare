package com.p2p.core.transfer;

import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.PeerInfo;
import com.p2p.core.transfer.DownloadManager.DownloadListener;
import com.p2p.core.transfer.DownloadManager.DownloadState;
import com.p2p.core.transfer.DownloadManager.DownloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DT.3 guard: download events fan out to EVERY registered {@link DownloadListener}, instead of the
 * single-slot {@code onProgressUpdate}/{@code onComplete}/{@code onError} consumer where a second
 * registration silently clobbered the first.
 *
 * <p>The headline desktop symptom was the collision on the <i>completion</i> path: the Search button's
 * {@code onComplete} (which flips the button to "Done!" and refreshes the shared folder) was overwritten
 * by the Downloads card's assignment, so it never ran. These tests model two observers of one task and
 * assert both are notified — a single-slot regression of {@code addListener} fails exactly this.
 *
 * <p>Uses the in-memory {@link DownloadManager.ChunkFetcher} seam (no sockets, no read-back: a null
 * {@code expectedChecksum} skips whole-file verification) so the download orchestration runs fast.
 */
class DownloadManagerListenerTest {

    /** Enough chunks that progress callbacks fire while staying tiny on disk (sparse positioned writes). */
    private static final int TOTAL_CHUNKS = 10;

    private static DownloadTask newTask(Path tmp, String name) {
        long fileSize = (long) TOTAL_CHUNKS * Protocol.CHUNK_SIZE; // ceil(fileSize/CHUNK_SIZE) == TOTAL_CHUNKS
        File out = tmp.resolve(name).toFile();
        List<PeerInfo> onePeer = List.of(new PeerInfo("127.0.0.1", 9001, new ArrayList<>()));
        return new DownloadTask(name, fileSize, out, onePeer, null);
    }

    private static DownloadManager inMemoryManager() {
        // Bytes are irrelevant (no checksum, no read-back); serve a tiny slice per chunk.
        return new DownloadManager((peer, filename, chunkIndex) -> new byte[]{1, 2, 3, 4});
    }

    /** The core DT.3 discriminator: two listeners on one task BOTH receive progress and completion. */
    @Test
    void bothListenersReceiveProgressAndCompletion(@TempDir Path tmp) throws Exception {
        DownloadManager dm = inMemoryManager();
        DownloadTask task = newTask(tmp, "fan.bin");

        AtomicInteger aProgress = new AtomicInteger();
        AtomicInteger aComplete = new AtomicInteger();
        AtomicInteger bProgress = new AtomicInteger();
        AtomicInteger bComplete = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2); // one per listener's onComplete

        // Listener A models the Search button (registered first).
        task.addListener(new DownloadListener() {
            @Override public void onProgress(DownloadTask t) { aProgress.incrementAndGet(); }
            @Override public void onComplete(DownloadTask t) { aComplete.incrementAndGet(); done.countDown(); }
        });
        // Listener B models the Downloads card (registered second — used to clobber A).
        task.addListener(new DownloadListener() {
            @Override public void onProgress(DownloadTask t) { bProgress.incrementAndGet(); }
            @Override public void onComplete(DownloadTask t) { bComplete.incrementAndGet(); done.countDown(); }
        });

        try {
            dm.download(task);
            assertTrue(done.await(30, TimeUnit.SECONDS), "both listeners' onComplete must fire");
            assertEquals(DownloadState.COMPLETE, task.state, "download should complete");

            assertEquals(1, aComplete.get(), "the first-registered listener must still receive onComplete");
            assertEquals(1, bComplete.get(), "the second-registered listener must also receive onComplete");
            assertTrue(aProgress.get() > 0, "the first-registered listener must receive progress");
            assertTrue(bProgress.get() > 0, "the second-registered listener must receive progress");
        } finally {
            dm.shutdown();
        }
    }

    /** Backward compatibility: the legacy single-slot consumer fields still fire alongside a listener. */
    @Test
    void legacyConsumerFiresAlongsideListener(@TempDir Path tmp) throws Exception {
        DownloadManager dm = inMemoryManager();
        DownloadTask task = newTask(tmp, "legacy.bin");

        AtomicInteger legacyComplete = new AtomicInteger();
        AtomicInteger listenerComplete = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        task.onComplete = t -> legacyComplete.incrementAndGet();
        task.addListener(new DownloadListener() {
            @Override public void onComplete(DownloadTask t) { listenerComplete.incrementAndGet(); done.countDown(); }
        });

        try {
            dm.download(task);
            assertTrue(done.await(30, TimeUnit.SECONDS), "the listener's onComplete must fire");
            assertEquals(DownloadState.COMPLETE, task.state, "download should complete");
            assertEquals(1, legacyComplete.get(), "the legacy onComplete consumer must still fire");
            assertEquals(1, listenerComplete.get(), "the listener must fire alongside the legacy consumer");
        } finally {
            dm.shutdown();
        }
    }

    /** A failure fans out to every listener's onError (no-peers path), and a throwing listener is isolated. */
    @Test
    void errorFansOutToAllListenersAndThrowingListenerIsIsolated(@TempDir Path tmp) throws Exception {
        DownloadManager dm = inMemoryManager();
        // Empty peer list makes executeDownload fail before any fetch with "No peers available".
        long fileSize = (long) TOTAL_CHUNKS * Protocol.CHUNK_SIZE;
        DownloadTask task = new DownloadTask("err.bin", fileSize, tmp.resolve("err.bin").toFile(),
                new ArrayList<>(), null);

        AtomicInteger aError = new AtomicInteger();
        AtomicInteger bError = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);

        // First listener throws; it must not prevent the second from being notified.
        task.addListener(new DownloadListener() {
            @Override public void onError(DownloadTask t) {
                aError.incrementAndGet();
                done.countDown();
                throw new RuntimeException("boom from a misbehaving UI listener");
            }
        });
        task.addListener(new DownloadListener() {
            @Override public void onError(DownloadTask t) { bError.incrementAndGet(); done.countDown(); }
        });

        try {
            dm.download(task);
            assertTrue(done.await(30, TimeUnit.SECONDS), "both listeners' onError must fire");
            assertEquals(DownloadState.FAILED, task.state, "download should fail with no peers");
            assertEquals(1, aError.get(), "the throwing listener still receives onError");
            assertEquals(1, bError.get(), "a throwing listener must not starve the next listener");
        } finally {
            dm.shutdown();
        }
    }

    /** removeListener stops further notifications; the still-registered listener completes normally. */
    @Test
    void removedListenerReceivesNoEvents(@TempDir Path tmp) throws Exception {
        DownloadManager dm = inMemoryManager();
        DownloadTask task = newTask(tmp, "remove.bin");

        AtomicInteger removedCount = new AtomicInteger();
        AtomicInteger keptComplete = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        DownloadListener removed = new DownloadListener() {
            @Override public void onProgress(DownloadTask t) { removedCount.incrementAndGet(); }
            @Override public void onComplete(DownloadTask t) { removedCount.incrementAndGet(); }
            @Override public void onError(DownloadTask t) { removedCount.incrementAndGet(); }
        };
        task.addListener(removed);
        task.removeListener(removed);
        task.addListener(new DownloadListener() {
            @Override public void onComplete(DownloadTask t) { keptComplete.incrementAndGet(); done.countDown(); }
        });

        try {
            dm.download(task);
            assertTrue(done.await(30, TimeUnit.SECONDS), "the kept listener's onComplete must fire");
            assertEquals(DownloadState.COMPLETE, task.state, "download should complete");
            assertEquals(1, keptComplete.get(), "the still-registered listener must be notified");
            assertEquals(0, removedCount.get(), "a removed listener must receive no events");
        } finally {
            dm.shutdown();
        }
    }
}

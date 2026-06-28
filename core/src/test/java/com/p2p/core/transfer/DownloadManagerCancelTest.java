package com.p2p.core.transfer;

import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.PeerInfo;
import com.p2p.core.transfer.DownloadManager.DownloadState;
import com.p2p.core.transfer.DownloadManager.DownloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for TE.6: cancelling a download must actually stop the in-flight chunk workers.
 *
 * <p>Before TE.6, {@code cancel()} cancelled only the per-file coordinator future; the chunk-worker
 * tasks already submitted to the shared pool kept fetching to completion in the background, so a
 * "cancelled" download silently finished. The fix sets a volatile cancellation flag the workers poll
 * AND cancels the tracked worker futures, then routes the coordinator to {@link DownloadState#PAUSED}.
 *
 * <p>The test injects an in-memory {@link DownloadManager.ChunkFetcher} so the orchestration runs with
 * zero sockets. It proves the flag / loop-exit / no-new-fetch logic; it does <b>not</b> exercise the
 * real-socket case where an in-flight TLS read lingers until its read timeout (out of scope for the
 * cancellation signal, see {@link DownloadManager#cancel}).
 */
class DownloadManagerCancelTest {

    /**
     * Core TE.6 guard. A single worker is parked mid-fetch on a gate; we cancel, then release the gate
     * and prove that NO further chunk is fetched and the download does not complete. The discriminator:
     * under the old code the gate-blocked worker (never cancelled) resumes after the release and fetches
     * the remaining chunks, so {@code postCancelFetches > 0} and the file completes; with the fix the
     * worker is interrupted/flagged and never pulls another chunk.
     */
    @Test
    void cancelStopsInFlightWorkers(@TempDir Path tmp) throws Exception {
        int chunkCount = 20;
        byte[] content = makeContent(chunkCount);
        int total = totalChunks(content);

        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger postCancelFetches = new AtomicInteger();
        AtomicBoolean cancelSignalled = new AtomicBoolean(false);
        CountDownLatch reachedGate = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        int gateAt = 3; // park the worker on its 3rd fetch, well before the file is complete

        DownloadManager dm = new DownloadManager((peer, filename, chunkIndex) -> {
            if (cancelSignalled.get()) postCancelFetches.incrementAndGet();
            int n = fetches.incrementAndGet();
            if (n == gateAt) {
                reachedGate.countDown();
                try {
                    // Block until the test releases the gate — OR until cancel() interrupts this worker,
                    // which the fix relies on to abort the in-flight chunk promptly.
                    releaseGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fetch interrupted by cancel");
                }
            }
            return sliceOf(content, chunkIndex);
        });
        try {
            File out = tmp.resolve("file.bin").toFile();
            // Single peer => exactly one chunk worker => fetches are serial and deterministic.
            DownloadTask task = new DownloadTask(out.getName(), content.length, out,
                    List.of(new PeerInfo("10.0.0.2", 2, new ArrayList<>())), null);
            dm.download(task);

            assertTrue(reachedGate.await(10, TimeUnit.SECONDS), "worker never reached the gate");

            cancelSignalled.set(true);
            dm.cancel(out.getName());

            // Release the gate: an UNCANCELLED worker (old behavior) would now resume and fetch the rest.
            releaseGate.countDown();
            // Give any rogue worker ample time to pull more chunks before we assert it didn't.
            Thread.sleep(500);

            assertEquals(0, postCancelFetches.get(),
                    "no chunk may be fetched after cancel(); workers must stop, not finish in the background");
            assertTrue(fetches.get() < total,
                    "cancel must stop the download before all " + total + " chunks are fetched (got "
                            + fetches.get() + ")");

            DownloadState settled = awaitSettled(task);
            assertEquals(DownloadState.PAUSED, settled,
                    "a user-cancelled download must wind down to PAUSED (resumable), not FAILED/COMPLETE");
            assertNotEquals(DownloadState.COMPLETE, settled, "a cancelled download must not silently complete");
        } finally {
            releaseGate.countDown(); // unblock the worker if an assertion failed before the release
            dm.shutdown();
        }
    }

    /** Polls until the task leaves the active (DOWNLOADING/CONNECTING) states or times out. */
    private static DownloadState awaitSettled(DownloadTask task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            DownloadState s = task.state;
            if (s != DownloadState.DOWNLOADING && s != DownloadState.CONNECTING && s != DownloadState.QUEUED) {
                return s;
            }
            Thread.sleep(10);
        }
        return task.state;
    }

    // --- helpers (mirror DownloadManagerFailoverTest) ---

    private static int totalChunks(byte[] content) {
        return (content.length + Protocol.CHUNK_SIZE - 1) / Protocol.CHUNK_SIZE;
    }

    private static byte[] sliceOf(byte[] content, int chunkIndex) {
        long offset = (long) chunkIndex * Protocol.CHUNK_SIZE;
        int len = (int) Math.min(Protocol.CHUNK_SIZE, content.length - offset);
        byte[] slice = new byte[len];
        System.arraycopy(content, (int) offset, slice, 0, len);
        return slice;
    }

    /** {@code chunks} chunks of deterministic bytes; the final chunk is deliberately partial. */
    private static byte[] makeContent(int chunks) {
        int size = (chunks - 1) * Protocol.CHUNK_SIZE + 137;
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + chunks);
        return data;
    }
}

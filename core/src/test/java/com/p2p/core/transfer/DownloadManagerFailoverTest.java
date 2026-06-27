package com.p2p.core.transfer;

import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.PeerInfo;
import com.p2p.core.transfer.DownloadManager.DownloadState;
import com.p2p.core.transfer.DownloadManager.DownloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for T0.3: peer failover and per-chunk retry.
 *
 * The old design pinned chunk {@code i} to {@code peers.get(i % peers.size())} with no retry,
 * so a single down peer failed every chunk assigned to it and the whole download went FAILED
 * even when a healthy peer held the file. The fix pulls chunks from a shared work-queue, requeues
 * a failed chunk onto a DIFFERENT healthy peer, and evicts a peer after repeated failures.
 *
 * Tests inject an in-memory {@link DownloadManager.ChunkFetcher} so the orchestration runs with
 * zero sockets/TLS, and pass a null checksum to isolate the failover mechanism from T0.4's
 * whole-file verification (byte-equality assertions still prove the reassembled bytes are correct).
 */
class DownloadManagerFailoverTest {

    /** Mirrors {@code DownloadManager.PEER_DEAD_THRESHOLD} (private). */
    private static final int DEAD_THRESHOLD = 3;

    private static final String BAD_IP = "10.0.0.1";
    private static final String GOOD_IP = "10.0.0.2";

    /**
     * Core T0.3 guard: with one dead peer among healthy ones, the download still completes via the
     * healthy peer. On the old round-robin-pinned design the dead peer's chunks fail permanently and
     * the download goes FAILED; with failover they are requeued onto the healthy peer.
     */
    @Test
    void deadPeerAmongHealthyOnesStillCompletes(@TempDir Path tmp) throws Exception {
        byte[] content = makeContent(6);
        AtomicInteger toGood = new AtomicInteger();

        DownloadManager dm = new DownloadManager((peer, filename, chunkIndex) -> {
            if (BAD_IP.equals(peer.ip)) throw new IOException("peer down");
            toGood.incrementAndGet();
            return sliceOf(content, chunkIndex);
        });
        try {
            File out = tmp.resolve("file.bin").toFile();
            DownloadTask task = run(dm, out, content.length, List.of(badPeer(), goodPeer()));

            assertEquals(DownloadState.COMPLETE, task.state,
                    "a healthy peer holding the file must let the download finish despite a dead peer");
            assertArrayEquals(content, Files.readAllBytes(out.toPath()),
                    "every chunk, including those that failed over, must be the correct bytes");
            assertTrue(toGood.get() >= 6, "all chunks should ultimately have been served by the healthy peer");
        } finally {
            dm.shutdown();
        }
    }

    /**
     * When every peer is dead the download must fail cleanly and promptly — not hang in an infinite
     * requeue loop. Each chunk is tried at most once per peer, so total attempts are bounded.
     */
    @Test
    void allPeersDeadFailsCleanlyWithoutSpinning(@TempDir Path tmp) throws Exception {
        byte[] content = makeContent(4);
        int chunks = totalChunks(content);
        AtomicInteger attempts = new AtomicInteger();

        List<PeerInfo> peers = List.of(badPeer(), new PeerInfo("10.0.0.3", 3, new ArrayList<>()));
        DownloadManager dm = new DownloadManager((peer, filename, chunkIndex) -> {
            attempts.incrementAndGet();
            throw new IOException("peer down");
        });
        try {
            File out = tmp.resolve("file.bin").toFile();
            DownloadTask task = run(dm, out, content.length, peers);

            assertEquals(DownloadState.FAILED, task.state, "no reachable peer => download must FAIL");
            assertNotNull(task.errorMessage, "failure should carry a message");
            // Bounded: at most one attempt per (chunk, peer). Proves there is no infinite retry loop.
            assertTrue(attempts.get() <= chunks * peers.size(),
                    "attempts (" + attempts.get() + ") must be bounded by chunks*peers (" + chunks * peers.size() + ")");
        } finally {
            dm.shutdown();
        }
    }

    /**
     * Dead-peer eviction: a peer that keeps failing is dropped after {@link #DEAD_THRESHOLD} failures
     * and stops being assigned new chunks. With 2 peers the effective worker count is 2, so attempts to
     * the bad peer are bounded by {@code threshold + (workers - 1)} for the pass-the-check-before-the-
     * counter-trips race. That bound (~4) sits well below the no-eviction baseline (~chunks/2 ≈ 8), so
     * the assertion actually discriminates eviction from plain round-robin.
     */
    @Test
    void repeatedlyFailingPeerIsEvicted(@TempDir Path tmp) throws Exception {
        int chunkCount = 16;
        byte[] content = makeContent(chunkCount);
        AtomicInteger toBad = new AtomicInteger();

        DownloadManager dm = new DownloadManager((peer, filename, chunkIndex) -> {
            if (BAD_IP.equals(peer.ip)) {
                toBad.incrementAndGet();
                throw new IOException("peer down");
            }
            return sliceOf(content, chunkIndex);
        });
        try {
            File out = tmp.resolve("file.bin").toFile();
            DownloadTask task = run(dm, out, content.length, List.of(badPeer(), goodPeer()));

            assertEquals(DownloadState.COMPLETE, task.state, "the healthy peer should carry the download");
            assertArrayEquals(content, Files.readAllBytes(out.toPath()));

            int workers = 2; // min(MAX=8, min(chunks=16, peers=2))
            int bound = DEAD_THRESHOLD + (workers - 1);
            assertTrue(toBad.get() <= bound,
                    "evicted peer should see at most " + bound + " attempts, saw " + toBad.get()
                            + " (no-eviction baseline would be ~" + (chunkCount / 2) + ")");
        } finally {
            dm.shutdown();
        }
    }

    // --- helpers ---

    private DownloadTask run(DownloadManager dm, File out, long size, List<PeerInfo> peers) throws Exception {
        DownloadTask task = new DownloadTask(out.getName(), size, out, peers, null);
        CountDownLatch done = new CountDownLatch(1);
        task.onComplete = t -> done.countDown();
        task.onError = t -> done.countDown();
        dm.download(task);
        assertTrue(done.await(15, TimeUnit.SECONDS), "download did not terminate within 15s");
        return task;
    }

    private static PeerInfo badPeer() { return new PeerInfo(BAD_IP, 1, new ArrayList<>()); }
    private static PeerInfo goodPeer() { return new PeerInfo(GOOD_IP, 2, new ArrayList<>()); }

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

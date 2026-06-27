package com.p2p.core.transfer;

import com.p2p.core.chunking.FileChunker;
import com.p2p.core.chunking.FileReassembler;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for T0.4: completion must verify the reassembled file's whole-file
 * SHA-256 against the advertised checksum, not merely that every chunk slot is filled.
 *
 * Each chunk is only validated against the SERVING peer's own per-chunk checksum, so a
 * peer that serves a mislabeled file passes every per-chunk check yet hands back a
 * self-consistent set of WRONG bytes. The old "all slots present == COMPLETE" logic
 * accepted that silently; the fix detects it, refuses to mark COMPLETE, and drops the
 * corrupt bytes so they are not re-advertised from the shared folder.
 *
 * The test injects an in-memory {@link DownloadManager.ChunkFetcher} so the
 * orchestration is exercised with zero sockets/TLS.
 */
class DownloadManagerVerifyTest {

    private static final int CHUNKS = 3;

    @Test
    void matchingChecksumCompletes(@TempDir Path tmp) throws Exception {
        byte[] content = makeContent(7);
        DownloadManager dm = new DownloadManager(sliceFetcher(content));
        try {
            File out = tmp.resolve("file.bin").toFile();
            DownloadTask task = run(dm, out, content.length, FileChunker.sha256(content));
            assertEquals(DownloadState.COMPLETE, task.state, "a matching checksum should COMPLETE");
            assertArrayEquals(content, Files.readAllBytes(out.toPath()));
        } finally {
            dm.shutdown();
        }
    }

    /**
     * The literal P0 attack: the peer serves a self-consistent file of identical length
     * (every per-chunk checksum its own fetcher would compute is honoured), but it is the
     * WRONG file. Verified against the correct advertised checksum, the download must fail.
     */
    @Test
    void wrongBytesAgainstCorrectChecksumIsRejectedAndCleanedUp(@TempDir Path tmp) throws Exception {
        byte[] correct = makeContent(7);
        byte[] served = makeContent(7);
        served[5] ^= 0xFF; // a different file of identical length

        DownloadManager dm = new DownloadManager(sliceFetcher(served));
        try {
            File out = tmp.resolve("file.bin").toFile();
            DownloadTask task = run(dm, out, correct.length, FileChunker.sha256(correct));

            assertEquals(DownloadState.FAILED, task.state,
                    "a self-consistent but WRONG file must NOT be reported COMPLETE");
            assertNotNull(task.errorMessage, "failure should carry a message");
            assertFalse(out.exists(),
                    "corrupt output must be dropped, not left in the shared folder to be re-advertised");
            assertFalse(metaOf(out).exists(), "resume state must be cleared so a retry re-fetches");
        } finally {
            dm.shutdown();
        }
    }

    @Test
    void blankChecksumSkipsVerification(@TempDir Path tmp) throws Exception {
        byte[] content = makeContent(7);
        DownloadManager dm = new DownloadManager(sliceFetcher(content));
        try {
            File out = tmp.resolve("file.bin").toFile();
            DownloadTask task = run(dm, out, content.length, null);
            assertEquals(DownloadState.COMPLETE, task.state,
                    "no advertised checksum → nothing to verify against → still COMPLETE");
            assertArrayEquals(content, Files.readAllBytes(out.toPath()));
        } finally {
            dm.shutdown();
        }
    }

    /**
     * A prior run wrote every chunk and saved a "complete" .meta, but the on-disk bytes are
     * wrong. The refactor newly routes that resume path through verification, so it must be
     * rejected without fetching a single chunk.
     */
    @Test
    void resumedCompleteButCorruptIsRejected(@TempDir Path tmp) throws Exception {
        byte[] correct = makeContent(7);
        byte[] corrupt = makeContent(7);
        corrupt[0] ^= 0xFF;

        File out = tmp.resolve("file.bin").toFile();
        // Simulate a finished-but-corrupt prior download: all chunks written, state saved.
        FileReassembler fr = new FileReassembler(out, CHUNKS);
        for (int i = 0; i < CHUNKS; i++) fr.writeChunk(i, sliceOf(corrupt, i));
        fr.saveState();
        fr.close();
        assertTrue(metaOf(out).exists());

        DownloadManager dm = new DownloadManager((peer, filename, chunkIndex) -> {
            throw new AssertionError("resume path must not fetch any chunk");
        });
        try {
            DownloadTask task = run(dm, out, correct.length, FileChunker.sha256(correct));
            assertEquals(DownloadState.FAILED, task.state,
                    "a resumed-complete but corrupt file must be rejected, not trusted");
            assertFalse(out.exists(), "corrupt resumed file must be deleted");
        } finally {
            dm.shutdown();
        }
    }

    // --- helpers ---

    private DownloadTask run(DownloadManager dm, File out, long size, String checksum) throws Exception {
        List<PeerInfo> peers = List.of(new PeerInfo("127.0.0.1", 9001, new ArrayList<>()));
        DownloadTask task = new DownloadTask(out.getName(), size, out, peers, checksum);
        CountDownLatch done = new CountDownLatch(1);
        task.onComplete = t -> done.countDown();
        task.onError = t -> done.countDown();
        dm.download(task);
        assertTrue(done.await(10, TimeUnit.SECONDS), "download did not terminate within 10s");
        return task;
    }

    private static DownloadManager.ChunkFetcher sliceFetcher(byte[] content) {
        return (peer, filename, chunkIndex) -> sliceOf(content, chunkIndex);
    }

    private static byte[] sliceOf(byte[] content, int chunkIndex) {
        long offset = (long) chunkIndex * Protocol.CHUNK_SIZE;
        int len = (int) Math.min(Protocol.CHUNK_SIZE, content.length - offset);
        byte[] slice = new byte[len];
        System.arraycopy(content, (int) offset, slice, 0, len);
        return slice;
    }

    private static File metaOf(File out) {
        return new File(out.getParent(), out.getName() + ".meta");
    }

    /** {@code chunks} chunks of deterministic bytes; the final chunk is deliberately partial. */
    private static byte[] makeContent(int seed) {
        int size = (CHUNKS - 1) * Protocol.CHUNK_SIZE + 137;
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + seed);
        return data;
    }
}

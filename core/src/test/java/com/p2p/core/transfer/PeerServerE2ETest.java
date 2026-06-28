package com.p2p.core.transfer;

import com.p2p.core.chunking.FileChunker;
import com.p2p.core.crypto.TLSHelper;
import com.p2p.core.protocol.Protocol.PeerInfo;
import com.p2p.core.transfer.DownloadManager.DownloadState;
import com.p2p.core.transfer.DownloadManager.DownloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-stack, real-socket transfer test: a live {@link PeerServer} serves a real file over
 * TLS and the default (persistent-TLS-connection) {@link DownloadManager} downloads it end to
 * end. This path was dead before the keystore-generate-if-absent fix - {@code TLSHelper.init}
 * threw on a clean checkout, so neither the server socket nor the client handshake could start.
 *
 * <p>One test exercises the entire honest pipeline at once: TLS handshake (generated cert),
 * the length-prefixed request/response framing (T0.1/TE.1), server-side validation (TE.2/TE.3),
 * and the whole-file SHA-256 verification (T0.4). A second proves T0.4 rejects a mislabeled file
 * over real sockets.
 */
class PeerServerE2ETest {

    private static final int FILE_SIZE = 1_200_000; // > 2 * 512KB → spans 3 chunks

    @Test
    void downloadsAndVerifiesAMultiChunkFileOverTls(@TempDir Path shared, @TempDir Path outDir) throws Exception {
        TLSHelper.init(shared.toFile());
        byte[] content = randomBytes(FILE_SIZE, 1);
        File source = writeFile(shared, "movie.bin", content);
        String checksum = FileChunker.sha256OfFile(source);

        PeerServer server = new PeerServer(0, shared::toFile);
        DownloadManager dm = new DownloadManager();
        try {
            server.start();
            File out = outDir.resolve("movie.bin").toFile();
            DownloadTask task = download(dm, server.getBoundPort(), "movie.bin", FILE_SIZE, out, checksum);

            assertEquals(DownloadState.COMPLETE, task.state, "honest multi-chunk download must COMPLETE");
            assertArrayEquals(content, Files.readAllBytes(out.toPath()), "downloaded bytes must match the source");
        } finally {
            dm.shutdown();
            server.stop();
        }
    }

    @Test
    void rejectsAMislabeledFileOverTls(@TempDir Path shared, @TempDir Path outDir) throws Exception {
        TLSHelper.init(shared.toFile());
        byte[] served = randomBytes(FILE_SIZE, 2);
        writeFile(shared, "report.bin", served);
        String wrongChecksum = FileChunker.sha256(randomBytes(FILE_SIZE, 99)); // checksum of a different file

        PeerServer server = new PeerServer(0, shared::toFile);
        DownloadManager dm = new DownloadManager();
        try {
            server.start();
            File out = outDir.resolve("report.bin").toFile();
            DownloadTask task = download(dm, server.getBoundPort(), "report.bin", FILE_SIZE, out, wrongChecksum);

            assertEquals(DownloadState.FAILED, task.state, "a whole-file checksum mismatch must FAIL (T0.4)");
            assertFalse(out.exists(), "the corrupt output must be cleaned up, not left to be re-shared");
        } finally {
            dm.shutdown();
            server.stop();
        }
    }

    // ── T0.5: the downloader pins the peer to its tracker-advertised public key ──

    @Test
    void downloadsWhenPinnedToTheServersAdvertisedKey(@TempDir Path shared, @TempDir Path outDir) throws Exception {
        TLSHelper.init(shared.toFile());
        byte[] content = randomBytes(FILE_SIZE, 3);
        File source = writeFile(shared, "pinned.bin", content);
        String checksum = FileChunker.sha256OfFile(source);

        PeerServer server = new PeerServer(0, shared::toFile);
        DownloadManager dm = new DownloadManager();
        try {
            server.start();
            File out = outDir.resolve("pinned.bin").toFile();
            // The PeerServer uses TLSHelper's own keystore, so its key fingerprint is the local one.
            String keyId = TLSHelper.getLocalFingerprint();
            DownloadTask task = download(dm, server.getBoundPort(), "pinned.bin", FILE_SIZE, out, checksum, keyId);

            assertEquals(DownloadState.COMPLETE, task.state, "a download pinned to the correct key must COMPLETE");
            assertArrayEquals(content, Files.readAllBytes(out.toPath()), "downloaded bytes must match the source");
        } finally {
            dm.shutdown();
            server.stop();
        }
    }

    @Test
    void rejectsAPeerWhoseAdvertisedKeyDoesNotMatch(@TempDir Path shared, @TempDir Path outDir) throws Exception {
        TLSHelper.init(shared.toFile());
        byte[] content = randomBytes(FILE_SIZE, 4);
        File source = writeFile(shared, "mitm.bin", content);
        String checksum = FileChunker.sha256OfFile(source); // honest checksum — only the KEY is wrong

        PeerServer server = new PeerServer(0, shared::toFile);
        DownloadManager dm = new DownloadManager();
        try {
            server.start();
            File out = outDir.resolve("mitm.bin").toFile();
            // The tracker advertised a different key than the peer actually presents (a MITM, or a peer
            // impersonating an identity). Pinning must refuse every chunk so the download cannot complete —
            // without the pin check the bytes are correct and this would COMPLETE, so it is a true discriminator.
            String wrongKeyId = "0".repeat(64); // a syntactically-valid but wrong fingerprint
            long started = System.nanoTime();
            DownloadTask task = download(dm, server.getBoundPort(), "mitm.bin", FILE_SIZE, out, checksum, wrongKeyId);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertEquals(DownloadState.FAILED, task.state, "a peer that fails the key pin must not serve the file");
            assertFalse(out.exists(), "nothing should be left from an unauthenticated peer");
            // Pin failure happens at handshake time, not on a stalled read — so the whole thing fails fast,
            // well under the per-chunk 30s read timeout (the peer is evicted after a few quick rejects).
            assertTrue(elapsedMs < 10_000, "pin rejection must be fast (handshake-time), was " + elapsedMs + "ms");
        } finally {
            dm.shutdown();
            server.stop();
        }
    }

    private static DownloadTask download(DownloadManager dm, int port, String name, long size,
                                         File out, String checksum) throws Exception {
        return download(dm, port, name, size, out, checksum, null);
    }

    private static DownloadTask download(DownloadManager dm, int port, String name, long size,
                                         File out, String checksum, String keyId) throws Exception {
        List<PeerInfo> peers = List.of(new PeerInfo("127.0.0.1", port, new java.util.ArrayList<>(), keyId));
        DownloadTask task = new DownloadTask(name, size, out, peers, checksum);
        CountDownLatch done = new CountDownLatch(1);
        task.onComplete = t -> done.countDown();
        task.onError = t -> done.countDown();
        dm.download(task);
        assertTrue(done.await(20, TimeUnit.SECONDS), "download did not terminate within 20s");
        return task;
    }

    private static File writeFile(Path dir, String name, byte[] content) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.write(f.toPath(), content);
        return f;
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }
}

package com.p2p.core.transfer;

import com.p2p.core.chunking.FileChunker;
import com.p2p.core.crypto.TLSHelper;
import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.ChunkRequest;
import com.p2p.core.protocol.Protocol.ChunkResponse;
import com.p2p.core.protocol.Protocol.Message;
import com.p2p.core.protocol.Protocol.MessageType;
import com.p2p.core.protocol.Protocol.PeerInfo;
import com.p2p.core.transfer.DownloadManager.DownloadState;
import com.p2p.core.transfer.DownloadManager.DownloadTask;
import com.p2p.core.transfer.DownloadManager.PeerConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guards for T0.1 (stop doing a TLS handshake per chunk). These are the only tests
 * that fail if connection reuse / persistence regresses — the existing E2E download test stays
 * green whether or not the socket is reused, because a per-chunk-socket implementation downloads
 * the file just as correctly.
 *
 * <ul>
 *   <li><b>Client contract:</b> a counting {@link DownloadManager.ChunkSource} proves a worker
 *       opens ONE connection per peer and reuses it for every chunk it pulls — not one per chunk.</li>
 *   <li><b>Server contract:</b> a raw TLS socket sends two framed requests back to back and gets
 *       two framed responses, proving {@link PeerServer} keeps the connection open across requests
 *       instead of one-request-then-close.</li>
 * </ul>
 */
class PeerConnectionReuseTest {

    private static final int FILE_SIZE = 4 * Protocol.CHUNK_SIZE + 137; // spans 5 chunks

    @Test
    void aWorkerOpensOneConnectionPerPeerAndReusesItForEveryChunk(@TempDir Path tmp) throws Exception {
        byte[] content = randomBytes(FILE_SIZE, 7);
        int totalChunks = FileChunker.getTotalChunks(content.length);

        AtomicInteger connects = new AtomicInteger();
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();

        DownloadManager.ChunkSource source = peer -> {
            connects.incrementAndGet();
            return new PeerConnection() {
                @Override public byte[] fetchChunk(String filename, int chunkIndex) {
                    fetches.incrementAndGet();
                    return sliceOf(content, chunkIndex);
                }
                @Override public void close() { closes.incrementAndGet(); }
            };
        };

        DownloadManager dm = new DownloadManager(source);
        try {
            File out = tmp.resolve("file.bin").toFile();
            // One peer → exactly one chunk-worker → exactly one connection reused for all chunks.
            List<PeerInfo> peers = List.of(new PeerInfo("127.0.0.1", 9001, new ArrayList<>()));
            DownloadTask task = new DownloadTask(out.getName(), content.length, out, peers, null);
            CountDownLatch done = new CountDownLatch(1);
            task.onComplete = t -> done.countDown();
            task.onError = t -> done.countDown();
            dm.download(task);
            assertTrue(done.await(10, TimeUnit.SECONDS), "download did not terminate within 10s");

            assertEquals(DownloadState.COMPLETE, task.state);
            assertEquals(totalChunks, fetches.get(), "every chunk must be fetched exactly once");
            assertEquals(1, connects.get(),
                    "the worker must open ONE connection for the peer (not one per chunk — that is the T0.1 bug)");
            assertEquals(1, closes.get(), "the connection must be closed when the worker finishes draining");
        } finally {
            dm.shutdown();
        }
    }

    @Test
    void serverServesManyRequestsOverOnePersistentConnection(@TempDir Path shared) throws Exception {
        TLSHelper.init(shared.toFile());
        byte[] content = randomBytes(FILE_SIZE, 11);
        File source = writeFile(shared, "movie.bin", content);

        PeerServer server = new PeerServer(0, shared::toFile);
        try {
            server.start();
            try (Socket socket = TLSHelper.createClientSocket("127.0.0.1", server.getBoundPort());
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {

                // Two requests, one socket. A one-request-then-close server would FIN after the first
                // response and the second readMessage would see EOF (null) — failing this test.
                byte[] chunk0 = requestChunk(out, in, "movie.bin", 0);
                byte[] chunk1 = requestChunk(out, in, "movie.bin", 1);

                assertArrayEquals(FileChunker.readChunk(source, 0), chunk0, "chunk 0 over the shared connection");
                assertArrayEquals(FileChunker.readChunk(source, 1), chunk1, "chunk 1 over the SAME connection");
            }
        } finally {
            server.stop();
        }
    }

    /** Sends one framed CHUNK_REQUEST and reads the framed response + body off the same socket. */
    private static byte[] requestChunk(DataOutputStream out, DataInputStream in, String name, int idx)
            throws Exception {
        Frames.writeMessage(out, new Message(MessageType.CHUNK_REQUEST, new ChunkRequest(name, idx)));
        out.flush();

        Message resp = Frames.readMessage(in);
        assertNotNull(resp, "server closed the connection instead of serving the request");
        ChunkResponse cr = resp.getPayload(ChunkResponse.class);
        assertTrue(cr.success, "server should serve a valid chunk: " + cr.error);
        byte[] body = in.readNBytes(cr.size);
        assertEquals(cr.size, body.length, "body must be fully framed");
        return body;
    }

    private static byte[] sliceOf(byte[] content, int chunkIndex) {
        long offset = (long) chunkIndex * Protocol.CHUNK_SIZE;
        int len = (int) Math.min(Protocol.CHUNK_SIZE, content.length - offset);
        byte[] slice = new byte[len];
        System.arraycopy(content, (int) offset, slice, 0, len);
        return slice;
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

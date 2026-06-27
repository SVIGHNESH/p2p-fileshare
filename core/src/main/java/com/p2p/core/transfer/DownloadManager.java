package com.p2p.core.transfer;

import com.p2p.core.chunking.FileChunker;
import com.p2p.core.chunking.FileReassembler;
import com.p2p.core.crypto.TLSHelper;
import com.p2p.core.protocol.Protocol.*;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Coordinates downloading a file from multiple peers simultaneously.
 * Each chunk is assigned to a peer and downloaded in parallel threads.
 * Supports resuming interrupted downloads.
 */
public class DownloadManager {

    public enum DownloadState { QUEUED, CONNECTING, DOWNLOADING, VERIFYING, COMPLETE, FAILED, PAUSED }

    /**
     * Fetches a single verified chunk from a peer. The default implementation opens
     * a TLS connection and validates the chunk checksum; tests inject an in-memory
     * fetcher so the download orchestration can be exercised without sockets.
     */
    @FunctionalInterface
    public interface ChunkFetcher {
        byte[] fetch(PeerInfo peer, String filename, int chunkIndex) throws IOException;
    }

    public static class DownloadTask {
        public final String filename;
        public final long fileSize;
        public final File outputFile;
        public final List<PeerInfo> peers;
        /**
         * Advertised whole-file SHA-256 to verify the reassembled file against (T0.4).
         * Null/blank means the source advertised no checksum, so there is nothing to
         * verify against and the integrity check is skipped.
         */
        public final String expectedChecksum;
        public volatile DownloadState state = DownloadState.QUEUED;
        public volatile String errorMessage;
        public volatile double progress;
        public Consumer<DownloadTask> onProgressUpdate;
        public Consumer<DownloadTask> onComplete;
        public Consumer<DownloadTask> onError;

        public DownloadTask(String filename, long fileSize, File outputFile, List<PeerInfo> peers) {
            this(filename, fileSize, outputFile, peers, null);
        }

        public DownloadTask(String filename, long fileSize, File outputFile,
                            List<PeerInfo> peers, String expectedChecksum) {
            this.filename = filename;
            this.fileSize = fileSize;
            this.outputFile = outputFile;
            this.peers = peers;
            this.expectedChecksum = expectedChecksum;
        }

        public String getProgressText() {
            return String.format("%.0f%%  •  %.1f / %.1f MB",
                    progress * 100,
                    (progress * fileSize) / 1_000_000.0,
                    fileSize / 1_000_000.0);
        }
    }

    private final ChunkFetcher chunkFetcher;
    // T0.2: the per-file coordinator blocks on Future.get() while chunk workers do the
    // actual fetching. They MUST live in separate pools — sharing one pool means that
    // once concurrent downloads reach the pool size, every thread is a blocked
    // coordinator and no thread is left to fetch chunks (classic pool-starvation
    // deadlock). Coordinators are lightweight blockers, so a cached pool scales with
    // active downloads; chunk fetching is bounded I/O, so a fixed pool caps parallelism.
    private final ExecutorService coordinatorPool = Executors.newCachedThreadPool(daemonFactory("p2p-dl-coord"));
    private final ExecutorService chunkPool = Executors.newFixedThreadPool(8, daemonFactory("p2p-dl-chunk"));
    private final ConcurrentHashMap<String, Future<?>> activeTasks = new ConcurrentHashMap<>();

    public DownloadManager() {
        this(DownloadManager::tlsDownloadChunk);
    }

    /** Test/seam constructor: inject how a single chunk is fetched. */
    public DownloadManager(ChunkFetcher chunkFetcher) {
        this.chunkFetcher = chunkFetcher;
    }

    public void download(DownloadTask task) {
        Future<?> future = coordinatorPool.submit(() -> executeDownload(task));
        activeTasks.put(task.filename, future);
    }

    public void cancel(String filename) {
        Future<?> f = activeTasks.remove(filename);
        if (f != null) f.cancel(true);
    }

    private void executeDownload(DownloadTask task) {
        try {
            int totalChunks = FileChunker.getTotalChunks(task.fileSize);
            task.state = DownloadState.CONNECTING;
            notifyProgress(task);

            // Resume: load prior state if .meta exists
            FileReassembler reassembler = FileReassembler.loadState(task.outputFile);
            if (reassembler == null) {
                reassembler = new FileReassembler(task.outputFile, totalChunks);
                task.outputFile.getParentFile().mkdirs();
            }
            final FileReassembler fr = reassembler;

            // Fetch any still-missing chunks. A resumed download whose .meta already
            // reports every slot filled skips straight to verification (which the old
            // early-return path never did, so a resumed-but-corrupt file slipped through).
            int[] missing = fr.missingChunks();
            if (missing.length > 0) {
                task.state = DownloadState.DOWNLOADING;

                // Distribute chunks across available peers in round-robin
                List<Future<?>> chunkFutures = new CopyOnWriteArrayList<>();
                for (int i = 0; i < missing.length; i++) {
                    final int chunkIndex = missing[i];
                    final PeerInfo peer = task.peers.get(i % task.peers.size());

                    chunkFutures.add(chunkPool.submit(() -> {
                        try {
                            byte[] data = chunkFetcher.fetch(peer, task.filename, chunkIndex);
                            if (data != null) {
                                fr.writeChunk(chunkIndex, data);
                                task.progress = fr.getProgress();
                                fr.saveState();
                                notifyProgress(task);
                            }
                        } catch (Exception e) {
                            System.err.println("[DL] Chunk " + chunkIndex + " failed: " + e.getMessage());
                        }
                    }));
                }

                // Wait for all chunks
                for (Future<?> f : chunkFutures) {
                    try { f.get(); } catch (Exception ignored) {}
                }
            }

            if (!fr.isComplete()) {
                fail(task, "Some chunks could not be downloaded. Try again to resume.");
                return;
            }

            // T0.4: every chunk slot is filled, but each chunk was only validated against
            // the serving peer's OWN per-chunk checksum — a peer serving a mislabeled file
            // passes all of those yet yields a self-consistent set of WRONG bytes. Verify
            // the whole reassembled file against the advertised SHA-256 before declaring it
            // complete, so a wrong-but-consistent file is never accepted.
            task.state = DownloadState.VERIFYING;
            notifyProgress(task);

            String expected = task.expectedChecksum;
            if (expected != null && !expected.isBlank()) {
                String actual;
                try {
                    actual = FileChunker.sha256OfFile(task.outputFile);
                } catch (IOException e) {
                    // Could not read the assembled file to hash it — possibly transient.
                    // Keep the .meta so a retry resumes rather than re-downloading everything.
                    fail(task, "Could not verify downloaded file: " + e.getMessage());
                    return;
                }
                if (!expected.equalsIgnoreCase(actual)) {
                    // Definitely the wrong bytes. Drop both the file and its resume state so the
                    // corruption is neither presented as complete nor re-hashed and re-advertised
                    // to the tracker from the shared folder. A retry re-fetches from scratch;
                    // automatic failover to a different peer is T0.3.
                    fr.deleteMetaFile();
                    task.outputFile.delete();
                    fail(task, "Downloaded file failed integrity check (checksum mismatch).");
                    return;
                }
            }

            fr.deleteMetaFile();
            task.state = DownloadState.COMPLETE;
            task.progress = 1.0;
            notifyProgress(task);
            if (task.onComplete != null) task.onComplete.accept(task);
        } catch (Exception e) {
            fail(task, e.getMessage());
        } finally {
            activeTasks.remove(task.filename);
        }
    }

    private void fail(DownloadTask task, String message) {
        task.state = DownloadState.FAILED;
        task.errorMessage = message;
        if (task.onError != null) task.onError.accept(task);
    }

    /** Default fetcher: open a TLS connection, request one chunk, and verify its checksum. */
    private static byte[] tlsDownloadChunk(PeerInfo peer, String filename, int chunkIndex) throws IOException {
        try (Socket socket = TLSHelper.createClientSocket(peer.ip, peer.port);
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            socket.setSoTimeout(30000);
            Message req = new Message(MessageType.CHUNK_REQUEST, new ChunkRequest(filename, chunkIndex));
            out.print(req.toJson());
            out.flush();

            // Read JSON header line
            StringBuilder headerLine = new StringBuilder();
            int b;
            while ((b = in.read()) != '\n' && b != -1) headerLine.append((char) b);
            Message resp = Message.fromJson(headerLine.toString());
            ChunkResponse cr = resp.getPayload(ChunkResponse.class);

            if (!cr.success) throw new IOException("Peer error: " + cr.error);

            byte[] data = in.readNBytes(cr.size);
            if (!FileChunker.verifyChunk(data, cr.checksum)) {
                throw new IOException("Checksum mismatch for chunk " + chunkIndex);
            }
            return data;
        }
    }

    private void notifyProgress(DownloadTask task) {
        if (task.onProgressUpdate != null) task.onProgressUpdate.accept(task);
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public void shutdown() {
        coordinatorPool.shutdownNow();
        chunkPool.shutdownNow();
    }
}

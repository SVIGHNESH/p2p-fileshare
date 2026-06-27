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
        public volatile DownloadState state = DownloadState.QUEUED;
        public volatile String errorMessage;
        public volatile double progress;
        public Consumer<DownloadTask> onProgressUpdate;
        public Consumer<DownloadTask> onComplete;
        public Consumer<DownloadTask> onError;

        public DownloadTask(String filename, long fileSize, File outputFile, List<PeerInfo> peers) {
            this.filename = filename;
            this.fileSize = fileSize;
            this.outputFile = outputFile;
            this.peers = peers;
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

            int[] missing = reassembler.missingChunks();
            if (missing.length == 0) {
                task.state = DownloadState.COMPLETE;
                reassembler.deleteMetaFile();
                if (task.onComplete != null) task.onComplete.accept(task);
                return;
            }

            task.state = DownloadState.DOWNLOADING;

            // Distribute chunks across available peers in round-robin
            final FileReassembler fr = reassembler;
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

            task.state = DownloadState.VERIFYING;
            notifyProgress(task);

            if (fr.isComplete()) {
                fr.deleteMetaFile();
                task.state = DownloadState.COMPLETE;
                task.progress = 1.0;
                notifyProgress(task);
                if (task.onComplete != null) task.onComplete.accept(task);
            } else {
                task.state = DownloadState.FAILED;
                task.errorMessage = "Some chunks could not be downloaded. Try again to resume.";
                if (task.onError != null) task.onError.accept(task);
            }
        } catch (Exception e) {
            task.state = DownloadState.FAILED;
            task.errorMessage = e.getMessage();
            if (task.onError != null) task.onError.accept(task);
        } finally {
            activeTasks.remove(task.filename);
        }
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

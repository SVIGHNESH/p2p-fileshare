package com.p2p.core.transfer;

import com.p2p.core.chunking.FileChunker;
import com.p2p.core.chunking.FileReassembler;
import com.p2p.core.crypto.TLSHelper;
import com.p2p.core.protocol.Protocol.*;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Coordinates downloading a file from multiple peers simultaneously.
 * Missing chunks are pulled from a shared work-queue by parallel workers; a chunk
 * whose fetch fails is requeued onto a different healthy peer and a peer that fails
 * repeatedly is evicted, so one down peer no longer fails the whole download (T0.3).
 * Supports resuming interrupted downloads.
 */
public class DownloadManager {

    /**
     * A peer that fails this many chunk fetches is considered dead and stops being
     * assigned new chunks (T0.3). Per-chunk failover already prevents retrying the
     * same peer for the same chunk; this cross-chunk counter stops a flaky/down peer
     * from being tried for every remaining chunk.
     */
    private static final int PEER_DEAD_THRESHOLD = 3;

    /** Upper bound on concurrent chunk workers per download; the effective count is also capped by peer count. */
    private static final int MAX_CHUNK_WORKERS_PER_DOWNLOAD = 8;

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

                if (task.peers == null || task.peers.isEmpty()) {
                    fail(task, "No peers available to download from.");
                    return;
                }

                fetchMissingChunks(task, fr, missing);
            }

            if (!fr.isComplete()) {
                fail(task, "Some chunks could not be downloaded from any available peer. Try again to resume.");
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

    /**
     * Fetches every still-missing chunk using a shared work-queue with peer failover (T0.3).
     *
     * <p>Rather than statically pinning chunk {@code i} to peer {@code i % peers}, any worker
     * pulls any pending chunk from a shared queue. A chunk whose fetch fails is requeued so a
     * DIFFERENT peer can serve it (the failing peer is recorded on the chunk and never retried
     * for it), and a peer that crosses {@link #PEER_DEAD_THRESHOLD} failures is evicted so dead
     * peers stop getting work. A single down peer therefore no longer fails the whole download
     * when healthy peers hold the file.
     *
     * <p>Termination is bounded: a chunk is requeued at most once per peer (each requeue adds
     * one entry to its tried-peer set), after which {@code pickPeer} returns {@code null} and the
     * chunk is dropped, leaving {@code fr.isComplete()} false so the caller fails the download.
     */
    private void fetchMissingChunks(DownloadTask task, FileReassembler fr, int[] missing) {
        Queue<ChunkWork> queue = new ConcurrentLinkedQueue<>();
        for (int idx : missing) queue.add(new ChunkWork(idx));

        Set<String> deadPeers = ConcurrentHashMap.newKeySet();
        ConcurrentHashMap<String, AtomicInteger> peerFailures = new ConcurrentHashMap<>();
        AtomicInteger peerCursor = new AtomicInteger();

        // Cap parallelism by peer count: pre-pipelining, one in-flight request per peer is the
        // right shape, and it keeps any one download from monopolizing the shared chunk pool.
        int workerCount = Math.min(MAX_CHUNK_WORKERS_PER_DOWNLOAD,
                Math.min(missing.length, Math.max(1, task.peers.size())));

        List<Future<?>> workers = new ArrayList<>(workerCount);
        for (int w = 0; w < workerCount; w++) {
            workers.add(chunkPool.submit(() ->
                    drainChunkQueue(task, fr, queue, deadPeers, peerFailures, peerCursor)));
        }
        for (Future<?> f : workers) {
            try { f.get(); } catch (Exception ignored) {}
        }
    }

    /** One chunk-fetch worker: drains the shared queue, failing over and requeuing as needed. */
    private void drainChunkQueue(DownloadTask task, FileReassembler fr, Queue<ChunkWork> queue,
                                 Set<String> deadPeers, ConcurrentHashMap<String, AtomicInteger> peerFailures,
                                 AtomicInteger peerCursor) {
        ChunkWork work;
        while ((work = queue.poll()) != null) {
            PeerInfo peer = pickPeer(task.peers, work.triedPeers, deadPeers, peerCursor);
            if (peer == null) {
                // No healthy, untried peer left for this chunk — it cannot be recovered. Drop it;
                // the unfilled slot makes fr.isComplete() false so the download is failed cleanly.
                continue;
            }
            String key = peerKey(peer);
            try {
                byte[] data = chunkFetcher.fetch(peer, task.filename, work.index);
                if (data == null) throw new IOException("peer returned no data");
                fr.writeChunk(work.index, data);
                task.progress = fr.getProgress();
                fr.saveState();
                notifyProgress(task);
            } catch (Exception e) {
                // Never retry the same peer for this chunk; bump its failure counter, evict it once
                // it crosses the dead threshold, and requeue the chunk so a different peer serves it.
                work.triedPeers.add(key);
                int fails = peerFailures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                if (fails >= PEER_DEAD_THRESHOLD) deadPeers.add(key);
                System.err.println("[DL] Chunk " + work.index + " from " + key + " failed: " + e.getMessage());
                queue.add(work);
            }
        }
    }

    /**
     * Round-robin selects a peer that is neither dead nor already tried for this chunk, or
     * {@code null} when none remain. The cursor is read ONCE per call to pick a starting offset
     * (which spreads load across peers and workers); the scan then iterates locally over
     * {@code start + k}. Incrementing the shared cursor inside the loop would be a bug: a
     * concurrent increment from another worker could shift this call's two reads onto the same
     * peer, so a healthy peer is skipped and the chunk is wrongly dropped.
     */
    private static PeerInfo pickPeer(List<PeerInfo> peers, Set<String> tried,
                                     Set<String> dead, AtomicInteger cursor) {
        int n = peers.size();
        int start = cursor.getAndIncrement();
        for (int k = 0; k < n; k++) {
            PeerInfo p = peers.get(Math.floorMod(start + k, n));
            String key = peerKey(p);
            if (!dead.contains(key) && !tried.contains(key)) return p;
        }
        return null;
    }

    private static String peerKey(PeerInfo p) {
        return p.ip + ":" + p.port;
    }

    /** A pending chunk plus the set of peers already tried (and failed) for it. */
    private static final class ChunkWork {
        final int index;
        final Set<String> triedPeers = ConcurrentHashMap.newKeySet();
        ChunkWork(int index) { this.index = index; }
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

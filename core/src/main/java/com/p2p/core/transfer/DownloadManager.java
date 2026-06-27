package com.p2p.core.transfer;

import com.p2p.core.chunking.FileChunker;
import com.p2p.core.chunking.FileReassembler;
import com.p2p.core.crypto.TLSHelper;
import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.*;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * TE.4: how often the resume {@code .meta} is persisted while downloading. Persisting after every
     * single chunk meant one atomic temp-file-write+rename per chunk (thousands for a large file); the
     * resume contract only needs to bound how much progress a crash can lose. We snapshot every Nth
     * received chunk and force one final save after the workers join, so a crash re-fetches at most
     * N-1 chunks — never corruption, since the write itself is atomic.
     */
    private static final int SAVE_STATE_EVERY_N_CHUNKS = 32;

    public enum DownloadState { QUEUED, CONNECTING, DOWNLOADING, VERIFYING, COMPLETE, FAILED, PAUSED }

    /**
     * Opens a (persistent) connection to a peer for fetching chunks (T0.1). The default
     * implementation does a single TLS handshake and reuses the socket for every chunk a worker
     * pulls for that peer; tests inject an in-memory source so the download orchestration can be
     * exercised without sockets.
     */
    @FunctionalInterface
    public interface ChunkSource {
        PeerConnection connect(PeerInfo peer) throws IOException;
    }

    /**
     * A live connection to one peer over which many chunk requests are pipelined back to back,
     * amortizing the TLS handshake across the whole download (T0.1). A connection is owned by a
     * single chunk-worker thread (thread-confined), so {@link #fetchChunk} needs no internal locking.
     */
    public interface PeerConnection extends Closeable {
        byte[] fetchChunk(String filename, int chunkIndex) throws IOException;
    }

    /**
     * Legacy stateless seam: fetches one verified chunk per call. Retained so existing socket-free
     * tests (and any caller passing a per-chunk fetcher) keep working; it is adapted to a
     * {@link ChunkSource} whose connection simply delegates each chunk to {@link #fetch}.
     */
    @FunctionalInterface
    public interface ChunkFetcher {
        byte[] fetch(PeerInfo peer, String filename, int chunkIndex) throws IOException;
    }

    /**
     * Multi-listener callback sink (DT.3). The legacy single-{@link Consumer} fields on
     * {@link DownloadTask} ({@code onProgressUpdate}/{@code onComplete}/{@code onError}) only hold ONE
     * observer each, so when two UI views both wanted updates for the same task the second assignment
     * silently clobbered the first — on desktop the Downloads card overwrote the Search button's
     * callbacks, stranding the button on "Starting..." and skipping its post-complete shared-folder
     * refresh. Register any number of listeners via {@link DownloadTask#addListener} and every one is
     * notified. <b>All methods are invoked off the UI thread</b> (on a download-pool thread), so UI
     * layers must marshal back (e.g. {@code Platform.runLater} / {@code runOnUiThread}). Override only
     * the events you care about; a listener that throws is isolated and never affects the others or the
     * download's own state machine.
     */
    public interface DownloadListener {
        default void onProgress(DownloadTask task) {}
        default void onComplete(DownloadTask task) {}
        default void onError(DownloadTask task) {}
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
        /**
         * Progress callback. <b>Invoked off the UI thread</b> (on a download-pool thread), so UI layers
         * MUST marshal back to their UI thread (e.g. {@code Platform.runLater} / {@code runOnUiThread}).
         * TE.5: throttled to at most one call per advancing whole percentage point during {@code DOWNLOADING}
         * — a fast multi-chunk download no longer fires one callback per 512 KB chunk. State transitions
         * (CONNECTING, VERIFYING) and the terminal 100% update always fire regardless of the throttle.
         */
        public Consumer<DownloadTask> onProgressUpdate;
        /** Completion callback; like {@link #onProgressUpdate}, <b>invoked off the UI thread</b> — marshal it. */
        public Consumer<DownloadTask> onComplete;
        /** Failure callback; like {@link #onProgressUpdate}, <b>invoked off the UI thread</b> — marshal it. */
        public Consumer<DownloadTask> onError;

        /**
         * Fan-out listener registry (DT.3). Unlike the single-slot {@code onX} consumers above, EVERY
         * registered listener is notified, so multiple views can observe the same task without
         * clobbering each other. Copy-on-write so the download thread can iterate it while a UI thread
         * registers or removes a listener (and so a listener can {@link #removeListener} itself mid-dispatch).
         */
        final List<DownloadListener> listeners = new CopyOnWriteArrayList<>();

        /** Registers a listener for this task's progress/completion/error events. Returns {@code this} for chaining. */
        public DownloadTask addListener(DownloadListener l) {
            if (l != null) listeners.add(l);
            return this;
        }

        /** Removes a previously-registered listener; safe to call from within a listener callback. */
        public void removeListener(DownloadListener l) {
            listeners.remove(l);
        }

        /**
         * TE.6 cancellation flag. Set by {@link DownloadManager#cancel} and polled by every chunk worker
         * (at the top of its drain loop) and by the coordinator after the workers join, so a cancelled
         * download stops pulling new chunks promptly and winds down to {@link DownloadState#PAUSED}
         * (resume state is preserved) rather than running to completion in the background.
         */
        volatile boolean cancelled = false;
        /** TE.6: the per-file coordinator future, tracked so {@link DownloadManager#cancel} can cancel it. */
        volatile Future<?> coordinatorFuture;
        /**
         * TE.6: futures of the currently in-flight chunk-worker tasks. Tracked so {@link DownloadManager#cancel}
         * can also cancel THEM — before TE.6 cancel() only cancelled the coordinator, leaving the already-
         * submitted workers fetching to completion. Copy-on-write so the coordinator can populate/clear it
         * while a cancelling thread iterates it.
         */
        final List<Future<?>> chunkFutures = new CopyOnWriteArrayList<>();

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

    private final ChunkSource chunkSource;
    // T0.2: the per-file coordinator blocks on Future.get() while chunk workers do the
    // actual fetching. They MUST live in separate pools — sharing one pool means that
    // once concurrent downloads reach the pool size, every thread is a blocked
    // coordinator and no thread is left to fetch chunks (classic pool-starvation
    // deadlock). Coordinators are lightweight blockers, so a cached pool scales with
    // active downloads; chunk fetching is bounded I/O, so a fixed pool caps parallelism.
    private final ExecutorService coordinatorPool = Executors.newCachedThreadPool(daemonFactory("p2p-dl-coord"));
    private final ExecutorService chunkPool = Executors.newFixedThreadPool(8, daemonFactory("p2p-dl-chunk"));
    private final ConcurrentHashMap<String, DownloadTask> activeTasks = new ConcurrentHashMap<>();

    public DownloadManager() {
        this(TlsPeerConnection::open);
    }

    /** Seam constructor: inject how a peer connection is opened (default = persistent TLS). */
    public DownloadManager(ChunkSource chunkSource) {
        this.chunkSource = chunkSource;
    }

    /**
     * Backward-compatible seam: inject a stateless per-chunk fetcher. Adapted to a {@link ChunkSource}
     * whose connection opens nothing and delegates each chunk to {@code fetcher.fetch}, preserving the
     * exact one-call-per-chunk semantics the socket-free orchestration tests rely on.
     */
    public DownloadManager(ChunkFetcher fetcher) {
        this(peer -> new PeerConnection() {
            @Override public byte[] fetchChunk(String filename, int chunkIndex) throws IOException {
                return fetcher.fetch(peer, filename, chunkIndex);
            }
            @Override public void close() {}
        });
    }

    public void download(DownloadTask task) {
        // Defensive reset so a reused/resumed task object starts clean (TE.6).
        task.cancelled = false;
        task.chunkFutures.clear();
        activeTasks.put(task.filename, task);
        Future<?> future = coordinatorPool.submit(() -> executeDownload(task));
        task.coordinatorFuture = future;
    }

    /**
     * Cancels an in-flight download (TE.6). Before this fix {@code cancel} only cancelled the per-file
     * coordinator future, so the chunk-worker tasks already submitted to {@link #chunkPool} kept fetching
     * to completion in the background — a "cancelled" download actually finished. Now we:
     * <ol>
     *   <li>set {@link DownloadTask#cancelled} (volatile) <b>first</b>, so a worker between chunks sees it
     *       at its next queue poll and stops without pulling more work;</li>
     *   <li>cancel every tracked chunk-worker future <i>and</i> the coordinator, interrupting any worker
     *       parked in an interruptible blocking wait so it aborts the current chunk promptly.</li>
     * </ol>
     * Resume state ({@code .meta}) is preserved, so the partial download can be resumed; the coordinator
     * routes a cancelled download to {@link DownloadState#PAUSED} rather than FAILED.
     *
     * <p><b>Scope:</b> {@code cancel} cannot abort an in-flight TLS socket read — interrupting a worker
     * blocked in {@code SocketInputStream.read} does not unblock it. Such a worker lingers until its
     * per-request read timeout ({@code TlsPeerConnection.READ_TIMEOUT_MS}, 30s) elapses, then sees the
     * flag and stops; it never starts a new chunk. We deliberately do <i>not</i> close connections from
     * here: the per-worker connection map is thread-confined inside {@link #drainChunkQueue}, and that
     * confinement is what lets T0.1's reuse run lock-free.
     */
    public void cancel(String filename) {
        DownloadTask task = activeTasks.remove(filename);
        if (task == null) return;
        task.cancelled = true;
        for (Future<?> f : task.chunkFutures) f.cancel(true);
        Future<?> coord = task.coordinatorFuture;
        if (coord != null) coord.cancel(true);
    }

    private void executeDownload(DownloadTask task) {
        FileReassembler fr = null;
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
            fr = reassembler;

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

            // TE.6: a cancel() during the fetch loop stops the workers; wind down to PAUSED (resume state
            // was already force-saved in fetchMissingChunks) instead of falling through to the
            // isComplete()==false path, which would mark the user-cancelled download FAILED with a scary
            // "could not be downloaded" message. We intentionally do NOT close fr here — an fr.close() that
            // threw would propagate to the catch below and flip the state to FAILED, defeating the point;
            // the finally block's closeQuietly(fr) releases the channel.
            if (task.cancelled) {
                task.state = DownloadState.PAUSED;
                return;
            }

            // TE.4: release the write channel now — this flushes all chunk writes to disk so the
            // whole-file hash below reads the complete file, and frees the handle so the
            // mismatch path's outputFile.delete() succeeds (notably on Windows).
            fr.close();

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
            notifyComplete(task);
        } catch (Exception e) {
            fail(task, e.getMessage());
        } finally {
            closeQuietly(fr); // idempotent; covers the exception path where close() above was skipped
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
        AtomicInteger writeCounter = new AtomicInteger();
        // TE.5: last whole percentage point for which a progress callback fired, shared across all
        // workers of this download so the throttle is global to the download, not per worker.
        AtomicInteger lastNotifiedPercent = new AtomicInteger(0);

        // Cap parallelism by peer count: pre-pipelining, one in-flight request per peer is the
        // right shape, and it keeps any one download from monopolizing the shared chunk pool.
        int workerCount = Math.min(MAX_CHUNK_WORKERS_PER_DOWNLOAD,
                Math.min(missing.length, Math.max(1, task.peers.size())));

        List<Future<?>> workers = new ArrayList<>(workerCount);
        for (int w = 0; w < workerCount; w++) {
            Future<?> wf = chunkPool.submit(() ->
                    drainChunkQueue(task, fr, queue, deadPeers, peerFailures, peerCursor, writeCounter,
                            lastNotifiedPercent));
            workers.add(wf);
            task.chunkFutures.add(wf); // TE.6: so cancel() can interrupt the in-flight fetches
        }
        for (Future<?> f : workers) {
            try { f.get(); } catch (Exception ignored) {}
        }
        // TE.6: the workers have joined; drop their futures so a late cancel() doesn't act on stale ones.
        task.chunkFutures.clear();

        // TE.4: persist the final bitmap once the workers have joined. The in-loop save is throttled,
        // so without this a clean stop right after the last chunk would leave the .meta lagging and a
        // resume would needlessly re-fetch the tail. Best-effort: a failure here only costs re-fetch.
        try { fr.saveState(); } catch (IOException ignored) {}
    }

    /** One chunk-fetch worker: drains the shared queue, failing over and requeuing as needed. */
    private void drainChunkQueue(DownloadTask task, FileReassembler fr, Queue<ChunkWork> queue,
                                 Set<String> deadPeers, ConcurrentHashMap<String, AtomicInteger> peerFailures,
                                 AtomicInteger peerCursor, AtomicInteger writeCounter,
                                 AtomicInteger lastNotifiedPercent) {
        // Thread-confined: this worker keeps one open connection per peer and reuses it across every
        // chunk it pulls for that peer (T0.1), so the TLS handshake is paid once per peer, not once per
        // chunk. The map is local to this thread, so no connection is ever touched concurrently.
        Map<String, PeerConnection> connections = new HashMap<>();
        try {
            ChunkWork work;
            // TE.6: stop pulling new chunks as soon as the download is cancelled. The flag (volatile, set
            // before cancel() interrupts us) is the sole exit guard — we deliberately don't also test the
            // thread's interrupt status, since this worker rides back into the shared chunkPool and must
            // not carry a set interrupt flag out. A fetch interrupted mid-blocking-wait throws below, the
            // catch handles it, and the next loop check sees the flag.
            while (!task.cancelled && (work = queue.poll()) != null) {
                PeerInfo peer = pickPeer(task.peers, work.triedPeers, deadPeers, peerCursor);
                if (peer == null) {
                    // No healthy, untried peer left for this chunk — it cannot be recovered. Drop it;
                    // the unfilled slot makes fr.isComplete() false so the download is failed cleanly.
                    continue;
                }
                String key = peerKey(peer);
                try {
                    PeerConnection conn = connections.get(key);
                    if (conn == null) {
                        conn = chunkSource.connect(peer);
                        connections.put(key, conn);
                    }
                    byte[] data = conn.fetchChunk(task.filename, work.index);
                    if (data == null) throw new IOException("peer returned no data");
                    fr.writeChunk(work.index, data);
                    task.progress = fr.getProgress();
                    // TE.4: snapshot resume state periodically rather than after every chunk; the
                    // final state is force-saved once the workers join (see fetchMissingChunks).
                    if (writeCounter.incrementAndGet() % SAVE_STATE_EVERY_N_CHUNKS == 0) fr.saveState();
                    // TE.5: throttle the per-chunk UI callback to at most one per advancing percentage point.
                    notifyChunkProgress(task, lastNotifiedPercent);
                } catch (Exception e) {
                    // A failure can leave the stream mid-frame, so drop and close this peer's connection;
                    // a later chunk for the same peer reconnects cleanly. Never retry the same peer for
                    // THIS chunk; bump its failure counter, evict it past the dead threshold, and requeue
                    // the chunk so a different peer serves it.
                    closeQuietly(connections.remove(key));
                    work.triedPeers.add(key);
                    int fails = peerFailures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                    if (fails >= PEER_DEAD_THRESHOLD) deadPeers.add(key);
                    System.err.println("[DL] Chunk " + work.index + " from " + key + " failed: " + e.getMessage());
                    queue.add(work);
                }
            }
        } finally {
            for (PeerConnection conn : connections.values()) closeQuietly(conn);
            // TE.6: clear any interrupt left by cancel()'s future.cancel(true) so this pooled thread
            // returns to the shared chunkPool clean, instead of relying on the executor to clear it.
            Thread.interrupted();
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) {}
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
        notifyError(task);
    }

    /**
     * Default {@link PeerConnection}: one TLS socket reused for many length-prefixed chunk requests (T0.1).
     * The handshake happens once in {@link #open}; each {@link #fetchChunk} writes a request frame and reads
     * the response frame plus the raw chunk body, validating the body size and per-chunk checksum.
     */
    static final class TlsPeerConnection implements PeerConnection {
        /** Per-request idle read timeout; a stalled peer fails this chunk and triggers T0.3 failover. */
        private static final int READ_TIMEOUT_MS = 30_000;

        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;

        private TlsPeerConnection(Socket socket) throws IOException {
            this.socket = socket;
            try {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            } catch (IOException e) {
                // The half-built connection is unreachable, so close the socket here rather than
                // leaking it (the old try-with-resources fetcher was leak-safe; a long-lived object isn't).
                try { socket.close(); } catch (IOException ignored) {}
                throw e;
            }
        }

        /**
         * Opens a persistent TLS connection to {@code peer} (one handshake, reused for all its chunks).
         * T0.5: when the tracker advertised the peer's public-key fingerprint ({@link PeerInfo#keyId}),
         * the connection is pinned to it — a peer (or MITM) presenting a different certificate fails the
         * pin check at handshake time, so {@code open} throws and T0.3 fails the chunk over to a healthy
         * peer rather than downloading from an impostor. A null/blank keyId is unpinned (legacy peers).
         */
        static TlsPeerConnection open(PeerInfo peer) throws IOException {
            return new TlsPeerConnection(TLSHelper.createClientSocket(peer.ip, peer.port, peer.keyId));
        }

        @Override
        public byte[] fetchChunk(String filename, int chunkIndex) throws IOException {
            Frames.writeMessage(out, new Message(MessageType.CHUNK_REQUEST, new ChunkRequest(filename, chunkIndex)));
            out.flush();

            Message resp = Frames.readMessage(in);
            if (resp == null) throw new EOFException("Peer closed connection before responding");
            ChunkResponse cr = resp.getPayload(ChunkResponse.class);
            if (cr == null) throw new IOException("Malformed chunk response");
            if (!cr.success) throw new IOException("Peer error: " + cr.error);

            // TE.1 bounded framing: never trust the peer's advertised body size — a chunk is at most
            // CHUNK_SIZE, so a larger (or negative) claim is a protocol violation, not a 2 GB allocation.
            if (cr.size < 0 || cr.size > Protocol.CHUNK_SIZE) {
                throw new IOException("Chunk body size out of range: " + cr.size);
            }
            byte[] data = in.readNBytes(cr.size);
            if (data.length < cr.size) throw new EOFException("Truncated chunk body");
            if (!FileChunker.verifyChunk(data, cr.checksum)) {
                throw new IOException("Checksum mismatch for chunk " + chunkIndex);
            }
            return data;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * Fans a progress event out to the legacy single-slot {@code onProgressUpdate} consumer AND every
     * registered {@link DownloadListener} (DT.3). Each invocation is isolated (see {@link #safeRun}) so a
     * throwing UI callback can neither starve the other observers nor corrupt the download state machine.
     */
    private void notifyProgress(DownloadTask task) {
        safeAccept(task.onProgressUpdate, task);
        for (DownloadListener l : task.listeners) safeRun(() -> l.onProgress(task));
    }

    /** Fans a completion event out to the legacy {@code onComplete} consumer and every listener. */
    private void notifyComplete(DownloadTask task) {
        safeAccept(task.onComplete, task);
        for (DownloadListener l : task.listeners) safeRun(() -> l.onComplete(task));
    }

    /** Fans a failure event out to the legacy {@code onError} consumer and every listener. */
    private void notifyError(DownloadTask task) {
        safeAccept(task.onError, task);
        for (DownloadListener l : task.listeners) safeRun(() -> l.onError(task));
    }

    private static void safeAccept(Consumer<DownloadTask> cb, DownloadTask task) {
        if (cb != null) safeRun(() -> cb.accept(task));
    }

    /**
     * Isolates a single callback invocation: a UI listener that throws is logged and swallowed rather
     * than propagating. This matters on the completion path — {@code notifyComplete} fires inside
     * {@code executeDownload}'s try block, so an unguarded throw would be caught and would flip an
     * already-succeeded download to FAILED; with the guard, one bad listener affects nothing else.
     */
    private static void safeRun(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            System.err.println("[DL] download listener threw: " + e);
        }
    }

    /**
     * TE.5: emit a per-chunk progress callback only when the whole-percent figure advances, so a
     * download of thousands of 512 KB chunks fires at most ~100 callbacks instead of one per chunk
     * (UI callbacks are off-thread and would otherwise flood). {@code lastNotifiedPercent} is shared
     * across the download's workers; the {@code pct > prev} guard keeps it monotonic and the CAS lets
     * exactly one worker fire for each advancing percent under concurrency. The terminal 100% update is
     * sent unconditionally by the COMPLETE path, so a final tail below one percent is never swallowed.
     */
    private void notifyChunkProgress(DownloadTask task, AtomicInteger lastNotifiedPercent) {
        int pct = (int) (task.progress * 100);
        int prev = lastNotifiedPercent.get();
        if (pct > prev && lastNotifiedPercent.compareAndSet(prev, pct)) {
            notifyProgress(task);
        }
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

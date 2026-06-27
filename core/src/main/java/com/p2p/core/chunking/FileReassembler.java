package com.p2p.core.chunking;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Reassembles a file from chunks fetched (possibly out of order, by many threads at once).
 *
 * <p>TE.4: a single {@link FileChannel} is held open for the life of the download and chunks
 * are written with the absolute-position overload {@link FileChannel#write(ByteBuffer, long)},
 * which does not touch the channel's shared position and is therefore safe for concurrent
 * writers with no global lock — replacing the old per-chunk open/close under a {@code synchronized}
 * monitor that serialized every write on disk I/O. Only the in-memory received-bitmap is guarded.
 *
 * <p>The instance is {@link Closeable}: the caller closes it once all chunks are written (and
 * before reading the file back to verify it), which flushes and releases the channel.
 */
public class FileReassembler implements Closeable {

    private final File outputFile;
    private final int totalChunks;
    private final boolean[] received; // guarded by 'this'

    /** Lazily opened on the first write; never truncates an existing (partially-resumed) file. */
    private volatile FileChannel channel;

    public FileReassembler(File outputFile, int totalChunks) {
        this.outputFile = outputFile;
        this.totalChunks = totalChunks;
        this.received = new boolean[totalChunks];
    }

    public void writeChunk(int chunkIndex, byte[] data) throws IOException {
        long offset = (long) chunkIndex * com.p2p.core.protocol.Protocol.CHUNK_SIZE;
        FileChannel ch = channel();
        ByteBuffer buf = ByteBuffer.wrap(data);
        // Absolute positioned write: thread-safe across workers because it never uses or
        // mutates the channel's shared position. The loop covers a partial (short) write.
        while (buf.hasRemaining()) {
            offset += ch.write(buf, offset);
        }
        markReceived(chunkIndex);
    }

    /** Get-or-open the shared channel. WRITE+CREATE only — truncating would zero a resumed file. */
    private FileChannel channel() throws IOException {
        FileChannel ch = channel;
        if (ch == null) {
            synchronized (this) {
                ch = channel;
                if (ch == null) {
                    ch = FileChannel.open(outputFile.toPath(),
                            StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                    channel = ch;
                }
            }
        }
        return ch;
    }

    private synchronized void markReceived(int chunkIndex) {
        received[chunkIndex] = true;
    }

    public synchronized boolean isChunkReceived(int chunkIndex) {
        return received[chunkIndex];
    }

    public synchronized boolean isComplete() {
        for (boolean r : received) if (!r) return false;
        return true;
    }

    public synchronized int receivedCount() {
        int count = 0;
        for (boolean r : received) if (r) count++;
        return count;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public double getProgress() {
        return (double) receivedCount() / totalChunks;
    }

    /** Returns indices of chunks not yet downloaded — for resume support. */
    public synchronized int[] missingChunks() {
        int missing = totalChunks - receivedCount();
        int[] indices = new int[missing];
        int j = 0;
        for (int i = 0; i < totalChunks; i++) {
            if (!received[i]) indices[j++] = i;
        }
        return indices;
    }

    /**
     * Persist download state to a {@code .meta} file so downloads can resume after restart.
     *
     * <p>TE.4: written atomically (temp file + rename) and {@code synchronized}. The old version
     * truncated the live {@code .meta} on open and was unsynchronized, so a crash mid-write — or two
     * worker threads saving at once — left a torn/garbled file that {@code loadState} would read back
     * as a wrong bitmap. The rename publishes an all-or-nothing snapshot: a reader sees either the
     * previous complete state or the new complete state, never a partial one.
     */
    public synchronized void saveState() throws IOException {
        File dir = outputFile.getParentFile();
        Path meta = new File(dir, outputFile.getName() + ".meta").toPath();
        Path tmp = new File(dir, outputFile.getName() + ".meta.tmp").toPath();
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            dos.writeInt(totalChunks);
            for (boolean r : received) dos.writeBoolean(r);
        }
        try {
            Files.move(tmp, meta, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Exotic filesystem without atomic rename: fall back to a best-effort replace.
            Files.move(tmp, meta, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Load prior state from .meta file. Returns null if no prior state exists. */
    public static FileReassembler loadState(File outputFile) throws IOException {
        File meta = new File(outputFile.getParent(), outputFile.getName() + ".meta");
        if (!meta.exists()) return null;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(meta))) {
            int totalChunks = dis.readInt();
            FileReassembler r = new FileReassembler(outputFile, totalChunks);
            for (int i = 0; i < totalChunks; i++) r.received[i] = dis.readBoolean();
            return r;
        }
    }

    public void deleteMetaFile() {
        File meta = new File(outputFile.getParent(), outputFile.getName() + ".meta");
        meta.delete();
    }

    /** Flush and release the shared write channel. Idempotent; safe to call when never opened. */
    @Override
    public synchronized void close() throws IOException {
        FileChannel ch = channel;
        if (ch != null) {
            channel = null;
            ch.close();
        }
    }
}

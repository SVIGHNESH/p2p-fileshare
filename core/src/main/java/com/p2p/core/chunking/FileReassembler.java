package com.p2p.core.chunking;

import java.io.*;

public class FileReassembler {

    private final File outputFile;
    private final int totalChunks;
    private final boolean[] received;

    public FileReassembler(File outputFile, int totalChunks) {
        this.outputFile = outputFile;
        this.totalChunks = totalChunks;
        this.received = new boolean[totalChunks];
    }

    public synchronized void writeChunk(int chunkIndex, byte[] data) throws IOException {
        long offset = (long) chunkIndex * com.p2p.core.protocol.Protocol.CHUNK_SIZE;
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            raf.seek(offset);
            raf.write(data);
        }
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

    /** Persist download state to a .meta file so downloads can resume after restart. */
    public void saveState() throws IOException {
        File meta = new File(outputFile.getParent(), outputFile.getName() + ".meta");
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(meta))) {
            dos.writeInt(totalChunks);
            for (boolean r : received) dos.writeBoolean(r);
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
}

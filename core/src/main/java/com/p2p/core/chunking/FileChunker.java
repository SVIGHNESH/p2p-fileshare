package com.p2p.core.chunking;

import com.p2p.core.protocol.Protocol;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileChunker {

    public static int getTotalChunks(long fileSize) {
        return (int) Math.ceil((double) fileSize / Protocol.CHUNK_SIZE);
    }

    public static byte[] readChunk(File file, int chunkIndex) throws IOException {
        long offset = (long) chunkIndex * Protocol.CHUNK_SIZE;
        int length = (int) Math.min(Protocol.CHUNK_SIZE, file.length() - offset);
        byte[] data = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            raf.readFully(data);
        }
        return data;
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha256OfFile(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean verifyChunk(byte[] data, String expectedChecksum) {
        return sha256(data).equals(expectedChecksum);
    }
}

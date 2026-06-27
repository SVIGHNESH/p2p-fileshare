package com.p2p.core.transfer;

import com.p2p.core.chunking.FileChunker;
import com.p2p.core.crypto.TLSHelper;
import com.p2p.core.protocol.Protocol.*;

import javax.net.ssl.SSLServerSocket;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Runs on every peer — listens for incoming chunk requests from other peers
 * and serves file chunks over an encrypted TLS connection.
 */
public class PeerServer {

    private final int port;
    private final Supplier<java.io.File> sharedFolderProvider;
    private SSLServerSocket serverSocket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;

    public PeerServer(int port, Supplier<java.io.File> sharedFolderProvider) {
        this.port = port;
        this.sharedFolderProvider = sharedFolderProvider;
    }

    public void start() {
        new Thread(() -> {
            try {
                serverSocket = TLSHelper.createServerSocket(port);
                running = true;
                System.out.println("[PeerServer] Listening on port " + port + " (TLS)");
                while (running) {
                    Socket client = serverSocket.accept();
                    pool.submit(() -> handleClient(client));
                }
            } catch (Exception e) {
                if (running) System.err.println("[PeerServer] Error: " + e.getMessage());
            }
        }, "peer-server").start();
    }

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            String line = in.readLine();
            if (line == null) return;
            Message msg = Message.fromJson(line);
            if (msg.type != MessageType.CHUNK_REQUEST) return;

            ChunkRequest req = msg.getPayload(ChunkRequest.class);
            java.io.File sharedFolder = sharedFolderProvider.get();
            java.io.File file = resolveSharedFile(sharedFolder, req.filename);

            if (file == null) {
                writeResponse(out, new Message(MessageType.CHUNK_RESPONSE,
                        new ChunkResponse("File not found")));
                return;
            }

            // TE.2: a negative or out-of-range chunk index would otherwise reach
            // FileChunker.readChunk and crash it (negative seek / NegativeArraySizeException),
            // silently dropping the connection. Reject it with a structured error instead.
            if (!isValidChunkIndex(req.chunkIndex, file.length())) {
                writeResponse(out, new Message(MessageType.CHUNK_RESPONSE,
                        new ChunkResponse("Invalid chunk index: " + req.chunkIndex)));
                return;
            }

            byte[] chunk = FileChunker.readChunk(file, req.chunkIndex);
            String checksum = FileChunker.sha256(chunk);
            ChunkResponse resp = new ChunkResponse(req.filename, req.chunkIndex, chunk.length, checksum);

            // Send JSON header first, then raw bytes
            String header = new Message(MessageType.CHUNK_RESPONSE, resp).toJson();
            out.write(header.getBytes());
            out.write(chunk);
            out.flush();

        } catch (Exception e) {
            System.err.println("[PeerServer] Client error: " + e.getMessage());
        }
    }

    private void writeResponse(DataOutputStream out, Message msg) throws IOException {
        out.write(msg.toJson().getBytes());
        out.flush();
    }

    /**
     * Resolves a requested filename to an existing regular file strictly inside the shared
     * folder, or returns {@code null} if it is missing, not a file, or escapes the folder
     * (TE.3 path-traversal guard).
     *
     * <p>The canonical-path prefix check MUST include a trailing {@link File#separator}.
     * Comparing bare canonical paths - the previous {@code startsWith(sharedFolder.getCanonicalPath())}
     * - lets a sibling directory slip through: with a shared folder {@code .../share}, a request for
     * {@code ../share-secret/x} canonicalizes to {@code .../share-secret/x}, which {@code startsWith}
     * {@code .../share} and was wrongly served. Requiring {@code .../share} + separator rejects it.
     */
    static java.io.File resolveSharedFile(java.io.File sharedFolder, String filename) throws IOException {
        if (filename == null || filename.isEmpty()) return null;
        java.io.File file = new java.io.File(sharedFolder, filename);
        if (!file.exists() || !file.isFile()) return null;
        String base = sharedFolder.getCanonicalPath();
        String canonical = file.getCanonicalPath();
        return canonical.startsWith(base + File.separator) ? file : null;
    }

    /** True if {@code chunkIndex} addresses a real chunk of a file of {@code fileSize} bytes (TE.2). */
    static boolean isValidChunkIndex(int chunkIndex, long fileSize) {
        return chunkIndex >= 0 && chunkIndex < FileChunker.getTotalChunks(fileSize);
    }

    public void stop() {
        running = false;
        pool.shutdownNow();
        if (serverSocket != null) try { serverSocket.close(); } catch (Exception ignored) {}
    }

    public int getPort() { return port; }
}

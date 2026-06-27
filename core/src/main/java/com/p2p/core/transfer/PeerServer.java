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
 *
 * <p>T0.1: a connection is kept open and serves many chunk requests back to back (length-prefixed
 * framing via {@link Frames}) instead of one-request-then-close, so a downloader pays the TLS
 * handshake once per peer rather than once per 512 KB chunk.
 */
public class PeerServer {

    /**
     * Idle read timeout on a served connection. A persistent connection is held open between chunk
     * requests; this caps how long a stalled or vanished peer can pin a server thread (slowloris-style
     * guard, mirroring the tracker's TR.4 read timeout). 60s comfortably spans the gap between a
     * downloader's successive chunk requests over a live connection.
     */
    private static final int IDLE_TIMEOUT_MS = 60_000;

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
        // Bind synchronously so the bound port is known the moment start() returns and a
        // bind/TLS-init failure surfaces here instead of being swallowed on a background
        // thread (which left callers believing the server was up). Only the accept loop runs async.
        try {
            serverSocket = TLSHelper.createServerSocket(port);
        } catch (Exception e) {
            System.err.println("[PeerServer] Failed to start on port " + port + ": " + e.getMessage());
            return;
        }
        running = true;
        System.out.println("[PeerServer] Listening on port " + getBoundPort() + " (TLS)");
        new Thread(this::acceptLoop, "peer-server").start();
    }

    private void acceptLoop() {
        try {
            while (running) {
                Socket client = serverSocket.accept();
                pool.submit(() -> handleClient(client));
            }
        } catch (Exception e) {
            if (running) System.err.println("[PeerServer] Error: " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) {
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            socket.setSoTimeout(IDLE_TIMEOUT_MS);

            // Serve requests back to back over the one connection until the peer closes it (T0.1).
            // Frames.readMessage returns null on a clean close at a frame boundary — normal termination.
            Message msg;
            while ((msg = Frames.readMessage(in)) != null) {
                if (msg.type != MessageType.CHUNK_REQUEST) {
                    // Unknown/garbage frame: reply with a structured error and keep the connection
                    // open — a single bad request must not tear down a connection serving good ones.
                    writeResponse(out, new ChunkResponse("Unsupported request type"));
                    continue;
                }
                handleChunkRequest(msg.getPayload(ChunkRequest.class), out);
            }

        } catch (EOFException eof) {
            // Peer disappeared mid-frame; nothing to serve.
        } catch (Exception e) {
            System.err.println("[PeerServer] Client error: " + e.getMessage());
        }
    }

    /** Serves a single chunk request onto the (persistent) connection. Never throws on bad input (TE.2/TE.3). */
    private void handleChunkRequest(ChunkRequest req, DataOutputStream out) throws IOException {
        java.io.File sharedFolder = sharedFolderProvider.get();
        java.io.File file = resolveSharedFile(sharedFolder, req.filename);

        if (file == null) {
            writeResponse(out, new ChunkResponse("File not found"));
            return;
        }

        // TE.2: a negative or out-of-range chunk index would otherwise reach FileChunker.readChunk
        // and crash it (negative seek / NegativeArraySizeException). Reject it with a structured error.
        if (!isValidChunkIndex(req.chunkIndex, file.length())) {
            writeResponse(out, new ChunkResponse("Invalid chunk index: " + req.chunkIndex));
            return;
        }

        byte[] chunk = FileChunker.readChunk(file, req.chunkIndex);
        String checksum = FileChunker.sha256(chunk);
        ChunkResponse resp = new ChunkResponse(req.filename, req.chunkIndex, chunk.length, checksum);

        // Length-prefixed JSON header frame, then the raw chunk bytes (sized by resp.size).
        Frames.writeMessage(out, new Message(MessageType.CHUNK_RESPONSE, resp));
        out.write(chunk);
        out.flush();
    }

    private void writeResponse(DataOutputStream out, ChunkResponse resp) throws IOException {
        Frames.writeMessage(out, new Message(MessageType.CHUNK_RESPONSE, resp));
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

    /**
     * The actual bound port, valid after {@link #start()} returns successfully. Differs from
     * {@link #getPort()} when the server was constructed with port 0 (ephemeral), which lets
     * tests bind a free port and connect to it without racing the accept loop.
     */
    public int getBoundPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }
}

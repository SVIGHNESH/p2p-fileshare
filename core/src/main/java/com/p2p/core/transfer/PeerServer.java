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
            java.io.File file = new java.io.File(sharedFolder, req.filename);

            if (!file.exists() || !file.getCanonicalPath().startsWith(sharedFolder.getCanonicalPath())) {
                writeResponse(out, new Message(MessageType.CHUNK_RESPONSE,
                        new ChunkResponse("File not found")));
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

    public void stop() {
        running = false;
        pool.shutdownNow();
        if (serverSocket != null) try { serverSocket.close(); } catch (Exception ignored) {}
    }

    public int getPort() { return port; }
}

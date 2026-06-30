package com.p2p.tracker;

import com.p2p.core.discovery.UDPDiscovery;
import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TrackerServer {

    // TR.4: slowloris guard — a client that never sends a complete line is dropped after this long.
    static final int DEFAULT_READ_TIMEOUT_MS = 10_000;
    // The request line cap MUST exceed the largest LEGITIMATE register, or real peers would be
    // rejected (desktop re-registers its entire file list every 30s). A register is double-JSON-
    // encoded, so budget generously per file and derive the cap from the registry's own per-peer
    // file/name caps rather than picking an arbitrary number.
    private static final int MAX_SERIALIZED_BYTES_PER_FILE = 2 * PeerRegistry.MAX_FILENAME_LENGTH + 256;
    static final int DEFAULT_MAX_LINE_BYTES =
            PeerRegistry.MAX_FILES_PER_PEER * MAX_SERIALIZED_BYTES_PER_FILE + 64 * 1024;

    private final int port;
    private final PeerRegistry registry = new PeerRegistry();
    private final UDPDiscovery discovery = new UDPDiscovery();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    // TR.1: one shared, daemon-threaded pool reused across connections and drained in stop(),
    // instead of allocating (and leaking) a fresh non-daemon cached pool per accepted socket.
    private final ExecutorService clientPool = Executors.newCachedThreadPool(daemonFactory("tracker-client"));
    private ServerSocket serverSocket;

    // TR.4 knobs — production uses the defaults; tests dial them down to drive the slowloris and
    // oversized-line paths without multi-second waits or multi-megabyte payloads.
    private volatile int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private volatile int maxLineBytes = DEFAULT_MAX_LINE_BYTES;

    public TrackerServer(int port) {
        this.port = port;
    }

    void setReadTimeoutMs(int ms) { this.readTimeoutMs = ms; }
    void setMaxLineBytes(int bytes) { this.maxLineBytes = bytes; }

    public void start() throws IOException {
        int boundPort = bind();
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║      P2P Share — Tracker Server      ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf( "║  Listening on port %-18d║%n", boundPort);
        System.out.println("║  UDP auto-discovery: ENABLED         ║");
        System.out.println("║  Press Ctrl+C to stop                ║");
        System.out.println("╚══════════════════════════════════════╝");

        // Broadcast presence on LAN via UDP multicast
        discovery.startAnnouncing(boundPort);

        // Evict dead peers every 30 seconds
        scheduler.scheduleAtFixedRate(
                registry::evictStalePeers, 30, 30, TimeUnit.SECONDS);

        // Status log every 60 seconds
        scheduler.scheduleAtFixedRate(() ->
                System.out.printf("[Tracker] Active peers: %d | Files: %d%n",
                        registry.getPeerCount(), registry.getAllFilenames().size()),
                60, 60, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        acceptLoop();
    }

    /**
     * Binds the listening socket and returns the actual bound port. Does NOT start UDP discovery,
     * the schedulers, or the shutdown hook — {@link #start()} layers those on. Pass port 0 for an
     * ephemeral port; tests use this seam to serve on the loopback interface without UDP multicast.
     * Call {@link #acceptLoop()} afterwards to begin serving.
     */
    public int bind() throws IOException {
        serverSocket = new ServerSocket(port);
        return serverSocket.getLocalPort();
    }

    /** Serves client connections on the calling thread until the socket is closed. Blocking. */
    public void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                clientPool.submit(() -> handleClient(client));
            } catch (Exception e) {
                if (!serverSocket.isClosed()) System.err.println("[Tracker] Accept error: " + e.getMessage());
            }
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

    private void handleClient(Socket socket) {
        // TE.1: pin UTF-8 on both directions so the wire encoding can't drift with the platform
        // default charset. (PeerServer/DownloadManager are the remaining TE.1 surface.)
        try (socket;
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            // TR.4: bound how long a slow/dead client can pin this handler thread (slowloris guard).
            socket.setSoTimeout(readTimeoutMs);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String clientIp = socket.getInetAddress().getHostAddress();

            String line;
            try {
                // TR.4: read with a hard byte cap instead of an unbounded readLine().
                line = readBoundedLine(in, maxLineBytes);
            } catch (LineTooLongException e) {
                // TR.4 → TR.5: oversized input earns a structured ERROR, not a silent drop.
                out.println(error("Request too large").toJson().trim());
                return;
            }
            if (line == null) return; // EOF before any data

            // TR.5: processMessage never throws — every parse/validation failure becomes an ERROR.
            out.println(processMessage(line, clientIp).toJson().trim());

        } catch (SocketTimeoutException e) {
            // TR.4: the client sent no complete request within the timeout — close quietly. There is
            // no well-formed request to answer, so (unlike a bad-input read) we do not send ERROR.
        } catch (Exception e) {
            System.err.println("[Tracker] Client error: " + e.getMessage());
        }
    }

    /** Thrown by {@link #readBoundedLine} once a line reaches the byte cap without a terminator. */
    static class LineTooLongException extends IOException {
        LineTooLongException(int maxBytes) { super("Line exceeded " + maxBytes + " bytes"); }
    }

    /**
     * Reads one {@code '\n'}-terminated line from {@code in}, decoded as UTF-8, consuming at most
     * {@code maxBytes} payload bytes (a trailing {@code '\r'} is stripped for CRLF tolerance).
     * Returns {@code null} only at immediate end-of-stream; a partial line followed by EOF is
     * returned as-is (matching {@code BufferedReader.readLine}). Throws {@link LineTooLongException}
     * the moment the cap would be exceeded, so the caller can reject the request instead of
     * buffering unbounded input. Socket-free for direct unit testing.
     */
    static String readBoundedLine(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return decodeStrippingCr(buf);
            if (buf.size() >= maxBytes) throw new LineTooLongException(maxBytes);
            buf.write(b);
        }
        return buf.size() == 0 ? null : decodeStrippingCr(buf);
    }

    private static String decodeStrippingCr(ByteArrayOutputStream buf) {
        byte[] bytes = buf.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') len--; // tolerate CRLF
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    private static Message error(String reason) {
        return new Message(MessageType.ERROR, reason);
    }

    private static boolean validPort(int port) {
        return port > 0 && port <= 65_535;
    }

    /**
     * Parses, validates and dispatches one request line, always returning a response Message —
     * never throwing. TR.5: every bad-input path (malformed JSON, the literal {@code "null"}, an
     * unknown/missing {@code type}, or a null/garbage payload) is answered with a structured ERROR
     * instead of NPE-ing the switch and dropping the connection with no reply.
     */
    private Message processMessage(String line, String clientIp) {
        Message msg;
        try {
            msg = Message.fromJson(line);
        } catch (Exception e) { // JsonSyntaxException and friends
            return error("Malformed JSON");
        }
        // `"null"` deserializes to a null Message; an unknown enum constant leaves type == null,
        // which would NPE switch(msg.type) before the default case ever runs.
        if (msg == null || msg.type == null) return error("Missing or unknown message type");

        try {
            // TR.2: the peer's source IP is always the accepted socket's address, never the
            // payload-claimed `ip`. detectLanIp() falls back to 127.0.1.1 on Linux and Android sends
            // the literal "unknown", so trusting the payload stores loopback/unknown addresses that
            // no downloader can connect to; it also lets any client spoof another peer's registry
            // entry. The listening port still comes from the payload (the TCP source port is ephemeral).
            switch (msg.type) {
                case REGISTER: {
                    RegisterRequest req = msg.getPayload(RegisterRequest.class);
                    if (req == null || !validPort(req.port)) return error("Invalid REGISTER payload");
                    // T0.5: carry the peer's advertised public-key fingerprint into the registry so it
                    // is relayed to downloaders for transfer-connection pinning (PeerRegistry caps its
                    // length). The IP is still the socket source (TR.2), never the payload. The cosmetic
                    // displayName is likewise payload-claimed and forgeable, so PeerRegistry sanitizes it
                    // (strips control chars/newlines, caps length) before it is ever relayed to a querier.
                    return registry.register(new PeerInfo(clientIp, req.port, req.files, req.keyId, req.displayName))
                            ? new Message(MessageType.HEARTBEAT, "OK")
                            : error("Registry at capacity");
                }
                case UNREGISTER: {
                    RegisterRequest req = msg.getPayload(RegisterRequest.class);
                    if (req == null || !validPort(req.port)) return error("Invalid UNREGISTER payload");
                    registry.unregister(clientIp, req.port);
                    return new Message(MessageType.HEARTBEAT, "OK");
                }
                case QUERY: {
                    QueryRequest req = msg.getPayload(QueryRequest.class);
                    if (req == null) return error("Invalid QUERY payload");
                    // Blank filename = browse: return every active peer with its full file list.
                    List<PeerInfo> peers = (req.filename == null || req.filename.isBlank())
                            ? registry.getAllActivePeers()
                            : registry.findPeersWithFile(req.filename);
                    return new Message(MessageType.PEER_LIST,
                            new PeerListResponse(req.filename, peers));
                }
                case HEARTBEAT: {
                    RegisterRequest req = msg.getPayload(RegisterRequest.class);
                    if (req == null || !validPort(req.port)) return error("Invalid HEARTBEAT payload");
                    registry.heartbeat(clientIp, req.port);
                    return new Message(MessageType.HEARTBEAT, "OK");
                }
                default:
                    return error("Unsupported message type: " + msg.type);
            }
        } catch (Exception e) { // defensive: any unexpected payload-shape failure still gets an ERROR
            return error("Bad request");
        }
    }

    public void stop() {
        discovery.stop();
        scheduler.shutdownNow();
        clientPool.shutdownNow();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        System.out.println("[Tracker] Stopped.");
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_TRACKER_PORT;
        new TrackerServer(port).start();
    }
}

package com.p2p.tracker;

import com.p2p.core.discovery.UDPDiscovery;
import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TrackerServer {

    private final int port;
    private final PeerRegistry registry = new PeerRegistry();
    private final UDPDiscovery discovery = new UDPDiscovery();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    // TR.1: one shared, daemon-threaded pool reused across connections and drained in stop(),
    // instead of allocating (and leaking) a fresh non-daemon cached pool per accepted socket.
    private final ExecutorService clientPool = Executors.newCachedThreadPool(daemonFactory("tracker-client"));
    private ServerSocket serverSocket;

    public TrackerServer(int port) {
        this.port = port;
    }

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
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            String clientIp = socket.getInetAddress().getHostAddress();
            String line = in.readLine();
            if (line == null) return;

            Message msg = Message.fromJson(line);
            Message response = processMessage(msg, clientIp);
            out.println(response.toJson().trim());

        } catch (Exception e) {
            System.err.println("[Tracker] Client error: " + e.getMessage());
        }
    }

    private Message processMessage(Message msg, String clientIp) {
        // TR.2: the peer's source IP is always the accepted socket's address, never the
        // payload-claimed `ip`. detectLanIp() falls back to 127.0.1.1 on Linux and Android sends
        // the literal "unknown", so trusting the payload stores loopback/unknown addresses that no
        // downloader can connect to; it also lets any client spoof another peer's registry entry.
        // The listening port still comes from the payload (the TCP source port is ephemeral).
        switch (msg.type) {
            case REGISTER: {
                RegisterRequest req = msg.getPayload(RegisterRequest.class);
                registry.register(new PeerInfo(clientIp, req.port, req.files));
                return new Message(MessageType.HEARTBEAT, "OK");
            }
            case UNREGISTER: {
                RegisterRequest req = msg.getPayload(RegisterRequest.class);
                registry.unregister(clientIp, req.port);
                return new Message(MessageType.HEARTBEAT, "OK");
            }
            case QUERY: {
                QueryRequest req = msg.getPayload(QueryRequest.class);
                // Blank filename = browse: return every active peer with its full file list.
                List<PeerInfo> peers = (req.filename == null || req.filename.isBlank())
                        ? registry.getAllActivePeers()
                        : registry.findPeersWithFile(req.filename);
                return new Message(MessageType.PEER_LIST,
                        new PeerListResponse(req.filename, peers));
            }
            case HEARTBEAT: {
                RegisterRequest req = msg.getPayload(RegisterRequest.class);
                registry.heartbeat(clientIp, req.port);
                return new Message(MessageType.HEARTBEAT, "OK");
            }
            default:
                return new Message(MessageType.ERROR, "Unknown message type");
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

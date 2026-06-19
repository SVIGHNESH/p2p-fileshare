package com.p2p.tracker;

import com.p2p.core.discovery.UDPDiscovery;
import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TrackerServer {

    private final int port;
    private final PeerRegistry registry = new PeerRegistry();
    private final UDPDiscovery discovery = new UDPDiscovery();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private ServerSocket serverSocket;

    public TrackerServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║      P2P Share — Tracker Server      ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf( "║  Listening on port %-18d║%n", port);
        System.out.println("║  UDP auto-discovery: ENABLED         ║");
        System.out.println("║  Press Ctrl+C to stop                ║");
        System.out.println("╚══════════════════════════════════════╝");

        // Broadcast presence on LAN via UDP multicast
        discovery.startAnnouncing(port);

        // Evict dead peers every 30 seconds
        scheduler.scheduleAtFixedRate(
                registry::evictStalePeers, 30, 30, TimeUnit.SECONDS);

        // Status log every 60 seconds
        scheduler.scheduleAtFixedRate(() ->
                System.out.printf("[Tracker] Active peers: %d | Files: %d%n",
                        registry.getPeerCount(), registry.getAllFilenames().size()),
                60, 60, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                Executors.newCachedThreadPool().submit(() -> handleClient(client));
            } catch (Exception e) {
                if (!serverSocket.isClosed()) System.err.println("[Tracker] Accept error: " + e.getMessage());
            }
        }
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
        switch (msg.type) {
            case REGISTER: {
                RegisterRequest req = msg.getPayload(RegisterRequest.class);
                // Use the actual connection IP if peer sends 0.0.0.0
                String ip = (req.ip == null || req.ip.equals("0.0.0.0")) ? clientIp : req.ip;
                registry.register(new PeerInfo(ip, req.port, req.files));
                return new Message(MessageType.HEARTBEAT, "OK");
            }
            case UNREGISTER: {
                RegisterRequest req = msg.getPayload(RegisterRequest.class);
                registry.unregister(req.ip, req.port);
                return new Message(MessageType.HEARTBEAT, "OK");
            }
            case QUERY: {
                QueryRequest req = msg.getPayload(QueryRequest.class);
                List<PeerInfo> peers = registry.findPeersWithFile(req.filename);
                return new Message(MessageType.PEER_LIST,
                        new PeerListResponse(req.filename, peers));
            }
            case HEARTBEAT: {
                RegisterRequest req = msg.getPayload(RegisterRequest.class);
                registry.heartbeat(req.ip, req.port);
                return new Message(MessageType.HEARTBEAT, "OK");
            }
            default:
                return new Message(MessageType.ERROR, "Unknown message type");
        }
    }

    public void stop() {
        discovery.stop();
        scheduler.shutdownNow();
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        System.out.println("[Tracker] Stopped.");
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_TRACKER_PORT;
        new TrackerServer(port).start();
    }
}

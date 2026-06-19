package com.p2p.core.discovery;

import com.p2p.core.protocol.Protocol;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Automatically finds the Tracker Server on the local network via UDP multicast.
 * Users never need to type an IP address manually — they just open the app
 * and the tracker is found automatically within a few seconds.
 */
public class UDPDiscovery {

    private static final String ANNOUNCE_PREFIX = "TRACKER:";
    private MulticastSocket socket;
    private InetAddress group;
    private volatile boolean running;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /** Called on tracker side: broadcast its presence every 5 seconds. */
    public void startAnnouncing(int trackerPort) {
        try {
            group = InetAddress.getByName(Protocol.DISCOVERY_GROUP);
            socket = new MulticastSocket();
            running = true;
            String msg = ANNOUNCE_PREFIX + trackerPort;
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, group, Protocol.DISCOVERY_PORT);
            scheduler.scheduleAtFixedRate(() -> {
                if (!running) return;
                try { socket.send(packet); } catch (Exception ignored) {}
            }, 0, 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[Discovery] Announce failed: " + e.getMessage());
        }
    }

    /**
     * Called on peer side: listens for tracker announcements.
     * Calls onFound with "host:port" string when tracker is discovered.
     * Stops listening after first discovery.
     */
    public void listenForTracker(Consumer<String> onFound) {
        new Thread(() -> {
            try (MulticastSocket ms = new MulticastSocket(Protocol.DISCOVERY_PORT)) {
                ms.setSoTimeout(15000); // Give up after 15s if nothing found
                group = InetAddress.getByName(Protocol.DISCOVERY_GROUP);
                ms.joinGroup(group);
                byte[] buf = new byte[64];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                ms.receive(pkt);
                String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                if (msg.startsWith(ANNOUNCE_PREFIX)) {
                    int port = Integer.parseInt(msg.substring(ANNOUNCE_PREFIX.length()).trim());
                    String trackerHost = pkt.getAddress().getHostAddress();
                    onFound.accept(trackerHost + ":" + port);
                }
                ms.leaveGroup(group);
            } catch (SocketTimeoutException e) {
                onFound.accept(null); // signal: not found
            } catch (Exception e) {
                onFound.accept(null);
            }
        }, "udp-discovery").start();
    }

    /** Scan the LAN for all trackers (for UI display). */
    public List<String> scanForTrackers(int waitSeconds) {
        List<String> found = new ArrayList<>();
        try (MulticastSocket ms = new MulticastSocket(Protocol.DISCOVERY_PORT)) {
            ms.setSoTimeout(waitSeconds * 1000);
            InetAddress g = InetAddress.getByName(Protocol.DISCOVERY_GROUP);
            ms.joinGroup(g);
            byte[] buf = new byte[64];
            while (true) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    ms.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith(ANNOUNCE_PREFIX)) {
                        int port = Integer.parseInt(msg.substring(ANNOUNCE_PREFIX.length()).trim());
                        String entry = pkt.getAddress().getHostAddress() + ":" + port;
                        if (!found.contains(entry)) found.add(entry);
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
            ms.leaveGroup(g);
        } catch (Exception ignored) {}
        return found;
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
        if (socket != null) socket.close();
    }
}

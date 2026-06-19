package com.p2p.core.tracker;

import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.*;

import java.io.*;
import java.net.Socket;
import java.util.List;

public class TrackerClient {

    private String trackerHost;
    private int trackerPort;

    public TrackerClient(String trackerHost, int trackerPort) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
    }

    public void setTracker(String host, int port) {
        this.trackerHost = host;
        this.trackerPort = port;
    }

    public boolean register(String myIp, int myPort, List<FileInfo> files) {
        try {
            Message response = sendAndReceive(new Message(
                    MessageType.REGISTER,
                    new RegisterRequest(myIp, myPort, files)));
            return response != null && response.type != MessageType.ERROR;
        } catch (Exception e) {
            System.err.println("[TrackerClient] Register failed: " + e.getMessage());
            return false;
        }
    }

    public List<PeerInfo> query(String filename) {
        try {
            Message response = sendAndReceive(new Message(
                    MessageType.QUERY,
                    new QueryRequest(filename)));
            if (response == null || response.type == MessageType.ERROR) return List.of();
            PeerListResponse plr = response.getPayload(PeerListResponse.class);
            return plr.peers != null ? plr.peers : List.of();
        } catch (Exception e) {
            System.err.println("[TrackerClient] Query failed: " + e.getMessage());
            return List.of();
        }
    }

    public void unregister(String myIp, int myPort) {
        try {
            sendAndReceive(new Message(MessageType.UNREGISTER,
                    new RegisterRequest(myIp, myPort, List.of())));
        } catch (Exception ignored) {}
    }

    private Message sendAndReceive(Message msg) throws IOException {
        try (Socket socket = new Socket(trackerHost, trackerPort);
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            socket.setSoTimeout(5000);
            out.print(msg.toJson());
            String line = in.readLine();
            return line != null ? Message.fromJson(line) : null;
        }
    }
}

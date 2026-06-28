package com.p2p.core.tracker;

import com.p2p.core.crypto.TLSHelper;
import com.p2p.core.protocol.Protocol;
import com.p2p.core.protocol.Protocol.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
            // T0.5: advertise this install's public-key fingerprint so downloaders can pin the
            // transfer connection to it. Null until TLSHelper.init() has run — an unpinned (legacy)
            // download still works, just without peer authentication.
            Message response = sendAndReceive(new Message(
                    MessageType.REGISTER,
                    new RegisterRequest(myIp, myPort, files, TLSHelper.getLocalFingerprint())));
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

    /** Browse all files on the network: returns every active peer with its file list. */
    public List<PeerInfo> browseAll() {
        return query("");
    }

    public void unregister(String myIp, int myPort) {
        try {
            sendAndReceive(new Message(MessageType.UNREGISTER,
                    new RegisterRequest(myIp, myPort, List.of())));
        } catch (Exception ignored) {}
    }

    private Message sendAndReceive(Message msg) throws IOException {
        // TE.1: pin UTF-8 to match the tracker server's pinned streams. On JDK 18+ the default is
        // already UTF-8 (JEP 400), but being explicit on both ends prevents a mismatch on non-ASCII
        // filenames if the JVM default is forced back (e.g. -Dfile.encoding=COMPAT).
        try (Socket socket = new Socket(trackerHost, trackerPort);
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(5000);
            out.println(msg.toJson());
            String line = in.readLine();
            return line != null ? Message.fromJson(line) : null;
        }
    }
}

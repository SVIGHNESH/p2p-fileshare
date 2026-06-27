package com.p2p.tracker;

import com.p2p.core.protocol.Protocol.FileInfo;
import com.p2p.core.protocol.Protocol.Message;
import com.p2p.core.protocol.Protocol.MessageType;
import com.p2p.core.protocol.Protocol.PeerListResponse;
import com.p2p.core.protocol.Protocol.QueryRequest;
import com.p2p.core.protocol.Protocol.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-socket E2E tests for the tracker request path.
 *
 * The tracker is plaintext TCP (no TLS keystore needed), so unlike the peer transfer layer it can
 * be driven over a genuine loopback socket. That matters because the bugs under test live in the
 * socket→handler wiring itself: TR.2 (trust the socket IP, not the payload) can only be verified
 * with a real connection, and TR.1 (one shared, drained pool) is a thread-lifecycle property.
 *
 * The server is started via the {@link TrackerServer#bind()}/{@link TrackerServer#acceptLoop()}
 * seam on an ephemeral port, skipping the UDP-multicast announce that {@code start()} would fire.
 */
class TrackerServerE2ETest {

    private TrackerServer server;
    private int port;
    private Thread acceptThread;

    @BeforeEach
    void startServer() throws IOException {
        server = new TrackerServer(0);
        port = server.bind();
        acceptThread = new Thread(server::acceptLoop, "tracker-test-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    /** Sends one request and returns the single-line response, mirroring the real client framing. */
    private Message roundTrip(Message request) throws IOException {
        return roundTripRaw(request.toJson().trim());
    }

    /** Sends an arbitrary raw request line (UTF-8 + '\n') and returns the parsed single-line reply. */
    private Message roundTripRaw(String rawLine) throws IOException {
        // Connect from the loopback interface so the server's accepted socket sees 127.0.0.1.
        try (Socket s = new Socket("127.0.0.1", port);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
            out.println(rawLine);
            String line = in.readLine();
            return line == null ? null : Message.fromJson(line);
        }
    }

    // ── TR.2: trust the socket IP, not the payload ───────────────────────────

    @Test
    void registerUsesSocketIpNotPayloadClaimedIp() throws IOException {
        // The peer lies (or, realistically, detectLanIp() returned the 127.0.1.1 Linux fallback).
        FileInfo file = new FileInfo("movie.mp4", 1_000_000, 2, "deadbeef");
        RegisterRequest reg = new RegisterRequest("127.0.1.1", 7777, new ArrayList<>(List.of(file)));
        Message regResp = roundTrip(new Message(MessageType.REGISTER, reg));
        assertEquals(MessageType.HEARTBEAT, regResp.type, "REGISTER must be acknowledged");

        Message queryResp = roundTrip(new Message(MessageType.QUERY, new QueryRequest("movie.mp4")));
        PeerListResponse plr = queryResp.getPayload(PeerListResponse.class);
        assertEquals(1, plr.peers.size(), "the registered peer must be discoverable");
        assertEquals("127.0.0.1", plr.peers.get(0).ip,
                "tracker must serve the real socket source IP, not the payload-claimed 127.0.1.1");
        assertEquals(7777, plr.peers.get(0).port,
                "the listening port still comes from the payload, not the ephemeral TCP source port");
    }

    @Test
    void unregisterMatchesTheSocketDerivedKey() throws IOException {
        FileInfo file = new FileInfo("song.mp3", 5000, 1, "cafe");
        // Register claiming a bogus IP — the tracker keys the entry under the real socket IP.
        roundTrip(new Message(MessageType.REGISTER,
                new RegisterRequest("unknown", 6001, new ArrayList<>(List.of(file)))));
        assertEquals(1, roundTrip(new Message(MessageType.QUERY, new QueryRequest("song.mp3")))
                .getPayload(PeerListResponse.class).peers.size());

        // UNREGISTER from the same loopback socket with the same listening port must resolve to the
        // same key and actually remove the entry — which only works because both sides derive the IP
        // from the socket rather than the (bogus) payload "unknown".
        roundTrip(new Message(MessageType.UNREGISTER,
                new RegisterRequest("unknown", 6001, null)));
        assertEquals(0, roundTrip(new Message(MessageType.QUERY, new QueryRequest("song.mp3")))
                .getPayload(PeerListResponse.class).peers.size(),
                "UNREGISTER keyed on the socket IP must remove the peer");
    }

    // ── TR.3: a poisoned file list must not break the query path ──────────────

    @Test
    void nullNamedFileDoesNotBreakTheQueryPath() throws IOException {
        // A peer registers a poison FileInfo (name == null) alongside a real one. On the old code
        // the null name NPE'd inside findPeersWithFile's stream and the connection dropped with no
        // response (readLine == null below).
        List<FileInfo> files = new ArrayList<>(Arrays.asList(
                new FileInfo(), new FileInfo("doc.pdf", 10, 1, "x")));
        roundTrip(new Message(MessageType.REGISTER,
                new RegisterRequest(null, 8888, files)));

        Message queryResp = roundTrip(new Message(MessageType.QUERY, new QueryRequest("doc.pdf")));
        assertNotNull(queryResp, "query must get a response even after a null-named file was registered");
        assertEquals(MessageType.PEER_LIST, queryResp.type);
        assertEquals(1, queryResp.getPayload(PeerListResponse.class).peers.size(),
                "the peer with the real file is still found");
    }

    // ── TR.1: one shared pool, drained on stop ───────────────────────────────

    @Test
    void clientHandlerThreadsAreDrainedOnStop() throws Exception {
        // Exercise the pool with several connections so handler threads exist.
        for (int i = 0; i < 5; i++) {
            roundTrip(new Message(MessageType.QUERY, new QueryRequest("nothing.bin")));
        }
        server.stop();

        // After stop() drains the shared pool, no daemon handler thread should linger. (Poll briefly:
        // shutdownNow interrupts idle cached-pool workers, but thread teardown is asynchronous.)
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && countLiveTrackerClientThreads() > 0) {
            Thread.sleep(50);
        }
        assertEquals(0, countLiveTrackerClientThreads(),
                "the shared client pool must be drained by stop(), leaving no lingering handler threads");
    }

    private static int countLiveTrackerClientThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("tracker-client")) n++;
        }
        return n;
    }

    // ── TR.5: structured ERROR on bad input (never a silent dropped connection) ─

    @Test
    void malformedJsonGetsStructuredError() throws IOException {
        Message resp = roundTripRaw("this is definitely not json");
        assertNotNull(resp, "a malformed request must get a reply, not a dropped connection");
        assertEquals(MessageType.ERROR, resp.type);
    }

    @Test
    void literalNullMessageGetsStructuredError() throws IOException {
        // Gson deserializes the literal "null" to a null Message; the old switch NPE'd on msg.type.
        Message resp = roundTripRaw("null");
        assertNotNull(resp);
        assertEquals(MessageType.ERROR, resp.type);
    }

    @Test
    void unknownMessageTypeGetsStructuredError() throws IOException {
        // An unknown enum constant leaves type == null, which NPE'd switch(msg.type) before the
        // default case could run — so this exercises a different path than a valid-but-odd type.
        Message resp = roundTripRaw("{\"type\":\"BOGUS_TYPE\",\"payload\":\"{}\"}");
        assertNotNull(resp);
        assertEquals(MessageType.ERROR, resp.type);
    }

    @Test
    void missingPayloadGetsStructuredError() throws IOException {
        // A valid type but no payload → getPayload(...) returns null → would NPE on req.port.
        Message resp = roundTripRaw("{\"type\":\"REGISTER\"}");
        assertNotNull(resp);
        assertEquals(MessageType.ERROR, resp.type);
    }

    @Test
    void registerWithInvalidPortGetsStructuredError() throws IOException {
        Message resp = roundTrip(new Message(MessageType.REGISTER,
                new RegisterRequest("x", 0, new ArrayList<>())));
        assertEquals(MessageType.ERROR, resp.type, "port 0 is not a valid listening port");
    }

    // ── TR.4: read timeout (slowloris) and bounded line length ─────────────────

    @Test
    void slowlorisConnectionIsClosedAfterReadTimeout() throws IOException {
        server.setReadTimeoutMs(300); // tiny so the test does not wait the 10s production default
        long start = System.currentTimeMillis();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000); // client-side guard so a regression can't hang the suite forever
            // Send nothing at all. The server must time out reading and close the connection.
            int b = s.getInputStream().read();
            long elapsed = System.currentTimeMillis() - start;
            assertEquals(-1, b, "a client that never sends a line must be disconnected (EOF), not pinned");
            assertTrue(elapsed >= 250 && elapsed < 4000,
                    "the disconnect must be driven by the ~300ms read timeout, not the client guard");
        }
    }

    @Test
    void oversizedLineGetsStructuredError() throws IOException {
        server.setMaxLineBytes(50); // small cap so the test sends bytes, not megabytes
        // Send exactly cap+1 bytes with no newline: the server consumes all of them, hits the cap,
        // and replies ERROR. cap+1 (not more) leaves no unread bytes, so close() sends a clean FIN
        // rather than an RST that could race the ERROR reply.
        byte[] payload = "a".repeat(51).getBytes(StandardCharsets.UTF_8);
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(payload);
            s.getOutputStream().flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Message resp = Message.fromJson(in.readLine());
            assertEquals(MessageType.ERROR, resp.type, "an oversized line earns an ERROR, not a drop");
        }
    }

    @Test
    void aPeerAtTheFilesCapStillRegistersSuccessfully() throws IOException {
        // The cap relationship the line limit must respect: a legitimate max-files REGISTER (the
        // desktop re-sends its whole list every 30s) must NOT be rejected as oversized.
        List<FileInfo> files = new ArrayList<>();
        for (int i = 0; i < PeerRegistry.MAX_FILES_PER_PEER; i++) {
            files.add(new FileInfo(String.format("track-%05d.flac", i), 4_000_000, 8,
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")); // 64-hex
        }
        Message resp = roundTrip(new Message(MessageType.REGISTER,
                new RegisterRequest("ignored", 9001, files)));
        assertEquals(MessageType.HEARTBEAT, resp.type,
                "a max-files register must succeed — the line cap is derived to exceed it");

        assertEquals(1, roundTrip(new Message(MessageType.QUERY, new QueryRequest("track-00000.flac")))
                .getPayload(PeerListResponse.class).peers.size(), "the registered files are queryable");
    }
}

package com.p2p.core.protocol;

import com.p2p.core.protocol.Protocol.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the wire protocol. These lock in the JSON serialization
 * contract so refactors to the transfer engine cannot silently change it.
 */
class ProtocolTest {

    @Test
    void chunkSizeConstantIsHalfMegabyte() {
        assertEquals(512 * 1024, Protocol.CHUNK_SIZE);
    }

    @Test
    void toJsonIsNewlineTerminatedForLineFraming() {
        // The peer/tracker wire format is newline-delimited JSON; readers call
        // readLine(), so every encoded message must end with exactly one '\n'.
        String json = new Message(MessageType.HEARTBEAT, new QueryRequest("x")).toJson();
        assertTrue(json.endsWith("\n"), "encoded message must end with newline");
        assertEquals(1, json.chars().filter(c -> c == '\n').count(), "exactly one newline");
    }

    @Test
    void messageRoundTripPreservesTypeAndPayload() {
        Message original = new Message(MessageType.CHUNK_REQUEST, new ChunkRequest("movie.mkv", 7));

        // Strip the framing newline the way a line-based reader would.
        Message decoded = Message.fromJson(original.toJson().trim());

        assertEquals(MessageType.CHUNK_REQUEST, decoded.type);
        ChunkRequest req = decoded.getPayload(ChunkRequest.class);
        assertEquals("movie.mkv", req.filename);
        assertEquals(7, req.chunkIndex);
    }

    @Test
    void everyMessageTypeSurvivesRoundTrip() {
        for (MessageType type : MessageType.values()) {
            Message decoded = Message.fromJson(new Message(type, new QueryRequest("q")).toJson().trim());
            assertEquals(type, decoded.type, "type " + type + " must survive serialization");
        }
    }

    @Test
    void fileInfoRoundTrip() {
        FileInfo info = new FileInfo("report.pdf", 1_234_567L, 3, "deadbeef");
        FileInfo decoded = new Message(MessageType.REGISTER, info).getPayload(FileInfo.class);
        assertEquals("report.pdf", decoded.name);
        assertEquals(1_234_567L, decoded.size);
        assertEquals(3, decoded.totalChunks);
        assertEquals("deadbeef", decoded.checksum);
    }

    @Test
    void peerListResponsePreservesNestedFiles() {
        FileInfo file = new FileInfo("a.bin", 10L, 1, "abc");
        PeerInfo peer = new PeerInfo("192.168.1.5", 9001, List.of(file));
        PeerListResponse response = new PeerListResponse("a.bin", List.of(peer));

        PeerListResponse decoded =
                new Message(MessageType.PEER_LIST, response).getPayload(PeerListResponse.class);

        assertEquals("a.bin", decoded.filename);
        assertEquals(1, decoded.peers.size());
        PeerInfo decodedPeer = decoded.peers.get(0);
        assertEquals("192.168.1.5", decodedPeer.ip);
        assertEquals(9001, decodedPeer.port);
        assertEquals(1, decodedPeer.files.size());
        assertEquals("a.bin", decodedPeer.files.get(0).name);
    }

    @Test
    void chunkResponseSuccessConstructorMarksSuccess() {
        ChunkResponse resp = new ChunkResponse("a.bin", 2, 4096, "cafe");
        assertTrue(resp.success);
        assertNull(resp.error);
        assertEquals(2, resp.chunkIndex);
        assertEquals(4096, resp.size);
        assertEquals("cafe", resp.checksum);
    }

    @Test
    void chunkResponseErrorConstructorRoundTrips() {
        Message decoded = Message.fromJson(
                new Message(MessageType.CHUNK_RESPONSE, new ChunkResponse("File not found")).toJson().trim());
        ChunkResponse resp = decoded.getPayload(ChunkResponse.class);
        assertFalse(resp.success);
        assertEquals("File not found", resp.error);
    }
}

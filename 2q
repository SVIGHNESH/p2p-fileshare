package com.p2p.core.protocol;

import com.google.gson.Gson;
import java.util.List;

public class Protocol {

    public static final int CHUNK_SIZE = 512 * 1024; // 512 KB
    public static final int DEFAULT_TRACKER_PORT = 9000;
    public static final int DEFAULT_PEER_PORT = 9001;
    public static final String DISCOVERY_GROUP = "230.0.0.1";
    public static final int DISCOVERY_PORT = 9002;
    public static final String DISCOVERY_MAGIC = "P2PSHARE_DISCOVER";

    private static final Gson GSON = new Gson();

    public enum MessageType {
        REGISTER, UNREGISTER, QUERY, PEER_LIST,
        CHUNK_REQUEST, CHUNK_RESPONSE, HEARTBEAT, ERROR
    }

    public static class Message {
        public MessageType type;
        public String payload;

        public Message(MessageType type, Object payload) {
            this.type = type;
            this.payload = GSON.toJson(payload);
        }

        public <T> T getPayload(Class<T> cls) {
            return GSON.fromJson(payload, cls);
        }

        public String toJson() {
            return GSON.toJson(this) + "\n";
        }

        public static Message fromJson(String json) {
            return GSON.fromJson(json, Message.class);
        }
    }

    public static class FileInfo {
        public String name;
        public long size;
        public int totalChunks;
        public String checksum;

        public FileInfo() {}
        public FileInfo(String name, long size, int totalChunks, String checksum) {
            this.name = name;
            this.size = size;
            this.totalChunks = totalChunks;
            this.checksum = checksum;
        }
    }

    public static class PeerInfo {
        public String ip;
        public int port;
        public List<FileInfo> files;

        public PeerInfo() {}
        public PeerInfo(String ip, int port, List<FileInfo> files) {
            this.ip = ip;
            this.port = port;
            this.files = files;
        }
    }

    public static class RegisterRequest {
        public String ip;
        public int port;
        public List<FileInfo> files;

        public RegisterRequest(String ip, int port, List<FileInfo> files) {
            this.ip = ip;
            this.port = port;
            this.files = files;
        }
    }

    public static class QueryRequest {
        public String filename;
        public QueryRequest(String filename) { this.filename = filename; }
    }

    public static class PeerListResponse {
        public String filename;
        public List<PeerInfo> peers;
        public PeerListResponse(String filename, List<PeerInfo> peers) {
            this.filename = filename;
            this.peers = peers;
        }
    }

    public static class ChunkRequest {
        public String filename;
        public int chunkIndex;
        public ChunkRequest(String filename, int chunkIndex) {
            this.filename = filename;
            this.chunkIndex = chunkIndex;
        }
    }

    public static class ChunkResponse {
        public String filename;
        public int chunkIndex;
        public int size;
        public String checksum;
        public boolean success;
        public String error;

        public ChunkResponse(String filename, int chunkIndex, int size, String checksum) {
            this.filename = filename;
            this.chunkIndex = chunkIndex;
            this.size = size;
            this.checksum = checksum;
            this.success = true;
        }

        public ChunkResponse(String error) {
            this.success = false;
            this.error = error;
        }
    }
}

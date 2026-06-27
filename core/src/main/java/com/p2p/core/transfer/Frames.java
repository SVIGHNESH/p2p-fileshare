package com.p2p.core.transfer;

import com.p2p.core.protocol.Protocol.Message;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Length-prefixed, UTF-8 wire framing shared by both ends of the peer transfer protocol (T0.1, TE.1).
 *
 * <p>A persistent TLS connection carries many request/response pairs back to back, so the reader needs
 * an unambiguous end-of-message marker instead of the old byte-at-a-time "read until {@code \n}". Each
 * control message is framed as a 4-byte big-endian length prefix followed by exactly that many UTF-8 JSON
 * bytes; a chunk body (raw bytes) follows its {@code CHUNK_RESPONSE} frame and is sized by the response's
 * {@code size} field, not a second prefix.
 *
 * <p>Read and write live in this one helper on purpose: the frame length written MUST equal the length
 * read, so the two ends can never drift (the same both-ends-or-neither discipline as the UTF-8 charset pin).
 */
final class Frames {

    private Frames() {}

    /**
     * Upper bound on a single control-message frame. Chunk request/response JSON is tiny (well under 1 KB),
     * so a generous 64 KB cap rejects a peer that advertises a huge length to exhaust memory (TE.1 bounded
     * framing) without ever rejecting a legitimate message. The raw chunk body is bounded separately by the
     * reader against {@link com.p2p.core.protocol.Protocol#CHUNK_SIZE}.
     */
    static final int MAX_FRAME = 64 * 1024;

    /** Writes {@code msg} as a length-prefixed UTF-8 JSON frame. Does not flush; the caller flushes. */
    static void writeMessage(DataOutputStream out, Message msg) throws IOException {
        byte[] json = msg.toJson().getBytes(StandardCharsets.UTF_8);
        out.writeInt(json.length);
        out.write(json);
    }

    /**
     * Reads one length-prefixed UTF-8 JSON frame.
     *
     * @return the decoded {@link Message}, or {@code null} on a clean disconnect (the peer closed the
     *         stream exactly at a frame boundary — normal termination of a persistent connection).
     * @throws IOException if the advertised length is out of range or the stream ends mid-frame.
     */
    static Message readMessage(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException cleanClose) {
            return null; // peer closed at a frame boundary — not an error
        }
        if (len < 0 || len > MAX_FRAME) {
            throw new IOException("Control frame length out of range: " + len);
        }
        byte[] json = in.readNBytes(len);
        if (json.length < len) {
            throw new EOFException("Truncated control frame: expected " + len + " bytes, got " + json.length);
        }
        return Message.fromJson(new String(json, StandardCharsets.UTF_8));
    }
}

package com.p2p.tracker;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Socket-free unit tests for {@link TrackerServer#readBoundedLine}, the bounded replacement for the
 * old unbounded {@code BufferedReader.readLine()} (TR.4) that also pins UTF-8 decoding (TE.1).
 *
 * The cap boundary is exactly where these reads break in practice, so the off-by-one cases
 * (a line of exactly {@code maxBytes} must pass; one byte more must throw) are tested directly,
 * the same socket-free-helper approach used for {@code PeerServer.resolveSharedFile} in TE.2/TE.3.
 */
class ReadBoundedLineTest {

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsASimpleNewlineTerminatedLine() throws IOException {
        assertEquals("hello", TrackerServer.readBoundedLine(bytes("hello\nworld\n"), 1024),
                "reads up to (not including) the first newline");
    }

    @Test
    void stripsTrailingCarriageReturnForCrlf() throws IOException {
        assertEquals("hello", TrackerServer.readBoundedLine(bytes("hello\r\n"), 1024),
                "a CRLF terminator must not leak the \\r into the payload");
    }

    @Test
    void returnsEmptyStringForABareNewline() throws IOException {
        assertEquals("", TrackerServer.readBoundedLine(bytes("\n"), 1024),
                "a bare newline is an empty line, not end-of-stream");
    }

    @Test
    void returnsNullAtImmediateEndOfStream() throws IOException {
        assertNull(TrackerServer.readBoundedLine(bytes(""), 1024),
                "EOF before any byte is read returns null");
    }

    @Test
    void returnsPartialLineAtEofWithoutTerminator() throws IOException {
        assertEquals("partial", TrackerServer.readBoundedLine(bytes("partial"), 1024),
                "a trailing line with no newline before EOF is returned as-is, like readLine()");
    }

    @Test
    void decodesMultiByteUtf8() throws IOException {
        // "ünïcödé" is multi-byte in UTF-8; the cap is on bytes, the result is decoded as UTF-8.
        assertEquals("ünïcödé", TrackerServer.readBoundedLine(bytes("ünïcödé\n"), 1024));
    }

    @Test
    void acceptsALineOfExactlyMaxBytes() throws IOException {
        // 5 payload bytes + terminator, cap 5 → boundary case must be accepted, not rejected.
        assertEquals("abcde", TrackerServer.readBoundedLine(bytes("abcde\n"), 5));
    }

    @Test
    void rejectsALineOneByteOverTheCap() {
        // 6 payload bytes before the newline, cap 5 → the cap is hit before the terminator.
        assertThrows(TrackerServer.LineTooLongException.class,
                () -> TrackerServer.readBoundedLine(bytes("abcdef\n"), 5),
                "exceeding the cap before a newline must throw, not silently truncate");
    }

    @Test
    void rejectsAnEndlessUnterminatedLine() {
        // The slowloris/memory-exhaustion shape: lots of bytes, no terminator in sight.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) sb.append('x');
        assertThrows(TrackerServer.LineTooLongException.class,
                () -> TrackerServer.readBoundedLine(bytes(sb.toString()), 1024),
                "an unbounded line must be capped rather than buffered to the end");
    }
}

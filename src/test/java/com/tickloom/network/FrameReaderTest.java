package com.tickloom.network;

import com.tickloom.ProcessId;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive unit‑tests for {@link FrameReader} covering:
 *  1. Complete frame in one read
 *  2. Header split across reads
 *  3. Payload split across many reads (large message)
 *  4. Multiple frames in a single read
 *  5. Invalid payload length (negative / > MAX)
 *  6. EOF before payload complete
 */
class FrameReaderTest {

    /* ----------------- helpers ----------------- */
    private static byte[] buildFrame(int streamId, byte type, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(Frame.HEADER_SIZE + payload.length);
        buf.putInt(streamId);
        buf.put(type);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    private static Message dummyMessage() {
        return new Message(ProcessId.of("client"), ProcessId.of("server"), PeerType.CLIENT, new MessageType("test"), new byte[0], "x");
    }

    /* -------------- happy‑path cases -------------- */

    @Test
    void readCompleteFrame() throws IOException {
        FrameReader fr = new FrameReader();
        byte[] payload = "hello".getBytes();
        byte[] frame   = buildFrame(1, (byte) 7, payload);

        SocketChannel ch = new SocketChannelBuilder().thatReads(frame).build();
        assertTrue(fr.readFrom(ch).isFrameComplete());

        Frame out = fr.pollFrame();
        assertNotNull(out);
        assertEquals(1, out.getStreamId());
        assertEquals(7, out.getFrameType());
        assertEquals(payload.length, out.getPayloadLength());
    }

    @Test
    void headerSplitAcrossReads() throws IOException {
        FrameReader fr = new FrameReader();
        byte[] payload = "abc".getBytes();
        byte[] frame   = buildFrame(2, (byte) 3, payload);

        // Split header: first 5 bytes, then rest (header+payload)
        byte[] firstChunk  = Arrays.copyOfRange(frame, 0, 5);
        byte[] secondChunk = Arrays.copyOfRange(frame, 5, frame.length);

        SocketChannel ch = new SocketChannelBuilder().thatReads(firstChunk, secondChunk).build();
        assertFalse(fr.readFrom(ch).isFrameComplete());          // after first 5 bytes
        ReadResult readResult = fr.readFrom(ch);
        assertTrue(readResult.isFrameComplete());           // after rest
        Frame f = fr.pollFrame();
        assertEquals(2, f.getStreamId());
    }

    @Test
    void payloadSplitManyReads_largeMessage() throws IOException {
        FrameReader fr = new FrameReader();
        byte[] big = new byte[Frame.MAX_PAYLOAD_SIZE / 2];
        Arrays.fill(big, (byte) 0xA5);
        byte[] frame = buildFrame(3, (byte) 1, big);

        // feed 1 KB at a time
        SocketChannelBuilder b = new SocketChannelBuilder();
        final int CHUNK = 1024;
        for (int i = 0; i < frame.length; i += CHUNK) {
            b.thatReads(Arrays.copyOfRange(frame, i, Math.min(frame.length, i + CHUNK)));
        }
        SocketChannel ch = b.build();

        while (!fr.readFrom(ch).isFrameComplete()) {
            // loop until complete
        }
        Frame f = fr.pollFrame();
        assertEquals(big.length, f.getPayloadLength());
    }

    @Test
    void multipleFramesInSingleRead() throws IOException {
        FrameReader fr = new FrameReader();
        byte[] f1 = buildFrame(10, (byte) 1, "x".getBytes());
        byte[] f2 = buildFrame(11, (byte) 2, "y".getBytes());
        byte[] concat = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, concat, 0, f1.length);
        System.arraycopy(f2, 0, concat, f1.length, f2.length);

        SocketChannel ch = new SocketChannelBuilder().thatReads(concat).build();
        assertTrue(fr.readFrom(ch).isFrameComplete());
        assertEquals(10, fr.pollFrame().getStreamId());
        assertEquals(11, fr.pollFrame().getStreamId());
        assertNull(fr.pollFrame());
    }

    /* -------------- error cases -------------- */

    @Test
    void invalidNegativePayloadLength() throws IOException {
        FrameReader fr = new FrameReader();
        ByteBuffer buf = ByteBuffer.allocate(Frame.HEADER_SIZE);
        buf.putInt(1).put((byte) 1).putInt(-5).flip();
        SocketChannel ch = new SocketChannelBuilder().thatReads(buf.array()).build();
        assertThrows(IllegalStateException.class, () -> fr.readFrom(ch));
    }

    @Test
    void payloadExceedsMax() throws IOException {
        FrameReader fr = new FrameReader();
        int tooBig = Frame.MAX_PAYLOAD_SIZE + 1;
        ByteBuffer buf = ByteBuffer.allocate(Frame.HEADER_SIZE);
        buf.putInt(1).put((byte) 1).putInt(tooBig).flip();
        SocketChannel ch = new SocketChannelBuilder().thatReads(buf.array()).build();
        assertThrows(IllegalStateException.class, () -> fr.readFrom(ch));
    }

    @Test
    void eofBeforePayloadComplete() throws IOException {
        FrameReader fr = new FrameReader();
        byte[] payload = "hello".getBytes();
        byte[] frame   = buildFrame(5, (byte) 5, payload);
        // Send only header then EOF
        byte[] headerOnly = Arrays.copyOf(frame, Frame.HEADER_SIZE);
        SocketChannel ch = new SocketChannelBuilder().thatReads(headerOnly /* then EOF */).build();
        ReadResult readResult = fr.readFrom(ch);
        assertFalse(readResult.isFrameComplete());
        assertThrows(EOFException.class, () -> fr.readFrom(ch));
    }

    /* -------------- existing smoke test -------------- */

    @Test
    void readIncompleteFrame_oneByte() throws IOException {
        FrameReader fr = new FrameReader();
        SocketChannel ch = new SocketChannelBuilder().thatReads(new byte[1]).build();
        assertFalse(fr.readFrom(ch).isFrameComplete());
    }
}

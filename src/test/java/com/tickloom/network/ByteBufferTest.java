package com.tickloom.network;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Optional;

public class ByteBufferTest {

    @Test
    public void testReadsSingleCompleteFrame() {

        byte[] payload = "abc".getBytes();
        byte[] frame   = buildFrame(2, (byte) 3, payload);

        ByteBuffer buf = ByteBuffer.wrap(frame);
//        TrialReader reader = new TrialReader();
//        Optional<Frame> readFrame = reader.readFrame(buf);
//        assertTrue(readFrame.isPresent());

        StateBasedFrameReader context = new StateBasedFrameReader();
        while (buf.hasRemaining()) {
            context.tryReading(buf);
        }

    }



        static class TrialReader {
        enum State { READING_HEADER, READING_PAYLOAD }
        State state = State.READING_HEADER;
        ByteBuffer headerBuf = ByteBuffer.allocate(Header.SIZE);
        ByteBuffer payloadBuf = null;
        public Optional<Frame> readFrame(ByteBuffer buf) {
            switch (state) {
                case READING_HEADER: {
                    if (headerBuf.remaining() > 0) {
                        int bytesToCopy = Math.min(buf.remaining(), headerBuf.remaining());
                        ByteBuffer sliceToCopy = buf.slice(buf.position(), bytesToCopy);
                        headerBuf.put(sliceToCopy);
                        if (headerBuf.remaining() == 0) {
                            headerBuf.flip();
                            state = State.READING_PAYLOAD;
                            int payloadLength = headerBuf.getInt();
                            payloadBuf = ByteBuffer.allocate(payloadLength);
                        }
                    }
                }
                case READING_PAYLOAD: {
                    if (payloadBuf.remaining() > 0) {
                        int bytesToCopy = Math.min(buf.remaining(), payloadBuf.remaining());
                        ByteBuffer sliceToCopy = buf.slice(buf.position(), bytesToCopy);
                        payloadBuf.put(sliceToCopy);
                        if (payloadBuf.remaining() == 0) {
                            payloadBuf.flip();
                            byte[] payload = payloadBuf.array();
                            System.out.println("payload = " + payload.length);
                            System.out.println("payload = " + new String(payload));
                            Frame frame = new Frame(headerBuf.getInt(), headerBuf.get(), payloadBuf);

                            state = State.READING_HEADER;
                            payloadBuf = null;
                            headerBuf.clear();

                            System.out.println("frame = " + frame);
                            return Optional.of(frame);
                        }
                    }
                }
            }
            return Optional.empty();
        }
    }

    private static byte[] buildFrame(int streamId, byte type, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(Header.SIZE + payload.length);
        buf.putInt(streamId);
        buf.put(type);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }
}

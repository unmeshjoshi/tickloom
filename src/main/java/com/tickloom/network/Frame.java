package com.tickloom.network;

import java.nio.ByteBuffer;

/**
 * Represents a complete frame with stream ID, frame type, and payload.
 * Used by FrameReader and NioConnection for consistency.
 */
public class Frame {
    // Frame format constants
    public static final int MAX_PAYLOAD_SIZE = 10_000_000; // 10MB max
    
    private final int streamId;
    private final byte frameType;
    private final byte[] payload;
    //Header size = streamId (4 bytes) + frameType (1 byte) + length (4 bytes)
    public static final int HEADER_SIZE = Integer.BYTES + Byte.BYTES + Integer.BYTES;

    public Frame(int streamId, byte frameType, ByteBuffer payloadBuffer) {
        this.streamId = streamId;
        this.frameType = frameType;
        this.payload = new byte[payloadBuffer.remaining()];
        payloadBuffer.get(this.payload);
    }

    static ByteBuffer toByteBuffer(Frame frame) {
        // Allocate buffer with header + payload
        ByteBuffer buffer = ByteBuffer.allocate(frame.getTotalSize());

        // Write header: streamId (4 bytes) + frameType (1 byte) + length (4 bytes)
        buffer.putInt(frame.getStreamId());
        buffer.put(frame.getFrameType());
        buffer.putInt(frame.getPayloadLength());

        // Write payload
        buffer.put(frame.getPayload());

        // Prepare for writing
        buffer.flip();

        return buffer;
    }

    public int getStreamId() {
        return streamId;
    }

    public byte getFrameType() {
        return frameType;
    }

    public byte[] getPayload() {
        return payload;
    }

    public int getPayloadLength() {
        return payload.length;
    }

    public int getTotalSize() {
        return HEADER_SIZE + payload.length;
    }

    @Override
    public String toString() {
        return String.format("Frame{streamId=%d, type=%d, payloadSize=%d}",
                streamId, frameType, payload.length);
    }
} 
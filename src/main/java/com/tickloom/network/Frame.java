package com.tickloom.network;

import java.nio.ByteBuffer;

/**
 * Represents a complete frame with stream ID, frame type, and payload.
 * Used by FrameReader and NioConnection for consistency.
 */
public class Frame {
    // Frame format constants
    public static final int HEADER_SIZE = 9; // streamId (4) + frameType (1) + payloadLength (4)
    public static final int MAX_PAYLOAD_SIZE = 10_000_000; // 10MB max
    
    private final int streamId;
    private final byte frameType;
    private final byte[] payload;

    public Frame(int streamId, byte frameType, byte[] payload) {
        this.streamId = streamId;
        this.frameType = frameType;
        this.payload = payload;
    }

    public Frame(int streamId, byte frameType, ByteBuffer payloadBuffer) {
        this.streamId = streamId;
        this.frameType = frameType;
        this.payload = new byte[payloadBuffer.remaining()];
        payloadBuffer.get(this.payload);
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
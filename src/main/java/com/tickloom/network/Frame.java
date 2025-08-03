package com.tickloom.network;

import java.nio.ByteBuffer;

import static com.tickloom.network.Frame.MAX_PAYLOAD_SIZE;

/**
 * Represents a complete frame with stream ID, frame type, and payload.
 * Used by FrameReader and NioConnection for consistency.
 */

record Header(int streamId, byte frameType, int payloadLength) {
    public static final int SIZE = Integer.BYTES + Byte.BYTES + Integer.BYTES;

    public void writeTo(ByteBuffer buffer) {
            buffer.putInt(streamId());
            buffer.put(frameType());
            buffer.putInt(payloadLength());
    }

    public static Header readFrom(ByteBuffer buffer) {
        int streamId = buffer.getInt();
        byte frameType = buffer.get();
        int payloadLength = buffer.getInt();

        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_SIZE)
            throw new IllegalStateException("Bad payload len: " + payloadLength);

        return new Header(streamId, frameType, payloadLength);
    }

}

public record Frame(Header header, ByteBuffer payload) {
    // Frame format constants
    public static final int MAX_PAYLOAD_SIZE = 10 * 1024 * 1024; // 10MB max
    // Header size = streamId (4 bytes) + frameType (1 byte) + length (4 bytes)
    public static final int HEADER_SIZE = Header.SIZE;

    // Additional convenience constructor matching the old signature
    public Frame(int streamId, byte frameType, ByteBuffer payloadBuffer) {
        this(new Header(streamId, frameType, payloadBuffer.remaining()), payloadBuffer);
    }

    /*

     */

    // Instance convenience method
    public ByteBuffer encode() {
        // Allocate buffer with header + payload
        ByteBuffer buffer = ByteBuffer.allocate(getTotalSize());
        writeHeader(this, buffer);
        writePayload(this, buffer);
        buffer.flip();
        return buffer;
    }

    private static void writePayload(Frame frame, ByteBuffer buffer) {
        buffer.put(frame.payload.array());
    }

    private static void writeHeader(Frame frame, ByteBuffer buffer) {
        // Write header: streamId (4 bytes) + frameType (1 byte) + length (4 bytes)
        frame.header.writeTo(buffer);
    }

    public int getStreamId() {
        return header.streamId();
    }

    public byte getFrameType() {
        return header.frameType();
    }

    public byte[] getPayload() {
        return payload.array();
    }

    public int getPayloadLength() {
        return payload.remaining();
    }

    public int getTotalSize() {
        return HEADER_SIZE + getPayloadLength();
    }

    @Override
    public String toString() {
        return String.format("Frame{streamId=%d, type=%d, payloadSize=%d}",
                header.streamId(), header.frameType(), header.payloadLength());
    }
}
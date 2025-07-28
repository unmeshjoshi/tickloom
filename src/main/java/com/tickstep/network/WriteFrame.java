package com.tickstep.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Handles writing framed messages to a socket channel.
 * Provides utility methods for checking write progress and managing buffers.
 */
public class WriteFrame {
    private final ByteBuffer buffer;
    private final int streamId;
    private final byte frameType;
    private final int totalSize;

    /**
     * Creates a new write frame with the given data.
     * 
     * @param streamId The stream identifier
     * @param frameType The frame type
     * @param payload The message payload
     */
    public WriteFrame(int streamId, byte frameType, byte[] payload) {
        this.streamId = streamId;
        this.frameType = frameType;
        this.totalSize = 9 + payload.length; // 9 bytes header + payload
        
        // Allocate buffer with header + payload
        this.buffer = ByteBuffer.allocate(totalSize);
        
        // Write header: streamId (4 bytes) + frameType (1 byte) + length (4 bytes)
        buffer.putInt(streamId);
        buffer.put(frameType);
        buffer.putInt(payload.length);
        
        // Write payload
        buffer.put(payload);
        
        // Prepare for writing
        buffer.flip();
    }

    /**
     * Creates a write frame from a message using the legacy 4-byte length prefix format.
     * This maintains backward compatibility with the existing message format.
     * 
     * @param message The message to frame
     * @param codec The message codec to encode the message
     */
    public WriteFrame(byte[] messageData) {
        this.streamId = 0; // Default stream ID for legacy format
        this.frameType = 0; // Default frame type for legacy format
        //TODO: Not writing frame type and stream ID

        this.totalSize = 4 + messageData.length; // 4 bytes length + payload
        
        // Allocate buffer with length prefix + payload
        this.buffer = ByteBuffer.allocate(totalSize);
        
        // Write length prefix (legacy format)
        buffer.putInt(messageData.length);
        
        // Write payload
        buffer.put(messageData);
        
        // Prepare for writing
        buffer.flip();
    }

    /**
     * Writes data to the channel.
     * 
     * @param channel The socket channel to write to
     * @return The number of bytes written
     * @throws IOException if an I/O error occurs
     */
    public int write(SocketChannel channel) throws IOException {
        if (buffer.hasRemaining()) {
            return channel.write(buffer);
        }
        return 0;
    }

    /**
     * Checks if there are remaining bytes to write.
     * 
     * @return true if there are remaining bytes, false if write is complete
     */
    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    /**
     * Gets the number of remaining bytes to write.
     * 
     * @return The number of remaining bytes
     */
    public int remaining() {
        return buffer.remaining();
    }

    /**
     * Gets the total size of this frame (header + payload).
     * 
     * @return The total size in bytes
     */
    public int getTotalSize() {
        return totalSize;
    }

    /**
     * Gets the number of bytes already written.
     * 
     * @return The number of bytes written
     */
    public int getBytesWritten() {
        return totalSize - buffer.remaining();
    }

    /**
     * Gets the write progress as a percentage.
     * 
     * @return The write progress (0.0 to 1.0)
     */
    public double getWriteProgress() {
        return (double) getBytesWritten() / totalSize;
    }

    /**
     * Checks if the write is complete.
     * 
     * @return true if write is complete, false otherwise
     */
    public boolean isComplete() {
        return !buffer.hasRemaining();
    }

    /**
     * Gets the stream ID for this frame.
     * 
     * @return The stream ID
     */
    public int getStreamId() {
        return streamId;
    }

    /**
     * Gets the frame type for this frame.
     * 
     * @return The frame type
     */
    public byte getFrameType() {
        return frameType;
    }

    /**
     * Creates a copy of this write frame for retransmission.
     * 
     * @return A new WriteFrame with the same data
     */
    public WriteFrame copy() {
        buffer.rewind();
        byte[] data = new byte[totalSize];
        buffer.get(data);
        buffer.flip();
        
        if (totalSize == 4 + data.length - 4) { // Legacy format
            return new WriteFrame(data);
        } else { // New format
            return new WriteFrame(streamId, frameType, data);
        }
    }

    @Override
    public String toString() {
        return String.format("WriteFrame{streamId=%d, type=%d, totalSize=%d, remaining=%d, progress=%.1f%%}", 
                           streamId, frameType, totalSize, remaining(), getWriteProgress() * 100);
    }
} 
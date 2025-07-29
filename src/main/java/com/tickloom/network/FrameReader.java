package com.tickloom.network;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Handles reading framed messages from a socket channel.
 * Uses explicit state machine for better readability and maintainability.
 */
public class FrameReader {
    
    /**
     * Explicit states for the frame reading process.
     */
    public enum ReadState {
        READING_HEADER,      // Reading the 9-byte header
        READING_PAYLOAD,     // Reading the payload data
        COMPLETE            // Frame is complete and ready to be consumed
    }
    
    private final ByteBuffer headerBuffer;
    private ByteBuffer payloadBuffer = null;
    private int streamId = -1;
    private byte frameType = -1;
    private int payloadLength = -1;
    private ReadState state = ReadState.READING_HEADER;

    public FrameReader() {
        this.headerBuffer = ByteBuffer.allocate(Frame.HEADER_SIZE);
    }

    /**
     * Reads data from the channel, handling both header and payload phases.
     *
     * @param channel The socket channel to read from
     * @return Current ReadState indicating the progress of frame reading
     * @throws IOException  if an I/O error occurs
     * @throws EOFException if the channel is closed
     */
    private ReadState read(SocketChannel channel) throws IOException {
        switch (state) {
            case READING_HEADER:
                return readHeader(channel);
            case READING_PAYLOAD:
                return readPayload(channel);
            case COMPLETE:
                return ReadState.COMPLETE;
            default:
                throw new IllegalStateException("Unknown state: " + state);
        }
    }

    /**
     * Reads data from the channel and returns a ReadResult encapsulating the outcome.
     *
     * @param channel The socket channel to read from
     * @return ReadResult indicating the outcome of the read operation
     * @throws IOException if an I/O error occurs
     */
    public ReadResult readFrom(SocketChannel channel) throws IOException {
        ReadState currentState = read(channel);
        return currentState == ReadState.COMPLETE ? ReadResult.frameComplete() : ReadResult.incomplete();
    }

    private ReadState readHeader(SocketChannel channel) throws IOException {
        if (headerBuffer.hasRemaining()) {
            int read = channel.read(headerBuffer);
            System.out.println("NIO: Read " + read + " bytes for header, remaining: " + headerBuffer.remaining());
            if (read == -1) throw new EOFException();
            if (headerBuffer.hasRemaining()) {
                return ReadState.READING_HEADER; // Still reading header
            }
        }

        // Header is complete, parse it
        headerBuffer.flip();
        System.out.println("NIO: Header complete, parsing...");

        // Format: 9 bytes: streamId (4 bytes) + frameType (1 byte) + payloadLength (4 bytes)
        streamId = headerBuffer.getInt();
        frameType = headerBuffer.get();
        payloadLength = headerBuffer.getInt();

        System.out.println("NIO: Parsed payload length: " + payloadLength);

        // Validate payload length - throw exception on error
        if (payloadLength < 0 || payloadLength > Frame.MAX_PAYLOAD_SIZE) {
            throw new IOException("Invalid payload length: " + payloadLength);
        }

        payloadBuffer = ByteBuffer.allocate(payloadLength);
        headerBuffer.clear();
        state = ReadState.READING_PAYLOAD;
        
        // Try to read payload immediately if there's more data
        return readPayload(channel);
    }

    private ReadState readPayload(SocketChannel channel) throws IOException {
        if (payloadBuffer.hasRemaining()) {
            int read = channel.read(payloadBuffer);
            System.out.println("NIO: Read " + read + " bytes for payload, remaining: " + payloadBuffer.remaining());
            if (read == -1) throw new EOFException();
            
            if (payloadBuffer.hasRemaining()) {
                return ReadState.READING_PAYLOAD; // Still reading payload
            }
        }
        
        // Payload is complete
        state = ReadState.COMPLETE;
        return ReadState.COMPLETE;
    }

    /**
     * Creates a complete frame from the read data.
     * Call this only after read() returns ReadState.COMPLETE.
     *
     * @return The complete frame
     */
    public Frame complete() {
        if (state != ReadState.COMPLETE) {
            throw new IllegalStateException("Frame is not complete, current state: " + state);
        }

        payloadBuffer.flip();
        Frame frame = new Frame(streamId, frameType, payloadBuffer);

        // Reset for next frame
        reset();
        return frame;
    }

    /**
     * Resets the frame reader for reuse.
     */
    public void reset() {
        headerBuffer.clear();
        payloadBuffer = null;
        state = ReadState.READING_HEADER;
        streamId = -1;
        frameType = -1;
        payloadLength = -1;
    }

    @Override
    public String toString() {
        return String.format("FrameReader{state=%s, payloadLength=%d, streamId=%d, frameType=%d}",
                state, payloadLength, streamId, frameType);
    }
} 
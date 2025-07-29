package com.tickloom.network;

import com.tickloom.ProcessId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFrameTest {
    @Test
    public void readCompleteFrame() throws IOException {
        MessageCodec codec = new JsonMessageCodec();
        FrameReader frameReader = new FrameReader();

        // Create a frame with test data
        byte[] payload = ("{\"pid\":\"" + ProcessId.of("client").name() + "\",\"role\":\"client\",\"version\":1}").getBytes();
        Frame frame = new Frame(1, (byte) 1, payload);
        
        // Create the complete frame data (header + payload)
        byte[] frameData = new byte[frame.getTotalSize()];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(frameData);
        buffer.putInt(frame.getStreamId());
        buffer.put(frame.getFrameType());
        buffer.putInt(frame.getPayloadLength());
        buffer.put(frame.getPayload());

        SocketChannelBuilder builder = new SocketChannelBuilder();
        SocketChannel socketChannel = builder.thatReads(frameData).build();
        
        ReadResult readResult = frameReader.readFrom(socketChannel);
        assertTrue(readResult.isFrameComplete());
        
        Frame readFrame = frameReader.complete();
        assertEquals(1, readFrame.getStreamId());
        assertEquals((byte) 1, readFrame.getFrameType());
    }

}
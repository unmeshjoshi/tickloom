package com.tickstep.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SocketChannelBuilder {

    private byte[] dataToRead = null;
    private boolean isClosed = false;

    public SocketChannelBuilder() {}

    public static SocketChannelBuilder aChannel() {
        return new SocketChannelBuilder();
    }

    /**
     * Configures the mock channel to "read" the provided data.
     * When the SUT calls channel.read(buffer), this data will be copied into the buffer.
     */
    public SocketChannelBuilder thatReads(byte[] data) {
        this.dataToRead = data;
        return this;
    }

    /**
     * Configures the mock channel to behave as if it was closed by the peer.
     * The read() method will return -1.
     */
    public SocketChannelBuilder thatIsClosed() {
        this.isClosed = true;
        return this;
    }

    public SocketChannel build() throws IOException {
        SocketChannel mockChannel = mock(SocketChannel.class);

        if (this.isClosed) {
            when(mockChannel.read(any(ByteBuffer.class))).thenReturn(-1);
        } else if (this.dataToRead != null) {
            // Use thenAnswer to simulate a real read operation
            when(mockChannel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
                ByteBuffer buffer = invocation.getArgument(0);
                int bytesToRead = Math.min(buffer.remaining(), dataToRead.length);
                buffer.put(dataToRead, 0, bytesToRead);
                return bytesToRead;
            });
        }

        return mockChannel;
    }
}
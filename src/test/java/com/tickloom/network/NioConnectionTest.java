package com.tickloom.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.assertThrows;

class NioConnectionTest {

    
    @BeforeEach
    void setUp() {

    }

    @Test
    void readShouldPropagateIOException() throws IOException {
        SocketChannel socketChannel = new SocketChannelBuilder().thatIsClosed().build();
        SelectionKey selectionKey = new SelectionKeyBuilder().thatIsReadable().build();
        MessageCodec codec = new JsonMessageCodec();
        NioConnection nioConnection = new NioConnection(null, socketChannel, selectionKey, codec);
        assertThrows(IOException.class, () -> nioConnection.read());
    }
}
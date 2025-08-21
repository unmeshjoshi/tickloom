package com.tickloom.network;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class AcceptorTest {

    @Test
    void closesSocketWhenRegistrationFails() throws Exception {
        // Mock the server socket that owns the accept()
        ServerSocketChannel srv = mock(ServerSocketChannel.class);
        // Mock a fresh clientId SocketChannel returned by accept()
        SocketChannel client = mock(SocketChannel.class);

        // Pretend accept() returns the clientId socket
        when(srv.accept()).thenReturn(client);

        // Selector + SelectionKey mocks
        Selector selector = SelectorProvider.provider().openSelector();
        SelectionKey srvKey = mock(SelectionKey.class);
        when(srvKey.selector()).thenReturn(selector);

        // Make clientId.register(..) throw -> simulate "too many keys" or cancelled key
        when(client.register(any(Selector.class), anyInt()))
                .thenThrow(new ClosedChannelException());

        // Acceptor under test
        Acceptor acceptor = new Acceptor(/*unused NioNetwork instance */ null, srv, srvKey);

        assertThrows(ClosedChannelException.class, acceptor::accept);

        // verify cleanup
        InOrder order = inOrder(client);
        // ensure close() was invoked after configureBlocking(), before method exits
        order.verify(client).configureBlocking(false);
        order.verify(client).close();
        order.verifyNoMoreInteractions();
    }


    @Test
    void closesSocketWhenRegistrationFailsWithAnyException() throws Exception {
        // Mock the server socket that owns the accept()
        ServerSocketChannel srv = mock(ServerSocketChannel.class);
        // Mock a fresh clientId SocketChannel returned by accept()
        SocketChannel client = mock(SocketChannel.class);

        // Pretend accept() returns the clientId socket
        when(srv.accept()).thenReturn(client);

        // Selector + SelectionKey mocks
        Selector selector = SelectorProvider.provider().openSelector();
        SelectionKey srvKey = mock(SelectionKey.class);
        when(srvKey.selector()).thenReturn(selector);

        // Make clientId.register(..) throw -> simulate "too many keys" or cancelled key
        when(client.register(any(Selector.class), anyInt()))
                .thenThrow(new RuntimeException());

        // Acceptor under test
        Acceptor acceptor = new Acceptor(/*unused NioNetwork instance */ null, srv, srvKey);

        assertThrows(RuntimeException.class, acceptor::accept);

        // verify cleanup
        InOrder order = inOrder(client);
        // ensure close() was invoked after configureBlocking(), before method exits
        order.verify(client).configureBlocking(false);
        order.verify(client).close();
        order.verifyNoMoreInteractions();
    }
}
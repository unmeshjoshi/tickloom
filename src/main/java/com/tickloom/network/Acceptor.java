package com.tickloom.network;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * Accepts connections from a server socket channel and registers them with the selector
 * We can have multiple server sockets listening on different ports.e.g.
 * one for clients and one for servers. Having separate acceptors for each server socket
 * makes it easier to manage and maintain.
 */
public class Acceptor {
    private final NioNetwork network;
    private final ServerSocketChannel serverChannel;
    private final SelectionKey selectionKey;
    private MessageCodec codec;

    public Acceptor(NioNetwork network, ServerSocketChannel serverChannel, SelectionKey selectionKey) {
        this.network = network;
        this.serverChannel = serverChannel;
        this.selectionKey = selectionKey;
    }

    public void accept() throws IOException {
        SocketChannel acceptedChannel = serverChannel.accept();
        if (acceptedChannel == null) {
            // No pending connections to accept
            return;
        }
        acceptedChannel.configureBlocking(false);
        SelectionKey channelKey = acceptedChannel.register(selectionKey.selector(), SelectionKey.OP_READ);
        channelKey.attach(new NioConnection(network, acceptedChannel, channelKey, network.getCodec()));
    }
    
    public ServerSocketChannel getServerChannel() {
        return serverChannel;
    }
}

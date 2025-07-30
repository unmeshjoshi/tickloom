package com.tickloom.network;

import java.io.IOException;
import java.net.StandardSocketOptions;
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
        SocketChannel sc = serverChannel.accept();
        if (sc == null) return;                       // nothing to clean

        try {
            sc.configureBlocking(false);
            //The protocol sends lots of latency‑sensitive, small frames (heartbeats, RPC replies).
            //Waiting an extra RTT for coalescing adds 1–20ms latency—visible in request/response benchmarks.
            // Non‑blocking code already batches larger writes (gathering write), so we don’t need Nagle for throughput.
            sc.setOption(StandardSocketOptions.TCP_NODELAY, true);

            //Lets the OS send an idle‑time probe (zero‑byte segment) after the socket has been silent for a long period
            // (default 2h on Linux). If the probe isn’t ACKed, the connection is declared dead;
            //the next read/write throws IOException.
            sc.setOption(StandardSocketOptions.SO_KEEPALIVE, true);

            SelectionKey key = sc.register(selectionKey.selector(), SelectionKey.OP_READ);
            key.attach(new NioConnection(network, sc, key, network.getCodec()));
        } catch (IOException | RuntimeException e) {  // any failure after accept()
            // ensure fd is closed before propagating
            try { sc.close(); } catch (IOException ignored) { }
            throw e;                                  // bubble up to selector loop
        }
    }
    
    public ServerSocketChannel getServerChannel() {
        return serverChannel;
    }
}

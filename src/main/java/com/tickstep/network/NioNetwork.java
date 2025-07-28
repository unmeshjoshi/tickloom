package com.tickstep.network;

import com.tickstep.ProcessId;
import com.tickstep.config.NodeRegistry;
import com.tickstep.messaging.Message;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class NioNetwork implements Network {

    private final MessageCodec codec;
    private final NodeRegistry registry;
    private final Selector selector;
    final HashMap<ProcessId, NioConnection> connections = new HashMap<>();
    private NodeRegistry nodeRegistry;

    public NioNetwork(MessageCodec codec, NodeRegistry registry, Selector selector) {
        this.codec = codec;
        this.registry = registry;
        this.selector = selector;
    }

    private List<Acceptor> acceptors = new ArrayList<>();

    public void bind(InetAddressAndPort address) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.configureBlocking(false);
        int listenBacklog = 1024;
        serverChannel.bind(address.toInetSocketAddress(), listenBacklog);

        SelectionKey serverKey = serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        Acceptor acceptor = new Acceptor(this, serverChannel, serverKey);
        serverKey.attach(acceptor);

        acceptors.add(acceptor);
    }

    @Override
    public void tick() {
        try {
            int noOfAvailableKeys = selector.selectNow();
            if (noOfAvailableKeys <= 0) {
                return;
            }

            selector.selectedKeys().forEach(key -> {
                System.out.println("NIO: Processing key - acceptable: " + key.isAcceptable() + 
                                 ", connectable: " + key.isConnectable() + 
                                 ", readable: " + key.isReadable() + 
                                 ", writable: " + key.isWritable());
                
                if (key.isAcceptable()) {
                    Acceptor acceptor = (Acceptor) key.attachment();
                    try {
                        acceptor.accept();
                    } catch (IOException e) {
                        System.err.println(e.getMessage());
                    }
                } else if (key.isConnectable()) {
                    NioConnection connection = (NioConnection) key.attachment();
                    connection.handleConnect();
                }
                
                // Handle read/write events (can happen on same key as connectable)
                if (key.isReadable()) {
                    NioConnection connection = (NioConnection) key.attachment();
                    connection.read();
                }
                
                if (key.isWritable()) {
                    NioConnection connection = (NioConnection) key.attachment();
                    connection.write();
                }

            });
            
            // Clear the selected keys set so they can be selected again
            selector.selectedKeys().clear();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public void removeConnection(NioConnection nioConnection) {
        connections.remove(nioConnection.getSourceId());
    }

    public void handleMessage(Message message, NioConnection nioConnection) {
        NioConnection existingConnection = connections.get(message.source());
        if (existingConnection == null || !existingConnection.isConnected()) {
            System.out.println("Received a new message from unknown source: " + message.source() + " Adding to connections map ");
            connections.put(message.source(), nioConnection);
        }
        System.out.println("message = " + message);
    }

    @Override
    public void send(Message message) throws IOException {
        NioConnection nioConnection = getOrCreateOutboundChannel(message.destination(), false);
        nioConnection.send(message);
    }

    /**
     * Gets an existing outbound channel or creates a new one if needed.
     * Follows the One Connection per Direction principle by always using outbound connections.
     */
    private NioConnection getOrCreateOutboundChannel(ProcessId peerId, boolean waitTillConnected) throws IOException {
        NioConnection connection = connections.get(peerId);
        if (!isChannelUsable(connection)) {
            logCreatingNewChannel(connection);
            SocketChannel channel = createAndConfigureChannel(waitTillConnected);
            InetAddressAndPort address = registry.getInetAddress(peerId);
            SelectionKey key = establishConnection(selector, channel, address);
            NioConnection nioConnection = new NioConnection(this, channel, key, codec);
            System.out.println("NIO: Stored new channel in outboundConnections map");
            key.attach(nioConnection);
            connections.put(peerId, nioConnection);
            return nioConnection;
        }

        logChannelStatus(peerId, connection.getChannel());
        logUsingExistingChannel(connection.getChannel());
        
        // Ensure write interest is set if there are pending writes
        if (connection.hasPendingWrites()) {
            connection.ensureWriteInterest();
        }
        
        return connection;

    }

    private static SocketChannel createAndConfigureChannel(boolean waitTillConnected) throws IOException {
        SocketChannel channel = SocketChannel.open();
        // Configure blocking mode based on flag (true = blocking, false = non-blocking)
        channel.configureBlocking(waitTillConnected); // TODO: Blocking only needed in tests. Need to fix
        return channel;
    }

    private static SelectionKey establishConnection(Selector selector, SocketChannel channel, InetAddressAndPort address) throws IOException {
        boolean connected = channel.connect(address.toInetSocketAddress());
        if (!connected) {
            logConnectionInProgress();
            return channel.register(selector, SelectionKey.OP_CONNECT);
        } else {
            logImmediateConnection();
            channel.configureBlocking(false); //TODO: For tests only
            return channel.register(selector, SelectionKey.OP_READ);
        }
    }

    private static void logConnectionInProgress() {
        System.out.println("NIO: Connection in progress, registering for connect events");
    }

    private static void logImmediateConnection() {
        System.out.println("NIO: Immediate connection, registering for read events");
    }

    private void logChannelStatus(ProcessId address, SocketChannel channel) {
        System.out.println("NIO: getOrCreateOutboundChannel for " + address +
                " (existing channel=" + channel +
                ", connected=" + (channel != null ? channel.isConnected() : "null") +
                ", isOpen=" + (channel != null ? channel.isOpen() : "null") + ")");
    }

    private boolean isChannelUsable(NioConnection channel) {
        return channel != null && channel.getChannel().isOpen();
    }

    private void logUsingExistingChannel(SocketChannel channel) {
        System.out.println("NIO: Using existing channel (connected=" + channel.isConnected() + ")");
    }

    private void logCreatingNewChannel(NioConnection channel) {
        if (channel != null) {
            System.out.println("NIO: Existing channel not open, creating new one");
        } else {
            System.out.println("NIO: No existing channel, creating new one");
        }
    }

    public MessageCodec getCodec() {
        return codec;
    }

    public void close() throws IOException {
        // Close all connections
        for (NioConnection connection : connections.values()) {
            try {
                connection.getChannel().close();
            } catch (IOException e) {
                System.err.println("Error closing connection: " + e.getMessage());
            }
        }
        connections.clear();
        
        // Close all acceptors
        for (Acceptor acceptor : acceptors) {
            try {
                acceptor.getServerChannel().close();
            } catch (IOException e) {
                System.err.println("Error closing acceptor: " + e.getMessage());
            }
        }
        acceptors.clear();
        
        // Close selector
        if (selector != null && selector.isOpen()) {
            selector.close();
        }
    }
}

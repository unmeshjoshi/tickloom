package com.tickloom.network;

import com.tickloom.ProcessId;
import com.tickloom.config.ClusterTopology;
import com.tickloom.config.ProcessConfig;
import com.tickloom.messaging.Message;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * ──────────────────────────────────────────────────────────────────────────────
 *  NioConnection – transport policy notes
 * ──────────────────────────────────────────────────────────────────────────────
 *
 *  CURRENT (v1)
 *  ----------------------------------------------------------------------------
 *  • One TCP socket per peer, bidirectional.
 *  • Both client requests/responses and replica‐to‐replica messages share the
 *    same queue, buffer sizes, and back‑pressure thresholds.
 *  • Framing is a simple 9‑byte header (4+1+4) + payload.
 *
 *  WHY IT’S OK FOR NOW
 *  • Simpler state machine → faster to reason about and debug.
 *  • Fewer sockets → easier on ephemeral‑port exhaustion in small clusters.
 *  • Unit + integration tests exercise the full path without role branches.
 *
 *  ROADMAP (v2+)
 *  ----------------------------------------------------------------------------
 *  1. **Role‑specific pools**
 *     – `ClientConnection` vs `ReplicaConnection`.
 *     – Different queue limits (latency vs throughput).
 *
 *  2. **Directional replica links**
 *     – One outbound + one inbound socket per server pair to
 *       isolate send‐buffer stalls.
 *
 *  3. **QoS / priority lanes**
 *     – Stream‑id bucket 0‑99 reserved for control/heartbeats (handled first).
 *     – Bulk blobs (>1MB) on dedicated stream range or separate socket.
 *
 *  4. **Security profiles**
 *     – mTLS for clients; lighter shared‑secret TLS for intra‑cluster.
 *     – Optional compression on replica channels only.
 *
 *  5. **Selector sharding**
 *     – clientSelector (latency‑optimised)
 *     – clusterSelector (throughput‑optimised)
 *
 *  Migration path: handshake frame (type INIT) advertises `peerType`.  The
 *  acceptor promotes the provisional connection to the right subclass/pool
 *  without disrupting existing message flow.
 *
 *  Keep this comment up‑to‑date when transport policy evolves.
 * ──────────────────────────────────────────────────────────────────────────────
 */

public class NioNetwork extends Network {

    private final MessageCodec codec;
    private final ClusterTopology registry;
    private final Selector selector;
    final HashMap<ProcessId, NioConnection> connections = new HashMap<>();

    public NioNetwork(MessageCodec codec, ClusterTopology registry, Selector selector) {
        this.codec = codec;
        this.registry = registry;
        this.selector = selector;
    }

    private final List<Acceptor> acceptors = new ArrayList<>();

    public static NioNetwork create(ClusterTopology topo, MessageCodec messageCodec) throws IOException {
        Selector selector = Selector.open();
        return new NioNetwork(messageCodec, topo, selector);
    }

    /**
     * Binds a process to its configured address from the topology.
     * This method automatically looks up the process configuration and binds to the appropriate address.
     *
     * @param processId the process to bind
     * @throws IOException if binding fails
     * @throws IllegalArgumentException if process not found in topology
     */
    public void bind(ProcessId processId) throws IOException {
        InetAddressAndPort address = registry.getInetAddress(processId);
        if (address == null) {
            throw new IllegalArgumentException("Process " + processId + " not found in cluster topology");
        }
        bind(address);
    }

    public void bind(InetAddressAndPort address) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.configureBlocking(false);
        int listenBacklog = 1024; //TODO: make configurable
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
                try {
                    System.out.println("NIO: Processing key - acceptable: " + key.isAcceptable() + 
                                     ", connectable: " + key.isConnectable() + 
                                     ", readable: " + key.isReadable() + 
                                     ", writable: " + key.isWritable());
                    
                    if (key.isAcceptable()) {
                        Acceptor acceptor = (Acceptor) key.attachment();
                        try {
                            acceptor.accept();
                        } catch (IOException e) {
                            // No additional cleanup needed here—the acceptor already closed any
                            // half‑initialized SocketChannel.  Log and keep the loop running.
                            System.err.printf("[ACCEPTOR] %s%n", e.getMessage());

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
                } catch (IOException e) {
                    // Handle cancelled keys and other exceptions gracefully
                    System.err.println("NIO: Error processing key: " + e.getMessage());
                    NioConnection connection = (NioConnection) key.attachment();
                    connection.cleanupConnection();
                }
            });
            
            // Clear the selected keys set so they can be selected again
            selector.selectedKeys().clear();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public void removeConnection(NioConnection nioConnection) {
        // Remove by destination ID for outbound connections, or by source ID for inbound connections
        ProcessId destinationId = nioConnection.getDestinationId();
        ProcessId sourceId = nioConnection.getSourceId();
        
        if (destinationId != null && connections.containsKey(destinationId)) {
            connections.remove(destinationId);
            System.out.println("NIO: Removed outbound connection for destination: " + destinationId);
        } else if (sourceId != null && connections.containsKey(sourceId)) {
            connections.remove(sourceId);
            System.out.println("NIO: Removed inbound connection for source: " + sourceId);
        } else {
            // Fallback: remove by value (less efficient but handles edge cases)
            connections.entrySet().removeIf(entry -> entry.getValue() == nioConnection);
            System.out.println("NIO: Removed connection by value comparison");
        }
    }

    public void dispatch(Message message, NioConnection nioConnection) {
        NioConnection existingConnection = connections.get(message.source());
        if (existingConnection == null || !existingConnection.isConnected()) {
            System.out.println("Received a new message from unknown source: " + message.source() + " Adding to connections map ");
            System.out.println("Connection being added: " + nioConnection + ", channel: " + nioConnection.getChannel() + 
                              ", isOpen: " + nioConnection.getChannel().isOpen() + ", isConnected: " + nioConnection.getChannel().isConnected());
            connections.put(message.source(), nioConnection);
        }
        System.out.println("message = " + message);

        dispatchReceivedMessage(message);
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
        System.out.println("NIO: getOrCreateOutboundChannel for " + peerId + ", connection: " + connection + 
                          ", isChannelUsable: " + isChannelUsable(connection));
        if (!isChannelUsable(connection)) {
            // Check if this peer is in the registry (i.e., it's a server that is a part of the cluster topology)
            try {
                InetAddressAndPort address = registry.getInetAddress(peerId);
                // Peer is in registry, create outbound connection
                NioConnection nioConnection = createNewConnection(peerId, waitTillConnected, connection, address);
                NioConnection existingConnection = connections.put(peerId, nioConnection);

                if (existingConnection != null) {
                    System.out.println("NIO: Replaced existing connection for destination: " + peerId);
                    existingConnection.cleanupConnection();
                }

                return nioConnection;
            } catch (Exception e) {
                System.out.println("NIO: Cannot create outbound connection to Peer" + peerId );
                if (connection == null) {
                    // Peer is not in registry (i.e., it's a client), can only use existing connection
                    throw new IOException("No existing connection to client " + peerId);
                }
                // Connection exists but is not usable, throw exception
                throw new IOException(" Cannot create outbound connection to Peer " + peerId);
            }
        }

        logChannelStatus(peerId, connection.getChannel());
        logUsingExistingChannel(connection.getChannel());
        
        // Ensure write interest is set if there are pending writes
        if (connection.hasPendingWrites()) {
            connection.ensureWriteInterest();
        }
        
        return connection;

    }

    private NioConnection createNewConnection(ProcessId peerId, boolean waitTillConnected, NioConnection connection, InetAddressAndPort address) throws IOException {
        logCreatingNewChannel(connection);
        SocketChannel channel = createAndConfigureChannel(waitTillConnected);
        SelectionKey key = establishConnection(selector, channel, address);
        NioConnection nioConnection = new NioConnection(this, channel, key, codec);
        nioConnection.setDestinationId(peerId); // Set destination for outbound connections
        System.out.println("NIO: Stored new channel in outboundConnections map");
        key.attach(nioConnection);
        return nioConnection;
    }

    private static SocketChannel createAndConfigureChannel(boolean waitTillConnected) throws IOException {
        SocketChannel channel = SocketChannel.open();
        // Configure blocking mode based on flag (true = blocking, false = non-blocking)
        channel.configureBlocking(waitTillConnected); // TODO: Blocking only needed in tests. Need to fix
        return channel;
    }

    private static SelectionKey establishConnection(Selector selector, SocketChannel channel, InetAddressAndPort address) throws IOException {
        channel.configureBlocking(false);
        boolean connected = channel.connect(address.toInetSocketAddress());
        if (!connected) {
            logConnectionInProgress();
            return channel.register(selector, SelectionKey.OP_CONNECT);
        } else {
            logImmediateConnection();
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

    public int getConnectionCount() {
        return connections.size();
    }

    @Override
    public void close() throws IOException {
        closeAndClearConnections();
        closeAllAcceptors();
        closeSelector();
    }

    private void closeSelector() throws IOException {
        // Close selector
        if (selector != null && selector.isOpen()) {
            selector.close();
        }
    }

    private void closeAllAcceptors() {
        // Close all acceptors
        for (Acceptor acceptor : acceptors) {
            try {
                acceptor.getServerChannel().close();
            } catch (IOException e) {
                System.err.println("Error closing acceptor: " + e.getMessage());
            }
        }
        acceptors.clear();
    }

    private void closeAndClearConnections() {
        // Close all connections
        for (NioConnection connection : connections.values()) {
                connection.cleanupConnection();
        }

        connections.clear();
    }

    public int getNoOfConnections() {
        return connections.size();
    }
}

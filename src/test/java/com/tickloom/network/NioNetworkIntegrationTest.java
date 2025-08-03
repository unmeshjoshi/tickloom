package com.tickloom.network;

import com.tickloom.ProcessId;
import com.tickloom.config.ClusterTopology;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for NioNetwork that tests real client-server communication.
 * Tests connection establishment, message sending/receiving, and network lifecycle.
 */
class NioNetworkIntegrationTest {

    private static final String LOCALHOST = "127.0.0.1";
    
    private NioNetwork serverNetwork;
    private NioNetwork clientNetwork;
    private ClusterTopology serverRegistry;
    private MessageCodec codec;
    private Selector serverSelector;
    private Selector clientSelector;
    
    private ProcessId serverId;
    private ProcessId clientId;
    private InetAddressAndPort serverAddress;
    private InetAddressAndPort clientAddress;
    
    private List<Message> serverReceivedMessages;
    private List<Message> clientReceivedMessages;

    @BeforeEach
    void setUp() throws IOException {
        // Initialize components
        codec = new JsonMessageCodec();
        serverSelector = Selector.open();
        clientSelector = Selector.open();
        
        // Create process IDs
        serverId = ProcessId.of("server-1");
        clientId = ProcessId.of("client-1");
        
        // Find available ports
        int serverPort = findAvailablePort();
        int clientPort = findAvailablePort();
        
        serverAddress = InetAddressAndPort.from(LOCALHOST, serverPort);
        clientAddress = InetAddressAndPort.from(LOCALHOST, clientPort);
        
        // Create registries
        serverRegistry = createTestRegistry(serverId, serverAddress, clientId, clientAddress);

        // Create networks with custom message handling
        serverNetwork = new NioNetwork(codec, serverRegistry, serverSelector) {
            @Override
            public void handleMessage(Message message, NioConnection nioConnection) {
                super.handleMessage(message, nioConnection);
                serverReceivedMessages.add(message);
                try {
                    Message serverResponse = Message.of(
                            serverId, message.source(), PeerType.SERVER,
                            MessageType.of("TEST"),
                            ("Hello from server to peer: " + message.source()).getBytes() ,
                            message.correlationId()
                    );
                    send(serverResponse);
                } catch (IOException e) {
                    System.out.println("Error sending response for message " + message + ": = " + e.getMessage());
                }
            }
        };
        
        clientNetwork = new NioNetwork(codec, serverRegistry, clientSelector) {
            @Override
            public void handleMessage(Message message, NioConnection nioConnection) {
                clientReceivedMessages.add(message);
            }
        };
        
        // Initialize message tracking
        serverReceivedMessages = new ArrayList<>();
        clientReceivedMessages = new ArrayList<>();
    }

    @AfterEach
    void tearDown() throws IOException {
        closeNetworks(clientNetwork, serverNetwork);
        closeSelectors(clientSelector, serverSelector);
    }

    private void closeSelectors(Selector ... selectors) throws IOException {
        for (Selector selector : selectors) {
            selector.close();
        }
    }

    private void closeNetworks(NioNetwork... networks) throws IOException {
        for (NioNetwork network : networks) {
            network.close();
        }
    }

    @Test
    void shouldEstablishConnectionAndExchangeMessages() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);

        // Send message from client to server
        Message clientMessage = Message.of(
            clientId, serverId, PeerType.CLIENT, 
            MessageType.of("TEST"), 
            "Hello from client".getBytes(), 
            "msg-1"
        );
        
        System.out.println("Sending message: " + clientMessage);
        clientNetwork.send(clientMessage);
        
        // Process networks to handle connection and message
        System.out.println("Processing networks...");
        runUntil(() -> serverReceivedMessages.size() == 1);
        // Verify server received the message
        assertEquals(1, serverReceivedMessages.size());
        Message receivedMessage = serverReceivedMessages.get(0);
        assertEquals(clientId, receivedMessage.source());
        assertEquals(serverId, receivedMessage.destination());
        assertEquals("Hello from client", new String(receivedMessage.payload()));

        runUntil(() -> clientReceivedMessages.size() == 1);

        assertEquals(1, clientReceivedMessages.size());
        Message receivedResponse = clientReceivedMessages.get(0);
        assertEquals(serverId, receivedResponse.source());
        assertEquals(clientId, receivedResponse.destination());
        assertEquals("Hello from server to peer: " + clientId, new String(receivedResponse.payload()));
    }

    @Test
    void shouldHandleMultipleMessages() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);
        
        // Send multiple messages from client
        int messageCount = 100;
        serverReceivedMessages.clear();
        clientReceivedMessages.clear();
        
        for (int i = 0; i < messageCount; i++) {
            Message message = Message.of(
                clientId, serverId, PeerType.CLIENT,
                MessageType.of("MULTI"),
                ("Message " + i).getBytes(),
                "msg-" + i
            );
            clientNetwork.send(message);
        }
        
        // Wait for all messages to be received and responded to
        runUntil(() -> serverReceivedMessages.size() == messageCount);
        runUntil(() -> clientReceivedMessages.size() == messageCount);
        
        // Verify all messages were received
        assertEquals(messageCount, serverReceivedMessages.size());
        assertEquals(messageCount, clientReceivedMessages.size());
        
        // Verify message content
        for (int i = 0; i < messageCount; i++) {
            Message received = serverReceivedMessages.get(i);
            assertEquals("Message " + i, new String(received.payload()));
        }
    }

    @Test
    void shouldHandleLargeMessages() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);
        
        // Create a large message (10MB)
        byte[] largePayload = new byte[1024*1024*7];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }
        
        Message largeMessage = Message.of(
            clientId, serverId, PeerType.CLIENT,
            MessageType.of("LARGE"),
            largePayload,
            "large-msg"
        );
        
        serverReceivedMessages.clear();
        clientReceivedMessages.clear();
        
        clientNetwork.send(largeMessage);
        
        // Wait for message exchange
        runUntil(() -> serverReceivedMessages.size() == 1);
        runUntil(() -> clientReceivedMessages.size() == 1);
        
        // Verify large message was received correctly
        assertEquals(1, serverReceivedMessages.size());
        assertEquals(1, clientReceivedMessages.size());
        
        Message received = serverReceivedMessages.get(0);
        assertArrayEquals(largePayload, received.payload());
        assertEquals("LARGE", received.messageType().name());
    }

    @Test
    void shouldHandleConnectionReuse() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);
        
        // Send first message (establishes connection)
        Message message1 = Message.of(
            clientId, serverId, PeerType.CLIENT,
            MessageType.of("FIRST"),
            "First message".getBytes(),
            "msg-1"
        );
        
        serverReceivedMessages.clear();
        clientReceivedMessages.clear();
        
        clientNetwork.send(message1);
        
        // Wait for first message exchange
        runUntil(() -> serverReceivedMessages.size() == 1);
        runUntil(() -> clientReceivedMessages.size() == 1);
        

        // Send second message (should reuse connection)
        Message message2 = Message.of(
            clientId, serverId, PeerType.CLIENT,
            MessageType.of("SECOND"),
            "Second message".getBytes(),
            "msg-2"
        );
        
        clientNetwork.send(message2);
        
        // Wait for second message exchange
        runUntil(() -> serverReceivedMessages.size() == 2);
        runUntil(() -> clientReceivedMessages.size() == 2);
        
        // Verify both client and server have only one connection
        assertEquals(1, serverNetwork.connections.size());
        assertEquals(1, clientNetwork.connections.size());
        
        Message received = serverReceivedMessages.get(1);
        assertEquals("Second message", new String(received.payload()));
        assertEquals("SECOND", received.messageType().name());
    }

    @Test
    void shouldHandleConnectionFailures() throws Exception {
        // Don't start the server - this will cause connection failures
        
        // Try to connect to a non-existent server
        Message message = Message.of(
            clientId, serverId, PeerType.CLIENT,
            MessageType.of("FAILURE"),
            "Test message".getBytes(),
            "failure-test"
        );
        
        // This should fail gracefully
        clientNetwork.send(message);
        
        // Run for a short time to let the connection attempt fail
        runUntil(() -> {
            // Check if connection was cleaned up
            return clientNetwork.getConnectionCount() == 0;
        });
        
        // Verify connection was cleaned up
        assertEquals(0, clientNetwork.getConnectionCount());
    }

    @Test
    void shouldHandleClientDisconnection() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);
        
        // Establish connection
        Message message = Message.of(
            clientId, serverId, PeerType.CLIENT,
            MessageType.of("DISCONNECT"),
            "Test message".getBytes(),
            "disconnect-test"
        );
        
        serverReceivedMessages.clear();
        clientReceivedMessages.clear();
        
        clientNetwork.send(message);
        
        // Wait for message exchange
        runUntil(() -> serverReceivedMessages.size() > 0);
        
        // Verify connection exists
        assertTrue(serverNetwork.getConnectionCount() > 0);
        
        // Close client network (simulates client crash/disconnect)
        clientNetwork.close();
        
        // Run for a while to let server detect disconnection
        runUntil(() -> {
            // Server should detect disconnection and clean up
            return serverNetwork.getConnectionCount() == 0;
        });
        
        // Verify server cleaned up the connection
        assertEquals(0, serverNetwork.getConnectionCount());
    }

    @Test
    void shouldHandleConcurrentConnections() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);
        
        // Create multiple client networks
        int clientCount = 100;
        List<NioNetwork> clientNetworks = new ArrayList<>();
        List<ProcessId> clientIds = new ArrayList<>();

        Map<ProcessId, List<Message>> perClientMessages = new HashMap<>();
        for (int i = 0; i < clientCount; i++) {
            ProcessId clientId = ProcessId.of("concurrent-client-" + i);
            InetAddressAndPort clientAddress = InetAddressAndPort.from("127.0.0.1", 0);
            ClusterTopology clientRegistry = createTestRegistry(serverId, serverAddress, clientId, clientAddress);
            Selector clientSelector = Selector.open();
            
            NioNetwork clientNetwork = new NioNetwork(codec, clientRegistry, clientSelector) {
                @Override
                public void handleMessage(Message message, NioConnection nioConnection) {
                    perClientMessages
                            .computeIfAbsent(clientId, k -> new ArrayList<>())
                            .add(message);
                }
            };
            
            clientNetworks.add(clientNetwork);
            clientIds.add(clientId);
        }
        
        // Send messages from all clients simultaneously
        List<Message> messages = constructMessages(clientCount, clientIds);

        serverReceivedMessages.clear();

        sendMessageFromEachClient(clientCount, clientNetworks, messages);

        // Wait for all messages to be received
        //runRuntil ticks server network
        runUntilEveryClientGetsResponse(clientNetworks, clientCount, perClientMessages);

        // Verify all messages were received
        assertEquals(clientCount, serverReceivedMessages.size());
        
        // Verify server has connections for all clients
        assertEquals(clientCount, serverNetwork.getConnectionCount());

        assertThatEachClientGotOnlyOneResponse(perClientMessages);


        cleanupClientNetwork(clientNetworks);

        // Verify all connections were cleaned up
        assertEquals(0, serverNetwork.getConnectionCount());
    }

    private void cleanupClientNetwork(List<NioNetwork> clientNetworks) throws IOException {
        // Clean up client networks
        for (NioNetwork clientNetwork : clientNetworks) {
            clientNetwork.close();
        }

        // Wait for server to clean up all connections
        runUntil(() -> {
            serverNetwork.tick();
            return serverNetwork.getConnectionCount() == 0;
        });
    }

    private static void assertThatEachClientGotOnlyOneResponse(Map<ProcessId, List<Message>> perClientMessages) {
        for (ProcessId clientId : perClientMessages.keySet()) {
            List<Message> clientMessages = perClientMessages.get(clientId);
            assertEquals(1, clientMessages.size());
            assertTrue(new String(clientMessages.get(0).payload()).contains(clientId.toString())); //clientMessages.get(0).payload//each client should receive only one response.
        }
    }

    private void runUntilEveryClientGetsResponse(List<NioNetwork> clientNetworks, int clientCount, Map<ProcessId, List<Message>> perClientMessages) {
        runUntil(() -> {
            // Tick all client networks
            for (NioNetwork clientNetwork : clientNetworks) {
                try {
                    clientNetwork.tick();
                } catch (Exception e) {
                    // Ignore closed networks
                }
            }
            return serverReceivedMessages.size() == clientCount && perClientMessages.size() == clientCount;
        });
    }

    private static void sendMessageFromEachClient(int clientCount, List<NioNetwork> clientNetworks, List<Message> messages) throws IOException {
        // Send all messages
        for (int i = 0; i < clientCount; i++) {
            clientNetworks.get(i).send(messages.get(i));
        }
    }

    private List<Message> constructMessages(int clientCount, List<ProcessId> clientIds) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            Message message = Message.of(
                clientIds.get(i), serverId, PeerType.CLIENT,
                MessageType.of("CONCURRENT"),
                ("Message from client " + i).getBytes(),
                "concurrent-" + i
            );
            messages.add(message);
        }
        return messages;
    }

    @Test
    void shouldHandleServerRestart() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);
        
        // Establish initial connection
        Message message1 = Message.of(
            clientId, serverId, PeerType.CLIENT,
            MessageType.of("RESTART"),
            "Test message 1".getBytes(),
            "restart-test-1"
        );
        
        serverReceivedMessages.clear();
        clientReceivedMessages.clear();
        
        clientNetwork.send(message1);
        
        // Wait for initial message exchange
        runUntil(() -> serverReceivedMessages.size() > 0);
        runUntil(() -> clientReceivedMessages.size() > 0);
        
        // Verify initial connection exists and message was exchanged
        assertTrue(serverNetwork.getConnectionCount() > 0);
        assertEquals(1, serverReceivedMessages.size());
        assertEquals(1, clientReceivedMessages.size());

        // Restart server (close and create new instance)
        System.out.println("Restarting server...");
        serverNetwork.close();
        
        // Create a new server network instance with the same registry
        serverSelector = Selector.open();
        serverNetwork = new NioNetwork(codec, serverRegistry, serverSelector) {
            @Override
            public void handleMessage(Message message, NioConnection nioConnection) {
                super.handleMessage(message, nioConnection);
                serverReceivedMessages.add(message);
                // Auto-respond to client messages
                try {
                    Message response = Message.of(
                        serverId, message.source(), PeerType.SERVER,
                        MessageType.of("RESTART_RESPONSE"),
                        ("Hello from restarted server").getBytes(),
                        message.correlationId()
                    );
                    send(response);
                } catch (IOException e) {
                    System.err.println("Failed to send response: " + e.getMessage());
                }
            }
        };
        serverNetwork.bind(serverAddress);

        
        // Clear message counts for the restart phase
        serverReceivedMessages.clear();
        clientReceivedMessages.clear();
        
        // The client will detect the failed connection
        // We need to wait for the connection to be cleaned up first
        runUntil(() -> {
            // The client should detect the connection failure and clean up
            return clientNetwork.getConnectionCount() == 0;
        });
        
        // Now send a new message to the restarted server
        Message message2 = Message.of(
            clientId, serverId, PeerType.CLIENT,
            MessageType.of("RESTART2"),
            "Test message 2".getBytes(),
            "restart-test-2"
        );
        
        clientNetwork.send(message2);
        
        // Wait for the new message to be processed by the restarted server
        runUntil(() -> serverReceivedMessages.size() > 0);
        
        // The restarted server should receive the message and add the client to its connections map
        // Then it should be able to send a response using that connection
        runUntil(() -> clientReceivedMessages.size() > 0);
        
        // Verify that the restarted server received the new message
        assertEquals(1, serverReceivedMessages.size());
        Message newServerMessage = serverReceivedMessages.get(0);
        assertEquals("Test message 2", new String(newServerMessage.payload()));
        assertEquals("RESTART2", newServerMessage.messageType().name());
        
        // Verify that the client received response from the restarted server
        assertEquals(1, clientReceivedMessages.size());
        Message newClientMessage = clientReceivedMessages.get(0);
        assertEquals("Hello from restarted server", new String(newClientMessage.payload()));
        assertEquals("RESTART_RESPONSE", newClientMessage.messageType().name());
        
        // Verify that the restarted server has exactly one connection (the client)
        assertEquals(1, serverNetwork.getConnectionCount());
        
        // Verify that the client has exactly one connection (to the restarted server)
        assertEquals(1, clientNetwork.getConnectionCount());
        
        System.out.println("Server restart test completed successfully - new connection established and messages flowing");
    }

    @Test
    void shouldHandleBackpressure() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);
        
        // Create many client networks to test backpressure
        int clientCount = 20; // More than typical backlog
        List<NioNetwork> clientNetworks = new ArrayList<>();
        List<ProcessId> clientIds = new ArrayList<>();
        
        for (int i = 0; i < clientCount; i++) {
            ProcessId clientId = ProcessId.of("backpressure-client-" + i);
            InetAddressAndPort clientAddress = InetAddressAndPort.from("127.0.0.1", 0);
            ClusterTopology clientRegistry = createTestRegistry(serverId, serverAddress, clientId, clientAddress);
            Selector clientSelector = Selector.open();
            
            NioNetwork clientNetwork = new NioNetwork(codec, clientRegistry, clientSelector) {
                @Override
                public void handleMessage(Message message, NioConnection nioConnection) {
                    clientReceivedMessages.add(message);
                }
            };
            
            clientNetworks.add(clientNetwork);
            clientIds.add(clientId);
        }
        
        // Try to connect all clients simultaneously
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            Message message = Message.of(
                clientIds.get(i), serverId, PeerType.CLIENT,
                MessageType.of("BACKPRESSURE"),
                ("Message from client " + i).getBytes(),
                "backpressure-" + i
            );
            messages.add(message);
        }
        
        serverReceivedMessages.clear();
        
        // Send all messages
        sendMessageFromEachClient(clientCount, clientNetworks, messages);

        // Wait for some messages to be processed (not all may succeed due to backpressure)
        runUntil(() -> {
            // Tick all client networks
            for (NioNetwork clientNetwork : clientNetworks) {
                try {
                    clientNetwork.tick();
                } catch (Exception e) {
                    // Ignore closed networks
                }
            }
            return serverReceivedMessages.size() > 0;
        });
        
        // Verify at least some connections were established
        assertTrue(serverReceivedMessages.size() > 0);
        
        // Clean up
        for (NioNetwork clientNetwork : clientNetworks) {
            clientNetwork.close();
        }
        
        // Wait for cleanup
        runUntil(() -> {
            // Tick all client networks
            for (NioNetwork clientNetwork : clientNetworks) {
                try {
                    clientNetwork.tick();
                } catch (Exception e) {
                    // Ignore closed networks
                }
            }
            return serverNetwork.getConnectionCount() == 0;
        });
        assertEquals(0, serverNetwork.getConnectionCount());
    }

    private final int noOfTicks = 1000; // Shorter timeout to see what's happening
    private void runUntil(Supplier<Boolean> condition) {
        int tickCount = 0;
        while (!condition.get()) {
            try {
                serverNetwork.tick();
            } catch (Exception e) {
                // Server network might be closed, continue with client only
                System.out.println("Server network tick failed (likely closed): " + e.getMessage());
            }
            try {
                clientNetwork.tick();
            } catch (Exception e) {
                // Client network might be closed, continue with server only
                System.out.println("Client network tick failed (likely closed): " + e.getMessage());
            }
            tickCount++;
            
            if (tickCount % 100 == 0) {
                System.out.println("Tick " + tickCount + ": Server received: " + serverReceivedMessages.size() + 
                     ", Client received: " + clientReceivedMessages.size());
            }
            
            if (tickCount > noOfTicks) {
                fail("Timeout waiting for condition to be met. Server received: " + serverReceivedMessages.size() + 
                     ", Client received: " + clientReceivedMessages.size());
            }
        }
        System.out.println("Condition met after " + tickCount + " ticks");
    }


    private ClusterTopology createTestRegistry(ProcessId serverId, InetAddressAndPort serverAddress,
                                               ProcessId clientId, InetAddressAndPort clientAddress) {
        // Create a test config with only server configurations
        String yaml = "processConfigs:\n" +
                "  - processId: \"" + serverId.name() + "\"\n" +
                "    ip: \"" + serverAddress.address().getHostAddress() + "\"\n" +
                "    port: " + serverAddress.port() + "\n";
        
        System.out.println("Creating registry with YAML: " + yaml);
        com.tickloom.config.Config config = com.tickloom.config.Config.load(yaml);
        return new ClusterTopology(config);
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
} 
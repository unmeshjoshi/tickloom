package com.tickstep.network;

import com.tickstep.ProcessId;
import com.tickstep.config.NodeRegistry;
import com.tickstep.messaging.Message;
import com.tickstep.messaging.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for NioNetwork that tests real client-server communication.
 * Tests connection establishment, message sending/receiving, and network lifecycle.
 */
class NioNetworkIntegrationTest {

    private static final int TEST_TIMEOUT_SECONDS = 10;
    private static final String LOCALHOST = "127.0.0.1";
    
    private NioNetwork serverNetwork;
    private NioNetwork clientNetwork;
    private NodeRegistry serverRegistry;
    private NodeRegistry clientRegistry;
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
        clientRegistry = createTestRegistry(serverId, serverAddress, clientId, clientAddress);
        
        // Create networks with custom message handling
        serverNetwork = new NioNetwork(codec, serverRegistry, serverSelector) {
            @Override
            public void handleMessage(Message message, NioConnection nioConnection) {
                super.handleMessage(message, nioConnection);
                serverReceivedMessages.add(message);
                try {
                    Message serverResponse = Message.of(
                            serverId, message.source(), PeerType.REPLICA,
                            MessageType.of("TEST"),
                            "Hello from server".getBytes(),
                            message.correlationId()
                    );
                    send(serverResponse);
                } catch (IOException e) {
                    System.out.println("Error sending response for message " + message + ": = " + e.getMessage());
                }
            }
        };
        
        clientNetwork = new NioNetwork(codec, clientRegistry, clientSelector) {
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
        if (serverNetwork != null) {
            serverNetwork.close();
        }
        if (clientNetwork != null) {
            clientNetwork.close();
        }
        if (serverSelector != null && serverSelector.isOpen()) {
            serverSelector.close();
        }
        if (clientSelector != null && clientSelector.isOpen()) {
            clientSelector.close();
        }
    }

    @Test
//    @Timeout(TEST_TIMEOUT_SECONDS)
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
        assertEquals("Hello from server", new String(receivedResponse.payload()));
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    void shouldHandleMultipleMessages() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);
        
        // Send multiple messages from client
        int messageCount = 5;
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
    @Timeout(TEST_TIMEOUT_SECONDS)
    void shouldHandleLargeMessages() throws Exception {
        // Start server
        serverNetwork.bind(serverAddress);
        
        // Create a large message (1KB)
        byte[] largePayload = new byte[1024];
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
    @Timeout(TEST_TIMEOUT_SECONDS)
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
        
        // Reset for second message
        serverReceivedMessages.clear();
        clientReceivedMessages.clear();
        
        // Send second message (should reuse connection)
        Message message2 = Message.of(
            clientId, serverId, PeerType.CLIENT,
            MessageType.of("SECOND"),
            "Second message".getBytes(),
            "msg-2"
        );
        
        clientNetwork.send(message2);
        
        // Wait for second message exchange
        runUntil(() -> serverReceivedMessages.size() == 1);
        runUntil(() -> clientReceivedMessages.size() == 1);
        
        // Verify both messages were received
        assertEquals(1, serverReceivedMessages.size());
        assertEquals(1, clientReceivedMessages.size());
        
        Message received = serverReceivedMessages.get(0);
        assertEquals("Second message", new String(received.payload()));
        assertEquals("SECOND", received.messageType().name());
    }

    private int noOfTicks = 1000; // Shorter timeout to see what's happening
    private void runUntil(Supplier<Boolean> condition) {
        long startTime = System.currentTimeMillis();
        int tickCount = 0;
        while (!condition.get()) {
            serverNetwork.tick();
            clientNetwork.tick();
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


    private NodeRegistry createTestRegistry(ProcessId serverId, InetAddressAndPort serverAddress,
                                          ProcessId clientId, InetAddressAndPort clientAddress) {
        // Create a test config with the process configurations
        String yaml = "processConfigs:\n" +
                "  - processId: \"" + serverId.name() + "\"\n" +
                "    ip: \"" + serverAddress.address().getHostAddress() + "\"\n" +
                "    port: " + serverAddress.port() + "\n" +
                "  - processId: \"" + clientId.name() + "\"\n" +
                "    ip: \"" + clientAddress.address().getHostAddress() + "\"\n" +
                "    port: " + clientAddress.port();
        
        System.out.println("Creating registry with YAML: " + yaml);
        com.tickstep.config.Config config = com.tickstep.config.Config.load(yaml);
        return new NodeRegistry(config);
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
} 
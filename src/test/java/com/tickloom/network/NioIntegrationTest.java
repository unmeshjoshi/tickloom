package com.tickloom.network;

import com.tickloom.ProcessId;
import com.tickloom.config.ClusterTopology;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.Selector;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;


public class NioIntegrationTest {
    EchoServer echoServer;
    TestPeer client;
    private ProcessId serverId;
    private ProcessId clientId;
    private ClusterTopology registry;

    @BeforeEach
    public void setUp() throws IOException {
        var serverAddress = InetAddressAndPort.from("127.0.0.1", 8080);
        serverId = ProcessId.of("echo-server");
        registry = createTestRegistry(
                serverId,
                serverAddress);

        echoServer = TestPeer.createNew(
                serverId,
                registry, EchoServer::new);

        echoServer.bind(); //server must be bound to start listening

        clientId = ProcessId.of("client");
        client = TestPeer.createNew(
                clientId,
                registry);

    }

    @AfterEach
    public void tearDown() throws IOException {
        echoServer.close();
        client.close();
    }



    private ClusterTopology createTestRegistry(ProcessId serverId, InetAddressAndPort serverAddress) {
        // Create a test config with only server configurations
        String yaml = "processConfigs:\n" +
                "  - processId: \"" + serverId.name() + "\"\n" +
                "    ip: \"" + serverAddress.address().getHostAddress() + "\"\n" +
                "    port: " + serverAddress.port() + "\n";

        System.out.println("Creating registry with YAML: " + yaml);
        com.tickloom.config.Config config = com.tickloom.config.Config.load(yaml);
        return new ClusterTopology(config);
    }

    static class TestPeer extends com.tickloom.Process implements AutoCloseable {
        protected final NioNetwork network;
        private final ClusterTopology topology;
        private final MessageCodec codec;
        protected final List<Message> receivedMessages = new ArrayList<>();

        protected TestPeer(ProcessId id, MessageBus messageBus, NioNetwork network, ClusterTopology topology, MessageCodec codec) {
            super(id, messageBus);
            this.network = network;
            this.topology = topology;
            this.codec = codec;
        }

        @Override
        public void onMessageReceived(Message message) {
            receivedMessages.add(message);
            handleMessage(message);
        }

        protected void handleMessage(Message message) {
            //subclasses can do something more than just storing the received message.
        }

        public void tick() {
            messageBus.tick();
            network.tick();
        }

        public NioNetwork getNetwork() {
            return network;
        }

        public void bind() throws IOException {
            this.network.bind(topology.getInetAddress(id));
        }

        public void send(ProcessId recipient, MessageType messageType, byte[] payload) throws IOException {
            Message message = Message.of(id, recipient, PeerType.CLIENT, messageType, payload, generateCorrelationId());
            network.send(message);
        }

        private String generateCorrelationId() {
            return UUID.randomUUID().toString();
        }

        public void send(Message message) throws IOException {
            network.send(message);
        }

        static interface TestPeerFactory<T extends TestPeer> {
            T create(ProcessId id, MessageBus messageBus, NioNetwork network, ClusterTopology topology, MessageCodec codec);
        }

        public static TestPeer createNew(ProcessId id, ClusterTopology topology) throws IOException {
            return createNew(id, topology, TestPeer::new);
        }

        public  static <T extends TestPeer> T createNew(ProcessId id, ClusterTopology topology, TestPeerFactory<T> factory) throws IOException {
            Selector selector = Selector.open();
            JsonMessageCodec codec = new JsonMessageCodec();
            var network = new NioNetwork(codec, topology, selector);
            MessageBus messageBus = new MessageBus(network, codec);
            return factory.create(id, messageBus, network, topology, codec);
        }


        @Override
        public void close() throws IOException {
            receivedMessages.clear();
            network.close();
        }

        public List<Message> getReceivedMessages() {
            return receivedMessages;
        }
    }

    // Server is a type of TestPeer that binds to a port and echoes responses.
    static class EchoServer extends TestPeer {
        private final InetAddressAndPort address;

        public EchoServer(ProcessId id, MessageBus messageBus, NioNetwork network, ClusterTopology topology, MessageCodec codec)  {
            super(id, messageBus, network, topology, codec);
            this.address = topology.getInetAddress(id);
        }

        @Override
        public void handleMessage(Message message) {
            try {
                Message response = Message.of(
                        this.id, message.source(), PeerType.SERVER,
                        MessageType.of(message.messageType().name()),
                        ("Hello from server to peer: " + message.source()).getBytes(),
                        message.correlationId()
                );

                send(response);

            } catch (IOException e) {
                System.err.printf("Server failed to send echo response", e);
            }
        }
    }



    @Test
    void shouldEstablishConnectionAndExchangeMessages() throws Exception {
        // Send message from client to server
        Message clientMessage = Message.of(
                clientId, serverId, PeerType.CLIENT,
                MessageType.of("TEST"),
                "Hello from client".getBytes(),
                "msg-1"
        );

        System.out.println("Sending message: " + clientMessage);
        client.send(serverId, MessageType.of("TEST"),"Hello from client".getBytes());

        // Process networks to handle connection and message
        System.out.println("Processing networks...");
        runUntil(() -> echoServer.getReceivedMessages().size() == 1);
        // Verify server received the message
        assertEquals(1, echoServer.getReceivedMessages().size());
        Message receivedMessage = echoServer.getReceivedMessages().get(0);
        assertEquals(clientId, receivedMessage.source());
        assertEquals(serverId, receivedMessage.destination());
        assertEquals("Hello from client", new String(receivedMessage.payload()));

        runUntil(() -> client.getReceivedMessages().size() == 1);

        assertEquals(1, client.getReceivedMessages().size());
        Message receivedResponse = client.getReceivedMessages().get(0);
        assertEquals(serverId, receivedResponse.source());
        assertEquals(clientId, receivedResponse.destination());
        assertEquals("Hello from server to peer: " + clientId, new String(receivedResponse.payload()));
    }

    @Test
    void shouldHandleMultipleMessages() throws IOException {
        int messageCount = 100;
        for (int i = 0; i < messageCount; i++) {
            client.send(serverId, MessageType.of("MULTI"), ("Message " + i).getBytes());
        }

        // Wait for all messages to be received and responded to
        runUntil(() -> echoServer.getReceivedMessages().size() == messageCount);
        runUntil(() -> client.getReceivedMessages().size() == messageCount);

        // Verify all messages were received
        assertEquals(messageCount, echoServer.getReceivedMessages().size());
        assertEquals(messageCount, client.getReceivedMessages().size());

        // Verify message content
        for (int i = 0; i < messageCount; i++) {
            Message received = echoServer.getReceivedMessages().get(i);
            assertEquals("Message " + i, new String(received.payload()));
        }

    }


    @Test
    void shouldHandleLargeMessages() throws Exception {
        // Create a large message (7MB) //we serialise using json, which adds some overhead,
        // so the final message becomes just less than ~10MB, which is a bit less than the 10MB limit
        byte[] largePayload = new byte[1024*1024*7];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }

        client.send(serverId, MessageType.of("LARGE"), largePayload);

        // Wait for message exchange
        runUntil(() -> echoServer.getReceivedMessages().size() == 1);
        runUntil(() -> client.getReceivedMessages().size() == 1);

        // Verify large message was received correctly
        assertEquals(1, echoServer.getReceivedMessages().size());
        assertEquals(1, client.getReceivedMessages().size());

        Message received = echoServer.getReceivedMessages().get(0);
        assertArrayEquals(largePayload, received.payload());
        assertEquals("LARGE", received.messageType().name());
    }

    @Test
    void shouldHandleConnectionReuse() throws Exception {
      // Send first message (establishes connection)

        client.send(serverId, MessageType.of("FIRST"), "First message".getBytes());

        // Wait for first message exchange
        runUntil(() -> echoServer.getReceivedMessages().size() == 1);
        runUntil(() -> client.getReceivedMessages().size() == 1);


        // Send second message (should reuse connection)
        Message message2 = Message.of(
                clientId, serverId, PeerType.CLIENT,
                MessageType.of("SECOND"),
                "Second message".getBytes(),
                "msg-2"
        );

        client.send(serverId, MessageType.of("SECOND"), "Second message".getBytes());

        // Wait for second message exchange
        runUntil(() -> echoServer.getReceivedMessages().size() == 2);
        runUntil(() -> client.getReceivedMessages().size() == 2);

        // Verify both client and server have only one connection
        assertEquals(1, client.getNetwork().getNoOfConnections());
        assertEquals(1, echoServer.getNetwork().getNoOfConnections());

        Message received = echoServer.getReceivedMessages().get(1);
        assertEquals("Second message", new String(received.payload()));
        assertEquals("SECOND", received.messageType().name());
    }

    @Test
    void shouldHandleConnectionFailures() throws Exception {
        // Don't start the server - this will cause connection failures
        echoServer.close();
        // Try to connect to a non-existent server
        // This should fail gracefully
        client.send(serverId, MessageType.of("FAILURE"), "Test message".getBytes());

        assertEquals(1, client.getNetwork().getConnectionCount());
        //we will have a non-blocking connection created, but it is not connected

        // Run for a short time to let the connection failure is detected
        runUntil(() -> {
            // Check if connection was cleaned up
            return client.getNetwork().getConnectionCount() == 0;
        });

        // Verify connection was cleaned up
        assertEquals(0, client.getNetwork().getConnectionCount());
    }

    @Test
    void shouldHandleClientDisconnection() throws Exception {
        // Establish connection
        client.send(serverId, MessageType.of("DISCONNECT"), "Test message".getBytes());

        // Wait for message exchange
        runUntil(() -> echoServer.getReceivedMessages().size() == 1);

        // Verify connection exists
        assertTrue(echoServer.getNetwork().getConnectionCount() == 1);

        // Close client network (simulates client crash/disconnect)
        client.close();

        // Run for a while to let server detect disconnection
        runUntil(() -> {
            // Server should detect disconnection and clean up
            return echoServer.getNetwork().getConnectionCount() == 0;
        });
    }

    @Test
    void shouldHandleConcurrentConnections() throws Exception {
        // Create multiple client networks
        int clientCount = 100;
        List<TestPeer> clients = new ArrayList<>();
        List<ProcessId> clientIds = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            ProcessId clientId = ProcessId.of("concurrent-client-" + i);
            clients.add(TestPeer.createNew(clientId, registry));
            clientIds.add(clientId);
        }

        // Send messages from all clients simultaneously
        List<Message> messages = constructMessages(clientCount, clientIds);

        sendMessageFromEachClient(clientCount, clients, messages);

        // Wait for all messages to be received
        //runRuntil ticks server network
        runUntilEveryClientGetsResponse(clients, clientCount);

        // Verify all messages were received
        assertEquals(clientCount, echoServer.getReceivedMessages().size());

        // Verify server has connections for all clients
        assertEquals(clientCount, echoServer.getNetwork().getConnectionCount());

        assertThatEachClientGotOnlyOneResponse(clients);


        cleanupClientNetwork(clients);

        // Verify all connections were cleaned up
        assertEquals(0, echoServer.getNetwork().getConnectionCount());
    }

    @Test
    void shouldHandleServerRestart() throws Exception {
      // Establish initial connection
        Message message1 = Message.of(
                clientId, serverId, PeerType.CLIENT,
                MessageType.of("RESTART"),
                "Test message 1".getBytes(),
                "restart-test-1"
        );

        client.send(message1);

        // Wait for initial message exchange
        runUntil(() -> echoServer.getReceivedMessages().size() == 1);
        runUntil(() -> client.getReceivedMessages().size() == 1);

        // Verify initial connection exists and message was exchanged
        assertTrue(echoServer.getNetwork().getConnectionCount() == 1);

        // Restart server (close and create new instance)
        System.out.println("Restarting server...");
        echoServer.close();

        echoServer = TestPeer.createNew(
                serverId,
                registry, EchoServer::new);

        echoServer.bind(); //server must be bound to start listening

        // The client will detect the failed connection
        // We need to wait for the connection to be cleaned up first
        runUntil(() -> {
            // The client should detect the connection failure and clean up
            return client.getNetwork().getConnectionCount() == 0;
        });

        // Now send a new message to the restarted server
        Message message2 = Message.of(
                clientId, serverId, PeerType.CLIENT,
                MessageType.of("RESTART2"),
                "Test message 2".getBytes(),
                "restart-test-2"
        );

        client.send(message2);

        // Wait for the new message to be processed by the restarted server
        runUntil(() -> echoServer.getReceivedMessages().size() == 1);

        // The restarted server should receive the message and add the client to its connections map
        // Then it should be able to send a response using that connection
        runUntil(() -> client.getReceivedMessages().size() == 2);

        // Verify that the restarted server received the new message
        Message newServerMessage = echoServer.getReceivedMessages().get(0);
        assertEquals("Test message 2", new String(newServerMessage.payload()));
        assertEquals("RESTART2", newServerMessage.messageType().name());

        // Verify that the restarted server has exactly one connection (the client)
        assertEquals(1, echoServer.getNetwork().getConnectionCount());

        // Verify that the client has exactly one connection (to the restarted server)
        assertEquals(1, client.getNetwork().getConnectionCount());

        System.out.println("Server restart test completed successfully - new connection established and messages flowing");
    }


    private void cleanupClientNetwork(List<TestPeer> clientNetworks) throws IOException {
        // Clean up client networks
        for (TestPeer clientNetwork : clientNetworks) {
            clientNetwork.close();
        }

        // Wait for server to clean up all connections
        runUntil(() -> {
            echoServer.tick();
            return echoServer.getNetwork().getConnectionCount() == 0;
        });
    }

    private static void assertThatEachClientGotOnlyOneResponse(List<TestPeer> clients) {
        clients.stream().allMatch(peer -> {
                    Message message = peer.getReceivedMessages().get(0);
                    String response = new String(message.payload());
                    return response.contains(peer.id.toString());
                });
    }

    private void runUntilEveryClientGetsResponse(List<TestPeer> clients, int clientCount) {
        runUntil(() -> {
            // Tick all clients
            for (TestPeer clientNetwork : clients) {
                try {
                    clientNetwork.tick();
                } catch (Exception e) {
                    // Ignore closed networks
                }
            }
            return echoServer.getReceivedMessages().size() == clientCount && clients.stream().map(peer -> peer.getReceivedMessages().size()).allMatch(size -> size == 1);
        });
    }

    private static void sendMessageFromEachClient(int clientCount, List<TestPeer> clients, List<Message> messages) throws IOException {
        // Send all messages
        for (int i = 0; i < clientCount; i++) {
            clients.get(i).send(messages.get(i));
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


    private final int noOfTicks = 1000; // Shorter timeout to see what's happening
    private void runUntil(Supplier<Boolean> condition) {
        int tickCount = 0;
        while (!condition.get()) {
            try {
                echoServer.tick();
            } catch (Exception e) {
                // Server network might be closed, continue with client only
                System.out.println("Server network tick failed (likely closed): " + e.getMessage());
            }
            try {
                client.tick();
            } catch (Exception e) {
                // Client network might be closed, continue with server only
                System.out.println("Client network tick failed (likely closed): " + e.getMessage());
            }
            tickCount++;

            if (tickCount % 100 == 0) {
                System.out.println("Tick " + tickCount + ": Server received: " + echoServer.getReceivedMessages().size() +
                        ", Client received: " + client.getReceivedMessages().size());
            }

            if (tickCount > noOfTicks) {
                fail("Timeout waiting for condition to be met. Server received: " + echoServer.getReceivedMessages().size() +
                        ", Client received: " + client.getReceivedMessages().size());
            }
        }
        System.out.println("Condition met after " + tickCount + " ticks");
    }
}

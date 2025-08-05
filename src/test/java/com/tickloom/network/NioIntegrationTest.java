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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;


public class NioIntegrationTest {
    EchoServer echoServer;
    TestPeer client;
    private ProcessId serverId;
    private ProcessId clientId;

    @BeforeEach
    public void setUp() throws IOException {
        var serverAddress = InetAddressAndPort.from("127.0.0.1", 8080);
        serverId = ProcessId.of("echo-server");
        var registry = createTestRegistry(
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

        public void sendTo(ProcessId recipient, MessageType messageType, byte[] payload) throws IOException {
            Message message = Message.of(id, recipient, PeerType.CLIENT, messageType, payload, generateCorrelationId());
            network.send(message);
        }

        private String generateCorrelationId() {
            return UUID.randomUUID().toString();
        }

        public void sendTo(Message message) throws IOException {
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

                sendTo(response);

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
        client.sendTo(serverId, MessageType.of("TEST"),"Hello from client".getBytes());

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
            client.sendTo(serverId, MessageType.of("MULTI"), ("Message " + i).getBytes());
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

        client.sendTo(serverId, MessageType.of("LARGE"), largePayload);

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

        client.sendTo(serverId, MessageType.of("FIRST"), "First message".getBytes());

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

        client.sendTo(serverId, MessageType.of("SECOND"), "Second message".getBytes());

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
        client.sendTo(serverId, MessageType.of("FAILURE"), "Test message".getBytes());

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

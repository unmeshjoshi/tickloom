package com.tickloom.network.integration;

import com.tickloom.ProcessId;
import com.tickloom.Tickable;
import com.tickloom.config.ClusterTopology;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.NioNetwork;
import com.tickloom.network.PeerType;

import java.io.IOException;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class TestPeer extends com.tickloom.Process implements Tickable, AutoCloseable {
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

    public static <T extends TestPeer> T createNew(ProcessId id, ClusterTopology topology, TestPeerFactory<T> factory) throws IOException {
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

package com.tickloom.network.integration;

import com.tickloom.ProcessId;
import com.tickloom.config.ClusterTopology;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.InetAddressAndPort;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.NioNetwork;
import com.tickloom.network.PeerType;

import java.io.IOException;

// Server is a type of TestPeer that binds to a port and echoes responses.
class EchoServer extends TestPeer {
    private final InetAddressAndPort address;

    public EchoServer(ProcessId id, MessageBus messageBus, NioNetwork network, ClusterTopology topology, MessageCodec codec) {
        super(id, messageBus, network, topology, codec);
        this.address = topology.getInetAddress(id);
    }

    @Override
    public void onMessageReceived(Message message) {
        super.onMessageReceived(message);
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

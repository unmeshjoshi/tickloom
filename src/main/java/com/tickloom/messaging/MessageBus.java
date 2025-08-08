package com.tickloom.messaging;

import com.tickloom.Process;
import com.tickloom.ProcessId;
import com.tickloom.Tickable;
import com.tickloom.network.MessageDispatcher;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.Network;
import com.tickloom.network.PeerType;
import com.tickloom.util.Utils;


import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessageBus implements MessageDispatcher, Tickable, AutoCloseable {

    protected final Network network;
    protected final MessageCodec messageCodec;

    private final Map<ProcessId, com.tickloom.Process> processMessageHandlers;

    /**
     * Creates a MessageBus with the given network and codec dependencies.
     *
     * @param network      the underlying network for message transmission
     * @param messageCodec the codec for message encoding/decoding
     * @throws IllegalArgumentException if either parameter is null
     */
    public MessageBus(Network network, MessageCodec messageCodec) {
        if (network == null) {
            throw new IllegalArgumentException("Network cannot be null");
        }
        if (messageCodec == null) {
            throw new IllegalArgumentException("MessageCodec cannot be null");
        }

        this.network = network;
        this.messageCodec = messageCodec;
        this.processMessageHandlers = new HashMap<>();
        network.registerMessageDispatcher(this);
    }

    /**
     * Sends a message through the underlying network.
     *
     * @param message the message to send
     * @throws IllegalArgumentException if message is null
     */
    public void sendMessage(Message message) throws IOException {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }

        if (isSelfMessage(message)) {
            Process self = processMessageHandlers.get(message.destination());
            self.receiveMessage(message);
            return;
        }

        network.send(message);
    }

    private static boolean isSelfMessage(Message message) {
        return message.source().equals(message.destination());
    }

    public void register(com.tickloom.Process process) {
        processMessageHandlers.put(process.id, process);
    }


    /**
     * Callback method that receives messages from the network.
     * This is called by the network when messages are available.
     * The MessageBus is responsible for routing messages to the correct process.
     */
    @Override
    public void onMessage(Message message) {
        String correlationId = message.correlationId();
        ProcessId destination = message.destination();

        System.out.println("MessageBus: Routing message " + message.messageType() + " from " + message.source() +
                " to " + destination + " (correlationId=" + correlationId + ")");

        com.tickloom.Process p = processMessageHandlers.get(destination);
        if (p != null) {
            System.out.println("MessageBus: Delivering to address handler (" + destination + ")");
            p.receiveMessage(message);
            return;
        }

        // Unroutable message (log but don't crash)
        System.out.println("MessageBus: No handler found for message " + message.messageType() +
                " (correlationId=" + correlationId + ", destination=" + destination + ")");
    }

    /**
     * This method implements the reactive Service Layer tick() pattern.
     * <p>
     * Note: This method does NOT tick the network - that is handled by the centralized
     * SimulationDriver to maintain proper tick orchestration. This method only processes
     * messages that were delivered in previous ticks.
     */
    public void tick() {
    }

    /**
     * Broadcasts a message from the source to all recipients in the list.
     * The sender (source) will not receive the message - only the other recipients.
     *
     * @param source      the source address sending the broadcast
     * @param recipients  the list of addresses to send the message to
     * @param messageType the type of message to broadcast
     * @param payload     the message payload
     */
    public void broadcast(ProcessId source, PeerType sourcePeerType, List<ProcessId> recipients,
                          MessageType messageType, byte[] payload) throws IOException {
        for (ProcessId recipient : recipients) {
            if (!recipient.equals(source)) {  // Don't send to self
                String correlationId = generateCorrelationId();
                Message message = Message.of(source, recipient, sourcePeerType, messageType, payload, correlationId);
                sendMessage(message);
            }
        }
    }


    /**
     * Generates a unique correlation ID for message tracking.
     */
    protected String generateCorrelationId() {
        return Utils.generateCorrelationId("msg");
    }

    //Visibility for testing
    public Map<ProcessId, com.tickloom.Process> getHandlers() {
        return Collections.unmodifiableMap(processMessageHandlers);
    }

    @Override
    public void close() throws Exception {

    }
}
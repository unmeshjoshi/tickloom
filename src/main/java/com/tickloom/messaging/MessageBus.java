package com.tickloom.messaging;

import com.tickloom.ProcessId;
import com.tickloom.network.MessageDispatcher;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.Network;
import com.tickloom.network.PeerType;


import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessageBus implements MessageDispatcher {

    protected final Network network;
    protected final MessageCodec messageCodec;

    private final Map<ProcessId, MessageHandler> processMessageHandlers;
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

        network.send(message);
    }

    public void registerHandler(ProcessId processId, MessageHandler handler) {
        if (processId == null) {
            throw new IllegalArgumentException("Process ID cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        processMessageHandlers.put(processId, handler);
    }


    /**
     * Callback method that receives messages from the network.
     * This is called by the network when messages are available.
     * <p>
     * Routing Priority:
     * 1. Check correlation ID first (client response pattern)
     * 2. Fall back to address routing (server request pattern)
     * 3. Log unroutable messages (no crash)
     */
    @Override
    public void onMessage(Message message) {
        String correlationId = message.correlationId();
        ProcessId destination = message.destination();

        System.out.println("MessageBus: Routing message " + message.messageType() + " from " + message.source() +
                " to " + destination + " (correlationId=" + correlationId + ")");

          MessageHandler addressHandler = processMessageHandlers.get(destination);
            if (addressHandler != null) {
                System.out.println("MessageBus: Delivering to address handler (" + destination + ")");
                addressHandler.onMessageReceived(message);
                return;
            }

            // PRIORITY 3: Unroutable message (log but don't crash)
        System.out.println("MessageBus: No handler found for message " + message.messageType() +
                " (correlationId=" + correlationId + ", destination=" + destination + ")");
    }

    /**
     * Routes received messages to registered handlers.
     * This method implements the reactive Service Layer tick() pattern.
     * <p>
     * Note: This method does NOT tick the network - that is handled by the centralized
     * SimulationDriver to maintain proper tick orchestration. This method only processes
     * messages that were delivered in previous ticks.
     */
    public void tick() {
        // Route messages to registered handlers (now handled by onMessage callback)
        // The network is ticked separately by SimulationDriver to maintain
        // centralized tick orchestration and deterministic ordering
        routeMessagesToHandlers();
    }

    /**
     * Routes messages to their respective handlers based on correlation ID or destination address.
     * This method is now called by the network callback instead of polling.
     */
    protected void routeMessagesToHandlers() {
        // This method is now handled by the onMessage callback
        // The network will call onMessage when messages are available
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
                Message message = Message.of(source, recipient, sourcePeerType,messageType, payload, correlationId);
                sendMessage(message);
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong correlationIdCounter = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Generates a unique correlation ID for message tracking.
     */
    protected String generateCorrelationId() {
        return "msg-" + System.currentTimeMillis() + "-" + correlationIdCounter.incrementAndGet();
    }
} 
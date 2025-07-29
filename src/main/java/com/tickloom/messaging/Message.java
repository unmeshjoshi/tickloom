package com.tickloom.messaging;

import com.tickloom.ProcessId;
import com.tickloom.network.PeerType;


/**
 * Represents a message in the distributed system.
 */
public record Message(
        ProcessId source,
        ProcessId destination,
        PeerType peerType,
        MessageType messageType,
        byte[] payload,
        String correlationId
) {
    /**
     * Creates a new Message with the specified parameters.
     * 
     * @param source the source process ID
     * @param destination the destination process ID
     * @param messageType the type of message
     * @param payload the message payload
     * @param correlationId the correlation ID for tracking
     */
    public Message {
        // Validate parameters
        if (source == null) {
            throw new IllegalArgumentException("Source cannot be null");
        }
        if (destination == null) {
            throw new IllegalArgumentException("Destination cannot be null");
        }
        if (peerType == null) {
            throw new IllegalArgumentException("Peer type cannot be null");
        }
        if (messageType == null) {
            throw new IllegalArgumentException("Message type cannot be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }
    }

    
    /**
     * Creates a message with correlation ID.
     * 
     * @param source the source process ID
     * @param destination the destination process ID
     * @param messageType the type of message
     * @param payload the message payload
     * @param correlationId the correlation ID
     * @return a new Message instance
     */
    public static Message of(ProcessId source, ProcessId destination, PeerType peerType, MessageType messageType, byte[] payload, String correlationId) {
        return new Message(source, destination, peerType,messageType, payload, correlationId);
    }
} 
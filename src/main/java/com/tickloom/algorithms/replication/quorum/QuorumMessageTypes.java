package com.tickloom.algorithms.replication.quorum;

import com.tickloom.messaging.MessageType;

/**
 * Message types specific to quorum-based replication.
 */
public final class QuorumMessageTypes {
    
    // Client request message types
    public static final MessageType CLIENT_GET_REQUEST = new MessageType("CLIENT_GET_REQUEST");
    public static final MessageType CLIENT_SET_REQUEST = new MessageType("CLIENT_SET_REQUEST");
    public static final MessageType CLIENT_GET_RESPONSE = new MessageType("CLIENT_GET_RESPONSE");
    public static final MessageType CLIENT_SET_RESPONSE = new MessageType("CLIENT_SET_RESPONSE");

    // Internal replica communication message types
    public static final MessageType INTERNAL_GET_REQUEST = new MessageType("INTERNAL_GET_REQUEST");
    public static final MessageType INTERNAL_SET_REQUEST = new MessageType("INTERNAL_SET_REQUEST");
    public static final MessageType INTERNAL_GET_RESPONSE = new MessageType("INTERNAL_GET_RESPONSE");
    public static final MessageType INTERNAL_SET_RESPONSE = new MessageType("INTERNAL_SET_RESPONSE");

    private QuorumMessageTypes() {
        // Utility class - prevent instantiation
    }
}

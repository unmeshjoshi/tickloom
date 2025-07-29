package com.tickstep.network;

/**
 * Constants for frame format.
 * Centralized location for all frame-related constants.
 */
public class FrameConstants {
    public static final int HEADER_SIZE = 9; // streamId (4) + frameType (1) + payloadLength (4)
    public static final int MAX_PAYLOAD_SIZE = 10_000_000; // 10MB max
    
    private FrameConstants() {
        // Utility class, prevent instantiation
    }
} 
package com.tickstep.network;

import com.tickstep.messaging.Message;

/**
 * Callback interface for push-based message delivery from Network implementations.
 * 
 * This interface enables the Network layer to proactively deliver messages to registered
 * handlers during its tick() cycle, eliminating the need for polling-based receive() calls.
 * 
 * Usage:
 * <pre>
 * network.registerMessageHandler(new MessageCallback() {
 *     public void onMessage(Message message, MessageContext context) {
 *         // Handle the message immediately
 *         routeToHandler(message, context);
 *     }
 * });
 * </pre>
 */
public interface MessageCallback {
    
    /**
     * Called by the Network implementation when a message is ready for delivery.
     * 
     * This method is invoked during Network.tick() when messages have been processed
     * and are ready for delivery to application layers.
     * 
     * @param message The message that has been received and is ready for processing
     */
    void onMessage(Message message);
} 
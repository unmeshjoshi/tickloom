package com.tickstep.network;

import com.tickstep.messaging.Message;

/**
 * Interface for components that can receive and handle messages.
 * Processes like Replicas, Workers, and Clients can register themselves
 */
public interface MessageHandler {

    /**
     * Called when a message is delivered to this handler.
     * @param message the received message
     */
    void onMessageReceived(Message message);
}
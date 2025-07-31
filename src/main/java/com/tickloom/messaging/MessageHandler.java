package com.tickloom.messaging;

/**
 * Interface for components that can receive and handle messages.
 */
public interface MessageHandler {

    /**
     * Called when a message is delivered to this handler.
     * @param message the received message
     */
    void onMessageReceived(Message message);
}
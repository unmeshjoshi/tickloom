package com.tickloom.network;

import com.tickloom.ProcessId;
import com.tickloom.messaging.Message;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A class that Processes can use to send Messages.
 * It allows registering a Handler for specific Processes
 * and routes the messages to the correct Handler.
 */
public class MessageBus implements MessageCallback {
    Map<ProcessId, MessageHandler> handlers = new HashMap();
    Network network;
    public void registerHandler(ProcessId processId, MessageHandler handler) {
        handlers.put(processId, handler);
    }

    @Override
    public void onMessage(Message message) {

    }

    public void send(Message message) {
        try {
            network.send(message);
        } catch (IOException e) {
            throw new RuntimeException("Failed to send message", e);
        }
    }



}
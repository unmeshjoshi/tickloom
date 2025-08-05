package com.tickloom.network;

import com.tickloom.messaging.Message;

import java.io.IOException;

public abstract class Network {
    MessageDispatcher dispatcher;

    public void registerMessageDispatcher(MessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    protected void dispatchReceivedMessage(Message message) {
        // Call registered callback for push-based delivery
        if (dispatcher != null) {
            dispatcher.onMessage(message);
        }
    }

    public abstract void tick();
    public abstract void send(Message message) throws IOException;
}

package com.tickloom;

import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.MessageHandler;

/**
 * A logical entity that is endpoint for the messages
 * */
public abstract class Process implements Tickable, MessageHandler, AutoCloseable {
    public final ProcessId id;
    public final MessageBus messageBus;

    public Process(ProcessId id, MessageBus messageBus) {
        this.messageBus = messageBus;
        this.id = id;
        messageBus.registerHandler(id, this);
    }

    @Override
    public abstract void onMessageReceived(Message message);

    @Override
    public abstract void tick();

    @Override
    public void close() throws Exception {
    }
}

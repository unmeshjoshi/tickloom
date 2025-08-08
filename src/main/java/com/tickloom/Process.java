package com.tickloom;

import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.PeerType;
import com.tickloom.util.Clock;
import com.tickloom.util.Utils;

/**
 * A logical entity that is endpoint for the messages
 * Has all the common utility methods.
 * */
public abstract class Process implements Tickable, MessageHandler, AutoCloseable {
    public final ProcessId id;
    public final MessageBus messageBus;
    protected final MessageCodec messageCodec;
    protected final RequestWaitingList<String, Object> waitingList;
    protected final int timeoutTicks;

    protected final Clock clock;

    public Process(ProcessId id, MessageBus messageBus, MessageCodec messageCodec, int timeoutTicks, Clock clock) {
        this.messageBus = messageBus;
        this.id = id;
        this.clock = clock;
        this.messageCodec = messageCodec;
        this.timeoutTicks = timeoutTicks;
        this.waitingList = new RequestWaitingList<>(timeoutTicks);
        messageBus.registerHandler(id, this);
    }

    @Override
    public abstract void onMessageReceived(Message message);

    @Override
    public final void tick() {
        waitingList.tick();
        onTick();
    };


    /**
     * Hook method for subclasses to perform additional tick processing.
     * This is called after common timeout handling.
     */
    protected void onTick() {
        // Subclasses can override to add specific tick processing
    }


    @Override
    public void close() throws Exception {
    }

    protected final Message createResponseMessage(Message receivedMessage, Object responsePayload, MessageType responseType) {
        Message responseMessage = createMessage(receivedMessage.source(), receivedMessage.correlationId(), responsePayload, responseType);
        return responseMessage;
    }

    protected final Message createMessage(ProcessId to, String internalCorrelationId, Object payload, MessageType messageType) {
        return Message.of(
                id, to, PeerType.SERVER, messageType,
                serializePayload(payload), internalCorrelationId
        );
    }


    /**
     * Serializes a payload object to bytes.
     */
    protected byte[] serializePayload(Object payload) {
        return messageCodec.encode(payload);
    }

    /**
     * Deserializes bytes to a payload object.
     */
    protected <T> T deserializePayload(byte[] data, Class<T> type) {
        return messageCodec.decode(data, type);
    }

}

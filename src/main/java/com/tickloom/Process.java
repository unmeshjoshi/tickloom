package com.tickloom;

import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.PeerType;
import com.tickloom.util.Clock;
import com.tickloom.util.IdGen;

import java.util.HashMap;
import java.util.Map;

/**
 * A logical entity that is endpoint for the messages
 * Has all the common utility methods.
 */
public abstract class Process implements Tickable, AutoCloseable {
    public final ProcessId id;
    public final MessageBus messageBus;
    protected final MessageCodec messageCodec;
    protected final RequestWaitingList<String, Object> waitingList;
    protected final int timeoutTicks;

    protected final Clock clock;
    protected final IdGen idGen;

    public Process(ProcessParams processParams) {
        this.messageBus = processParams.messageBus();
        this.id = processParams.id();
        this.clock = processParams.clock();
        this.messageCodec = processParams.messageCodec();
        this.timeoutTicks = processParams.timeoutTicks();
        this.waitingList = new RequestWaitingList<>(processParams.timeoutTicks());
        this.idGen = processParams.idGenerator();
        processParams.messageBus().register(this);
        initialiseMessageHandlers();
    }

    public final void receiveMessage(Message message) {
        onMessageReceived(message);
        MessageType messageType = message.messageType();
        Handler handler = getHandler(messageType);
        if (handler == null) {
            System.err.println("No handler found for message " + messageType);
            return;
        }
        handler.handle(message);

    }

    //TODO: Probably remove this hook.
    protected void onMessageReceived(Message message) {
        //hook for subclasses to perform additional preprocessing
    }

    @Override
    public final void tick() {
        waitingList.tick();
        onTick();
    }

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

    protected interface Handler {
        void handle(Message message);
    }

    protected Map<MessageType, Handler> handlers = new HashMap<>();

    private void initialiseMessageHandlers() {
        this.handlers.putAll(initialiseHandlers());
    }

    protected abstract Map<MessageType, Handler> initialiseHandlers();

    protected Handler getHandler(MessageType messageType) {
        return handlers.get(messageType);
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

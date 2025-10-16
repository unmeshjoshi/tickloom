package com.tickloom;

import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.PeerType;
import com.tickloom.storage.Storage;
import com.tickloom.storage.VersionedValue;
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
    protected final Storage storage;
    
    // Add initialization state
    protected volatile boolean isInitialised = false;

    public Process(ProcessParams processParams) {
        this.messageBus = processParams.messageBus();
        this.id = processParams.id();
        this.clock = processParams.clock();
        this.messageCodec = processParams.messageCodec();
        this.timeoutTicks = processParams.timeoutTicks();
        this.waitingList = new RequestWaitingList<>(processParams.timeoutTicks());
        this.idGen = processParams.idGenerator();
        this.storage = processParams.storage();
        processParams.messageBus().register(this);
        initialiseMessageHandlers();
        
        // Call initialization
        initialise();
    }

    public final void receiveMessage(Message message) {
        onMessageReceived(message);
        MessageType messageType = message.messageType();
        Handler handler = getHandler(messageType);
        if (handler == null) {
            System.err.println("No handler found for message " + messageType);
            return;
        }
        
        // Check if process is initialized before handling message
        if (!isInitialised) {
            System.err.println(id + ": Received message " + messageType + " but process not initialized yet");
            handleUninitializedMessage(message);
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

    // ========== INITIALIZATION SYSTEM ==========

    /**
     * Non-abstract initialization method that ensures proper initialization flow.
     * Calls the abstract onInit method and automatically marks as initialized when complete.
     */
    protected final void initialise() {

        ListenableFuture<?> initFuture = onInit();
        initFuture.handle((result, error) -> {
            if (error == null) {
                markInitialised();
                return;
            }
            System.err.println(id + ": Initialization failed: " + error.getMessage());
            error.printStackTrace();
            // Don't mark as initialized if startup fails
        });
    }

    /**
     * Method for subclasses to implement their initialization logic.
     * Should return a ListenableFuture that completes when initialization is done.
     * The Process class will automatically call markInitialised() when this future completes.
     * 
     * Default implementation is a no-op that completes immediately.
     * Subclasses can override this method if they need initialization.
     */
    protected ListenableFuture<?> onInit() {
        // Default no-op implementation - subclasses can override if needed
        ListenableFuture<Void> initFuture = new ListenableFuture<>();
        initFuture.complete(null);
        return initFuture;
    }

    /**
     * Check if the process is initialized.
     */
    public boolean isInitialised() {
        return isInitialised;
    }

    /**
     * Mark the process as initialized.
     * Should only be called by Process.initialise().
     */
    protected void markInitialised() {
        this.isInitialised = true;
        System.out.println(id + ": Process initialized successfully");
    }

    /**
     * Hook method for handling messages when process is not initialized.
     * Default implementation logs and ignores, but subclasses can override.
     */
    protected void handleUninitializedMessage(Message message) {
        System.err.println(id + ": Ignoring message " + message.messageType() + " - process not initialized");
    }

    // ========== PERSISTENCE METHODS ==========

    /**
     * Persist state with automatic serialization.
     */
    protected <T> ListenableFuture<Boolean> persist(String key, T stateObject) {
        byte[] keyBytes = key.getBytes();
        byte[] serializedState = messageCodec.encode(stateObject);
        return storage.set(keyBytes, serializedState);
    }
    
    /**
     * Persist state with success and failure handlers.
     */
    protected <T> void persist(String key, T stateObject, Runnable onSuccess, Runnable onFailure) {
        ListenableFuture<Boolean> persistFuture = persist(key, stateObject);
        
        persistFuture.handle((success, error) -> {
            if (error != null) {
                if (onFailure != null) {
                    onFailure.run();
                }
            } else if (success) {
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }
        });
    }
    
    /**
     * Persist state with only success handler (failure is logged).
     */
    protected <T> void persist(String key, T stateObject, Runnable onSuccess) {
        persist(key, stateObject, onSuccess, () -> {
            System.err.println(id + ": Failed to persist state for key: " + key);
        });
    }
    
    /**
     * Load persisted state with automatic deserialization.
     */
    protected <T> ListenableFuture<T> load(String key, Class<T> stateClass) {
        byte[] keyBytes = key.getBytes();
        ListenableFuture<byte[]> loadFuture = storage.get(keyBytes);
        
        ListenableFuture<T> resultFuture = new ListenableFuture<>();
        loadFuture.handle((loadedValue, error) -> {
            if (error != null) {
                resultFuture.fail(error);
            } else if (loadedValue == null) {
                resultFuture.complete(null);
            } else {
                try {
                    T result = messageCodec.decode(loadedValue, stateClass);
                    resultFuture.complete(result);
                } catch (Exception e) {
                    resultFuture.fail(e);
                }
            }
        });
        
        return resultFuture;
    }

}

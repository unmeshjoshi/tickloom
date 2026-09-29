package com.tickloom;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.*;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.PeerType;
import com.tickloom.storage.Storage;
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
    
    // Lifecycle state
    protected ProcessState state = ProcessState.CREATED;

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
        
    }

    public final void receiveMessage(Message message) {
        if (state == ProcessState.STOPPED) {
            return;
        }
        onMessageReceived(message);
        MessageType messageType = message.messageType();
        Handler handler = getHandler(messageType);
        if (handler == null) {
            System.err.println("No handler found for message " + messageType);
            return;
        }
        
        // Check if process is running before handling message
        if (state != ProcessState.RUNNING) {
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
        if (state == ProcessState.STOPPED) {
            return;
        }
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
        stop();
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

    public Storage getStorage() {
        return storage;
    }

    public ProcessState getState() {
        return state;
    }

    public boolean isInitialised() {
        return state == ProcessState.RUNNING;
    }

    /**
     * Mark the process as initialized.
     * Should only be called by Process.start().
     */
    protected void markInitialised() {
        this.state = ProcessState.RUNNING;
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
    protected <T> TickCompletableFuture<Boolean> persist(String key, T stateObject) {
        byte[] keyBytes = key.getBytes();
        byte[] serializedState = messageCodec.encode(stateObject);
        return storage.put(keyBytes, serializedState);
    }
    
    /**
     * Persist state with success and failure handlers.
     */
    protected <T> void persist(String key, T stateObject, Runnable onSuccess, Runnable onFailure) {
        TickCompletableFuture<Boolean> persistFuture = persist(key, stateObject);

        persistFuture.whenComplete((success, error) -> {
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
    protected <T> TickCompletableFuture<T> load(String key, Class<T> stateClass) {
        byte[] keyBytes = key.getBytes();
        TickCompletableFuture<byte[]> loadFuture = storage.get(keyBytes);
        
        TickCompletableFuture<T> resultFuture = new TickCompletableFuture<>();
        loadFuture.whenComplete((loadedValue, error) -> {
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

    public void start() {
        if (state == ProcessState.RUNNING || state == ProcessState.STARTING) {
            return;
        }
        this.state = ProcessState.STARTING;
        try {
            TickCompletableFuture<?> startFuture = onStart();
            startFuture.whenComplete((result, error) -> {
                if (state != ProcessState.STARTING) {
                    return; // stopped while starting
                }
                if (error != null) {
                    this.state = ProcessState.STOPPED;
                    System.err.println(id + ": Startup failed: " + error.getMessage());
                    error.printStackTrace();
                } else {
                    markInitialised();
                }
            });
        } catch (Throwable t) {
            this.state = ProcessState.STOPPED;
            System.err.println(id + ": Startup failed: " + t.getMessage());
            t.printStackTrace();
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        }
    }

    /**
     * Stops the process from participating in the cluster (halts ticks,
     * drops incoming messages, and invokes {@link #onStop()}).
     */
    public void stop() {
        if (state == ProcessState.STOPPED) {
            return;
        }
        this.state = ProcessState.STOPPED;
        onStop();
    }

    /**
     * Hook method for subclasses to perform cleanup when stopped.
     */
    protected void onStop() {
        // Subclasses can override to add specific stop processing
    }

    public boolean isStopped() {
        return state == ProcessState.STOPPED;
    }

    public boolean isRunning() {
        return state == ProcessState.RUNNING;
    }

    /**
     * Startup hook that runs <b>after</b> the process is fully constructed.
     *
     * <p>This avoids the trap described in <i>Effective Java</i> (3rd ed.), Item 19:
     * <i>"Design and document for inheritance or else prohibit it"</i> — <q>constructors must not
     * invoke overridable methods</q>. Invoking an overridable method from the {@code Process}
     * constructor would execute before subclass field initialisers and subclass constructor
     * bodies have run. Any subclass fields accessed during that time would be observed as
     * {@code null} or zero, and any assignments made would be silently overwritten when the
     * subclass field initialisers finally run.
     *
     * <p>Called once per process from {@link #start()} (e.g., via {@code ProcessFactory#createAndStart})
     * after the object is fully constructed. The returned future completes when startup is done;
     * the process is marked initialised then. The default implementation completes immediately.
     */
    protected TickCompletableFuture<?> onStart() {
        return TickCompletableFuture.completed(true);
    }
}

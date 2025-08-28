package com.tickloom.storage;

import com.tickloom.future.ListenableFuture;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Simulated storage implementation that provides asynchronous key-value operations
 * with configurable delays and fault injection for testing distributed systems.
 */
public class SimulatedStorage implements Storage {
    
    private final Random random;
    private final int defaultDelayTicks;
    private final double defaultFailureRate;
    
    // Internal state
    private final Map<BytesKey, VersionedValue> dataStore = new HashMap<>();
    private final PriorityQueue<PendingOperation> pendingOperations = new PriorityQueue<>();
    
    // Internal counter for operation timing (TigerBeetle pattern)
    private long currentTick = 0;
    
    /**
     * Creates a SimulatedStorage with no delays and no failures.
     * 
     * @param random seeded random generator for deterministic behavior
     */
    public SimulatedStorage(Random random) {
        this(random, 0, 0.0);
    }
    
    /**
     * Creates a SimulatedStorage with configurable behavior.
     * 
     * @param random seeded random generator for deterministic behavior
     * @param delayTicks number of ticks to delay operations (0 = immediate)
     * @param failureRate probability [0.0-1.0] that an operation will fail
     */
    public SimulatedStorage(Random random, int delayTicks, double failureRate) {
        if (random == null) {
            throw new IllegalArgumentException("Random cannot be null");
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Delay ticks cannot be negative");
        }
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException("Failure rate must be between 0.0 and 1.0");
        }
        
        this.random = random;
        this.defaultDelayTicks = delayTicks;
        this.defaultFailureRate = failureRate;
    }
    
    @Override
    public ListenableFuture<VersionedValue> get(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        
        ListenableFuture<VersionedValue> future = new ListenableFuture<>();
        BytesKey bytesKey = new BytesKey(key);
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new GetOperation(bytesKey, future, completionTick));
        
        return future;
    }
    
    @Override
    public ListenableFuture<Boolean> set(byte[] key, VersionedValue value) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        
        ListenableFuture<Boolean> future = new ListenableFuture<>();
        BytesKey bytesKey = new BytesKey(key);
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new SetOperation(bytesKey, value, future, completionTick));
        
        return future;
    }
    
    @Override
    public void tick() {
        currentTick++;
        
        // Process all operations that are due
        while (!pendingOperations.isEmpty() && 
               pendingOperations.peek().completionTick <= currentTick) {
            
            PendingOperation operation = pendingOperations.poll();
            processOperation(operation);
        }
    }
    
    private void processOperation(PendingOperation operation) {
        if (shouldSimulateFailure()) {
            operation.fail(new RuntimeException("Simulated storage failure"));
        } else {
            operation.execute(dataStore);
        }
    }
    
    private boolean shouldSimulateFailure() {
        return defaultFailureRate > 0.0 && random.nextDouble() < defaultFailureRate;
    }
    
    // Internal classes for pending operations
    
    private abstract static class PendingOperation implements Comparable<PendingOperation> {
        protected final BytesKey key;
        protected final long completionTick;
        
        protected PendingOperation(BytesKey key, long completionTick) {
            this.key = key;
            this.completionTick = completionTick;
        }
        
        @Override
        public int compareTo(PendingOperation other) {
            return Long.compare(this.completionTick, other.completionTick);
        }
        
        abstract void execute(Map<BytesKey, VersionedValue> dataStore);
        abstract void fail(RuntimeException exception);
    }
    
    private static class GetOperation extends PendingOperation {
        private final ListenableFuture<VersionedValue> future;
        
        GetOperation(BytesKey key, ListenableFuture<VersionedValue> future, long completionTick) {
            super(key, completionTick);
            this.future = future;
        }
        
        @Override
        void execute(Map<BytesKey, VersionedValue> dataStore) {
            VersionedValue value = dataStore.get(key);
            String keyStr = new String(key.bytes());
            String valueStr = value != null ? new String(value.value()) : "null";
            System.out.println("SimulatedStorage: GET operation - key: " + keyStr + ", value: " + valueStr);
            future.complete(value); // null if not found
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
    
    private static class SetOperation extends PendingOperation {
        private final VersionedValue value;
        private final ListenableFuture<Boolean> future;
        
        SetOperation(BytesKey key, VersionedValue value, 
                    ListenableFuture<Boolean> future, long completionTick) {
            super(key, completionTick);
            this.value = value;
            this.future = future;
        }
        
        @Override
        void execute(Map<BytesKey, VersionedValue> dataStore) {
            VersionedValue versionedValue = dataStore.get(key);
            if (versionedValue == null || versionedValue.timestamp() < value.timestamp()) {
                String keyStr = new String(key.bytes());
                String valueStr = new String(value.value());
                System.out.println("SimulatedStorage: SET operation - key: " + keyStr + ", value: " + valueStr +
                        ", timestamp: " + value.timestamp());
                dataStore.put(key, value);

            }
            future.complete(true);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
} 
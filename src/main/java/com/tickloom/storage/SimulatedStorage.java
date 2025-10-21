package com.tickloom.storage;

import com.tickloom.future.ListenableFuture;

import java.util.*;

/**
 * Simulated storage implementation that provides asynchronous key-value operations
 * with configurable delays and fault injection for testing distributed systems.
 * 
 * This implementation uses NavigableMap (TreeMap) for ordered key storage, which enables
 * efficient range queries and prefix operations. All operations are processed in
 * deterministic order during the tick() method.
 * 
 * Key features:
 * - Ordered key storage using NavigableMap
 * - Deterministic operation processing during tick()
 * - Configurable operation delays and failure rates
 * - Support for batch operations, range queries, and prefix operations
 * - Simple byte[] key-value storage without versioning
 */
public class SimulatedStorage implements Storage {
    
    private final Random random;
    private final int defaultDelayTicks;
    private final double defaultFailureRate;
    
    // Internal state
    private final NavigableMap<byte[], byte[]> dataStore = new TreeMap<>(Arrays::compare);
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
    
    // ========== Basic Operations ==========
    
    @Override
    public ListenableFuture<byte[]> get(byte[] key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        
        ListenableFuture<byte[]> future = new ListenableFuture<>();
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new GetOperation(key, future, completionTick));
        
        return future;
    }
    
    @Override
    public ListenableFuture<Boolean> put(byte[] key, byte[] value) {
        return put(key, value, WriteOptions.DEFAULT);
    }
    
    @Override
    public ListenableFuture<Boolean> put(byte[] key, byte[] value, WriteOptions options) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("WriteOptions cannot be null");
        }
        
        ListenableFuture<Boolean> future = new ListenableFuture<>();
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new SetOperation(key, value, future, completionTick, options));
        
        return future;
    }
    
    // ========== Advanced Operations ==========
    
    @Override
    public ListenableFuture<Boolean> put(WriteBatch writeBatch) {
        return put(writeBatch, WriteOptions.DEFAULT);
    }
    
    @Override
    public ListenableFuture<Boolean> put(WriteBatch writeBatch, WriteOptions options) {
        if (writeBatch == null) {
            throw new IllegalArgumentException("WriteBatch cannot be null");
        }
        if (writeBatch.isEmpty()) {
            throw new IllegalArgumentException("WriteBatch cannot be empty");
        }
        if (options == null) {
            throw new IllegalArgumentException("WriteOptions cannot be null");
        }
        
        ListenableFuture<Boolean> future = new ListenableFuture<>();
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new BatchWriteOperation(writeBatch, future, completionTick, options));
        
        return future;
    }
    
    @Override
    public ListenableFuture<Map<byte[], byte[]>> readRange(byte[] startKey, byte[] endKey) {
        if (startKey == null) {
            throw new IllegalArgumentException("Start key cannot be null");
        }
        if (endKey == null) {
            throw new IllegalArgumentException("End key cannot be null");
        }
        
        ListenableFuture<Map<byte[], byte[]>> future = new ListenableFuture<>();
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new RangeOperation(startKey, endKey, future, completionTick));
        
        return future;
    }
    
    @Override
    public ListenableFuture<byte[]> lowerKey(byte[] keyUpperBound) {
        ListenableFuture<byte[]> future = new ListenableFuture<>();
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new LastKeyOperation(keyUpperBound, future, completionTick));
        
        return future;
    }
    
    @Override
    public ListenableFuture<Void> sync() {
        ListenableFuture<Void> future = new ListenableFuture<>();
        
        long completionTick = currentTick + defaultDelayTicks;
        pendingOperations.offer(new SyncOperation(future, completionTick));
        
        return future;
    }
    
    @Override
    public void tick() {
        currentTick++;
        
        // Process all operations that are due, maintaining order
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
            try {
                operation.execute(dataStore);
            } catch (Exception e) {
                operation.fail(new RuntimeException("Storage operation failed", e));
            }
        }
    }
    
    private boolean shouldSimulateFailure() {
        return defaultFailureRate > 0.0 && random.nextDouble() < defaultFailureRate;
    }
    
    // ========== Internal Operation Classes ==========
    
    private abstract static class PendingOperation implements Comparable<PendingOperation> {
        protected final long completionTick;
        
        protected PendingOperation(long completionTick) {
            this.completionTick = completionTick;
        }
        
        @Override
        public int compareTo(PendingOperation other) {
            return Long.compare(this.completionTick, other.completionTick);
        }
        
        abstract void execute(NavigableMap<byte[], byte[]> dataStore);
        abstract void fail(RuntimeException exception);
    }
    
    private static class GetOperation extends PendingOperation {
        private final byte[] key;
        private final ListenableFuture<byte[]> future;
        
        GetOperation(byte[] key, ListenableFuture<byte[]> future, long completionTick) {
            super(completionTick);
            this.key = key;
            this.future = future;
        }
        
        @Override
        void execute(NavigableMap<byte[], byte[]> dataStore) {
            byte[] value = dataStore.get(key);
            
            String keyStr = new String(key);
            String valueStr = value != null ? new String(value) : "null";
            System.out.println("SimulatedStorage: GET operation - key: " + keyStr + ", value: " + valueStr);
            
            future.complete(value);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
    
    private static class SetOperation extends PendingOperation {
        private final byte[] key;
        private final byte[] value;
        private final ListenableFuture<Boolean> future;
        
        SetOperation(byte[] key, byte[] value, ListenableFuture<Boolean> future, 
                    long completionTick, WriteOptions options) {
            super(completionTick);
            this.key = key;
            this.value = value;
            this.future = future;
        }
        
        @Override
        void execute(NavigableMap<byte[], byte[]> dataStore) {
            dataStore.put(key, value);
            
            String keyStr = new String(key);
            String valueStr = new String(value);
            System.out.println("SimulatedStorage: SET operation - key: " + keyStr + 
                             ", value: " + valueStr + ", timestamp: " + completionTick);
            
            future.complete(true);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
    
    private static class BatchWriteOperation extends PendingOperation {
        private final WriteBatch writeBatch;
        private final ListenableFuture<Boolean> future;
        
        BatchWriteOperation(WriteBatch writeBatch, ListenableFuture<Boolean> future, 
                      long completionTick, WriteOptions options) {
            super(completionTick);
            this.writeBatch = writeBatch;
            this.future = future;
        }
        
        @Override
        void execute(NavigableMap<byte[], byte[]> dataStore) {
            try {
                // Process all key-value pairs in the batch
                Map<byte[], byte[]> operations = writeBatch.getOperations();
                for (Map.Entry<byte[], byte[]> entry : operations.entrySet()) {
                    byte[] key = entry.getKey();
                    byte[] value = entry.getValue();
                    
                    if (value == null) {
                        // Delete operation
                        dataStore.remove(key);
                    } else {
                        // Put operation
                        dataStore.put(key, value);
                    }
                }
                
                System.out.println("SimulatedStorage: BATCH operation completed with " + writeBatch.size() + " operations");
                future.complete(true);
            } catch (Exception e) {
                future.fail(new RuntimeException("Batch operation failed", e));
            }
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
    
    private static class RangeOperation extends PendingOperation {
        private final byte[] startKey;
        private final byte[] endKey;
        private final ListenableFuture<Map<byte[], byte[]>> future;
        
        RangeOperation(byte[] startKey, byte[] endKey, ListenableFuture<Map<byte[], byte[]>> future, 
                     long completionTick) {
            super(completionTick);
            this.startKey = startKey;
            this.endKey = endKey;
            this.future = future;
        }
        
        @Override
        void execute(NavigableMap<byte[], byte[]> dataStore) {
            Map<byte[], byte[]> result = new HashMap<>();
            
            // Get submap for the range
            NavigableMap<byte[], byte[]> subMap = dataStore.subMap(startKey, true, endKey, false);
            
            for (Map.Entry<byte[], byte[]> entry : subMap.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
            
            System.out.println("SimulatedStorage: RANGE operation - start: " + new String(startKey) + 
                             ", end: " + new String(endKey) + ", count: " + result.size());
            
            future.complete(result);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
    
    private static class KeysWithPrefixOperation extends PendingOperation {
        private final byte[] prefix;
        private final ListenableFuture<List<byte[]>> future;
        
        KeysWithPrefixOperation(byte[] prefix, ListenableFuture<List<byte[]>> future, long completionTick) {
            super(completionTick);
            this.prefix = prefix;
            this.future = future;
        }
        
        @Override
        void execute(NavigableMap<byte[], byte[]> dataStore) {
            List<byte[]> result = new ArrayList<>();

            // Find the first key with the prefix
            byte[] firstKey = dataStore.ceilingKey(prefix);
            if (firstKey != null && startsWith(firstKey, prefix)) {
                // Get all keys with this prefix
                NavigableMap<byte[], byte[]> subMap = dataStore.tailMap(firstKey, true);
                
                for (byte[] key : subMap.keySet()) {
                    if (startsWith(key, prefix)) {
                        result.add(key);
                    } else {
                        // No more keys with this prefix
                        break;
                    }
                }
            }
            
            System.out.println("SimulatedStorage: KEYS_WITH_PREFIX operation - prefix: " + new String(prefix) + 
                             ", count: " + result.size());
            
            future.complete(result);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
        
        private boolean startsWith(byte[] array, byte[] prefix) {
            if (array.length < prefix.length) {
                return false;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (array[i] != prefix[i]) {
                    return false;
                }
            }
            return true;
        }
    }
    
    private static class LastKeyOperation extends PendingOperation {
        private final byte[] keyUpperBound;
        private final ListenableFuture<byte[]> future;
        
        LastKeyOperation(byte[] keyUpperBound, ListenableFuture<byte[]> future, long completionTick) {
            super(completionTick);
            this.keyUpperBound = keyUpperBound;
            this.future = future;
        }
        
        @Override
        void execute(NavigableMap<byte[], byte[]> dataStore) {
            byte[] lastKey = dataStore.lowerKey(keyUpperBound);
            
            System.out.println("SimulatedStorage: LAST_KEY operation - result: " + 
                             (lastKey != null ? new String(lastKey) : "null"));
            
            future.complete(lastKey);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
    
    private static class SyncOperation extends PendingOperation {
        private final ListenableFuture<Void> future;
        
        SyncOperation(ListenableFuture<Void> future, long completionTick) {
            super(completionTick);
            this.future = future;
        }
        
        @Override
        void execute(NavigableMap<byte[], byte[]> dataStore) {
            System.out.println("SimulatedStorage: SYNC operation completed");
            future.complete(null);
        }
        
        @Override
        void fail(RuntimeException exception) {
            future.fail(exception);
        }
    }
} 
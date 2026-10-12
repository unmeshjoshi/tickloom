package com.tickloom.algorithms.replication.quorum;

import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.future.TickCompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogBackedKeyValueStoreTest {
    private static final int GROUP_WAIT = 2;

    private final SimulatedFileSystem fs = new SimulatedFileSystem();
    private LogBackedKeyValueStore store = new LogBackedKeyValueStore(fs, "kv", GROUP_WAIT);

    private void tick() {
        fs.tick();
        store.tick();
    }

    private <T> T await(TickCompletableFuture<T> future) {
        for (int i = 0; i < 1_000 && future.isPending(); i++) tick();
        assertFalse(future.isPending(), "still pending");
        if (future.isFailed()) fail(future.getException());
        return future.getResult();
    }

    private void open() {
        await(store.open());
    }

    private void crashAndRestart() {
        fs.crash();
        fs.restart();
        store = new LogBackedKeyValueStore(fs, "kv", GROUP_WAIT);
        open();
    }

    private static byte[] b(String s) {
        return s.getBytes();
    }

    @Test
    void aMissingKeyIsNull() {
        open();
        assertNull(store.get(b("nope")));
    }

    @Test
    void aWriteIsVisibleAtOnceAndDurableLater() {
        open();
        TickCompletableFuture<Void> put = store.put(b("k"), b("v"));

        assertArrayEquals(b("v"), store.get(b("k")));
        assertTrue(put.isPending(), "not durable yet");
        await(put);
    }

    @Test
    void theLastWriteWins() {
        open();
        await(store.put(b("k"), b("one")));
        await(store.put(b("k"), b("two")));
        assertArrayEquals(b("two"), store.get(b("k")));
    }

    @Test
    void durableWritesSurviveACrashAndLastWriteWinsOnReplay() {
        open();
        await(store.put(b("a"), b("1")));
        await(store.put(b("b"), b("2")));
        await(store.put(b("a"), b("3")));

        crashAndRestart();

        assertArrayEquals(b("3"), store.get(b("a")));
        assertArrayEquals(b("2"), store.get(b("b")));
    }

    @Test
    void aWriteThatWasNotDurableIsLostInACrash() {
        open();
        await(store.put(b("kept"), b("yes")));
        TickCompletableFuture<Void> lost = store.put(b("lost"), b("no"));
        assertTrue(lost.isPending());

        crashAndRestart();

        assertArrayEquals(b("yes"), store.get(b("kept")));
        assertNull(store.get(b("lost")));
    }

    @Test
    void reopeningTheSameInstanceRebuildsFromTheLog() {
        open();
        await(store.put(b("k"), b("v")));
        store.put(b("unsynced"), b("x"));   // in the map, not durable

        fs.crash();
        fs.restart();
        open();   // same instance, as a stop followed by a start does

        assertArrayEquals(b("v"), store.get(b("k")));
        assertNull(store.get(b("unsynced")), "the map is rebuilt, not kept");
    }

    @Test
    void emptyKeyAndEmptyValueAreStored() {
        open();
        await(store.put(new byte[0], new byte[0]));
        crashAndRestart();
        assertArrayEquals(new byte[0], store.get(new byte[0]));
    }

    @Test
    void theStoreDoesNotKeepTheCallersArrays() {
        open();
        byte[] key = b("k");
        byte[] value = b("v");
        await(store.put(key, value));
        key[0] = 'X';
        value[0] = 'X';
        assertArrayEquals(b("v"), store.get(b("k")));
    }
}

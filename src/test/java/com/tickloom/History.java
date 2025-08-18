package com.tickloom;

import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.messaging.MessageType;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

enum Outcome { OK, FAIL, TIMEOUT }
enum Op { READ, WRITE }

public class History {
    public void invoke(String name, Op op, byte[] key, byte[] value, long tick) {
        record(name, EventType.INVOKE, op, key, value, tick);
    }

    public void ok(String name, Op op, byte[] key, byte[] value, long tick) {
        record(name, EventType.OK, op, key, value, tick);
    }

    public void timeout(String name, Op op, byte[] key, byte[] value, long tick) {
        record(name, EventType.TIMEOUT, op, key, value, tick);
    }

    public void fail(String name, Op op, byte[] key, byte[] value, long tick) {
        record(name, EventType.FAIL, op, key, value, tick);
    }

    enum EventType { INVOKE, OK, FAIL, TIMEOUT }

    public static class Event {
        final long id;
        final String client;
        final EventType type;  // "ok=v", "fail", "timeout"
        final Op op;
        final byte[] key;
        final byte[] value; // e.g., "Write(k,v)" or "Read(k)"
        final long tick;
        final long nanoTime;

        Event(long id, String client, EventType type, Op op, byte[] key, byte[] value, long tick) {
            this.id = id;
            this.client = client;
            this.op = op;
            this.key = key;
            this.value = value;
            this.type = type;
            this.tick = tick;
            this.nanoTime = System.nanoTime();
        }

        public String toString() {
            return "Event{" +
                    "id=" + id +
                    ", client='" + client + '\'' +
                    ", type=" + type +
                    ", op=" + op +
                    ", key=" + (key == null ? "" : new String(key)) +
                    ", value=" + (value == null ? "" : new String(value)) +
                    ", tick=" + tick +
                    ", nanoTime=" + nanoTime +
                    "}";
        }
    }

    private final List<Event> events = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(0);

    public void record(String client, EventType type, Op op, byte[]key, byte[] value, long tick) {
        events.add(new Event(ids.incrementAndGet(), client, type, op, key, value, tick));
    }

    public List<Event> all() { return events; }
}
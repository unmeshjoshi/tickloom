package com.tickloom.history;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

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


    // ----- Simple adapter: com.tickloom.history.History -> EDN vector of op maps -----
    public String toEdn() {
        List<History.Event> evs = new ArrayList<>(all());
        if (evs.isEmpty()) return "[]";

        // time base (ns since first event), stable process ints per client
        long t0 = evs.stream().mapToLong(e -> e.nanoTime).min().orElse(System.nanoTime());
        Map<String, Integer> proc = new LinkedHashMap<>();
        int[] nextPid = {0};

        StringBuilder sb = new StringBuilder(evs.size() * 64);
        sb.append("[\n");
        for (int i = 0; i < evs.size(); i++) {
            History.Event e = evs.get(i);
            String type = switch (e.type) {
                case INVOKE -> ":invoke";
                case OK     -> ":ok";
                case FAIL   -> ":fail";
                case TIMEOUT-> ":info"; // timeout is indeterminate completion in Knossos
            };
            String f = (e.op == Op.WRITE) ? ":write" : ":read";
            int pid = proc.computeIfAbsent(e.client, k -> nextPid[0]++);

            // Value conventions for a register:
            // write: invoke->value written; completion->same value
            // read:  invoke->nil;          completion->value read (or nil)
            String val;
            boolean isWrite = (e.op == Op.WRITE);
            boolean isInvoke = e.type == History.EventType.INVOKE;
            if (isWrite) {
                val = (e.value == null) ? "nil" : ednStr(new String(e.value));
            } else {
                val = isInvoke ? "nil" : (e.value == null ? "nil" : ednStr(new String(e.value)));
            }

            long t = Math.max(0, e.nanoTime - t0);

            sb.append("  {:type ").append(type)
                    .append(", :f ").append(f)
                    .append(", :process ").append(pid)
                    .append(", :time ").append(t)
                    .append(", :index ").append(i)
                    .append(", :value ").append(val);

            if (e.type == History.EventType.TIMEOUT) {
                sb.append(", :error [:timeout]");
            }
            sb.append("}");
            if (i < evs.size() - 1) sb.append(",\n");
        }
        sb.append("\n]");
        return sb.toString();
    }

    private static String b64(byte[] b) {
        return (b == null) ? "" : Base64.getEncoder().encodeToString(b);
    }

    private static String ednStr(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"'  -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    public List<Event> all() { return events; }
}
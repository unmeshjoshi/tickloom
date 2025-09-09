package com.tickloom.history;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class History {
    public void invoke(String name, Op op, byte[] key, byte[] value) {
        record(name, EventType.INVOKE, op, key, value);
    }

    public void ok(String name, Op op, byte[] key, byte[] value) {
        record(name, EventType.OK, op, key, value);
    }

    public void timeout(String name, Op op, byte[] key, byte[] value) {
        record(name, EventType.TIMEOUT, op, key, value);
    }

    public void fail(String name, Op op, byte[] key, byte[] value) {
        record(name, EventType.FAIL, op, key, value);
    }

    enum EventType { INVOKE, OK, FAIL, TIMEOUT }

    //convert following to record
    public record Event(long id, String clientId,
                        EventType type, Op op, byte[] key, byte[] value, long nanoTime) {
        Event(long id, String client, EventType type, Op op, byte[] key, byte[] value) {
           this(id, client, type, op, key, value, System.nanoTime());
        }

        public String toString() {
            return "Event{" +
                    "id=" + id +
                    ", clientId='" + clientId + '\'' +
                    ", type=" + type +
                    ", op=" + op +
                    ", key=" + (key == null ? "" : new String(key)) +
                    ", value=" + (value == null ? "" : new String(value)) +
                    "}";
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            Event event = (Event) o;
            return id == event.id
                    && op == event.op
                    && Arrays.equals(key, event.key)
                    && Arrays.equals(value, event.value)
                    && Objects.equals(clientId, event.clientId)
                    && type == event.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, clientId, type, op, Arrays.hashCode(key), Arrays.hashCode(value));
        }
    }

    private final List<Event> events = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(0);

    public void record(String client, EventType type, Op op, byte[]key, byte[] value) {
        events.add(new Event(ids.incrementAndGet(), client, type, op, key, value));
    }


    // ----- Simple adapter: com.tickloom.history.History -> EDN vector of op maps -----
    public String toEdn() {
        List<History.Event> evs = new ArrayList<>(all());
        if (evs.isEmpty()) return "[]";

        // time base (ns since first event), stable process ints per clientId
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
            int pid = proc.computeIfAbsent(e.clientId, k -> nextPid[0]++);

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

    // ----- Adapter for Jepsen independent KV: :value is [key value] tuples -----
    public String toEdnKvTuples() {
        List<History.Event> evs = new ArrayList<>(all());
        if (evs.isEmpty()) return "[]";

        long t0 = evs.stream().mapToLong(e -> e.nanoTime).min().orElse(System.nanoTime());
        Map<String, Integer> proc = new LinkedHashMap<>();
        int[] nextPid = {0};

        StringBuilder sb = new StringBuilder(evs.size() * 80);
        sb.append("[\n");
        for (int i = 0; i < evs.size(); i++) {
            History.Event e = evs.get(i);
            String type = switch (e.type) {
                case INVOKE -> ":invoke";
                case OK     -> ":ok";
                case FAIL   -> ":fail";
                case TIMEOUT-> ":info";
            };
            String f = (e.op == Op.WRITE) ? ":write" : ":read";
            int pid = proc.computeIfAbsent(e.clientId, k -> nextPid[0]++);

            String keyStr = (e.key == null) ? "\"\"" : ednStr(new String(e.key));
            String valStr;
            boolean isWrite = (e.op == Op.WRITE);
            boolean isInvoke = e.type == History.EventType.INVOKE;
            if (isWrite) {
                valStr = "[" + keyStr + " " + ((e.value == null) ? "nil" : ednStr(new String(e.value))) + "]";
            } else {
                String observed = isInvoke ? "nil" : (e.value == null ? "nil" : ednStr(new String(e.value)));
                valStr = "[" + keyStr + " " + observed + "]";
            }

            long t = Math.max(0, e.nanoTime - t0);

            sb.append("  {:type ").append(type)
                    .append(", :f ").append(f)
                    .append(", :process ").append(pid)
                    .append(", :time ").append(t)
                    .append(", :index ").append(i)
                    .append(", :value ").append(valStr);

            if (e.type == History.EventType.TIMEOUT) {
                sb.append(", :error [:timeout]");
            }
            sb.append("}");
            if (i < evs.size() - 1) sb.append(",\n");
        }
        sb.append("\n]");
        return sb.toString();
    }
    public List<Event> all() { return events; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        History history = (History) o;
        // Equality is defined by the sequence of events.
        //The ids values should also exactly match as the number of events recorded should be exactly same.
        return Objects.equals(events, history.events)&& ids.get() == history.ids.get();
    }

    @Override
    public int hashCode() {
        // Hash based solely on events sequence for consistency with equals
        return Objects.hash(events, ids);
    }
    
    // === ENHANCED EDN GENERATION FOR DIFFERENT MODELS ===
    
    /**
     * Auto-detect model type and generate appropriate EDN
     */
    public String toEdn(String model) {
        return switch (model.toLowerCase()) {
            case "register" -> toEdn();
            case "kv" -> toEdnKvTuples();
            case "auto" -> detectModelAndGenerateEdn();
            default -> throw new IllegalArgumentException("Unknown model: " + model);
        };
    }
    
    /**
     * Auto-detect model based on whether operations have keys
     */
    private String detectModelAndGenerateEdn() {
        boolean hasKeys = events.stream().anyMatch(e -> e.key != null);
        return hasKeys ? toEdnKvTuples() : toEdn();
    }
}
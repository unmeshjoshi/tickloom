package com.tickloom.network;

import com.tickloom.Process;
import com.tickloom.ProcessId;
import com.tickloom.Tickable;
import com.tickloom.messaging.Message;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public abstract class Network implements Tickable, AutoCloseable {
    private final ProcessRegistry registry = new ProcessRegistry();

    public void register(Process process) {
        registry.register(process);
    }

    public Set<ProcessId> registeredProcessIds() {
        return registry.processIds();
    }

    protected void deliver(Message message) {
        Optional<Process> process = registry.get(message.destination());
        if (process.isEmpty()) {
            logDropped(message);
            return;
        }
        process.get().onMessage(message);
    }

    @Override
    public abstract void tick();

    public abstract void send(Message message) throws IOException;

    public void bind(ProcessId processId) throws IOException {
        // no-op.. only used by NioNetwork
    }

    @Override
    public void close() throws Exception {
    }

    private void logDropped(Message message) {
        System.out.println("Network: No process registered for " + message.destination()
                + ", dropping " + message.messageType());
    }

    static class ProcessRegistry {
        private final Map<ProcessId, Process> processes = new LinkedHashMap<>();

        public void register(Process process) {
            processes.put(process.id, process);
        }

        public Optional<Process> get(ProcessId processId) {
            return Optional.ofNullable(processes.get(processId));
        }

        public Set<ProcessId> processIds() {
            return processes.keySet();
        }
    }
}

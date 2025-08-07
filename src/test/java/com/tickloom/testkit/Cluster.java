package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.Tickable;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.config.ClusterTopology;
import com.tickloom.config.Config;
import com.tickloom.config.ProcessConfig;
import com.tickloom.messaging.MessageBus;
import com.tickloom.network.*;
import com.tickloom.storage.SimulatedStorage;
import com.tickloom.storage.Storage;
import com.tickloom.util.Clock;
import com.tickloom.util.SystemClock;
import com.tickloom.util.StubClock;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Ticks tickable components in order.
 */
class OrderedTicker implements Tickable {
    List<Tickable> tickables = new ArrayList<>();

    public OrderedTicker(List<Tickable> tickables) {
        this.tickables = tickables;
    }

    public static OrderedTicker of(Tickable... tickables) {
        OrderedTicker ticker = new OrderedTicker(Arrays.asList(tickables));
        return ticker;
    }
    public void tick() {
        tickables.forEach(Tickable::tick);
    }
}
public class Cluster implements Tickable, AutoCloseable {
    long seed = 0;
    Random random;
    List<Node> serverNodes = new ArrayList<>();
    List<Cluster.ClientNode> clientNodes = new ArrayList<>();
    boolean useSimulatedNetwork = false;
    Map<ProcessId, StubClock> processClocks = new HashMap<>();

    private int numProcesses = 3;
    private long initialClockTime = 1000L; // Initial time for all clocks (must be positive)
    private ClusterTopology topo;
    private MessageCodec messageCodec = new JsonMessageCodec();
    private Network sharedNetwork;
    private MessageBus sharedMessageBus;

    public Cluster withNumProcesses(int numProcesses) {
        this.numProcesses = numProcesses;
        return this;
    }

    public Cluster withSeed(long seed) {
        this.seed = seed;
        return this;
    }

    public Cluster useSimulatedNetwork() {
        this.useSimulatedNetwork = true;
        return this;
    }

    public Cluster withInitialClockTime(long initialTime) {
        if (initialTime <= 0) {
            throw new IllegalArgumentException("Initial clock time must be positive, got: " + initialTime);
        }
        this.initialClockTime = initialTime;
        return this;
    }



    public void tickUntil(Supplier<Boolean> p) {
        int timeout = 10000;
        int tickCount = 0;
        while (!p.get()) {
            if (tickCount > timeout) {
                fail("Timeout waiting for condition to be met.");
            }
            tick();
            tickCount++;
        }
    }

    public void close() {
        serverNodes.forEach(node -> {
            try {
                node.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        clientNodes.forEach(node -> {
            try {
                node.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Gets the clock for a specific process.
     * 
     * @param processId the ID of the process
     * @return the StubClock for the process
     * @throws IllegalArgumentException if the process ID is not found
     */
    public StubClock getClockForProcess(ProcessId processId) {
        StubClock clock = processClocks.get(processId);
        if (clock == null) {
            throw new IllegalArgumentException("Process not found: " + processId);
        }
        return clock;
    }

    /**
     * Gets clocks for all processes.
     * 
     * @return map of process IDs to their clocks
     */
    public Map<ProcessId, StubClock> getAllProcessClocks() {
        return new HashMap<>(processClocks);
    }

    /**
     * Sets the time for a specific process.
     * 
     * @param processId the ID of the process
     * @param time the time to set in milliseconds
     */
    public void setTimeForProcess(ProcessId processId, long time) {
        getClockForProcess(processId).setTime(time);
    }

    /**
     * Advances the time for a specific process.
     * 
     * @param processId the ID of the process
     * @param millis the number of milliseconds to advance
     */
    public void advanceTimeForProcess(ProcessId processId, long millis) {
        getClockForProcess(processId).advance(millis);
    }

    /**
     * Sets the same time for all processes.
     * 
     * @param time the time to set in milliseconds for all processes
     */
    public void setTimeForAllProcesses(long time) {
        processClocks.values().forEach(clock -> clock.setTime(time));
    }

    /**
     * Advances the time for all processes by the same amount.
     * 
     * @param millis the number of milliseconds to advance for all processes
     */
    public void advanceTimeForAllProcesses(long millis) {
        processClocks.values().forEach(clock -> clock.advance(millis));
    }

    public interface ProcessFactory<T extends com.tickloom.Process> {
        T create(ProcessId id, List<ProcessId> peerIds, MessageBus messageBus, MessageCodec messageCodec, Storage storage, Clock clock, int requestTimeoutTicks);
    }

    public interface ClientFactory<T extends ClusterClient> {
        T create(ProcessId clientId, List<ProcessId> replicaEndpoints,
                 MessageBus messageBus, MessageCodec messageCodec,
                 Clock clock, int timeoutTicks);
    }

    public <T extends ClusterClient> T newClient(ProcessId id, Cluster.ClientFactory<T> factory) throws IOException {
        Network network = useSimulatedNetwork ? sharedNetwork: createNetwork(messageCodec);
        MessageBus messageBus = useSimulatedNetwork ? sharedMessageBus: new MessageBus(network, messageCodec);
        // Create a StubClock for the client as well
        StubClock clientClock = new StubClock(initialClockTime);
        processClocks.put(id, clientClock);
        T clusterClient = factory.create(id, serverProcessIds(), messageBus, messageCodec, clientClock, 10000);
        clientNodes.add(new ClientNode(id, network, messageBus, clusterClient));
        return clusterClient;
    }

    private Network createNetwork(MessageCodec messageCodec) throws IOException {
        //as of now creating simulated network with no packet loss or delay
        return useSimulatedNetwork? SimulatedNetwork.noLossNetwork(random)
                :NioNetwork.create(topo, messageCodec);
    }

    private List<ProcessId> serverProcessIds() {
        return serverNodes.stream()
                .map(n -> n.id).collect(Collectors.toList());
    }


    static class Node implements Tickable {
        ProcessId id;
        Network network;
        MessageBus messageBus;
        com.tickloom.Process process;
        Storage storage;

        public Node(ProcessId id, Network network, MessageBus messageBus, com.tickloom.Process process, Storage storage) {
            this.id = id;
            this.network = network;
            this.messageBus = messageBus;
            this.process = process;
            this.storage = storage;
        }

        public void start() throws IOException {
            network.bind(id);
        }

        public void tick() {
            OrderedTicker.of(
                    network,
                    messageBus,
                    process,
                    storage)
                    .tick();
        }

        public void close() throws Exception {
            network.close();
            messageBus.close();
            process.close();
            storage.close();
        }
    }

    static class ClientNode {
        ProcessId id;
        Network network;
        MessageBus messageBus;
        ClusterClient client;

        public ClientNode(ProcessId id, Network network, MessageBus messageBus, ClusterClient client) {
            this.id = id;
            this.network = network;
            this.messageBus = messageBus;
            this.client = client;
        }

        public void tick() {
            OrderedTicker.of(
                    network,
                    messageBus,
                    client).tick();
        }

        public void close() throws Exception {
            network.close();
            messageBus.close();
            client.close();
        }
    }


    public void tick() {
        clientNodes.forEach(ClientNode::tick);
        serverNodes.forEach(Node::tick);
    }

    public <T extends com.tickloom.Process> Cluster build(ProcessFactory<T> factory) throws IOException {
        return build(factory, useSimulatedNetwork);
    }

    public <T extends com.tickloom.Process> Cluster build(ProcessFactory<T> factory, boolean withSimulatedNetwork) throws IOException {
        //Seed the random number generator
        random = new Random(seed);

        List<ProcessId> processIds = new ArrayList<>(numProcesses);
        for (int i = 1; i <= numProcesses; i++) {
            ProcessId processId = ProcessId.of("process-" + i);
            processIds.add(processId);
        }

        var basePort = 8000;
        Map<ProcessId, ProcessConfig> endpoints = new HashMap<>();
        for (int i = 0; i < processIds.size(); i++) {
            ProcessConfig config = new ProcessConfig(processIds.get(i).value(), "127.0.0.1", basePort + i);
            endpoints.put(processIds.get(i), config);
        }

        topo = new ClusterTopology(new Config(endpoints.values().stream().toList()));
        JsonMessageCodec messageCodec = new JsonMessageCodec();

        //In simulation mode, we use a single shared messagebus. so that message delivery
        //destinations are registered on the same messagebus. If different messagebuses are used
        //we need to do some wiring at the network layer.
        //When we use a real NIO network, we can use different networks and messagebuses as
        //messages are routed with the real network layer.
        this.sharedNetwork = createNetwork(messageCodec);
        this.sharedMessageBus = new MessageBus(sharedNetwork, messageCodec);

        for (int i = 0; i < processIds.size(); i++) {
            ProcessId processId = processIds.get(i);
            List<ProcessId> peers = processIds.stream().filter(id -> !id.equals(processId)).toList();
            Network network = sharedNetwork; //We can create separate network, NioNetwork.create(topo, messageCodec);
            SimulatedStorage storage = new SimulatedStorage(random);
            MessageBus messageBus = sharedMessageBus; //new MessageBus(network, messageCodec);
            // Always use StubClock for deterministic testing
            StubClock stubClock = new StubClock(initialClockTime);
            processClocks.put(processId, stubClock);
            Clock clock = stubClock;
            com.tickloom.Process process = factory.create(processId, peers, messageBus, messageCodec, storage, clock, 10000);
            serverNodes.add(new Node(processId, network, messageBus, process, storage));
        }
        return this;
    }

    public Cluster start() throws IOException {
        for (Node node : serverNodes) {
            node.start(); //All nodes start listening on the socket.
        }
        return this;
    }

}

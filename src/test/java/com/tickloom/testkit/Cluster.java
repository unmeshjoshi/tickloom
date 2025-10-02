package com.tickloom.testkit;

import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.Tickable;
import com.tickloom.future.ListenableFuture;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.config.ClusterTopology;
import com.tickloom.config.Config;
import com.tickloom.config.ProcessConfig;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.*;
import com.tickloom.storage.SimulatedStorage;
import com.tickloom.storage.Storage;
import com.tickloom.storage.VersionedValue;
import com.tickloom.util.Clock;
import com.tickloom.util.StubClock;

import java.io.IOException;
import java.util.*;
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
    private List<ProcessId> customProcessIds = new ArrayList<>(); // Custom process names like 'athens', 'byzantium', etc.
    private long initialClockTime = 1000L; // Initial time for all clocks (must be positive)
    private ClusterTopology topo;
    private MessageCodec messageCodec = new JsonMessageCodec();
    private Network sharedNetwork;
    private MessageBus sharedMessageBus;
    private boolean useLossyNetwork = false;

    public Cluster withNumProcesses(int numProcesses) {
        this.numProcesses = numProcesses;
        return this;
    }

    public Cluster withSeed(long seed) {
        this.seed = seed;
        return this;
    }

    public Cluster useSimulatedNetwork() {
        useSimulatedNetwork(false);
        return this;
    }

    private Cluster useSimulatedNetwork(boolean isLossy) {
        this.useSimulatedNetwork = true;
        this.useLossyNetwork = isLossy;
        return this;
    }

    public Cluster withLossySimulatedNetwork() {
        useSimulatedNetwork(true);
        return this;
    }

    public Cluster withInitialClockTime(long initialTime) {
        if (initialTime <= 0) {
            throw new IllegalArgumentException("Initial clock time must be positive, got: " + initialTime);
        }
        this.initialClockTime = initialTime;
        return this;
    }

    public Cluster withProcessIds(ProcessId... processIds) {
        return withProcessIds(Arrays.asList(processIds));
    }

    public Cluster withProcessIds(List<ProcessId> processIds) {
        if (processIds == null || processIds.isEmpty()) {
            throw new IllegalArgumentException("Process IDs cannot be null or empty");
        }
        
        // Validate ProcessIds are unique
        Set<ProcessId> uniqueIds = new HashSet<>();
        for (ProcessId processId : processIds) {
            if (processId == null) {
                throw new IllegalArgumentException("Process ID cannot be null");
            }
            if (!uniqueIds.add(processId)) {
                throw new IllegalArgumentException("Duplicate process ID: " + processId);
            }
        }
        
        this.customProcessIds = processIds;
        this.numProcesses = processIds.size(); // Override numProcesses
        return this;
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

    // ========== Network Partition and Delay Controls ==========



    /**
     * Creates a network partition between two processes (bidirectional).
     * Messages in both directions will be dropped.
     * 
     * @param process1 the first process ID
     * @param process2 the second process ID
     */
    public void partitionNodes(ProcessId process1, ProcessId process2) {
        ensureSimulatedNetwork();
        ((SimulatedNetwork) sharedNetwork).partitionTwoWay(process1, process2);
    }

    public void partitionNodes(NodeGroup from, NodeGroup to) {
        ensureSimulatedNetwork();
        for (ProcessId toProcess : to.processIds()) {
            for (ProcessId fromProcess : from.processIds()) {
                ((SimulatedNetwork) sharedNetwork).partitionTwoWay(fromProcess, toProcess);
            }
        }
    }



    /**
     * Creates a one-way network partition (unidirectional).
     * Messages from source to destination will be dropped.
     * 
     * @param source the source process ID
     * @param destination the destination process ID
     */
    public void partitionOneWay(ProcessId source, ProcessId destination) {
        ensureSimulatedNetwork();
        ((SimulatedNetwork) sharedNetwork).partitionOneWay(source, destination);
    }

    public void dropMessagesOfType(ProcessId source, ProcessId destination, MessageType messageType) {
        ensureSimulatedNetwork();
        ((SimulatedNetwork) sharedNetwork).dropMessagesOfType(source, destination, messageType);
    }

    public void dropNthMessagesOfType(ProcessId source, ProcessId destination, MessageType messageType, int nth) {
        ensureSimulatedNetwork();
        ((SimulatedNetwork) sharedNetwork).dropNthMessagesOfType(source, destination, messageType, nth);
    }


    /**
     * Heals a network partition between two processes (bidirectional).
     * Messages in both directions will be restored.
     * 
     * @param process1 the first process ID
     * @param process2 the second process ID
     */
    public void healPartition(ProcessId process1, ProcessId process2) {
        ensureSimulatedNetwork();
        ((SimulatedNetwork) sharedNetwork).healPartition(process1, process2);
    }



    /**
     * Sets network delay between two processes.
     * 
     * @param source the source process ID
     * @param destination the destination process ID
     * @param delayTicks the number of ticks to delay messages
     */
    public void setNetworkDelay(ProcessId source, ProcessId destination, int delayTicks) {
        ensureSimulatedNetwork();
        ((SimulatedNetwork) sharedNetwork).setDelay(source, destination, delayTicks);
    }



    /**
     * Sets packet loss rate between two processes.
     * 
     * @param source the source process ID
     * @param destination the destination process ID
     * @param lossRate the packet loss rate (0.0 to 1.0)
     */
    public void setPacketLoss(ProcessId source, ProcessId destination, double lossRate) {
        ensureSimulatedNetwork();
        ((SimulatedNetwork) sharedNetwork).setPacketLoss(source, destination, lossRate);
    }

    /**
     * Isolates a process from all other processes (creates partitions to all others).
     * 
     * @param processId the process to isolate
     */
    public void isolateProcess(ProcessId processId) {
        // Get all other processes and partition from the target
        getAllProcessClocks().keySet().stream()
            .filter(id -> !id.equals(processId))
            .forEach(otherProcess -> partitionNodes(processId, otherProcess));
    }

    /**
     * Reconnects an isolated process to all other processes.
     * 
     * @param processId the process to reconnect
     */
    public void reconnectProcess(ProcessId processId) {
        // Get all other processes and heal partitions to the target
        getAllProcessClocks().keySet().stream()
            .filter(id -> !id.equals(processId))
            .forEach(otherProcess -> healPartition(processId, otherProcess));
    }

    private void ensureSimulatedNetwork() {
        if (!useSimulatedNetwork || !(sharedNetwork instanceof SimulatedNetwork)) {
            throw new IllegalStateException("Network partition features require simulated network. Call useSimulatedNetwork() first.");
        }
    }

    // ========== Storage Access for Testing ==========

    /**
     * Gets the storage instance for a specific process.
     * Useful for direct storage assertions in tests.
     * 
     * @param processId the ID of the process
     * @return the Storage instance for the process
     * @throws IllegalArgumentException if the process ID is not found
     */
    public Storage getStorageForProcess(ProcessId processId) {
        Node node = findServerNode(processId);
        if (node == null) {
            throw new IllegalArgumentException("Process not found: " + processId);
        }
        return node.storage;
    }

    /**
     * Gets the process instance for a specific process ID.
     * Useful for accessing process-specific state in tests.
     * 
     * @param processId the ID of the process
     * @return the Process instance
     * @throws IllegalArgumentException if the process ID is not found
     */
    public com.tickloom.Process getProcess(ProcessId processId) {
        Node node = findServerNode(processId);
        if (node == null) {
            throw new IllegalArgumentException("Process not found: " + processId);
        }
        return node.process;
    }

    private Node findServerNode(ProcessId processId) {
        return serverNodes.stream()
            .filter(node -> node.id.equals(processId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Retrieves a name from storage on a specific process for testing.
     * This method handles the asynchronous nature of storage operations.
     * 
     * @param processId the ID of the process
     * @param key the key to retrieve
     * @return the VersionedValue if found, null otherwise
     */
    public VersionedValue getStorageValue(ProcessId processId, byte[] key) {
        Storage storage = getStorageForProcess(processId);
        ListenableFuture<VersionedValue> future = storage.get(key);
        
        // Tick until the storage operation completes
        while (!future.isCompleted()) {
            tick();
        }
        
        return future.getResult();
    }

    public void healAllPartitions() {
        ensureSimulatedNetwork();
        ((SimulatedNetwork) sharedNetwork).healAllPartitions();
    }

    public long getTimeForProcess(ProcessId processId) {
        return processClocks.get(processId).now();
    }

    public Random getRandom() {
        return random;
    }

    public Network getNetwork() {
        return sharedNetwork;
    }

    public void delayForMessageType(MessageType messageType, ProcessId processId, List<ProcessId> toProcessIds, int propDelayTicks) {
        ensureSimulatedNetwork();

        toProcessIds.forEach((toProcessId)->
        ((SimulatedNetwork)getNetwork()).setDelayForMessageType(processId, toProcessId, propDelayTicks, messageType));

    }

    public interface ClientFactory<T extends ClusterClient> {
        T create(ProcessId clientId, List<ProcessId> replicaEndpoints,
                 MessageBus messageBus, MessageCodec messageCodec,
                 Clock clock, int timeoutTicks);
    }

    /**
     * Creates a clientId that connects to all server nodes (default behavior).
     * 
     * @param id the clientId ID
     * @param factory the factory to create the clientId
     * @return the created clientId
     * @throws IOException if network creation fails
     */
    public <T extends ClusterClient> T newClient(ProcessId id, Cluster.ClientFactory<T> factory) throws IOException {
        return newClient(id, serverProcessIds(), factory);
    }

    /**
     * Creates a clientId that connects to a specific target node.
     * This allows simulating scenarios where clients connect to specific nodes.
     * 
     * @param id the clientId ID
     * @param targetNode the specific server node to connect to
     * @param factory the factory to create the clientId
     * @return the created clientId
     * @throws IOException if network creation fails
     */
    public <T extends ClusterClient> T newClientConnectedTo(ProcessId id, ProcessId targetNode, Cluster.ClientFactory<T> factory) throws IOException {
        return newClient(id, List.of(targetNode), factory);
    }

    /**
     * Creates a clientId that connects to specific target nodes.
     * 
     * @param id the clientId ID
     * @param targetNodes the specific server nodes to connect to
     * @param factory the factory to create the clientId
     * @return the created clientId
     * @throws IOException if network creation fails
     */
    private <T extends ClusterClient> T newClient(ProcessId id, List<ProcessId> targetNodes, Cluster.ClientFactory<T> factory) throws IOException {
        Network network = useSimulatedNetwork ? sharedNetwork: createNetwork(messageCodec);
        MessageBus messageBus = useSimulatedNetwork ? sharedMessageBus: new MessageBus(network, messageCodec);
        // Create a StubClock for the clientId as well
        StubClock clientClock = new StubClock(initialClockTime);
        processClocks.put(id, clientClock);
        T clusterClient = factory.create(id, targetNodes, messageBus, messageCodec, clientClock, 10000);
        clientNodes.add(new ClientNode(id, network, messageBus, clusterClient));
        return clusterClient;
    }

    public Cluster withLossyNetwork() {
        useLossyNetwork = true;
        return this;
    }

    private Network createNetwork(MessageCodec messageCodec) throws IOException {
        //as of now creating simulated network with no packet loss or delay
        return useSimulatedNetwork? createSimulatedNetwork()
                :NioNetwork.create(topo, messageCodec);
    }

    private SimulatedNetwork createSimulatedNetwork() {
        //Packet loss rate needs to be moved to NetworkOptions.
        return useLossyNetwork? SimulatedNetwork.lossyNetwork(random, 0) : SimulatedNetwork.noLossNetwork(random);
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
        advanceTimeForAllProcesses(1);
        clientNodes.forEach(ClientNode::tick);
        serverNodes.forEach(Node::tick);
    }

    public <T extends com.tickloom.Process> Cluster build(ProcessFactory factory) throws IOException {
        return build(factory, useSimulatedNetwork);
    }

    public <T extends com.tickloom.Process> Cluster build(ProcessFactory factory, boolean withSimulatedNetwork) throws IOException {
        //Seed the random number generator
        random = new Random(seed);

        List<ProcessId> processIds = customProcessIds.isEmpty() ?
                generateDefaultIds(numProcesses): customProcessIds;

        var basePort = 8000;
        Map<ProcessId, ProcessConfig> endpoints = new HashMap<>();
        for (int i = 0; i < processIds.size(); i++) {
            ProcessConfig config = new ProcessConfig(processIds.get(i).name(), "127.0.0.1", basePort + i);
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

    private List<ProcessId> generateDefaultIds(int numProcesses) {
        // Use custom process names
        // Use default process-1, process-2, etc.
        List<ProcessId> processIds = new ArrayList<>(numProcesses);
        for (int i = 1; i <= numProcesses; i++) {
            ProcessId processId = ProcessId.of("process-" + i);
            processIds.add(processId);
        }
        return processIds;
    }

    public Cluster start() throws IOException {
        for (Node node : serverNodes) {
            node.start(); //All nodes start listening on the socket.
        }
        return this;
    }

}

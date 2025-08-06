package com.tickloom.algorithms.replication.quorum;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.config.ClusterTopology;
import com.tickloom.config.Config;
import com.tickloom.config.ProcessConfig;
import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.MessageBus;
import com.tickloom.network.*;
import com.tickloom.storage.SimulatedStorage;
import com.tickloom.storage.Storage;
import com.tickloom.util.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;


class Node {
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
        network.tick();
        messageBus.tick();
        process.tick();
        storage.tick();
    }
}

class Cluster {
    List<Node> serverNodes = new ArrayList<>();
    List<ClientNode> clientNodes = new ArrayList<>();

    private int numProcesses = 3;
    private ClusterTopology topo;
    private MessageCodec messageCodec = new JsonMessageCodec();

    public Cluster numProcesses(int numProcesses) {
        this.numProcesses = numProcesses;
        return this;
    }

    public void tickUntil(Supplier<Boolean> p) {
        int timeout = 1000;
        int tickCount = 0;
        while (!p.get()) {
            if (tickCount > timeout) {
                fail("Timeout waiting for condition to be met.");
            }
            clientNodes.forEach(ClientNode::tick);
            serverNodes.forEach(Node::tick);
            tickCount++;
        }
    }

    static interface ProcessFactory<T extends com.tickloom.Process> {
        T create(ProcessId id, List<ProcessId> peerIds, MessageBus messageBus, MessageCodec messageCodec, Storage storage, int requestTimeoutTicks);
    }

    void build() throws IOException {
        build(QuorumReplica::new);
    }

    interface ClientFactory<T extends ClusterClient> {
        T create(ProcessId clientId, List<ProcessId> replicaEndpoints,
                 MessageBus messageBus, MessageCodec messageCodec,
                 int timeoutTicks);
    }

    <T extends ClusterClient> T newClient(ProcessId id, ClientFactory<T> factory) throws IOException {
        Network network = NioNetwork.create(topo, messageCodec);
        MessageBus messageBus = new MessageBus(network, messageCodec);
        T clusterClient = factory.create(id, serverProcessIds(), messageBus, messageCodec, 1000);
        clientNodes.add(new ClientNode(id, network, messageBus, clusterClient));
        return clusterClient;
    }

    private List<ProcessId> serverProcessIds() {
        return serverNodes.stream()
                .map(n -> n.id).collect(Collectors.toList());
    }

    class ClientNode {
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
            network.tick();
            messageBus.tick();
            client.tick();
        }
    }


    public void tick() {
        clientNodes.forEach(ClientNode::tick);
        serverNodes.forEach(Node::tick);
    }

    <T extends com.tickloom.Process> void build(ProcessFactory<T> factory) throws IOException {
        Random random = new Random();
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
        for (int i = 0; i < processIds.size(); i++)  {
            ProcessId processId  = processIds.get(i);
            List<ProcessId> peers = processIds.stream().filter(id -> !id.equals(processId)).toList();
            NioNetwork network = NioNetwork.create(topo, messageCodec);
            SimulatedStorage storage = new SimulatedStorage(random);
            MessageBus messageBus = new MessageBus(network, messageCodec);
            com.tickloom.Process process = factory.create(processId, peers, messageBus, messageCodec, storage, 1000);
            serverNodes.add(new Node(processId, network, messageBus, process, storage));
        }
    }

    public void start() throws IOException {
        for (Node node : serverNodes) {
            node.start(); //All nodes start listening on the socket.
        }
    }

}


public class QuorumReplicaClientTest {
    
    private QuorumReplicaClient client;
    private List<QuorumReplica> replicas;
    private List<SimulatedStorage> storages;
    private SimulatedNetwork network;
    private JsonMessageCodec messageCodec;
    private Random random;
    private MessageBus sharedMessageBus;

    @BeforeEach
    void setUp() {
        random = new Random();
        network = new SimulatedNetwork(random, 0, 0.0);
        messageCodec = new JsonMessageCodec();
        
        // Create shared message bus for all processes
        sharedMessageBus = new MessageBus(network, messageCodec);
        
        // Create 3 replicas for quorum
        ProcessId replica1Id = ProcessId.of("replica1");
        ProcessId replica2Id = ProcessId.of("replica2");
        ProcessId replica3Id = ProcessId.of("replica3");
        
        List<ProcessId> replicaIds = Arrays.asList(replica1Id, replica2Id, replica3Id);
        
        // Create replicas with shared message bus
        SimulatedStorage storage1 = new SimulatedStorage(random);
        QuorumReplica replica1 = new QuorumReplica(replica1Id, replicaIds,
            sharedMessageBus, messageCodec, storage1, 1000);
        SimulatedStorage storage2 = new SimulatedStorage(random);
        QuorumReplica replica2 = new QuorumReplica(replica2Id, replicaIds,
            sharedMessageBus, messageCodec, storage2, 1000);
        SimulatedStorage storage3 = new SimulatedStorage(random);
        QuorumReplica replica3 = new QuorumReplica(replica3Id, replicaIds,
            sharedMessageBus, messageCodec, storage3, 1000);
        
        replicas = Arrays.asList(replica1, replica2, replica3);
        storages = Arrays.asList(storage1, storage2, storage3);

        // Create client with shared message bus
        ProcessId clientId = ProcessId.of("client1");
        client = new QuorumReplicaClient(clientId, replicaIds,
            sharedMessageBus, messageCodec, 1000);
        
        // Register client with the message bus
        sharedMessageBus.registerHandler(clientId, client);
    }

    @Test
    public void testSetAndGetRequest() throws IOException {
        byte[] key = "test-key".getBytes();
        byte[] value = "test-value".getBytes();

        Cluster cluster = new Cluster();
        cluster.numProcesses(3);
        cluster.build();
        cluster.start();

        QuorumReplicaClient client = cluster.newClient(ProcessId.of("Client1"), QuorumReplicaClient::new);
        // Test SET operation
        ListenableFuture<SetResponse> setFuture = client.set(key, value);
        // Debug: Check initial state
        System.out.println("Initial pending requests: " + client.getPendingRequestCount());
        assertTrue(setFuture.isPending(), "Set future should be pending initially");

        cluster.tickUntil(()->setFuture.isCompleted());
        assertTrue(setFuture.isCompleted(), "Set operation should complete");
        SetResponse setResponse = setFuture.getResult();
        assertTrue(setResponse.success(), "Set operation should succeed");
        assertArrayEquals(key, setResponse.key(), "Response key should match request key");
    }

}

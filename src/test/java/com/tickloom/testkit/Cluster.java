package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.Tickable;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.config.ClusterTopology;
import com.tickloom.config.Config;
import com.tickloom.config.ProcessConfig;
import com.tickloom.messaging.MessageBus;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.MessageCodec;
import com.tickloom.network.Network;
import com.tickloom.network.NioNetwork;
import com.tickloom.storage.SimulatedStorage;
import com.tickloom.storage.Storage;

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
public class Cluster implements Tickable {
    List<Node> serverNodes = new ArrayList<>();
    List<Cluster.ClientNode> clientNodes = new ArrayList<>();

    private int numProcesses = 3;
    private ClusterTopology topo;
    private MessageCodec messageCodec = new JsonMessageCodec();

    public Cluster withNumProcesses(int numProcesses) {
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
            tick();
            tickCount++;
        }
    }

    public interface ProcessFactory<T extends com.tickloom.Process> {
        T create(ProcessId id, List<ProcessId> peerIds, MessageBus messageBus, MessageCodec messageCodec, Storage storage, int requestTimeoutTicks);
    }

    public interface ClientFactory<T extends ClusterClient> {
        T create(ProcessId clientId, List<ProcessId> replicaEndpoints,
                 MessageBus messageBus, MessageCodec messageCodec,
                 int timeoutTicks);
    }

    public <T extends ClusterClient> T newClient(ProcessId id, Cluster.ClientFactory<T> factory) throws IOException {
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
    }


    public void tick() {
        clientNodes.forEach(ClientNode::tick);
        serverNodes.forEach(Node::tick);
    }

    public <T extends com.tickloom.Process> Cluster build(ProcessFactory<T> factory) throws IOException {
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
        for (int i = 0; i < processIds.size(); i++) {
            ProcessId processId = processIds.get(i);
            List<ProcessId> peers = processIds.stream().filter(id -> !id.equals(processId)).toList();
            NioNetwork network = NioNetwork.create(topo, messageCodec);
            SimulatedStorage storage = new SimulatedStorage(random);
            MessageBus messageBus = new MessageBus(network, messageCodec);
            com.tickloom.Process process = factory.create(processId, peers, messageBus, messageCodec, storage, 1000);
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

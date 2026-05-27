package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.history.History;
import com.tickloom.history.HistoryRecorder;
import com.tickloom.testkit.Cluster;
import com.tickloom.testkit.dsl.ScenarioBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

record ClientDef(
    ProcessId id,
    ProcessId connectedTo){
}

public class Scenario<C extends ClusterClient, T> {
    List<ProcessId> serverIds = new ArrayList<>();
    List<ClientDef> clientDefs = new ArrayList<>();
    ProcessFactory serverFactory;
    Cluster.ClientFactory<C> clientFactory;
    private final String name;
    private final List<Step> steps = new ArrayList<>();
    private final HistoryRecorder<String> recorder = new HistoryRecorder<>();

    public Scenario(String name, ProcessFactory serverFactory, Cluster.ClientFactory<C> clientFactory) {
        this.name = name;
        this.serverFactory = serverFactory;
        this.clientFactory = clientFactory;
    }

    public Scenario<C, T> withServerIds(List<ProcessId> serverIds) {
        this.serverIds = serverIds;
        return this;
    }

    public Scenario<C, T> withClientDefs(List<ClientDef> clientDefs) {
        this.clientDefs = clientDefs;
        return this;
    }


    public String getName() { return name; }
    public List<Step> getSteps() { return steps; }
    public HistoryRecorder<String> getRecorder() { return recorder; }
    public History history() { return recorder.getHistory(); }

    public void addStep(Step step) { steps.add(step); }

    public void execute() throws IOException {
        try(var cluster = Cluster.create(serverIds, serverFactory)) {
            Map<ProcessId, C> clients = createClients(cluster, clientDefs);
            for (Step step : steps) {
                C c = clients.get(step.clientId());
                Action action = step.action();
                action.executeWith(clients, cluster);
            }
        }
    }

    static class QuorumKVWriteAction implements Action<QuorumReplicaClient> {
        String key;
        String value;
        ProcessId clientId;
        public QuorumKVWriteAction(ProcessId clientId, String key, String value) {
            this.clientId = clientId;
            this.key = key;
            this.value = value;
        }
        @Override
        public void executeWith(Map<ProcessId, QuorumReplicaClient> clients, Cluster cluster) {
            QuorumReplicaClient quorumReplicaClient = clients.get(clientId);
            TickCompletableFuture<SetResponse> setFuture = quorumReplicaClient.set(key.getBytes(), value.getBytes());
            cluster.tickUntil(() -> setFuture.isCompleted() && !setFuture.isFailed());
        }
    }

    private Map<ProcessId, C> createClients(Cluster cluster, List<ClientDef> clientDefs) throws IOException {
        var clients = new HashMap<ProcessId, C>();
        for (ClientDef clientDef : clientDefs) {
            C t = cluster.newClientConnectedTo(clientDef.id(), clientDef.connectedTo(), clientFactory);
            clients.put(clientDef.id(), t);
        }
        return clients;
    }
}

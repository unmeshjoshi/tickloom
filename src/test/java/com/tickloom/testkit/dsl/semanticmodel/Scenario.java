package com.tickloom.testkit.dsl.semanticmodel;

import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.history.HistoryRecorder;
import com.tickloom.history.JepsenHistory;
import com.tickloom.testkit.Cluster;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Boots a simulated cluster from the topology's factory lambdas, applies any
 * initial-condition {@link ClusterEvent}s collected via the DSL's
 * {@code given(...)} block, then runs the recorded steps. No reflection:
 * constructor changes on replica or client are compile errors at the factory site.
 */
public final class Scenario<C extends ClusterClient> {
    private final String name;
    private final List<ProcessId> serverIds;
    private final List<ClientDef> clientDefs;
    private final List<ClusterEvent> givens;
    private final ProcessFactory replicaFactory;
    private final Cluster.ClientFactory<C> clientFactory;
    private final List<Step<C, ?>> steps;

    public Scenario(String name,
                    List<ProcessId> serverIds,
                    List<ClientDef> clientDefs,
                    List<ClusterEvent> givens,
                    ProcessFactory replicaFactory,
                    Cluster.ClientFactory<C> clientFactory,
                    List<Step<C, ?>> steps) {
        this.name = name;
        this.serverIds = serverIds;
        this.clientDefs = clientDefs;
        this.givens = givens;
        this.replicaFactory = replicaFactory;
        this.clientFactory = clientFactory;
        this.steps = steps;
    }

    public String name() { return name; }

    /**
     * Every run creates a new history instance.
     * This allows the same scenario to run multiple times.
     * Each run creates a new cluster instance and executes the scenario.
     * @return
     * @throws IOException
     */

    public ScenarioResult run() throws IOException {
        HistoryRecorder<String> recorder = new HistoryRecorder<>();
        ClusterSnapshot snapshot;
        try (Cluster cluster = Cluster.createSimulated(serverIds, replicaFactory)) {
            Map<ProcessId, C> clients = createClients(cluster);
            for (ClusterEvent given : givens) {
                given.introduceIn(cluster);
            }
            for (Step<C, ?> step : steps) {
                step.execute(clients, cluster, recorder);
            }
            snapshot = new ClusterSnapshot(cluster);
        }
        return new ScenarioResult(recorder.jepsenHistory(), snapshot);
    }

    private Map<ProcessId, C> createClients(Cluster cluster) throws IOException {
        var clients = new HashMap<ProcessId, C>();
        for (ClientDef def : clientDefs) {
            C client = def.connectedTo() == null
                    ? cluster.newClient(def.id(), clientFactory)
                    : cluster.newClientConnectedTo(def.id(), def.connectedTo(), clientFactory);
            clients.put(def.id(), client);
        }
        return clients;
    }
}

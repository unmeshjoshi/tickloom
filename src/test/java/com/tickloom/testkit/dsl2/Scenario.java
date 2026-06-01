package com.tickloom.testkit.dsl2;

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
 * Boots a simulated cluster from the topology's factory lambdas and runs the recorded steps.
 * No reflection: constructor changes on replica or client are compile errors at the factory site.
 */
public final class Scenario<C extends ClusterClient> {
    private final String name;
    private final List<ProcessId> serverIds;
    private final List<ClientDef> clientDefs;
    private final ProcessFactory replicaFactory;
    private final Cluster.ClientFactory<C> clientFactory;
    private final List<Step<C, ?>> steps;
    private final HistoryRecorder<String> recorder = new HistoryRecorder<>();

    Scenario(String name,
             List<ProcessId> serverIds,
             List<ClientDef> clientDefs,
             ProcessFactory replicaFactory,
             Cluster.ClientFactory<C> clientFactory,
             List<Step<C, ?>> steps) {
        this.name = name;
        this.serverIds = serverIds;
        this.clientDefs = clientDefs;
        this.replicaFactory = replicaFactory;
        this.clientFactory = clientFactory;
        this.steps = steps;
    }

    public static ScenarioBuilder scenario(String name) {
        return ScenarioBuilder.scenario(name);
    }

    public String name() { return name; }
    public HistoryRecorder<String> recorder() { return recorder; }
    public JepsenHistory history() { return recorder.jepsenHistory(); }

    public void run() throws IOException {
        try (Cluster cluster = Cluster.createSimulated(serverIds, replicaFactory)) {
            Map<ProcessId, C> clients = createClients(cluster);
            for (Step<C, ?> step : steps) {
                step.execute(clients, cluster, recorder);
            }
        }
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

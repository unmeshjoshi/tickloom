package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.testkit.Cluster;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

class ScenarioRunner2Test {

    @Test
    public void shouldRunWriteStep() throws IOException {

        ProcessId clientId = ProcessId.of("client1");

        Scenario scenario = new Scenario("test", (peerIds, processParams) -> new QuorumReplica(peerIds, processParams),
                (replicaEndpoints, processParams) -> new QuorumReplicaClient(replicaEndpoints, processParams))
                .withServerIds(List.of(ProcessId.of("athens"), ProcessId.of("byzantium"), ProcessId.of("cyrene")))
                .withClientDefs(List.of(new ClientDef(clientId, ProcessId.of("athens"))));
        scenario.addStep(new Step(new Scenario.QuorumKVWriteAction(clientId, "key1", "value1"),
                new AwaitCompletion()));
        scenario.execute();
    }

}
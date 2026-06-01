package com.tickloom.testkit.dsl2;

import com.tickloom.algorithms.replication.quorum.QuorumMessageTypes;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.testkit.dsl2.quorum.QuorumSteps;
import org.junit.jupiter.api.Test;

import java.io.IOException;

class ScenarioRunner2Test {

    @Test
    public void shouldRunWriteStep() throws IOException {
        Scenario.scenario("simple write")
                .topology(t -> t
                        .servers("athens", "byzantium", "cyrene").ofType(QuorumReplica::new)
                        .clients(QuorumReplicaClient::new)
                        .stepsType(QuorumSteps::new)
                        .client("alice").connectedTo("athens"))
                .steps(s ->
                        s.client("alice").writes("key1", "value1")
                                .waitForCompletion())
                .run();
    }

    @Test
    public void shouldReadValueWrittenEarlier() throws IOException {
        Scenario.scenario("write then read")
                .topology(t -> t
                        .servers("athens", "byzantium", "cyrene").ofType(QuorumReplica::new)
                        .clients(QuorumReplicaClient::new)
                        .stepsType(QuorumSteps::new)
                        .client("alice").connectedTo("athens"))
                .steps(s -> {
                    s.client("alice").writes("k", "x=1").waitForCompletion();
                    s.client("alice").reads("k").expectReturn("x=1");
                })
                .run();
    }

    @Test
    public void shouldRouteBroadcastClientWhenConnectedToOmitted() throws IOException {
        Scenario.scenario("broadcast client")
                .topology(t -> t
                        .servers("athens", "byzantium", "cyrene").ofType(QuorumReplica::new)
                        .clients(QuorumReplicaClient::new)
                        .stepsType(QuorumSteps::new)
                        .client("bob"))
                .steps(s ->
                        s.client("bob").writes("k", "v").waitForCompletion())
                .run();
    }

    @Test
    public void shouldKeepStepPendingWhenQuorumIsNotReachable() throws IOException {
        Scenario.scenario("pending write")
                .topology(t -> t
                        .servers("athens", "byzantium", "cyrene").ofType(QuorumReplica::new)
                        .clients(QuorumReplicaClient::new)
                        .stepsType(QuorumSteps::new)
                        .client("alice").connectedTo("athens"))
                .steps(s ->
                        s.client("alice").writes("k", "v")
                                .duringClusterEvents(e -> e
                                        .delay(QuorumMessageTypes.INTERNAL_SET_REQUEST)
                                                .from("athens").to("byzantium", "cyrene").byTicks(500))
                                .expectPending(20))
                .run();
    }

    @Test
    public void shouldWaitUntilNodeStateMatches() throws IOException {
        Scenario.scenario("waitForNodeState")
                .topology(t -> t
                        .servers("athens", "byzantium", "cyrene").ofType(QuorumReplica::new)
                        .clients(QuorumReplicaClient::new)
                        .stepsType(QuorumSteps::new)
                        .client("alice").connectedTo("athens"))
                .steps(s ->
                        s.client("alice").writes("k", "x=0")
                                .waitForNodeState("athens",
                                        n -> n.storedString("k").map("x=0"::equals).orElse(false)))
                .run();
    }
}

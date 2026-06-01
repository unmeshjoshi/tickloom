package com.tickloom.testkit.dsl2;

import com.tickloom.ConsistencyChecker;
import com.tickloom.algorithms.replication.quorum.QuorumMessageTypes;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.testkit.dsl2.quorum.QuorumSteps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Port of DDIA §10.6: Alice sees the new value via Athens; later, Bob (talking to a different replica
 * isolated from the propagation path) sees the old value. Exercises duringClusterEvents (delay +
 * partition + recover), waitForNodeState, and expectReturn end-to-end, then asserts the recorded
 * history is sequentially consistent but not linearizable.
 */
class DDIALinearizabilityScenarioTest {

    private static final int PROP_DELAY_TICKS = 100;

    @Test
    @DisplayName("DDIA §10.6 — Alice sees new, later Bob sees old → not linearizable; SC holds")
    public void ddiaQuorumLinearizabilityVsSequentialConsistency() throws IOException {
        var afterSteps = Scenario.scenario("DDIA register")
                .topology(t -> t
                        .servers("athens", "byzantium", "cyrene").ofType(QuorumReplica::new)
                        .clients(QuorumReplicaClient::new)
                        .stepsType(QuorumSteps::new)
                        .client("writer").connectedTo("athens")
                        .client("alice").connectedTo("byzantium")
                        .client("bob").connectedTo("byzantium"))
                .steps(s -> {
                    s.client("writer").writes("order:1001", "x=0")
                            .waitForCompletion();

                    s.client("writer").writes("order:1001", "x=1")
                            .duringClusterEvents(e -> e
                                    .delay(QuorumMessageTypes.INTERNAL_SET_REQUEST)
                                        .from("athens").to("byzantium", "cyrene").byTicks(PROP_DELAY_TICKS))
                            .waitForNodeState("athens",
                                    n -> n.storedString("order:1001").map("x=1"::equals).orElse(false));

                    s.client("alice").reads("order:1001")
                            .duringClusterEvents(e -> e.partition("byzantium").from("cyrene"))
                            .expectReturn("x=1");

                    s.client("bob").reads("order:1001")
                            .duringClusterEvents(e -> e
                                    .recover("byzantium")
                                    .partition("byzantium").from("athens"))
                            .expectReturn("x=0");

                    // Drive the cluster to converge so the pending second write commits before
                    // the history is inspected. The previous .partition between byzantium/athens
                    // is healed first so the propagation can complete.
                    s.untilConverged("byzantium",
                            n -> n.storedString("order:1001").map("x=1"::equals).orElse(false))
                            .duringClusterEvents(e -> e.recover("byzantium"));
                    s.untilConverged("cyrene",
                            n -> n.storedString("order:1001").map("x=1"::equals).orElse(false));
                });

        afterSteps.run();

        String ednHistory = afterSteps.scenario().history().getEdnString();

        boolean linearizable = ConsistencyChecker.check(ednHistory,
                ConsistencyChecker.ConsistencyProperty.LINEARIZABILITY,
                ConsistencyChecker.DataModel.REGISTER);
        assertEquals(false, linearizable, "Linearizability should not hold");

        boolean sequential = ConsistencyChecker.check(ednHistory,
                ConsistencyChecker.ConsistencyProperty.SEQUENTIAL_CONSISTENCY,
                ConsistencyChecker.DataModel.REGISTER);
        assertEquals(true, sequential, "Sequential consistency should hold");
    }
}

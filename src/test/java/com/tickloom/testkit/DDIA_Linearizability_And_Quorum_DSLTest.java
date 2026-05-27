package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.algorithms.replication.quorum.QuorumMessageTypes;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.algorithms.replication.quorum.VersionedValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tickloom.ConsistencyChecker;
import com.tickloom.history.Op;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.testkit.dsl.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class DDIA_Linearizability_And_Quorum_DSLTest
        extends ClusterTest<QuorumReplicaClient, GetResponse, String> {

    // 3 replicas
    private static final ProcessId ATHENS = ProcessId.of("athens");
    private static final ProcessId BYZANTIUM = ProcessId.of("byzantium");
    private static final ProcessId CYRENE = ProcessId.of("cyrene");

    // clients
    private static final ProcessId ALICE = ProcessId.of("alice");
    private static final ProcessId BOB = ProcessId.of("bob");
    private static final ProcessId WRITER = ProcessId.of("writer");

    // delay window (~10 ticks) to hold back propagation from ATHENS to others
    private static final int PROP_DELAY_TICKS = 100;

    public DDIA_Linearizability_And_Quorum_DSLTest() throws IOException {
        super(List.of(ATHENS, BYZANTIUM, CYRENE), (peerIds, processParams) -> new QuorumReplica(peerIds, processParams), QuorumReplicaClient::new,
                response -> maskNull(response)
        );
    }

    private static String maskNull(GetResponse response) {
        return response == null || response.value() == null ? null : new String(response.value(), StandardCharsets.UTF_8);
    }





    public static Predicate<String> hasValue(String value) {
        return val -> Objects.equals(val, value);
    }

    @Test
    @DisplayName("Verify that the Scenario DSL correctly builds a semantic model")
    public void testScenarioDsl() {
        Scenario scenario = ScenarioBuilder.scenario("DDIA §10.6 via per-link delay: Alice sees new, later Bob sees old")
                .topology(t -> t
                        .clusterNodes(List.of("athens", "byzantium", "cyrene")).ofType(QuorumReplica.class)
                        .clients(c -> c
                                .client("writer").connectedTo("athens")
                                .client("alice").connectedTo("byzantium")
                                .client("bob").connectedTo("byzantium")
                        )
                )
                .ops(ops -> {
                    ops.client("writer").performs(WriteAction.write("order:1001", "x=0"))
                            .waitUntil("athens", hasValue("x=0"));

                    ops.client("writer").performs(WriteAction.write("order:1001", "x=1"))
                            .withClusterBehaviour(failure -> {
                                failure.delayedMessages("athens", List.of("byzantium", "cyrene"), PROP_DELAY_TICKS);
                            })
                            .waitUntil("athens", hasValue("x=1"))
                            .expectPending();

                    ops.client("alice").performs(ReadAction.read("order:1001"))
                            .withClusterBehaviour(failures -> {
                                failures.partition(List.of("byzantium"), List.of("cyrene"));
                            })
                            .expect("x=1");

                    ops.client("bob").performs(ReadAction.read("order:1001"))
                            .withClusterBehaviour(failures -> {
                                failures.reconnect("byzantium");
                                failures.partition(List.of("byzantium"), List.of("athens"));
                            })
                            .expect("x=0");
                })
                .build();

        ScenarioRunner.execute(scenario, this);

        assertEventually(() -> {
            byte[] key = "order:1001".getBytes(StandardCharsets.UTF_8);
            byte[] vNew = "x=1".getBytes(StandardCharsets.UTF_8);
            return List.of(ATHENS, BYZANTIUM, CYRENE).stream().allMatch(node -> {
                 return getNodeValue(node, key).isPresent() && Arrays.equals(vNew, getNodeValue(node, key).get());
             });
         });

        // Consistency verdicts:
        // Later Bob saw older → not linearizable.
        boolean lin = ConsistencyChecker.check(
                scenario.history(),
                ConsistencyChecker.ConsistencyProperty.LINEARIZABILITY,
                ConsistencyChecker.DataModel.REGISTER
        );
        assertEquals(false, lin, "Linearizability mismatch");

        // Different clients (Alice vs Bob), no same-client regression → SC can be satisfied.
        boolean seq = ConsistencyChecker.check(
                scenario.history(),
                ConsistencyChecker.ConsistencyProperty.SEQUENTIAL_CONSISTENCY,
                ConsistencyChecker.DataModel.REGISTER
        );
        assertEquals(true, seq, "Sequential consistency mismatch");
    }




}

package com.tickloom.testkit.dsl.quorum;

import com.tickloom.ConsistencyChecker;
import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumMessageTypes;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.VersionedValue;
import com.tickloom.history.JepsenHistory;
import com.tickloom.testkit.dsl.semanticmodel.DelayMessages;
import com.tickloom.testkit.dsl.semanticmodel.Partition;
import com.tickloom.testkit.dsl.semanticmodel.Reconnect;
import com.tickloom.testkit.dsl.semanticmodel.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DSL port of {@link com.tickloom.testkit.DDIA_Linearizability_And_Quorum_ScenarioTest}.
 *
 * <p>Same DDIA §10.6 scenario expressed declaratively through the dsl builder.
 * The history string in the assertion is the source of truth.
 */
class DDIA_LinearizabilityScenarioTest {

    private static final ProcessId ATHENS    = ProcessId.of("athens");
    private static final ProcessId BYZANTIUM = ProcessId.of("byzantium");
    private static final ProcessId CYRENE    = ProcessId.of("cyrene");

    private static final ProcessId WRITER = ProcessId.of("writer");
    private static final ProcessId ALICE  = ProcessId.of("alice");
    private static final ProcessId BOB    = ProcessId.of("bob");

    private static final String KEY  = "order:1001";
    private static final String VOLD = "x=0";
    private static final String VNEW = "x=1";

    private static final int PROP_DELAY_TICKS = 100;

    @Test
    @DisplayName("DDIA §10.6 via per-link delay: Alice sees new, later Bob sees old (LIN ❌)")
    void ddia106_link_delay_race() throws IOException {
        Scenario<QuorumReplicaClient> scenario = QuorumStepBuilder.scenario("DDIA §10.6 quorum non-linearizable")
                .servers(ATHENS, BYZANTIUM, CYRENE)
                .client(WRITER).connectedTo(ATHENS)
                .client(ALICE).connectedTo(BYZANTIUM)
                .client(BOB).connectedTo(BYZANTIUM)
                .steps(s -> {
                    // Seed initial value across the cluster.
                    s.client(WRITER).writes(KEY, VOLD).awaitCompletion();

                    // Writer updates to VNEW. Replication from ATHENS to the other replicas is
                    // delayed so only ATHENS sees the new value initially. We don't wait for the
                    // write future — only until ATHENS has applied locally.
                    s.client(WRITER).writes(KEY, VNEW)
                            .whileClusterEvent(new DelayMessages(
                                    QuorumMessageTypes.INTERNAL_SET_REQUEST,
                                    ATHENS, List.of(BYZANTIUM, CYRENE), PROP_DELAY_TICKS))
                            .await(cluster -> athensHas(cluster, VNEW));

                    // Alice reads via BYZANTIUM. Partitioning BYZANTIUM<->CYRENE forces the
                    // quorum to include ATHENS (which has VNEW), so Alice returns VNEW.
                    s.client(ALICE).reads(KEY)
                            .whileClusterEvent(new Partition(List.of(BYZANTIUM), List.of(CYRENE)))
                            .awaitCompletion();

                    // Bob reads via BYZANTIUM. We reconnect BYZANTIUM<->CYRENE and then
                    // partition BYZANTIUM<->ATHENS, so the quorum is {BYZANTIUM, CYRENE},
                    // both still at VOLD — Bob sees VOLD even though Alice saw VNEW.
                    // We keep ticking until every replica has converged to VNEW; that lets the
                    // delayed write to BYZANTIUM/CYRENE eventually arrive and the writer's
                    // VNEW future complete (its :ok shows up last in the history).
                    s.client(BOB).reads(KEY)
                            .whileClusterEvent(new Reconnect(BYZANTIUM))
                            .whileClusterEvent(new Partition(List.of(BYZANTIUM), List.of(ATHENS)))
                            .await(DDIA_LinearizabilityScenarioTest::allConvergedToVNew);
                });

        JepsenHistory history = scenario.run();

        assertEquals(
                "[{:process 0, :process-name \"writer\", :type :invoke, :f :write, :value \"x=0\"} " +
                "{:process 0, :process-name \"writer\", :type :ok, :f :write, :value \"x=0\"} " +
                "{:process 0, :process-name \"writer\", :type :invoke, :f :write, :value \"x=1\"} " +
                "{:process 1, :process-name \"alice\", :type :invoke, :f :read, :value nil} " +
                "{:process 1, :process-name \"alice\", :type :ok, :f :read, :value \"x=1\"} " +
                "{:process 2, :process-name \"bob\", :type :invoke, :f :read, :value nil} " +
                "{:process 2, :process-name \"bob\", :type :ok, :f :read, :value \"x=0\"} " +
                "{:process 0, :process-name \"writer\", :type :ok, :f :write, :value \"x=1\"}]",
                history.getEdnString());

        assertFalse(ConsistencyChecker.check(history.getEdnString(), ConsistencyChecker.ConsistencyProperty.LINEARIZABILITY, ConsistencyChecker.DataModel.REGISTER));
        assertTrue(ConsistencyChecker.check(history.getEdnString(), ConsistencyChecker.ConsistencyProperty.SEQUENTIAL_CONSISTENCY, ConsistencyChecker.DataModel.REGISTER));
    }

    private static boolean athensHas(com.tickloom.testkit.Cluster cluster, String expected) {
        VersionedValue stored = cluster.getDecodedStoredValue(ATHENS, KEY.getBytes(StandardCharsets.UTF_8), VersionedValue.class);
        return stored != null && Arrays.equals(expected.getBytes(StandardCharsets.UTF_8), stored.value());
    }

    private static boolean allConvergedToVNew(com.tickloom.testkit.Cluster cluster) {
        byte[] expected = VNEW.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = KEY.getBytes(StandardCharsets.UTF_8);
        return List.of(ATHENS, BYZANTIUM, CYRENE).stream().allMatch(node -> {
            VersionedValue stored = cluster.getDecodedStoredValue(node, keyBytes, VersionedValue.class);
            return stored != null && Arrays.equals(expected, stored.value());
        });
    }
}

package com.tickloom.testkit.dsl.quorum;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.history.JepsenHistory;
import com.tickloom.storage.Storage;
import com.tickloom.testkit.dsl.semanticmodel.ClusterSnapshot;
import com.tickloom.testkit.dsl.semanticmodel.Scenario;
import com.tickloom.testkit.dsl.semanticmodel.ScenarioResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LWW lost update from server clock skew.
 *
 * <p>Athens is at t=1000, Byzantium at t=2000. Bob writes through Byzantium
 * first and is tagged with the later clock; Alice then writes through Athens
 * with the earlier clock. A subsequent read returns Bob's value — Alice's
 * later real-time write loses LWW because her coordinator's clock was behind.
 */
class LWWLostUpdateScenarioTest {

    private static final ProcessId ATHENS    = ProcessId.of("athens");
    private static final ProcessId BYZANTIUM = ProcessId.of("byzantium");
    private static final ProcessId CYRENE    = ProcessId.of("cyrene");

    private static final ProcessId ALICE  = ProcessId.of("alice");
    private static final ProcessId BOB    = ProcessId.of("bob");
    private static final ProcessId READER = ProcessId.of("reader");

    private static final String KEY = "x";

    // QuorumReplica stores VersionedValue JSON; the `value` field is base64.
    private static final String A_AS_BASE64 = "QQ==";  // base64("A")
    private static final String B_AS_BASE64 = "Qg==";  // base64("B")

    @Test
    @DisplayName("LWW: bob's later-clock write silently overwrites alice's later real-time write")
    void lww_lost_update_from_server_clock_skew() throws IOException {
        Scenario<QuorumReplicaClient> scenario =
                QuorumStepBuilder.scenario("LWW lost update via server clock skew")
                        .servers(ATHENS, BYZANTIUM, CYRENE)
                        .clients(ALICE, BOB, READER)
                        .client(ALICE).connectedTo(ATHENS)
                        .client(BOB).connectedTo(BYZANTIUM)
                        .given(g -> g.serverTimeAt(ATHENS, 1_000L)
                                     .serverTimeAt(BYZANTIUM, 2_000L))
                        .steps(s -> {
                            s.client(BOB).writes(KEY, "B").expectSuccess();
                            s.client(ALICE).writes(KEY, "A").expectSuccess();

                            s.client(READER).reads(KEY)
                                    .expectResponse(v -> "B".equals(v));
                        });

        ScenarioResult result = scenario.run();

        JepsenHistory history = result.history();
        assertEquals(
                "[{:process 0, :process-name \"bob\", :type :invoke, :f :write, :value \"B\"} " +
                "{:process 0, :process-name \"bob\", :type :ok, :f :write, :value \"B\"} " +
                "{:process 1, :process-name \"alice\", :type :invoke, :f :write, :value \"A\"} " +
                "{:process 1, :process-name \"alice\", :type :ok, :f :write, :value \"A\"} " +
                "{:process 2, :process-name \"reader\", :type :invoke, :f :read, :value nil} " +
                "{:process 2, :process-name \"reader\", :type :ok, :f :read, :value \"B\"}]",
                history.getEdnString());

        ClusterSnapshot snapshot = result.snapshot();
        assertReplicaHasValue(snapshot, ATHENS, B_AS_BASE64, A_AS_BASE64);
        assertReplicaHasValue(snapshot, BYZANTIUM, B_AS_BASE64, A_AS_BASE64);
        assertReplicaHasValue(snapshot, CYRENE, B_AS_BASE64, A_AS_BASE64);
    }

    @Test
    @DisplayName("LWW: with clocks in sync alice's later write wins — reader sees A")
    void lww_does_not_reproduce_when_clocks_are_in_sync() throws IOException {
        Scenario<QuorumReplicaClient> scenario =
                QuorumStepBuilder.scenario("LWW with clocks in sync — alice's later write wins")
                        .servers(ATHENS, BYZANTIUM, CYRENE)
                        .clients(ALICE, BOB, READER)
                        .client(ALICE).connectedTo(ATHENS)
                        .client(BOB).connectedTo(BYZANTIUM)
                        .given(g -> g.serverTimeAt(ATHENS, 1_000L)
                                     .serverTimeAt(BYZANTIUM, 1_000L))
                        .steps(s -> {
                            s.client(BOB).writes(KEY, "B").expectSuccess();
                            s.client(ALICE).writes(KEY, "A").expectSuccess();

                            // Both coordinators start at the same clock, so alice's write —
                            // issued after bob's — picks up the later timestamp and wins LWW.
                            s.client(READER).reads(KEY)
                                    .expectResponse(v -> "A".equals(v));
                        });

        ScenarioResult result = scenario.run();

        ClusterSnapshot snapshot = result.snapshot();
        assertReplicaHasValue(snapshot, ATHENS, A_AS_BASE64, B_AS_BASE64);
        assertReplicaHasValue(snapshot, BYZANTIUM, A_AS_BASE64, B_AS_BASE64);
        assertReplicaHasValue(snapshot, CYRENE, A_AS_BASE64, B_AS_BASE64);
    }

    /**
     * Reads the raw stored bytes for {@code KEY} from {@code processId}'s storage
     * (without decoding the {@code VersionedValue} JSON) and asserts the bytes
     * contain {@code expectedBase64} as a substring and do not contain {@code rejectedBase64}.
     */
    private static void assertReplicaHasValue(ClusterSnapshot snapshot,
                                              ProcessId processId,
                                              String expectedBase64,
                                              String rejectedBase64) {
        QuorumReplica replica = snapshot.node(processId);
        assertNotNull(replica, processId + " replica should be inspectable from snapshot");

        Storage storage = replica.getStorage();
        TickCompletableFuture<byte[]> future = storage.get(KEY.getBytes(StandardCharsets.UTF_8));
        while (!future.isCompleted()) {
            storage.tick();
        }
        byte[] raw = future.getResult();
        assertNotNull(raw, processId + " should have a persisted value for '" + KEY + "'");

        String stored = new String(raw, StandardCharsets.UTF_8);
        assertTrue(stored.contains("\"" + expectedBase64 + "\""),
                processId + " should have base64 " + expectedBase64 + " persisted, got: " + stored);
        assertTrue(!stored.contains("\"" + rejectedBase64 + "\""),
                processId + " should NOT have base64 " + rejectedBase64 + " persisted, got: " + stored);
    }
}

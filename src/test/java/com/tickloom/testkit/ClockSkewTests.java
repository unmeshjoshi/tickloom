package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.future.ListenableFuture;
import com.tickloom.history.History;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ClockSkewTests extends ClusterTest<QuorumReplicaClient, GetResponse, String> {
    //Process Ids for cluster nodes
    private static final ProcessId ATHENS = ProcessId.of("athens");
    private static final ProcessId BYZANTIUM = ProcessId.of("byzantium");
    private static final ProcessId CYRENE = ProcessId.of("cyrene");
    private static final ProcessId DELPHI = ProcessId.of("delphi");
    private static final ProcessId SPARTA = ProcessId.of("sparta");

    //Process Ids for clients
    private static final ProcessId clientId1 = ProcessId.of("client1");
    private static final ProcessId clientId2 = ProcessId.of("client2");

    //Number of ticks representing clock skew across nodes
    private static final int SKEW_TICKS = 10;

    public ClockSkewTests() throws IOException {
        super(List.of(ATHENS, BYZANTIUM, CYRENE, DELPHI, SPARTA), QuorumReplica::new, QuorumReplicaClient::new, (response) ->
                maskNull(response.value()));
    }


    @Test
    @DisplayName("Stale read because of clock skew after own write breaks both, linearizability and sequential consistency")
        void clockSkewCausesStaleReadsIn_LWW_QuorumImplementations() {
        // Step 0: clients connected to different servers
        var client1 = clientConnectedTo(clientId1, CYRENE);
        var client2 = clientConnectedTo(clientId2, ATHENS);

        byte[] key = "k".getBytes();
        byte[] v1 = "v1".getBytes();
        byte[] v2 = "v2".getBytes();

        // Partition: CYRENE-side majority
        partition(NodeGroup.of(CYRENE, DELPHI, SPARTA), NodeGroup.of(ATHENS, BYZANTIUM))
                // Step 1: client1 writes v1 successfully (ok) via CYRENE
                .write(withWriter(client1, key, v1))
                // Minority (ATHENS/BYZANTIUM) never received v1
                .assertNoValue(key, Arrays.asList(ATHENS, BYZANTIUM))
                //Step 2: Reconfigure partitions: ATHENS joins CYRENE majority
                .healAllPartitions()
                .partition(NodeGroup.of(ATHENS, BYZANTIUM, CYRENE), NodeGroup.of(DELPHI, SPARTA))
                // Step 3: skew ATHENS behind; permissive LWW will accept lower-ts write
                .clockSkew(ATHENS, timestampOfStoredValue(CYRENE, key) - SKEW_TICKS)
                // Step 4: client2 writes v2 via ATHENS; succeeds
                .write(withWriter(client2, key, v2))
                // State check: ATHENS/BYZANTIUM→v2, CYRENE→v1
                .assertNodesContainValue(List.of(ATHENS, BYZANTIUM), key, v2)
                .assertNodesContainValue(List.of(CYRENE), key, v1);

        var reconnectedClient2 = clientConnectedTo(clientId2, CYRENE);
        // Step 5: client2 reconnects and sends reads to CYRENE which has (stale v1)
        // at higher timestamp. So Last Write Wins rule picks up v1 over v2.
        read(withReader(reconnectedClient2, key));

        assertEquals("[{:process 0, :process-name \"client1\", :type :invoke, :f :write, :value \"v1\"} " +
                        "{:process 0, :process-name \"client1\", :type :ok, :f :write, :value \"v1\"}" +
                        " {:process 1, :process-name \"client2\", :type :invoke, :f :write, :value \"v2\"} " +
                        "{:process 1, :process-name \"client2\", :type :ok, :f :write, :value \"v2\"} " +
                        "{:process 1, :process-name \"client2\", :type :invoke, :f :read, :value nil} " +
                        "{:process 1, :process-name \"client2\", :type :ok, :f :read, :value \"v1\"}]",
                getHistory().toEdn());

        //Invoke Jepsen's linearizability checker.
        assertLinearizability(false);

        //Invoke sequential consistency checker.
        assertSequentialConsistency(false);
    }

    private static Reader<QuorumReplicaClient, GetResponse, String> withReader(QuorumReplicaClient reconnectedClient2, byte[] key) {
        return new Reader<>(reconnectedClient2) {
            @Override
            public Supplier<ListenableFuture<GetResponse>> getSupplier() {
                return () -> client.get(key); // <-- return the lambda
            }
        };
    }

    private Writer<QuorumReplicaClient, String> withWriter(QuorumReplicaClient client2, byte[] key, byte[] value) {
        return new Writer<>(client2, new String(key), new String(value)) {

            @Override
            public String attemptedValue() {
                return this.value;
            }

            @Override
            public Supplier<ListenableFuture<?>> getSupplier() {
                return () -> client.set(this.key.getBytes(), this.value.getBytes());
            }
        };
    }
}

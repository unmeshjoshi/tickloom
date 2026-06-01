package com.tickloom.testkit.dsl2.quorum;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.history.HistoryRecorder;
import com.tickloom.history.Op;
import com.tickloom.testkit.Cluster;
import com.tickloom.testkit.dsl2.Action;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public record QuorumReadAction(ProcessId clientId, String key)
        implements Action<QuorumReplicaClient, String> {

    @Override
    public TickCompletableFuture<String> executeWith(Map<ProcessId, QuorumReplicaClient> clients,
                                                     Cluster cluster,
                                                     HistoryRecorder<String> recorder) {
        QuorumReplicaClient client = clients.get(clientId);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        TickCompletableFuture<GetResponse> raw = recorder.invoke(
                clientId,
                Op.READ,
                null,
                () -> client.get(keyBytes),
                QuorumReadAction::maskNull);
        return raw.thenApply(QuorumReadAction::maskNull);
    }

    private static String maskNull(GetResponse response) {
        return response == null || response.value() == null
                ? null
                : new String(response.value(), StandardCharsets.UTF_8);
    }
}

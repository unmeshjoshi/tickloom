package com.tickloom.testkit.dsl2.quorum;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.history.HistoryRecorder;
import com.tickloom.history.Op;
import com.tickloom.testkit.Cluster;
import com.tickloom.testkit.dsl2.Action;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public record QuorumWriteAction(ProcessId clientId, String key, String value)
        implements Action<QuorumReplicaClient, String> {

    @Override
    public TickCompletableFuture<String> executeWith(Map<ProcessId, QuorumReplicaClient> clients,
                                                     Cluster cluster,
                                                     HistoryRecorder<String> recorder) {
        QuorumReplicaClient client = clients.get(clientId);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        TickCompletableFuture<SetResponse> raw = recorder.invoke(
                clientId,
                Op.WRITE,
                value,
                () -> client.set(keyBytes, valueBytes),
                response -> value);
        return raw.thenApply(resp -> value);
    }
}

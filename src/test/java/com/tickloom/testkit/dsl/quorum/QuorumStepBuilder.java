package com.tickloom.testkit.dsl.quorum;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.history.Op;
import com.tickloom.testkit.dsl.EventOrAwaitScope;
import com.tickloom.testkit.dsl.Scenarios;
import com.tickloom.testkit.dsl.ServerStep;
import com.tickloom.testkit.dsl.StepBuilder;
import com.tickloom.testkit.dsl.semanticmodel.Action;
import com.tickloom.testkit.dsl.semanticmodel.ClusterEvent;

import java.nio.charset.StandardCharsets;

/**
 * Sole DSL surface for quorum-KV scenarios: static entry point, action verbs
 * (from {@link QuorumActionScope}), and setup verbs (from
 * {@link QuorumSetupScope}). One class per protocol.
 */
public final class QuorumStepBuilder
        extends StepBuilder<QuorumReplicaClient, QuorumActionScope, QuorumSetupScope>
        implements QuorumActionScope, QuorumSetupScope {

    public static ServerStep<QuorumReplicaClient, QuorumActionScope, QuorumSetupScope> scenario(String name) {
        return Scenarios.scenario(name, QuorumReplica::new, QuorumReplicaClient::new, QuorumStepBuilder::new);
    }

    @Override
    protected QuorumActionScope actionBuilder() {
        return this;
    }

    @Override
    protected QuorumSetupScope setupBuilder() {
        return this;
    }

    // ---- Action verbs ----

    @Override
    public EventOrAwaitScope<QuorumActionScope> writes(String key, String value) {
        ProcessId clientId = currentClientId();
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        Action<QuorumReplicaClient, String> action = (clients, cluster, recorder) ->
                recorder.invoke(clientId, Op.WRITE, value,
                                () -> clients.get(clientId).set(keyBytes, valueBytes),
                                response -> value)
                        .thenApply(response -> value);
        return beginStep(action);
    }

    @Override
    public EventOrAwaitScope<QuorumActionScope> reads(String key) {
        ProcessId clientId = currentClientId();
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        Action<QuorumReplicaClient, String> action = (clients, cluster, recorder) ->
                recorder.invoke(clientId, Op.READ, null,
                                () -> clients.get(clientId).get(keyBytes),
                                QuorumStepBuilder::valueOf)
                        .thenApply(QuorumStepBuilder::valueOf);
        return beginStep(action);
    }

    // ---- Setup verbs ----

    @Override
    public QuorumSetupScope serverTimeAt(ProcessId node, long time) {
        addGiven(cluster -> cluster.setTimeForProcess(node, time));
        return this;
    }

    @Override
    public QuorumSetupScope clientTimeAt(ProcessId client, long time) {
        addGiven(cluster -> cluster.setTimeForProcess(client, time));
        return this;
    }

    @Override
    public QuorumSetupScope apply(ClusterEvent event) {
        addGiven(event);
        return this;
    }

    private static String valueOf(GetResponse response) {
        return response == null || response.value() == null
                ? null
                : new String(response.value(), StandardCharsets.UTF_8);
    }
}

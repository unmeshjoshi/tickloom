package com.tickloom.testkit.dsl;

import com.tickloom.testkit.ClusterTest;
import com.tickloom.testkit.NodeGroup;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.algorithms.replication.quorum.QuorumMessageTypes;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.algorithms.replication.quorum.VersionedValue;
import com.tickloom.history.Op;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ScenarioRunner {

    public static void execute(Scenario scenario, ClusterTest<QuorumReplicaClient, GetResponse, String> test) {
        Map<String, QuorumReplicaClient> clientMap = setupClients(scenario, test);

        for (OpDef opDef : scenario.getOps()) {
            applyClusterBehaviours(opDef, test);
            executeOperation(scenario, opDef, clientMap.get(opDef.getClientName()), test);
        }
    }

    private static Map<String, QuorumReplicaClient> setupClients(Scenario scenario, ClusterTest<QuorumReplicaClient, GetResponse, String> test) {
        Map<String, QuorumReplicaClient> clientMap = new HashMap<>();
        for (ClientDef clientDef : scenario.getClients().values()) {
            QuorumReplicaClient client = test.clientConnectedTo(
                    ProcessId.of(clientDef.getName()),
                    ProcessId.of(clientDef.getConnectedTo())
            );
            clientMap.put(clientDef.getName(), client);
        }
        return clientMap;
    }

    private static void applyClusterBehaviours(OpDef opDef, ClusterTest<?,?,?> test) {
        ClusterBehaviourBuilder behaviourImpl = new ClusterBehaviourBuilder() {
            @Override
            public ClusterBehaviourBuilder delayedMessages(String from, List<String> to, int ticks) {
                List<ProcessId> toIds = to.stream().map(ProcessId::of).toList();
                test.delayForMessageType(QuorumMessageTypes.INTERNAL_SET_REQUEST, ProcessId.of(from), toIds, ticks);
                return this;
            }

            @Override
            public ClusterBehaviourBuilder partition(List<String> group1, List<String> group2) {
                List<ProcessId> g1 = group1.stream().map(ProcessId::of).toList();
                List<ProcessId> g2 = group2.stream().map(ProcessId::of).toList();
                test.partition(NodeGroup.of(g1.toArray(new ProcessId[0])), NodeGroup.of(g2.toArray(new ProcessId[0])));
                return this;
            }

            @Override
            public ClusterBehaviourBuilder reconnect(String processId) {
                test.cluster.reconnectProcess(ProcessId.of(processId));
                return this;
            }
        };

        for (Consumer<ClusterBehaviourBuilder> cb : opDef.getBehaviours()) {
            cb.accept(behaviourImpl);
        }
    }

    private static void executeOperation(Scenario scenario, OpDef opDef, QuorumReplicaClient client, ClusterTest<?,?,?> test) {
        if (opDef.getAction() instanceof WriteAction writeDef) {
            executeWrite(scenario, opDef, client, writeDef, test);
        } else if (opDef.getAction() instanceof ReadAction readDef) {
            executeRead(scenario, client, readDef, test);
        }
    }

    private static void executeWrite(Scenario scenario, OpDef opDef, QuorumReplicaClient client, WriteAction writeDef, ClusterTest<?,?,?> test) {
        byte[] keyBytes = writeDef.key().getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = writeDef.value().getBytes(StandardCharsets.UTF_8);

        var fut = scenario.getRecorder().<SetResponse>invoke(
                client.id, Op.WRITE, writeDef.value(),
                () -> client.set(keyBytes, valueBytes),
                response -> writeDef.value()
        );

        if (opDef.getWaitUntilNode() != null) {
            test.cluster.tickUntil(() -> {
                VersionedValue storageValue = test.cluster.getDecodedStoredValue(
                        ProcessId.of(opDef.getWaitUntilNode()), keyBytes, VersionedValue.class);
                if (storageValue == null) return false;
                String stringValue = new String(storageValue.value(), StandardCharsets.UTF_8);
                return opDef.getWaitCondition().test(stringValue);
            });
        } else {
            test.cluster.tickUntil(() -> fut.isCompleted() && !fut.isFailed());
        }
    }

    private static void executeRead(Scenario scenario, QuorumReplicaClient client, ReadAction readDef, ClusterTest<?,?,?> test) {
        byte[] keyBytes = readDef.key().getBytes(StandardCharsets.UTF_8);

        var fut = scenario.getRecorder().<GetResponse>invoke(
                client.id, Op.READ, (String) null,
                () -> client.get(keyBytes),
                ScenarioRunner::maskNull
        );
        test.assertEventually(() -> fut.isCompleted() && !fut.isFailed());
    }

    private static String maskNull(GetResponse response) {
        return response == null || response.value() == null ? null : new String(response.value(), StandardCharsets.UTF_8);
    }
}

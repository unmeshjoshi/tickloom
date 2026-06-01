package com.tickloom.testkit.dsl2.quorum;

import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.testkit.dsl2.ClientStep;
import com.tickloom.testkit.dsl2.StepBuilder;
import com.tickloom.testkit.dsl2.Steps;

public final class QuorumClientStep extends ClientStep<QuorumReplicaClient> {

    QuorumClientStep(String name, Steps<QuorumReplicaClient, ?> owner) {
        super(name, owner);
    }

    public StepBuilder<QuorumReplicaClient, String> writes(String key, String value) {
        return record(new QuorumWriteAction(clientId, key, value));
    }

    public StepBuilder<QuorumReplicaClient, String> reads(String key) {
        return record(new QuorumReadAction(clientId, key));
    }
}

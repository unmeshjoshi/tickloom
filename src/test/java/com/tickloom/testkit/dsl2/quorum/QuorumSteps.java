package com.tickloom.testkit.dsl2.quorum;

import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.testkit.dsl2.Steps;

public final class QuorumSteps extends Steps<QuorumReplicaClient, QuorumClientStep> {
    @Override
    protected QuorumClientStep newHandle(String name) {
        return new QuorumClientStep(name, this);
    }
}

package com.tickloom.algorithms.replication.quorum;

import com.tickloom.Process;
import com.tickloom.ProcessParams;
import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.storage.Storage;

import java.util.List;

public class QuorumReplicaProcessFactory implements ProcessFactory {
    @Override
    public Process create(List<ProcessId> peerIds, ProcessParams processParams) {
        return new QuorumReplica(peerIds, processParams);
    }
}

package com.tickstep;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplicaTest {

    @Test
    public void createsReplicaWithIdAndPeerIdList() {
        List<ProcessId> peerIds = List.of(ProcessId.of("server2"), ProcessId.of("server3"));
        Replica replica = new Replica(ProcessId.of("server1"), peerIds);
        assertEquals(ProcessId.of("server1"), replica.id);
        assertEquals(peerIds, replica.peerIds);
    }

}
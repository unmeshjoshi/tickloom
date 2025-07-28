package com.tickstep;

import java.util.List;

public class Replica extends Process {
    public final List<ProcessId> peerIds;

    public <E> Replica(ProcessId random, List<ProcessId> peerIds) {
        super(random);
        this.peerIds = peerIds;
    }

    @Override
    public void tick() {

    }
}

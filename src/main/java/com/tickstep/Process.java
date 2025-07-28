package com.tickstep;

public abstract class Process {
    public final ProcessId id;
    public Process(ProcessId id) {
        this.id = id;
    }
    public abstract void tick();
}

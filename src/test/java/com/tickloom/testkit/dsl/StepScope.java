package com.tickloom.testkit.dsl;

import com.tickloom.ProcessId;
import com.tickloom.testkit.Cluster;

import java.util.function.Predicate;

public interface StepScope<T extends ActionScope> {
    T client(ProcessId id);

    StepScope<T> await(Predicate<Cluster> condition);
}

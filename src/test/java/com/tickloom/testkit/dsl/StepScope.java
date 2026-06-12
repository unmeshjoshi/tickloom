package com.tickloom.testkit.dsl;

import com.tickloom.ProcessId;

public interface StepScope<T extends ActionScope> {
    T client(ProcessId id);
}

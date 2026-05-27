package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;

public record Step(ProcessId clientId, Action action, StepCondition condition) {}

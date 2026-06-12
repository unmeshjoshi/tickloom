package com.tickloom.testkit.dsl.semanticmodel;

import com.tickloom.ProcessId;

public record ClientDef(ProcessId id, ProcessId connectedTo) { }

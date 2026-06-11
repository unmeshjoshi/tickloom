package com.tickloom.testkit.dsl2.semanticmodel;

import com.tickloom.ProcessId;

public record ClientDef(ProcessId id, ProcessId connectedTo) { }

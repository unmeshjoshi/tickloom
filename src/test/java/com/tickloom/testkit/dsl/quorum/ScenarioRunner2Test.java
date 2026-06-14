package com.tickloom.testkit.dsl.quorum;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.history.JepsenHistory;
import com.tickloom.testkit.dsl.semanticmodel.Scenario;
import org.junit.jupiter.api.Test;

import java.io.IOException;

class ScenarioRunner2Test {

    /**
     * QuorumScenarios.scenario("name")
     *   .servers(athens, byzantium, cyrene)
     *   .client(alice).connectedTo(athens)
     *   .steps(s -> s.client(alice).writes("k","v")
     *                  .whileClusterEvent(partition(...))
     *                  .expectSuccess())
     */
    @Test
    public void shouldRunWriteStep() throws IOException {
        ProcessId athens   = ProcessId.of("athens");
        ProcessId byzantium = ProcessId.of("byzantium");
        ProcessId cyrene    = ProcessId.of("cyrene");
        ProcessId alice     = ProcessId.of("alice");

        Scenario<QuorumReplicaClient> scenario = QuorumStepBuilder.scenario("quorum write")
                .servers(athens, byzantium, cyrene)
                .clients(alice)
                .client(alice).connectedTo(athens)
                .steps(s -> s.client(alice).writes("key", "value").expectSuccess());

        JepsenHistory history = scenario.run();

        System.out.println("history = " + history.getEdnString());
    }
}

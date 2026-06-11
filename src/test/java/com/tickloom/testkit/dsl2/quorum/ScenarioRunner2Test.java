package com.tickloom.testkit.dsl2.quorum;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.history.JepsenHistory;
import com.tickloom.testkit.dsl2.semanticmodel.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

class ScenarioRunner2Test {

    /**
     * scenario("").topology(servers, of type,
     *                       t.clients.of type
     *                       t.client.(alice).connectedTo())
     *            .steps(s ->
     *             s.client("alice").writes("key", "value).
     *                  whileClusterEvent(delay(messageType)),
     *                  whileClusterEvent(partition(athens, byzantium))
     *                  .awaitCompletion() or await(node(athens).hasValue())
     *            s.
     *
     */

    static class ScenarioBuilder {
        private String name;

        public ScenarioBuilder scenario(String name) {
            this.name = name;
            return this;
        }

        static class StepBuilder {
            private String clientId;
            private Action action;
            private AwaitCompletion awaitCompletion;
            private ClusterEvent event;

            public StepBuilder client(String clientId) {
                this.clientId = clientId;
                return this;
            }

            public StepBuilder writes(String key, String value) {
              var action =  new QuorumWriteAction(ProcessId.of(clientId), key, value);
              return this;
            }
        }
        StepBuilder stepBuilder = new StepBuilder();
        public void steps(Consumer<StepBuilder> builder) {
            builder.accept(stepBuilder);
        }
    }
    @Test
    public void shouldRunWriteStep() throws IOException {
        var s =  new Scenario("quorum write",
               List.of(ProcessId.of("athens"),
                       ProcessId.of("byznatium"),
                       ProcessId.of("cyrene")),
               List.of(new ClientDef(ProcessId.of("alice"), ProcessId.of("athens"))),
               QuorumReplica::new,
               QuorumReplicaClient::new,
               List.of(
                       new Step(new QuorumWriteAction(ProcessId.of("alice"), "key", "value")).withAwait(new AwaitCompletion())));

        s.run();
        JepsenHistory history = s.history();
        System.out.println("history = " + history.getEdnString());

    }
}

package com.tickloom.testkit.dsl;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class ScenarioBuilder {
    private final Scenario scenario;

    private ScenarioBuilder(String name) {
        this.scenario = new Scenario(name);
    }

    public static ScenarioBuilder scenario(String name) {
        return new ScenarioBuilder(name);
    }

    public ScenarioBuilder topology(Consumer<TopologyBuilder> topologySpec) {
        topologySpec.accept(new TopologyBuilderImpl(scenario));
        return this;
    }

    public ScenarioBuilder ops(Consumer<OpsBuilder> opsSpec) {
        opsSpec.accept(new OpsBuilderImpl(scenario));
        return this;
    }

    public Scenario build() {
        return scenario;
    }

    // --- Interfaces ---

    public interface ClusterNodesBuilder {
        TopologyBuilder ofType(Class<?> type);
    }

    public interface ClientsBuilder {
        ClientSpec client(String name);
    }

    public interface TopologyBuilder {
        ClusterNodesBuilder clusterNodes(String... names);
        ClusterNodesBuilder clusterNodes(List<String> names);
        TopologyBuilder clients(Consumer<ClientsBuilder> clientsSpec);
    }

    public interface OpsBuilder {
        ClientOpBuilder client(String name);
    }

    public interface ClientOpBuilder {
        StepBuilder performs(Action action);
    }

    // --- Concrete Implementations ---

    private static class TopologyBuilderImpl implements TopologyBuilder {
        private final Scenario scenario;

        public TopologyBuilderImpl(Scenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public ClusterNodesBuilder clusterNodes(String... names) {
            return clusterNodes(Arrays.asList(names));
        }

        @Override
        public ClusterNodesBuilder clusterNodes(List<String> names) {
            return type -> {
                for (String name : names) {
                    scenario.addNode(new NodeDef(name, type));
                }
                return this;
            };
        }

        @Override
        public TopologyBuilder clients(Consumer<ClientsBuilder> clientsSpec) {
            clientsSpec.accept(new ClientsBuilderImpl(scenario));
            return this;
        }
    }

    public static class ClientSpec {
        private final ClientDef def;
        private final ClientsBuilder builder;
        private final Scenario scenario;

        public ClientSpec(String name, Scenario scenario, ClientsBuilder builder) {
            this.def = new ClientDef(name, null); // Will be updated
            this.scenario = scenario;
            this.builder = builder;
            scenario.addClient(def);
        }

        public ClientsBuilder connectedTo(String processId) {
            ClientDef updatedDef = new ClientDef(def.getName(), processId);
            scenario.addClient(updatedDef);
            return builder;
        }
    }

    private static class ClientsBuilderImpl implements ClientsBuilder {
        private final Scenario scenario;

        public ClientsBuilderImpl(Scenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public ClientSpec client(String name) {
            return new ClientSpec(name, scenario, this);
        }
    }

    private static class OpsBuilderImpl implements OpsBuilder {
        private final Scenario scenario;

        public OpsBuilderImpl(Scenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public ClientOpBuilder client(String name) {
            return new ClientOpBuilderImpl(name, scenario);
        }
    }

    private static class ClientOpBuilderImpl implements ClientOpBuilder {
        private final String clientName;
        private final Scenario scenario;

        public ClientOpBuilderImpl(String clientName, Scenario scenario) {
            this.clientName = clientName;
            this.scenario = scenario;
        }

        @Override
        public StepBuilder performs(Action action) {
            OpDef def = new OpDef();
            def.setClientName(clientName);
            def.setAction(action);
            scenario.addOp(def);
            return new StepBuilder(def);
        }
    }

    public static class StepBuilder {
        private final OpDef def;

        public StepBuilder(OpDef def) {
            this.def = def;
        }

        public StepBuilder withClusterBehaviour(Consumer<ClusterBehaviourBuilder> cb) {
            def.addBehaviour(cb);
            return this;
        }

        public StepBuilder waitUntil(String node, Predicate<String> condition) {
            def.setWaitUntilNode(node);
            def.setWaitCondition(condition);
            return this;
        }

        public StepBuilder expectPending() {
            def.setExpectPending(true);
            return this;
        }

        public StepBuilder expect(String value) {
            def.setExpectedValue(value);
            return this;
        }
    }
}

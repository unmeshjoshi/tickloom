package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.ClusterClient;
import com.tickloom.testkit.Cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Topology declaration. Factory lambdas (not Class objects) are used so a constructor refactor
 * in a replica or client becomes a compile error here, not a runtime crash.
 *
 * <pre>{@code
 * t.servers("athens","byzantium","cyrene").ofType(QuorumReplica::new)
 *  .clients(QuorumReplicaClient::new)
 *  .stepsType(QuorumSteps::new)
 *  .client("alice").connectedTo("athens")
 *  .client("bob")                                  // broadcast (no connectedTo)
 * }</pre>
 */
public final class TopologyBuilder {
    final State state;

    TopologyBuilder(State state) { this.state = state; }

    public ServersStage servers(String... ids) {
        for (String id : ids) state.serverIds.add(ProcessId.of(id));
        return new ServersStage(state);
    }

    /** Returned from {@code servers(...)}; awaits {@code .ofType(factory)}. */
    public static final class ServersStage {
        private final State state;
        ServersStage(State state) { this.state = state; }

        public TopologyAfterServers ofType(ProcessFactory replicaFactory) {
            state.replicaFactory = replicaFactory;
            return new TopologyAfterServers(state);
        }
    }

    /** Awaits {@code .clients(factory)}. */
    public static final class TopologyAfterServers {
        private final State state;
        TopologyAfterServers(State state) { this.state = state; }

        public <C extends ClusterClient> TopologyAfterClient<C> clients(Cluster.ClientFactory<C> clientFactory) {
            state.clientFactory = clientFactory;
            return new TopologyAfterClient<>(state);
        }
    }

    /** Awaits {@code .stepsType(supplier)}. Supplier's S is constrained to {@code Steps<C, ?>}. */
    public static final class TopologyAfterClient<C extends ClusterClient> {
        private final State state;
        TopologyAfterClient(State state) { this.state = state; }

        public <S extends Steps<C, ? extends ClientStep<C>>>
        TypedTopology<C, S> stepsType(Supplier<S> stepsFactory) {
            state.stepsFactory = stepsFactory;
            return new TypedTopology<>(state);
        }
    }

    /** Fully-typed: clients can be added; downstream steps lambda is typed via S. */
    public static class TypedTopology<C extends ClusterClient, S extends Steps<C, ?>> {
        final State state;
        TypedTopology(State state) { this.state = state; }

        public ClientStage<C, S> client(String name) {
            state.clientDefs.add(new ClientDef(ProcessId.of(name), null));
            return new ClientStage<>(state);
        }
    }

    /** Adds {@code .connectedTo(...)} after {@code .client(name)}; inherits chaining. */
    public static final class ClientStage<C extends ClusterClient, S extends Steps<C, ?>>
            extends TypedTopology<C, S> {
        ClientStage(State state) { super(state); }

        public ClientStage<C, S> connectedTo(String node) {
            int last = state.clientDefs.size() - 1;
            ClientDef cd = state.clientDefs.get(last);
            state.clientDefs.set(last, new ClientDef(cd.id(), ProcessId.of(node)));
            return this;
        }
    }

    static final class State {
        final String name;
        final List<ProcessId> serverIds = new ArrayList<>();
        final List<ClientDef> clientDefs = new ArrayList<>();
        ProcessFactory replicaFactory;
        Cluster.ClientFactory<?> clientFactory;
        Supplier<? extends Steps<?, ?>> stepsFactory;

        State(String name) { this.name = name; }

        void validate() {
            if (serverIds.isEmpty()) throw new IllegalStateException("topology has no servers");
            if (clientDefs.isEmpty()) throw new IllegalStateException("topology has no clients");
        }
    }
}

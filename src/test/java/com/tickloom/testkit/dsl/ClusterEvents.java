package com.tickloom.testkit.dsl;

import com.tickloom.ProcessId;
import com.tickloom.messaging.MessageType;
import com.tickloom.testkit.dsl.semanticmodel.DelayMessages;
import com.tickloom.testkit.dsl.semanticmodel.Partition;
import com.tickloom.testkit.dsl.semanticmodel.Reconnect;

import java.util.List;

/**
 * English-like factory methods for {@link com.tickloom.testkit.dsl.semanticmodel.ClusterEvent}s.
 *
 * <p>Intended to be used with {@code import static}, e.g.
 * <pre>{@code
 *   .whileClusterEvent(partition(BYZANTIUM).from(CYRENE))
 *   .whileClusterEvent(reconnect(BYZANTIUM))
 *   .whileClusterEvent(delay(INTERNAL_SET_REQUEST).from(ATHENS).to(BYZANTIUM, CYRENE).byTicks(100))
 * }</pre>
 */
public final class ClusterEvents {

    private ClusterEvents() {}

    public static PartitionBuilder partition(ProcessId... nodes) {
        return new PartitionBuilder(List.of(nodes));
    }

    public static Reconnect reconnect(ProcessId node) {
        return new Reconnect(node);
    }

    public static DelayBuilder delay(MessageType messageType) {
        return new DelayBuilder(messageType);
    }

    public static final class PartitionBuilder {
        private final List<ProcessId> side1;

        private PartitionBuilder(List<ProcessId> side1) {
            this.side1 = side1;
        }

        public Partition from(ProcessId... others) {
            return new Partition(side1, List.of(others));
        }
    }

    public static final class DelayBuilder {
        private final MessageType messageType;
        private ProcessId from;
        private List<ProcessId> to;

        private DelayBuilder(MessageType messageType) {
            this.messageType = messageType;
        }

        public DelayBuilder from(ProcessId from) {
            this.from = from;
            return this;
        }

        public DelayBuilder to(ProcessId... to) {
            this.to = List.of(to);
            return this;
        }

        public DelayMessages byTicks(int ticks) {
            return new DelayMessages(messageType, from, to, ticks);
        }
    }
}

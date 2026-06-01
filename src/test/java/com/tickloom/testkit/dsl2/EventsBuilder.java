package com.tickloom.testkit.dsl2;

import com.tickloom.ProcessId;
import com.tickloom.messaging.MessageType;

import java.util.ArrayList;
import java.util.List;

/**
 * Used inside {@code duringClusterEvents(e -> ...)}. Each chain on {@code e} adds one event.
 * Delay is staged so {@code from} must precede {@code to} must precede {@code byTicks}.
 *
 * <pre>{@code
 * .duringClusterEvents(e -> e
 *     .delay(QuorumMessageTypes.INTERNAL_SET_REQUEST)
 *         .from("athens").to("byzantium","cyrene").byTicks(100)
 *     .partition("byzantium").from("cyrene")
 *     .recover("byzantium"))
 * }</pre>
 */
public final class EventsBuilder {
    private final List<ClusterEvent> events = new ArrayList<>();

    public DelayFromStage delay(MessageType messageType) {
        return new DelayFromStage(this, messageType);
    }

    public PartitionFromStage partition(String... group) {
        return new PartitionFromStage(this, toIds(group));
    }

    public EventsBuilder recover(String node) {
        events.add(new Reconnect(ProcessId.of(node)));
        return this;
    }

    ClusterEvent toEvent() {
        if (events.isEmpty()) return ClusterEvent.NO_OP;
        if (events.size() == 1) return events.get(0);
        return new CompositeEvent(List.copyOf(events));
    }

    public static final class DelayFromStage {
        private final EventsBuilder owner;
        private final MessageType messageType;

        DelayFromStage(EventsBuilder owner, MessageType messageType) {
            this.owner = owner;
            this.messageType = messageType;
        }

        public DelayToStage from(String node) {
            return new DelayToStage(owner, messageType, ProcessId.of(node));
        }
    }

    public static final class DelayToStage {
        private final EventsBuilder owner;
        private final MessageType messageType;
        private final ProcessId from;

        DelayToStage(EventsBuilder owner, MessageType messageType, ProcessId from) {
            this.owner = owner;
            this.messageType = messageType;
            this.from = from;
        }

        public DelayByTicksStage to(String... nodes) {
            return new DelayByTicksStage(owner, messageType, from, toIds(nodes));
        }
    }

    public static final class DelayByTicksStage {
        private final EventsBuilder owner;
        private final MessageType messageType;
        private final ProcessId from;
        private final List<ProcessId> to;

        DelayByTicksStage(EventsBuilder owner, MessageType messageType, ProcessId from, List<ProcessId> to) {
            this.owner = owner;
            this.messageType = messageType;
            this.from = from;
            this.to = to;
        }

        public EventsBuilder byTicks(int ticks) {
            owner.events.add(new DelayMessages(messageType, from, to, ticks));
            return owner;
        }
    }

    public static final class PartitionFromStage {
        private final EventsBuilder owner;
        private final List<ProcessId> group1;

        PartitionFromStage(EventsBuilder owner, List<ProcessId> group1) {
            this.owner = owner;
            this.group1 = group1;
        }

        public EventsBuilder from(String... group2) {
            owner.events.add(new Partition(group1, toIds(group2)));
            return owner;
        }
    }

    private static List<ProcessId> toIds(String... names) {
        List<ProcessId> ids = new ArrayList<>(names.length);
        for (String n : names) ids.add(ProcessId.of(n));
        return ids;
    }
}

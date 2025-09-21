package com.tickloom.testkit;

import com.tickloom.ProcessId;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record NodeGroup(Set<ProcessId> processIds) {
    public static NodeGroup of(ProcessId... ids) {
        return new NodeGroup(Set.copyOf(Arrays.asList(ids)));
    }

    public static NodeGroup of(Collection<ProcessId> ids) {
        return new NodeGroup(Set.copyOf(ids));
    }

    public boolean contains(ProcessId id) {
        return processIds.contains(id);
    }

    public int size() {
        return processIds.size();
    }

    public NodeGroup plus(NodeGroup other) {
        return new NodeGroup(Stream.concat(processIds.stream(), other.processIds().stream())
                .collect(Collectors.toUnmodifiableSet()));
    }

    @Override
    public String toString() {
        return processIds.stream().map(ProcessId::name).collect(Collectors.joining(", ", "[", "]"));
    }
}

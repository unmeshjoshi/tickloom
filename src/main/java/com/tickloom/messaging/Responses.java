package com.tickloom.messaging;

import com.tickloom.ProcessId;

import java.util.*;
import java.util.function.Predicate;

public class Responses<T> {
    private final int totalExpected;
    private final int requiredQuorum;
    private final Predicate<T> successCondition;

    private final Map<ProcessId, T> successfulResponses = new HashMap<>();
    private final Map<ProcessId, T> rejections = new HashMap<>();
    private final List<Exception> exceptions = new ArrayList<>();

    public Responses(int totalExpected, int requiredQuorum, Predicate<T> successCondition) {
        if (totalExpected <= 0) {
            throw new IllegalArgumentException("totalExpected must be positive");
        }
        if (requiredQuorum <= 0 || requiredQuorum > totalExpected) {
            throw new IllegalArgumentException("requiredQuorum must be between 1 and " + totalExpected);
        }
        this.totalExpected = totalExpected;
        this.requiredQuorum = requiredQuorum;
        this.successCondition = Objects.requireNonNull(successCondition, "successCondition cannot be null");
    }

    public void add(ProcessId fromNode, T response) {
        if (successCondition.test(response)) {
            successfulResponses.put(fromNode, response);
        } else {
            rejections.put(fromNode, response);
        }
    }

    public void addError(Exception error) {
        exceptions.add(error);
    }

    public boolean hasReachedQuorum() {
        return successfulResponses.size() >= requiredQuorum;
    }

    public boolean isQuorumImpossible() {
        int maxPossibleSuccesses = totalExpected - totalFailures();
        return maxPossibleSuccesses < requiredQuorum;
    }

    public boolean isAllReceived() {
        return (successfulResponses.size() + totalFailures()) >= totalExpected;
    }

    public int totalFailures() {
        return rejections.size() + exceptions.size();
    }

    public int getRequiredQuorum() {
        return requiredQuorum;
    }

    public int getTotalExpected() {
        return totalExpected;
    }

    public Map<ProcessId, T> getSuccessfulResponses() {
        return Collections.unmodifiableMap(successfulResponses);
    }

    public Map<ProcessId, T> getRejections() {
        return Collections.unmodifiableMap(rejections);
    }

    public List<Exception> getExceptions() {
        return Collections.unmodifiableList(exceptions);
    }
}

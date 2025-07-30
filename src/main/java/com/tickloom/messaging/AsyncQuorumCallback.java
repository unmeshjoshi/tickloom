package com.tickloom.messaging;

import com.tickloom.ProcessId;
import com.tickloom.future.ListenableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Completes the associated future once quorum predicate succeeds.
 * This callback is used for distributed operations that require consensus.
 * 
 * @param <T> the type of the response
 */
public class AsyncQuorumCallback<T> implements RequestCallback<T> {
    private final int totalResponses;
    private final List<Exception> exceptions = new ArrayList<>();
    private final Map<ProcessId, T> responses = new HashMap<>();
    private final ListenableFuture<Map<ProcessId, T>> quorumFuture = new ListenableFuture<>();
    private final Predicate<T> successCondition;
    private volatile boolean completed = false;

    /**
     * Creates a new AsyncQuorumCallback with default success condition.
     * 
     * @param totalResponses the total number of expected responses
     */
    public AsyncQuorumCallback(int totalResponses) {
        this(totalResponses, (responses) -> true);
    }

    /**
     * Creates a new AsyncQuorumCallback with a custom success condition.
     * 
     * @param totalResponses the total number of expected responses
     * @param successCondition predicate that determines if a response is successful
     */
    public AsyncQuorumCallback(int totalResponses, Predicate<T> successCondition) {
        this.successCondition = successCondition;
        if (totalResponses <= 0) {
            throw new IllegalArgumentException("Total responses must be positive");
        }
        this.totalResponses = totalResponses;
    }

    /**
     * Calculates the majority quorum size.
     * 
     * @return the majority quorum size
     */
    private int majorityQuorum() {
        return totalResponses / 2 + 1;
    }

    @Override
    public void onResponse(T response, ProcessId fromNode) {
        if (completed) {
            return; // Already completed, ignore additional responses
        }
        responses.put(fromNode, response);
        tryCompletingFuture();
    }

    /**
     * Attempts to complete the future if quorum conditions are met.
     */
    private void tryCompletingFuture() {
        if (completed) {
            return; // Already completed
        }
        
        if (quorumSucceeded(responses)) {
            completed = true;
            quorumFuture.complete(responses);
            return;
        }
        
        if (responses.size() + exceptions.size() == totalResponses) {
            // All responses received but quorum not met
            completed = true;
            quorumFuture.fail(new RuntimeException("Quorum condition not met after " + totalResponses + " responses"));
        }
    }

    /**
     * Checks if the quorum condition is satisfied.
     * 
     * @param responses the current responses
     * @return true if quorum is satisfied, false otherwise
     */
    private boolean quorumSucceeded(Map<ProcessId, T> responses) {
        return responses.values()
                .stream()
                .filter(successCondition)
                .count() >= majorityQuorum();
    }

    @Override
    public void onError(Exception error) {
        if (completed) {
            return; // Already completed, ignore additional errors
        }
        exceptions.add(error);
        if (error instanceof java.util.concurrent.TimeoutException) {
            if (!quorumSucceeded(responses)) {
                completed = true;
                quorumFuture.fail(error);
                return;
            }
        }
        tryCompletingFuture();
    }

    /**
     * Gets the quorum future that will be completed when quorum is reached.
     * 
     * @return the quorum future
     */
    public ListenableFuture<Map<ProcessId, T>> getQuorumFuture() {
        return quorumFuture;
    }

    // -------------------------------------------------------------------
    // Convenience delegation methods for better readability
    // -------------------------------------------------------------------

    /**
     * Registers a success handler that will be invoked when the quorum future
     * completes successfully. This is syntactic sugar delegating to the
     * underlying ListenableFuture.
     *
     * @param onSuccess consumer invoked with the map of successful responses
     * @return this callback for fluent chaining
     */
    public AsyncQuorumCallback<T> onSuccess(Consumer<Map<ProcessId, T>> onSuccess) {
        quorumFuture.onSuccess(onSuccess);
        return this;
    }

    /**
     * Registers a failure handler that will be invoked when the quorum future
     * fails (e.g. quorum not reached, timeout).
     *
     * @param onFailure consumer invoked with the failure cause
     * @return this callback for fluent chaining
     */
    public AsyncQuorumCallback<T> onFailure(Consumer<Throwable> onFailure) {
        quorumFuture.onFailure(onFailure);
        return this;
    }
} 
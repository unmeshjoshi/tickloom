package com.tickloom.messaging;

import com.tickloom.ProcessId;
import com.tickloom.future.TickCompletableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Completes the associated future once quorum predicate succeeds.
 * This callback is used for distributed operations that require consensus.
 * 
 * @param <T> the type of the response
 */
public class AsyncQuorumCallback<T> implements RequestCallback<T> {
    private final int totalResponses;
    private final int requiredQuorum;
    private final List<Exception> exceptions = new ArrayList<>();
    private final Map<ProcessId, T> responses = new HashMap<>();
    private final TickCompletableFuture<Map<ProcessId, T>> quorumFuture = new TickCompletableFuture<>();
    private final Predicate<T> successCondition;
    private volatile boolean completed = false;

    /**
     * Creates a new AsyncQuorumCallback with an explicit required quorum size.
     *
     * @param totalResponses the total number of expected responses
     * @param requiredQuorum the number of successful responses needed to satisfy quorum
     * @param successCondition predicate that determines if a response is successful
     */

    /**
     * Creates a new AsyncQuorumCallback with a simple majority required quorum.
     *
     * @param totalResponses the total number of expected responses
     * @param successCondition predicate that determines if a response is successful
     */
    public AsyncQuorumCallback(int totalResponses, java.util.function.Predicate<T> successCondition) {
        this(totalResponses, (totalResponses / 2) + 1, successCondition);
    }

    public AsyncQuorumCallback<T> onSuccess(java.util.function.Consumer<Map<ProcessId, T>> successCallback) {
        quorumFuture.whenComplete((result, exception) -> {
            if (exception == null) {
                successCallback.accept(result);
            }
        });
        return this;
    }

    public AsyncQuorumCallback<T> onFailure(java.util.function.Consumer<Throwable> failureCallback) {
        quorumFuture.whenComplete((result, exception) -> {
            if (exception != null) {
                failureCallback.accept(exception);
            }
        });
        return this;
    }
    public AsyncQuorumCallback(int totalResponses, int requiredQuorum, Predicate<T> successCondition) {
        this.successCondition = successCondition;
        if (totalResponses <= 0) {
            throw new IllegalArgumentException("Total responses must be positive");
        }
        this.totalResponses = totalResponses;
        this.requiredQuorum = requiredQuorum;
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
                .count() >= requiredQuorum;
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
    public TickCompletableFuture<Map<ProcessId, T>> getQuorumFuture() {
        return quorumFuture;
    }
}
 
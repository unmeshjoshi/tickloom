package com.tickloom.messaging;

import com.tickloom.ProcessId;
import com.tickloom.future.TickCompletableFuture;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Completes the associated future once quorum predicate succeeds.
 * This callback is used for distributed operations that require consensus.
 *
 * @param <T> the type of the response
 */
public class AsyncQuorumCallback<T> implements RequestCallback<T> {
    private final Responses<T> responses;
    private final TickCompletableFuture<Map<ProcessId, T>> quorumFuture = new TickCompletableFuture<>();
    private boolean completed = false;

    public AsyncQuorumCallback(int totalResponses, int requiredQuorum, Predicate<T> successCondition) {
        this.responses = new Responses<>(
                totalResponses,
                requiredQuorum,
                successCondition != null ? successCondition : msg -> true
        );
    }

    @Override
    public void onResponse(T response, ProcessId fromNode) {
        if (completed) {
            return;
        }

        responses.add(fromNode, response);
        if (responses.hasReachedQuorum()) {
            completeWith(responses.getSuccessfulResponses());
            return;
        }

        checkFailureConditions(null);
    }

    @Override
    public void onError(Exception error) {
        if (completed) {
            return;
        }

        if (isTimeout(error)) {
            failWith(error);
            return;
        }

        responses.addError(error);
        checkFailureConditions(error);
    }

    private void checkFailureConditions(Throwable cause) {
        if (responses.isQuorumImpossible()) {
            failWith(quorumFailureException(cause));
        } else if (responses.isAllReceived()) {
            failWith(new RuntimeException("Quorum condition not met after all responses received", cause));
        }
    }

    private boolean isTimeout(Exception error) {
        return error instanceof TimeoutException;
    }

    private void completeWith(Map<ProcessId, T> result) {
        completed = true;
        quorumFuture.complete(result);
    }

    private void failWith(Throwable cause) {
        completed = true;
        quorumFuture.fail(cause);
    }

    private RuntimeException quorumFailureException(Throwable cause) {
        String message = String.format("Quorum impossible: received %d failures out of %d expected (required quorum: %d)",
                responses.totalFailures(), responses.getTotalExpected(), responses.getRequiredQuorum());
        return (cause != null) ? new RuntimeException(message, cause) : new RuntimeException(message);
    }

    public TickCompletableFuture<Map<ProcessId, T>> getQuorumFuture() {
        return quorumFuture;
    }

    public Map<ProcessId, T> getResponses() {
        return responses.getSuccessfulResponses();
    }

    public Map<ProcessId, T> getRejections() {
        return responses.getRejections();
    }

    public List<Exception> getExceptions() {
        return responses.getExceptions();
    }
}

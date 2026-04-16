package com.tickloom.future;

import com.tickloom.Continuation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A future that supports a single, unified non-blocking callback for single-threaded event loops.
 * This version is simplified to only use a `handle` method, enforcing that both success
 * and failure cases are always considered.
 *
 * @param <T> the type of the result name
 */

public class ListenableFuture<T> {

    public <U> ListenableFuture<U> map(Function<T, U> fn) {
        ListenableFuture<U> mapped = new ListenableFuture<>();
        this.handle((T result, Throwable exception) -> {
            if (exception != null) {
                mapped.fail(exception);
                return;
            }
            try {
                mapped.complete(fn.apply(result));
            } catch (Exception e) {
                mapped.fail(e);
            }
        });
        return mapped;
    }

    public <U> ListenableFuture<U> andThen(Function<T, ListenableFuture<U>> fn) {
        ListenableFuture<U> nextStage = new ListenableFuture<>();

        this.handle((T result, Throwable exception) -> {
            if (exception != null) {
                nextStage.fail(exception);
                return;
            }
            try {
                ListenableFuture<U> innerFuture = fn.apply(result);
                handleInnerFutureAndCompleteStage(innerFuture, nextStage);
            } catch (Exception e) {
                nextStage.fail(e);
            }
        });
        return nextStage;
    }

    private <U> void handleInnerFutureAndCompleteStage(ListenableFuture<U> innerFuture, ListenableFuture<U> nextStage) {
        // Flatten: when the inner future completes, complete the next stage
        innerFuture.handle((U innerResult, Throwable innerException) -> {
            if (innerException != null) {
                nextStage.fail(innerException);
            } else {
                nextStage.complete(innerResult);
            }
        });
    }

    public void whenComplete(Continuation<T> c) {
        this.handle((result, exception) -> {
            if (exception == null) {
                c.resume(result);
            } else {
                c.resumeWithError(exception);
            }
        });
    }

    private enum State {
        PENDING,
        COMPLETED,
        FAILED
    }

    private State state = State.PENDING;
    private T result;
    private Throwable exception;
    private final List<BiConsumer<T, Throwable>> handleCallbacks = new ArrayList<>();

    public static <T> ListenableFuture<T> completed(T value) {
        ListenableFuture<T> future = new ListenableFuture<>();
        future.complete(value);
        return future;
    }

    /**
     * Creates a new pending ListenableFuture.
     */
    public ListenableFuture() {
        // Starts in PENDING state
    }

    public boolean isPending() {
        return state == State.PENDING;
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean isFailed() {
        return state == State.FAILED;
    }

    /**
     * Returns the completed result.
     *
     * @return the result name
     * @throws IllegalStateException if the future is not successfully completed
     */
    public T getResult() {
        if (state != State.COMPLETED) {
            throw new IllegalStateException("Future is not completed successfully");
        }
        return result;
    }

    /**
     * Returns the failure exception.
     *
     * @return the exception that caused the failure
     * @throws IllegalStateException if the future has not failed
     */
    public Throwable getException() {
        if (state != State.FAILED) {
            throw new IllegalStateException("Future has not failed");
        }
        return exception;
    }

    /**
     * Completes the future with a successful result.
     *
     * @param result the result name
     */
    public void complete(T result) {
        if (state != State.PENDING) {
            throw new IllegalStateException("Future is already resolved");
        }
        this.state = State.COMPLETED;
        this.result = result;

        for (BiConsumer<T, Throwable> callback : handleCallbacks) {
            callback.accept(result, null);
        }
        handleCallbacks.clear();
    }

    /**
     * Fails the future with an exception.
     *
     * @param exception the exception that caused the failure
     */
    public void fail(Throwable exception) {
        if (state != State.PENDING) {
            throw new IllegalStateException("Future is already resolved");
        }
        this.state = State.FAILED;
        this.exception = exception;

        for (BiConsumer<T, Throwable> callback : handleCallbacks) {
            callback.accept(null, exception);
        }
        handleCallbacks.clear();
    }

    /**
     * Adds a callback that is always invoked when the future is resolved,
     * handling both success and failure cases.
     *
     * @param callback the callback to invoke with the result (or null) and exception (or null)
     * @return this future for method chaining
     */
    public ListenableFuture<T> handle(BiConsumer<T, Throwable> callback) {
        if (state == State.COMPLETED) {
            callback.accept(result, null);
        } else if (state == State.FAILED) {
            callback.accept(null, exception);
        } else {
            handleCallbacks.add(callback);
        }
        return this;
    }
}

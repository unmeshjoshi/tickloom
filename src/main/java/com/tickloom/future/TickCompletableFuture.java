package com.tickloom.future;

import com.tickloom.Continuation;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A future that supports a single,
 * unified non-blocking callback for single-threaded event loops.
 * @param <T> the type of the result name
 */

public class TickCompletableFuture<T> {

    public <U> TickCompletableFuture<U> thenApply(Function<T, U> fn) {
        TickCompletableFuture<U> mapped = new TickCompletableFuture<>();
        this.whenComplete((T result, Throwable exception) -> {
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

    public <U> TickCompletableFuture<U> thenCompose(Function<T, TickCompletableFuture<U>> fn) {
        TickCompletableFuture<U> nextStage = new TickCompletableFuture<>();

        this.whenComplete((T result, Throwable exception) -> {
            if (exception != null) {
                nextStage.fail(exception);
                return;
            }
            try {
                TickCompletableFuture<U> innerFuture = fn.apply(result);
                handleInnerFutureAndCompleteStage(innerFuture, nextStage);
            } catch (Exception e) {
                nextStage.fail(e);
            }
        });
        return nextStage;
    }

    private <U> void handleInnerFutureAndCompleteStage(TickCompletableFuture<U> innerFuture, TickCompletableFuture<U> nextStage) {
        // Flatten: when the inner future completes, complete the next stage
        innerFuture.whenComplete((U innerResult, Throwable innerException) -> {
            if (innerException != null) {
                nextStage.fail(innerException);
            } else {
                nextStage.complete(innerResult);
            }
        });
    }


    public TickCompletableFuture<T> whenComplete(Continuation<T> c) {
        return this.whenComplete((result, exception) -> {
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
    private BiConsumer<T, Throwable> callback;

    public static <T> TickCompletableFuture<T> completed(T value) {
        TickCompletableFuture<T> future = new TickCompletableFuture<>();
        future.complete(value);
        return future;
    }

    /**
     * Creates a new pending ListenableFuture.
     */
    public TickCompletableFuture() {
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

        if (callback != null) {
            callback.accept(result, null);
            callback = null;
        }
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

        if (callback != null) {
            callback.accept(null, exception);
            callback = null;
        }
    }

    /**
     * Adds a callback that is always invoked when the future is resolved,
     * handling both success and failure cases.
     * @param callback the callback to invoke with the result (or null) and exception (or null)
     * @return this future for method chaining
     */
    public TickCompletableFuture<T> whenComplete(BiConsumer<T, Throwable> callback) {
        TickCompletableFuture<T> nextStage = new TickCompletableFuture<>();

        BiConsumer<T, Throwable> wrappedCallback = (res, ex) -> {
            try {
                callback.accept(res, ex);
                if (ex != null) {
                    nextStage.fail(ex);
                } else {
                    nextStage.complete(res);
                }
            } catch (Throwable t) {
                nextStage.fail(t);
            }
        };

        if (state == State.COMPLETED) {
            wrappedCallback.accept(result, null);
        } else if (state == State.FAILED) {
            wrappedCallback.accept(null, exception);
        } else {
            if (this.callback != null) {
                throw new IllegalStateException("Only a single callback is supported for pending future");
            }
            this.callback = wrappedCallback;
        }
        return nextStage;
    }
}

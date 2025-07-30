package com.tickloom.future;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A future that supports non-blocking callbacks for single-threaded event loops.
 * Unlike standard Java futures, this implementation is specifically designed for
 * deterministic simulation environments where blocking operations are not allowed.
 * 
 * @param <T> the type of the result value
 */
public class ListenableFuture<T> {
    
    private enum State {
        PENDING,
        COMPLETED,
        FAILED
    }
    
    private State state = State.PENDING;
    private T result;
    private Throwable exception;
    private final List<Consumer<T>> successCallbacks = new ArrayList<>();
    private final List<Consumer<Throwable>> failureCallbacks = new ArrayList<>();
    
    /**
     * Creates a new pending ListenableFuture.
     */
    public ListenableFuture() {
        // Start in pending state
    }
    
    /**
     * @return true if this future is still pending completion
     */
    public boolean isPending() {
        return state == State.PENDING;
    }
    
    /**
     * @return true if this future has completed successfully
     */
    public boolean isCompleted() {
        return state == State.COMPLETED;
    }
    
    /**
     * @return true if this future has failed with an exception
     */
    public boolean isFailed() {
        return state == State.FAILED;
    }
    
    /**
     * Gets the result value if the future has completed successfully.
     * 
     * @return the result value
     * @throws IllegalStateException if the future is not completed or has failed
     */
    public T getResult() {
        if (state != State.COMPLETED) {
            throw new IllegalStateException("Future is not completed successfully");
        }
        return result;
    }
    
    /**
     * Gets the exception if the future has failed.
     * 
     * @return the exception that caused the failure
     * @throws IllegalStateException if the future is not failed
     */
    public Throwable getException() {
        if (state != State.FAILED) {
            throw new IllegalStateException("Future has not failed");
        }
        return exception;
    }
    
    /**
     * Completes the future with a successful result.
     * This method should be called by the owning component when the operation succeeds.
     * 
     * @param result the result value
     * @throws IllegalStateException if the future is already completed or failed
     */
    public void complete(T result) {
        if (state != State.PENDING) {
            throw new IllegalStateException("Future is already completed or failed");
        }
        
        this.state = State.COMPLETED;
        this.result = result;
        
        // Invoke all success callbacks
        for (Consumer<T> callback : successCallbacks) {
            callback.accept(result);
        }
        successCallbacks.clear(); // Clear callbacks after execution
    }
    
    /**
     * Fails the future with an exception.
     * This method should be called by the owning component when the operation fails.
     * 
     * @param exception the exception that caused the failure
     * @throws IllegalStateException if the future is already completed or failed
     */
    public void fail(Throwable exception) {
        if (state != State.PENDING) {
            throw new IllegalStateException("Future is already completed or failed");
        }
        
        this.state = State.FAILED;
        this.exception = exception;
        
        // Invoke all failure callbacks
        for (Consumer<Throwable> callback : failureCallbacks) {
            callback.accept(exception);
        }
        failureCallbacks.clear(); // Clear callbacks after execution
    }
    
    /**
     * Adds a callback to be invoked when the future completes successfully.
     * If the future is already completed, the callback is invoked immediately.
     * 
     * @param callback the callback to invoke on success
     * @return this future for method chaining
     */
    public ListenableFuture<T> onSuccess(Consumer<T> callback) {
        if (state == State.COMPLETED) {
            // Already completed, invoke immediately
            callback.accept(result);
        } else if (state == State.PENDING) {
            // Still pending, add to callback list
            successCallbacks.add(callback);
        }
        // If failed, don't add callback (it will never be invoked)
        
        return this;
    }
    
    /**
     * Adds a callback to be invoked when the future fails.
     * If the future is already failed, the callback is invoked immediately.
     * 
     * @param callback the callback to invoke on failure
     * @return this future for method chaining
     */
    public ListenableFuture<T> onFailure(Consumer<Throwable> callback) {
        if (state == State.FAILED) {
            // Already failed, invoke immediately
            callback.accept(exception);
        } else if (state == State.PENDING) {
            // Still pending, add to callback list
            failureCallbacks.add(callback);
        }
        // If completed successfully, don't add callback (it will never be invoked)
        
        return this;
    }
} 
package com.tickloom;

public interface Continuation<T> {
    void resume(T result);

    default void resumeWithError(Throwable error) {
        throw new RuntimeException("Unhandled error in continuation", error);
    }
}

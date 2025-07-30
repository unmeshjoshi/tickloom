package com.tickloom.messaging;

import com.tickloom.util.Timeout;

/**
 * Internal tracking details for a pending request in the RequestWaitingList.
 * Contains the callback and timeout for tick-based timeout management.
 */
class CallbackDetails {
    private final RequestCallback<?> requestCallback;
    private final Timeout timeout;

    /**
     * Creates a new CallbackDetails instance.
     * 
     * @param requestCallback the callback to invoke when the request completes
     * @param timeout the timeout for this request
     */
    public CallbackDetails(RequestCallback<?> requestCallback, Timeout timeout) {
        this.requestCallback = requestCallback;
        this.timeout = timeout;
    }

    /**
     * Gets the request callback.
     * 
     * @return the request callback
     */
    public RequestCallback<?> getRequestCallback() {
        return requestCallback;
    }

    /**
     * Gets the timeout for this request.
     * 
     * @return the timeout
     */
    public Timeout getTimeout() {
        return timeout;
    }

    /**
     * Checks if this callback has expired.
     * 
     * @return true if the callback has expired, false otherwise
     */
    boolean isExpired() {
        return timeout.fired();
    }
} 
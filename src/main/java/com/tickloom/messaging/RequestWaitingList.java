package com.tickloom.messaging;

import com.tickloom.ProcessId;
import com.tickloom.util.Timeout;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Generic request tracking system that manages pending requests and their callbacks.
 * This provides centralized timeout management and response handling for distributed operations.
 * Uses tick-based timeouts for deterministic simulation.
 * 
 * @param <Key> the type of the request key (typically correlation ID)
 * @param <Response> the type of the response
 */
public class RequestWaitingList<Key, Response> {
    
    private final Map<Key, CallbackDetails> pendingRequests = new ConcurrentHashMap<>();
    private final long expirationTicks;

    /**
     * Creates a new RequestWaitingList with default expiration duration.
     * 
     * @param expirationTicks the number of ticks after which requests expire
     */
    public RequestWaitingList(long expirationTicks) {
        this.expirationTicks = expirationTicks;
    }

    /**
     * Adds a new pending request with its callback.
     * 
     * @param key the request key (typically correlation ID)
     * @param callback the callback to invoke when the request completes
     */
    public void add(Key key, RequestCallback<Response> callback) {
        Timeout timeout = new Timeout("request-" + key, expirationTicks);
        timeout.start();
        pendingRequests.put(key, new CallbackDetails(callback, timeout));
    }

    /**
     * Handles a response for a pending request.
     * 
     * @param key the request key
     * @param response the response data
     */
    public void handleResponse(Key key, Response response) {
        if (!pendingRequests.containsKey(key)) {
            return;
        }
        
        CallbackDetails callbackDetails = pendingRequests.remove(key);
        @SuppressWarnings("unchecked")
        RequestCallback<Response> callback = (RequestCallback<Response>) callbackDetails.getRequestCallback();
        callback.onResponse(response, null); // No fromNode for generic responses
    }

    /**
     * Handles a response for a pending request with source node information.
     * 
     * @param key the request key
     * @param response the response data
     * @param fromNode the address of the node that sent the response
     */
    public void handleResponse(Key key, Response response, ProcessId fromNode) {
        if (!pendingRequests.containsKey(key)) {
            return;
        }
        
        CallbackDetails callbackDetails = pendingRequests.remove(key);
        @SuppressWarnings("unchecked")
        RequestCallback<Response> callback = (RequestCallback<Response>) callbackDetails.getRequestCallback();
        callback.onResponse(response, fromNode);
    }

    /**
     * Handles an error for a pending request.
     * 
     * @param key the request key
     * @param error the exception that occurred
     */
    public void handleError(Key key, Exception error) {
        CallbackDetails callbackDetails = pendingRequests.remove(key);
        if (callbackDetails != null) {
            callbackDetails.getRequestCallback().onError(error);
        }
    }

    /**
     * Processes timeouts for all pending requests.
     * This method should be called in the tick loop.
     */
    public void tick() {
        // First, tick all timeouts
        pendingRequests.values().forEach(callbackDetails -> 
            callbackDetails.getTimeout().tick());
        
        List<Key> expiredRequestKeys = getExpiredRequestKeys();
        if (expiredRequestKeys.isEmpty()) {
            return;
        }
        
        expiredRequestKeys.forEach(expiredRequestKey -> {
            CallbackDetails callbackDetails = pendingRequests.remove(expiredRequestKey);
            if (callbackDetails != null) {
                callbackDetails.getRequestCallback().onError(
                    new TimeoutException("Request expired after " + expirationTicks + " ticks")
                );
            }
        });
    }

    /**
     * Gets the list of expired request keys.
     * 
     * @return list of expired request keys
     */
    private List<Key> getExpiredRequestKeys() {
        return pendingRequests.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Gets the number of pending requests.
     * 
     * @return the number of pending requests
     */
    public int size() {
        return pendingRequests.size();
    }

    /**
     * Checks if there are any pending requests.
     * 
     * @return true if there are pending requests, false otherwise
     */
    public boolean isEmpty() {
        return pendingRequests.isEmpty();
    }

    /**
     * Clears all pending requests, invoking error callbacks for each.
     */
    public void clear() {
        pendingRequests.forEach((key, callbackDetails) -> {
            callbackDetails.getRequestCallback().onError(
                new RuntimeException("Request cancelled due to shutdown")
            );
        });
        pendingRequests.clear();
    }
} 
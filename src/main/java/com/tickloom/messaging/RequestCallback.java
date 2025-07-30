package com.tickloom.messaging;

import com.tickloom.ProcessId;

/**
 * Callback interface for handling request responses in the RequestWaitingList pattern.
 * This provides a generic way to handle both successful responses and errors.
 * 
 * @param <T> the type of the response
 */
public interface RequestCallback<T> {
    /**
     * Called when a response is received for a pending request.
     * 
     * @param response the response data
     * @param fromNode the address of the node that sent the response
     */
    void onResponse(T response, ProcessId fromNode);
    
    /**
     * Called when an error occurs for a pending request.
     * 
     * @param error the exception that occurred
     */
    void onError(Exception error);
} 
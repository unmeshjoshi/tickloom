package com.tickloom.network;

public interface MessageCodec {
    
    /**
     * Encodes any object (including Message) into a byte array for transmission or storage.
     * This unified method works for both Message objects and arbitrary payloads.
     * 
     * @param obj the object to encode
     * @return the encoded object as bytes
     * @throws RuntimeException if encoding fails or obj is null
     */
    byte[] encode(Object obj);
    
    /**
     * Decodes a byte array back into an object of the specified type.
     * This unified method works for both Message objects and arbitrary payloads.
     * 
     * @param data the encoded bytes
     * @param type the target class type
     * @return the decoded object
     * @throws RuntimeException if decoding fails
     */
    <T> T decode(byte[] data, Class<T> type);

}
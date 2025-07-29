package com.tickloom.messaging;

import com.tickloom.ProcessId;
import com.tickloom.network.PeerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MessageTest {

    private final ProcessId source = ProcessId.of("source");
    private final ProcessId destination = ProcessId.of("destination");
    private final MessageType messageType = MessageType.HELLO;
    private final byte[] payload = "test payload".getBytes();
    @Test
    public void rejectsNullSource() {
        assertThrows(IllegalArgumentException.class, () -> 
            new Message(null, destination, PeerType.CLIENT, messageType, payload, "correlation-123"));
    }

    @Test
    public void rejectsNullDestination() {
        assertThrows(IllegalArgumentException.class, () -> 
            new Message(source, null, PeerType.CLIENT, messageType, payload, "correlation-123"));
    }

    @Test
    public void rejectsNullPeerType() {
        assertThrows(IllegalArgumentException.class, () ->
            new Message(source, destination, null, messageType, payload, "correlation-123"));
    }

    @Test
    public void rejectsNullMessageType() {
        assertThrows(IllegalArgumentException.class, () -> 
            new Message(source, destination, PeerType.CLIENT, null, payload, "correlation-123"));
    }

    @Test
    public void rejectsNullPayload() {
        assertThrows(IllegalArgumentException.class, () -> 
            new Message(source, destination, PeerType.CLIENT, messageType, null, "correlation-123"));
    }
    @Test
    public void messagesWithSameValuesAreEqual() {
        Message message1 = new Message(source, destination, PeerType.CLIENT, messageType, payload, "correlation-123");
        Message message2 = new Message(source, destination, PeerType.CLIENT, messageType, payload, "correlation-123");
        
        assertEquals(message1, message2);
    }

    @Test
    public void messagesWithDifferentValuesAreNotEqual() {
        Message message1 = new Message(source, destination,PeerType.CLIENT, messageType, payload, "correlation-123");
        Message message2 = new Message(source, destination, PeerType.CLIENT, messageType, payload, "correlation-456");
        
        assertNotEquals(message1, message2);
    }
} 
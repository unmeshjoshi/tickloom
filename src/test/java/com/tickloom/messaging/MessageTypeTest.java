package com.tickloom.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MessageTypeTest {

    @Test
    public void handshakeTypesAreAvailable() {
        assertNotNull(MessageType.HELLO);
        assertNotNull(MessageType.WELCOME);
        assertNotNull(MessageType.REJECT);
    }

    @Test
    public void handshakeTypesHaveCorrectNames() {
        assertEquals("HELLO", MessageType.HELLO.name());
        assertEquals("WELCOME", MessageType.WELCOME.name());
        assertEquals("REJECT", MessageType.REJECT.name());
    }

    @Test
    public void canCreateCustomMessageType() {
        MessageType custom = MessageType.of("CUSTOM_TYPE");
        assertEquals("CUSTOM_TYPE", custom.name());
    }

    @Test
    public void canCreateMessageTypeWithConstructor() {
        MessageType custom = new MessageType("ANOTHER_CUSTOM");
        assertEquals("ANOTHER_CUSTOM", custom.name());
    }

    @Test
    public void messageTypesWithSameNameAreEqual() {
        MessageType type1 = MessageType.of("TEST_TYPE");
        MessageType type2 = MessageType.of("TEST_TYPE");
        MessageType type3 = new MessageType("TEST_TYPE");
        
        assertEquals(type1, type2);
        assertEquals(type1, type3);
        assertEquals(type2, type3);
    }

    @Test
    public void messageTypesWithDifferentNamesAreNotEqual() {
        MessageType type1 = MessageType.of("TYPE_1");
        MessageType type2 = MessageType.of("TYPE_2");
        
        assertNotEquals(type1, type2);
    }

    @Test
    public void predefinedTypesAreEqualToSameNamedCustomTypes() {
        MessageType customHeartbeat = MessageType.of("HELLO");
        assertEquals(MessageType.HELLO, customHeartbeat);
    }

    @Test
    public void rejectsNullName() {
        assertThrows(NullPointerException.class, () -> new MessageType(null));
    }

    @Test
    public void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new MessageType(""));
        assertThrows(IllegalArgumentException.class, () -> new MessageType("   "));
        assertThrows(IllegalArgumentException.class, () -> MessageType.of(""));
        assertThrows(IllegalArgumentException.class, () -> MessageType.of("   "));
    }

    @Test
    public void toStringReturnsName() {
        MessageType type = MessageType.of("TEST_TYPE");
        assertEquals("TEST_TYPE", type.toString());
    }

    @Test
    public void hashCodeIsConsistent() {
        MessageType type1 = MessageType.of("TEST_TYPE");
        MessageType type2 = MessageType.of("TEST_TYPE");
        
        assertEquals(type1.hashCode(), type2.hashCode());
    }

    @Test
    public void examplesOfCustomMessageTypes() {
        // Examples of message types that subclasses might define
        MessageType nack = MessageType.of("NACK");
        MessageType join = MessageType.of("JOIN");
        MessageType leave = MessageType.of("LEAVE");
        MessageType sync = MessageType.of("SYNC");
        MessageType election = MessageType.of("ELECTION");
        
        assertEquals("NACK", nack.name());
        assertEquals("JOIN", join.name());
        assertEquals("LEAVE", leave.name());
        assertEquals("SYNC", sync.name());
        assertEquals("ELECTION", election.name());

    }
} 
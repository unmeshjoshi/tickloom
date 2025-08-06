package com.tickloom.messaging;

import java.util.Objects;

/**
 * Represents a message type in the system. Similar to Netty's HttpMethod pattern,
 * this allows for predefined message types and custom types.
 */
public record MessageType(String name) {

    // Connection handshake message types
    //TODO: These are not used as of now. PeerId, MessageType etc is passed with each message.
    public static final MessageType HELLO = new MessageType("HELLO");
    public static final MessageType WELCOME = new MessageType("WELCOME");
    public static final MessageType REJECT = new MessageType("REJECT");

    /**
     * Creates a new MessageType with the specified name.
     *
     * @param name the name of the message type
     * @throws IllegalArgumentException if name is null or blank
     */
    public MessageType(String name) {
        this.name = Objects.requireNonNull(name, "Message type name cannot be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Message type name cannot be blank");
        }
    }

    /**
     * Returns the name of this message type.
     *
     * @return the message type name
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * Creates a custom message type with the specified name.
     *
     * @param name the name of the custom message type
     * @return a new MessageType instance
     */
    public static MessageType of(String name) {
        return new MessageType(name);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        MessageType that = (MessageType) obj;
        return Objects.equals(name, that.name);
    }

    @Override
    public String toString() {
        return name;
    }
} 
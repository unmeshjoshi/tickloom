package com.tickloom.network;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.tickloom.messaging.MessageType;

import java.io.IOException;

/**
 * Simple JSON-based implementation of MessageCodec for testing and development.
 * Uses Jackson for serialization/deserialization.
 */
public class JsonMessageCodec implements MessageCodec {
    private final ObjectMapper objectMapper;

    /**
     * Creates a new {@link JsonMessageCodec} configured with custom serializers
     * for project-specific types.
     */
    public JsonMessageCodec() {
        this.objectMapper = new ObjectMapper();
        
        // Register JDK8 module for Optional support
        objectMapper.registerModule(new Jdk8Module());
        
        // Create module for custom serializers
        SimpleModule module = new SimpleModule();
        module.addSerializer(MessageType.class, new MessageTypeSerializer());
        module.addDeserializer(MessageType.class, new MessageTypeDeserializer());
        objectMapper.registerModule(module);
    }

    @Override
    public byte[] encode(Object obj) {
        try {
            return objectMapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode object: " + obj, e);
        }
    }

    @Override
    public <T> T decode(byte[] data, Class<T> type) {
        try {
            return objectMapper.readValue(data, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode data to type: " + type, e);
        }
    }
    
    private static class MessageTypeSerializer extends JsonSerializer<MessageType> {
        @Override
        public void serialize(MessageType value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.name());
        }
    }
    
    private static class MessageTypeDeserializer extends JsonDeserializer<MessageType> {
        @Override
        public MessageType deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String name = p.getValueAsString();
            return MessageType.of(name);
        }
    }
} 
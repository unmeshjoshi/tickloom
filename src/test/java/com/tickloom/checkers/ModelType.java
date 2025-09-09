package com.tickloom.checkers;

/**
 * Enum for type-safe model specification in consistency checking.
 */
public enum ModelType {
    /**
     * Single register model - operations on a single value
     */
    REGISTER("register"),
    
    /**
     * Key-value model - operations on key-value pairs with per-key independence
     */
    KV("kv"),
    
    /**
     * Auto-detect model type based on history format
     */
    AUTO("auto");
    
    private final String value;
    
    ModelType(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    @Override
    public String toString() {
        return value;
    }
    
    /**
     * Convert string to ModelType enum
     */
    public static ModelType fromString(String value) {
        for (ModelType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown model type: " + value);
    }
}

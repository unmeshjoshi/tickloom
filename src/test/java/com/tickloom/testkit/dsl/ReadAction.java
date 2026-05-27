package com.tickloom.testkit.dsl;

public record ReadAction(String key) implements Action {
    public static ReadAction read(String key) {
        return new ReadAction(key);
    }
}

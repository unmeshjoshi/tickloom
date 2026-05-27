package com.tickloom.testkit.dsl;

public record WriteAction(String key, String value) implements Action {
    public static WriteAction write(String key, String value) {
        return new WriteAction(key, value);
    }
}

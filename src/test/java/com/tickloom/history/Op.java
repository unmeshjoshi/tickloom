package com.tickloom.history;

import java.util.Objects;

/**
 * Representation operations in the Jepsen History
 *
 * @param name
 */
public record Op(String name) {
    public static final Op READ = new Op("read");
    public static final Op WRITE = new Op("write");

    public Op {
        Objects.requireNonNull(name, "name cannot be null");
    }

    @Override
    public String toString() {
        return name;
    }
}

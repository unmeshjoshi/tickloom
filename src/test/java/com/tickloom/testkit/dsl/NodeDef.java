package com.tickloom.testkit.dsl;

public class NodeDef {
    private final String name;
    private final Class<?> type;

    public NodeDef(String name, Class<?> type) {
        this.name = name;
        this.type = type;
    }

    public String getName() { return name; }
    public Class<?> getType() { return type; }
}

package com.tickloom.testkit.dsl;

public class ClientDef {
    private final String name;
    private final String connectedTo;

    public ClientDef(String name, String connectedTo) {
        this.name = name;
        this.connectedTo = connectedTo;
    }

    public String getName() { return name; }
    public String getConnectedTo() { return connectedTo; }
}

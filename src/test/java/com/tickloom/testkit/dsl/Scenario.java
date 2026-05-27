package com.tickloom.testkit.dsl;

import com.tickloom.history.History;
import com.tickloom.history.HistoryRecorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Scenario {
    private final String name;
    private final Map<String, NodeDef> nodes = new LinkedHashMap<>();
    private final Map<String, ClientDef> clients = new LinkedHashMap<>();
    private final List<OpDef> ops = new ArrayList<>();
    private final HistoryRecorder<String> recorder = new HistoryRecorder<>();

    public Scenario(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public Map<String, NodeDef> getNodes() { return nodes; }
    public Map<String, ClientDef> getClients() { return clients; }
    public List<OpDef> getOps() { return ops; }
    public HistoryRecorder<String> getRecorder() { return recorder; }
    public History history() { return recorder.getHistory(); }

    public void addNode(NodeDef node) { nodes.put(node.getName(), node); }
    public void addClient(ClientDef client) { clients.put(client.getName(), client); }
    public void addOp(OpDef op) { ops.add(op); }
}

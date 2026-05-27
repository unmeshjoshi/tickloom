package com.tickloom.testkit.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class OpDef {
    private String clientName;
    private Action action;
    private final List<Consumer<ClusterBehaviourBuilder>> behaviours = new ArrayList<>();
    private String waitUntilNode;
    private Predicate<String> waitCondition;
    private boolean expectPending;
    private String expectedValue;

    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }

    public List<Consumer<ClusterBehaviourBuilder>> getBehaviours() { return behaviours; }
    public void addBehaviour(Consumer<ClusterBehaviourBuilder> behaviour) { this.behaviours.add(behaviour); }

    public String getWaitUntilNode() { return waitUntilNode; }
    public void setWaitUntilNode(String waitUntilNode) { this.waitUntilNode = waitUntilNode; }

    public Predicate<String> getWaitCondition() { return waitCondition; }
    public void setWaitCondition(Predicate<String> waitCondition) { this.waitCondition = waitCondition; }

    public boolean isExpectPending() { return expectPending; }
    public void setExpectPending(boolean expectPending) { this.expectPending = expectPending; }

    public String getExpectedValue() { return expectedValue; }
    public void setExpectedValue(String expectedValue) { this.expectedValue = expectedValue; }
}

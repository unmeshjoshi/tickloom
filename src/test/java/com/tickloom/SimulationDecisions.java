package com.tickloom;

import java.util.List;
import java.util.Random;

public class SimulationDecisions {
    public static final double DEFAULT_REQUEST_PROBABILITY = 0.4;
    public static final double DEFAULT_FAULT_PROBABILITY = 0.08;
    public static final int DEFAULT_HEAL_INTERVAL_TICKS = 40;

    public enum FaultType {
        ISOLATE_NODE,
        PARTITION_PAIR,
        DELAY_LINK,
        DROP_LINK
    }

    private final Random random;
    private final double requestProbability;
    private final double faultProbability;
    private final int healIntervalTicks;

    public SimulationDecisions(Random random) {
        this(random, DEFAULT_REQUEST_PROBABILITY, DEFAULT_FAULT_PROBABILITY, DEFAULT_HEAL_INTERVAL_TICKS);
    }


    public boolean shouldIssueClientRequest() {
        return random.nextDouble() <= requestProbability;
    }

    public boolean shouldInjectFault() {
        return random.nextDouble() <= faultProbability;
    }

    public boolean shouldHealNetwork(long currentTick) {
        return currentTick % healIntervalTicks == 0;
    }

    public FaultType chooseFaultType() {
        FaultType[] types = FaultType.values();
        return types[random.nextInt(types.length)];
    }

    public <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    public int between(int minInclusive, int maxInclusive) {
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }

    public SimulationDecisions(long seed) {
        this(new Random(seed));
    }

    public SimulationDecisions(Random random, double requestProbability, double faultProbability) {
        this(random, requestProbability, faultProbability, DEFAULT_HEAL_INTERVAL_TICKS);
    }

    public SimulationDecisions(Random random, double requestProbability, double faultProbability, int healIntervalTicks) {
        this.random = random;
        this.requestProbability = requestProbability;
        this.faultProbability = faultProbability;
        this.healIntervalTicks = healIntervalTicks;
    }

    public boolean shouldHeal(long currentTick) {
        return shouldHealNetwork(currentTick);
    }

    public Random rawRandom() {
        return random;
    }
}

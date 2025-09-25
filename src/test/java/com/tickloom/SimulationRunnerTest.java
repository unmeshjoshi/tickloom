package com.tickloom;

import com.tickloom.algorithms.replication.quorum.QuorumKVScenarioRunner;
import com.tickloom.history.History;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SimulationRunnerTest {

    @Test
    public void historyWithSameSeedShouldBeIdenticalAcrossRuns() throws IOException {
        //same seed with same cluster size and number of clients. (defaults to 3 processes and 3 clients)
        Long seed = 123L;
        SimulationRunner runner = new QuorumKVScenarioRunner(seed);
        SimulationRunner runner2 = new QuorumKVScenarioRunner(seed);
        History history1 = runner.runAndGetHistory(1000);
        History history2 = runner2.runAndGetHistory(1000);
        assertEquals(history1.toEdn(), history2.toEdn());
    }

    @Test
    public void historyWithDifferentSeedShouldNotBeIdentical() throws IOException {
        SimulationRunner runner = new QuorumKVScenarioRunner(123L);
        SimulationRunner runner2 = new QuorumKVScenarioRunner(456L);
        History history1 = runner.runAndGetHistory(1000);
        History history2 = runner2.runAndGetHistory(1000);
        assertNotEquals(history1.toEdn(), history2.toEdn());
    }

    @Test
    public void historyWithDifferentTickDurationShouldNotBeIdentical() throws IOException {
        SimulationRunner runner = new QuorumKVScenarioRunner(123L);
        SimulationRunner runner2 = new QuorumKVScenarioRunner(123L);
        History history1 = runner.runAndGetHistory(1000);
        History history2 = runner2.runAndGetHistory(2000);
        assertNotEquals(history1.toEdn(), history2.toEdn());
    }

    @Test
    public void historyWithDifferentNumProcessesShouldNotBeIdentical() throws IOException {
        SimulationRunner runner = new QuorumKVScenarioRunner(123L, 5, 3);
        SimulationRunner runner2 = new QuorumKVScenarioRunner(123L, 3, 3);
        History history1 = runner.runAndGetHistory(1000);
        History history2 = runner2.runAndGetHistory(1000);
        assertNotEquals(history1.toEdn(), history2.toEdn());
    }

    @Test
    public void historyWithDifferentNumClientsShouldNotBeIdentical() throws IOException {
        SimulationRunner runner = new QuorumKVScenarioRunner(123L, 3, 5);
        SimulationRunner runner2 = new QuorumKVScenarioRunner(123L, 3, 3);
        History history1 = runner.runAndGetHistory(1000);
        History history2 = runner2.runAndGetHistory(1000);
        assertNotEquals(history1.toEdn(), history2.toEdn());
    }

}
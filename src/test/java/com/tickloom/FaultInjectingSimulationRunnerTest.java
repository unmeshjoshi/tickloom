package com.tickloom;

import com.tickloom.algorithms.replication.quorum.FaultInjectingQuorumKVScenarioRunner;
import com.tickloom.history.History;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.tickloom.ConsistencyChecker.ConsistencyProperty.LINEARIZABILITY;
import static com.tickloom.ConsistencyChecker.DataModel.REGISTER;
import static org.junit.jupiter.api.Assertions.*;

class FaultInjectingSimulationRunnerTest {

    @Test
    void sameSeedShouldProduceSameFaultScheduleAndHistory() throws IOException {
        var runner1 = new FaultInjectingQuorumKVScenarioRunner(123L);
        var runner2 = new FaultInjectingQuorumKVScenarioRunner(123L);

        History history1 = runner1.runAndGetHistory(500);
        History history2 = runner2.runAndGetHistory(500);

        assertFalse(runner1.faultSchedule().isEmpty(), "Expected at least one injected fault");
        assertEquals(runner1.faultSchedule(), runner2.faultSchedule());
        assertEquals(history1.toEdn(), history2.toEdn());
    }

    @Test
    void differentSeedShouldProduceDifferentFaultSchedules() throws IOException {
        var runner1 = new FaultInjectingQuorumKVScenarioRunner(123L);
        var runner2 = new FaultInjectingQuorumKVScenarioRunner(456L);

        History history1 = runner1.runAndGetHistory(500);
        History history2 = runner2.runAndGetHistory(500);

        assertNotEquals(runner1.faultSchedule(), runner2.faultSchedule());
        assertNotEquals(history1.toEdn(), history2.toEdn());

    }

    @Test
    void faultInjectedHistoryShouldBeCheckable() throws IOException {
        var runner = new FaultInjectingQuorumKVScenarioRunner(123L);
        History history = runner.runAndGetHistory(500);

        assertFalse(runner.faultSchedule().isEmpty(), "Expected at least one injected fault");
        assertDoesNotThrow(() -> ConsistencyChecker.checkIndependent(history, LINEARIZABILITY, REGISTER));
    }

    @Test
    void printFaultScheduleAndHistoryForPresentation() throws IOException {
        long seed = 123L;
        long ticks = 120L;

        var runner = new FaultInjectingQuorumKVScenarioRunner(seed);
        History history = runner.runAndGetHistory(ticks);

        assertFalse(runner.faultSchedule().isEmpty(), "Expected at least one injected fault");

        printFaultSchedule(seed, ticks, runner);
        printHistoryEdn(history);
    }

    private static void printHistoryEdn(History history) {
        System.out.println("History:");
        System.out.println(formatHistoryForPresentation(history));
        System.out.println("=== End Presentation Scenario ===");
        System.out.println();
    }

    private static void printFaultSchedule(long seed, long ticks, FaultInjectingQuorumKVScenarioRunner runner) {
        System.out.println();
        System.out.println("=== Presentation Scenario ===");
        System.out.println("seed = " + seed + ", ticks = " + ticks);
        System.out.println();
        System.out.println("Fault schedule:");
        runner.faultSchedule().forEach(fault -> System.out.println("  " + fault));
        System.out.println();
    }

    private static String formatHistoryForPresentation(History history) {
        String edn = history.toEdn().trim();
        if (edn.length() < 2) {
            return edn;
        }

        String body = edn.substring(1, edn.length() - 1)
                .replace("} {", "}\n  {");

        return "[\n  " + body + "\n]";
    }
}

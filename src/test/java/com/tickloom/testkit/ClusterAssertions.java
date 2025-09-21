package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.storage.VersionedValue;

import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 *
 */
public class ClusterAssertions {

    private ClusterAssertions() {
    }

    public static void assertNodesContainValue(Cluster cluster, List<ProcessId> nodes, byte[] key, byte[] expectedValue) {
        assertTrue(nodes.stream()
                .allMatch(node -> storageContainsValue(cluster, node, key, expectedValue)));
    }

    public static void assertAllNodeStoragesContainValue(Cluster cluster, byte[] key, byte[] expectedValue) {
        assertTrue(cluster.serverNodes.stream()
                .allMatch(node -> storageContainsValue(cluster, node.id, key, expectedValue)));
    }

    /**
     * Asserts that a specific name exists in storage on a specific process.
     *
     * @param processId the ID of the process
     * @param key the key to check
     * @param expectedValue the expected name
     * @return true if the name matches, false otherwise
     */
    public static boolean storageContainsValue(Cluster cluster, ProcessId processId, byte[] key, byte[] expectedValue) {
        VersionedValue actual = cluster.getStorageValue(processId, key);
        if (actual == null) {
            fail();
        }
        return Arrays.equals(actual.value(), expectedValue);
    }

    public static void assertEventually(Cluster cluster,BooleanSupplier cond) {
        tickUntil(cluster,cond);
    }

    private static int DEFAULT_TIMEOUT_TICKS = 10000;
    private static void tickUntil(Cluster cluster,BooleanSupplier p) {
        int tickCount = 0;
        while (!p.getAsBoolean()) {
            if (tickCount > DEFAULT_TIMEOUT_TICKS) {
                fail("Timeout waiting for condition to be met.");
            }
            cluster.tick();
            tickCount++;
        }
    }



}

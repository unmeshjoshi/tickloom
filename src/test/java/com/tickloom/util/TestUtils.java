package com.tickloom.util;

import com.tickloom.Tickable;
import com.tickloom.history.History;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

public class TestUtils {
    private static final int noOfTicks = 1000; // Shorter timeout to see what's happening

    public static void tickUntil(List<Tickable> tickables, Supplier<Boolean> condition) {
        int tickCount = 0;
        while (!condition.get()) {
            tickables.stream().forEach(tickable -> {
                try {
                    tickable.tick();
                } catch (Exception e) {
                    // network might be closed
                    System.out.println("Tick failed (likely closed): " + e.getMessage());
                }
            });

            tickCount++;

            if (tickCount > noOfTicks) {
                fail("Timeout waiting for condition to be met.");
            }
        }
        System.out.println("Condition met after " + tickCount + " ticks");
    }

    public static String randomProcessId(String prefix) {
        return prefix + "-" + (int) (Math.random() * 1000);
    }

    public static void writeEdnFile(TestInfo testInfo, History history) throws IOException {
        final URL buildRootUrl = TestUtils.class.getResource("/");
        Path buildRoot = Path.of(buildRootUrl.getPath());
        Path historyDir = buildRoot.resolve("history-edns");
        Files.createDirectories(historyDir);
        final Path ednFilePath = historyDir.resolve(fileNameFrom(testInfo, "edn"));
        System.out.println("Writing to " + ednFilePath);
        Files.writeString(ednFilePath, history.toEdn(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
    }

    /** e.g., QuorumSimulationLinearizabilityTest_strictQuorumMaintainsLinearizabilityForKVStore.edn */
    public static String fileNameFrom(TestInfo info, String ext) {
        String cls = info.getTestClass().map(Class::getSimpleName).orElse("UnknownClass");
        String mth = info.getTestMethod().map(m -> m.getName())
                .orElse(sanitize(info.getDisplayName()));
        return cls + "_" + sanitize(mth) + (ext.startsWith(".") ? ext : "." + ext);
    }

    /** Replace anything unsafe for files on common OSes. */
    public static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}

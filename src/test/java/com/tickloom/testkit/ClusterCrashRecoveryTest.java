package com.tickloom.testkit;

import com.tickloom.Process;
import com.tickloom.ProcessFactory;
import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.fs.FileIO;
import com.tickloom.fs.OpenMode;
import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.MessageType;
import com.tickloom.wal.BatchedLog;
import com.tickloom.wal.Wal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A node that logs through Wal and BatchedLog on its own simulated disk, crashed and restarted by the cluster.
 * The rule: a record whose append future completed is on disk after the crash, and one that did not is gone.
 */
class ClusterCrashRecoveryTest {
    private static final int GROUP_WAIT_TICKS = 3;

    /** Opens its log file at startup and recovers it. It is not running until recovery completes. */
    static class LogProcess extends Process {
        List<byte[]> recovered = List.of();
        private Wal wal;
        private BatchedLog log;

        LogProcess(ProcessParams params) {
            super(params);
        }

        @Override
        protected TickCompletableFuture<?> onStart() {
            return fileSystem.open("log", OpenMode.OPEN_OR_CREATE)
                    .thenCompose((FileIO file) -> fileSystem.syncDirectory().thenApply(ignored -> file))
                    .thenCompose(file -> {
                        wal = new Wal(file);
                        return wal.recover();
                    })
                    .thenApply(entries -> {
                        recovered = entries.stream().map(Wal.Entry::payload).toList();
                        log = new BatchedLog(wal, GROUP_WAIT_TICKS);
                        return true;
                    });
        }

        TickCompletableFuture<Long> append(byte[] payload) {
            return log.append(payload);
        }

        @Override
        protected void onTick() {
            if (log != null) {
                log.tick();
            }
        }

        @Override
        protected Map<MessageType, Handler> initialiseHandlers() {
            return Map.of();
        }
    }

    private static final ProcessFactory FACTORY = new ProcessFactory() {
        @Override
        public Process create(List<ProcessId> peerIds, ProcessParams params) {
            return new LogProcess(params);
        }
    };

    private record Outcome(int appended, int acknowledged, int recovered) {}

    private Outcome crashAfterSomeAppends(long seed) throws Exception {
        Random workload = new Random(seed);
        try (Cluster cluster = Cluster.createSimulated(3, FACTORY, seed)) {
            ProcessId id = cluster.getServerProcessIds().get(0);
            cluster.tickUntil(() -> cluster.getProcess(id).isRunning());

            LogProcess process = cluster.getNode(id);
            List<byte[]> appended = new ArrayList<>();
            List<TickCompletableFuture<Long>> futures = new ArrayList<>();
            int rounds = 1 + workload.nextInt(6);
            for (int round = 0; round < rounds; round++) {
                for (int i = 0, batch = 1 + workload.nextInt(3); i < batch; i++) {
                    byte[] payload = new byte[1 + workload.nextInt(40)];
                    workload.nextBytes(payload);
                    appended.add(payload);
                    futures.add(process.append(payload));
                }
                for (int t = workload.nextInt(10); t > 0; t--) {
                    cluster.tick();
                }
            }
            int acknowledged = (int) futures.stream().filter(TickCompletableFuture::isCompleted).count();

            cluster.crashNode(id);
            assertTrue(cluster.getProcess(id).isStopped());
            cluster.restartNode(id);
            LogProcess restarted = cluster.getNode(id);
            assertNotSame(process, restarted, "the restarted node is a new process");
            assertFalse(restarted.isRunning(), "not running until recovery completes");
            cluster.tickUntil(restarted::isRunning);

            assertEquals(acknowledged, restarted.recovered.size(), "seed " + seed);
            for (int i = 0; i < acknowledged; i++) {
                assertArrayEquals(appended.get(i), restarted.recovered.get(i), "record " + i + ", seed " + seed);
            }
            return new Outcome(appended.size(), acknowledged, restarted.recovered.size());
        }
    }

    @Test
    void acknowledgedRecordsSurviveACrashAndTheRestartedNodeRecoversThem() throws Exception {
        int lost = 0;
        int nothingAcknowledged = 0;
        int everythingAcknowledged = 0;
        for (long seed = 0; seed < 50; seed++) {
            Outcome outcome = crashAfterSomeAppends(seed);
            if (outcome.acknowledged() < outcome.appended()) lost++;
            if (outcome.acknowledged() == 0) nothingAcknowledged++;
            if (outcome.acknowledged() == outcome.appended()) everythingAcknowledged++;
        }
        assertTrue(lost > 0, "no run lost an unacknowledged record, so the test checks nothing");
        assertTrue(nothingAcknowledged > 0);
        assertTrue(everythingAcknowledged > 0);
    }

    @Test
    void aRestartedNodeKeepsAppendingAfterRecovery() throws Exception {
        try (Cluster cluster = Cluster.createSimulated(3, FACTORY, 7)) {
            ProcessId id = cluster.getServerProcessIds().get(0);
            cluster.tickUntil(() -> cluster.getProcess(id).isRunning());

            LogProcess first = cluster.getNode(id);
            cluster.tickUntilComplete(first.append("one".getBytes()));
            cluster.crashNode(id);
            cluster.restartNode(id);
            cluster.tickUntil(() -> cluster.getProcess(id).isRunning());

            LogProcess second = cluster.getNode(id);
            cluster.tickUntilComplete(second.append("two".getBytes()));
            cluster.crashNode(id);
            cluster.restartNode(id);
            cluster.tickUntil(() -> cluster.getProcess(id).isRunning());

            LogProcess third = cluster.getNode(id);
            assertEquals(2, third.recovered.size());
            assertArrayEquals("one".getBytes(), third.recovered.get(0));
            assertArrayEquals("two".getBytes(), third.recovered.get(1));
        }
    }

    @Test
    void restartingARunningNodeIsRejected() throws Exception {
        try (Cluster cluster = Cluster.createSimulated(3, FACTORY, 1)) {
            ProcessId id = cluster.getServerProcessIds().get(0);
            cluster.tickUntil(() -> cluster.getProcess(id).isRunning());
            assertThrows(IllegalStateException.class, () -> cluster.restartNode(id));
        }
    }
}

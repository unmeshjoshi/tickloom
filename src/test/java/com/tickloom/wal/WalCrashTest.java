package com.tickloom.wal;

import org.junit.jupiter.api.Test;
import com.tickloom.fs.OpenMode;
import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.fs.FileIO;
import com.tickloom.future.TickCompletableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole stack on the simulated disk with a crash at a random moment: SimulatedFileSystem, Wal, BatchedLog.
 * A record whose append future completed was covered by a completed sync, so it is on disk. A record whose future
 * is still pending was not, so it is gone. Recovery returns exactly the acknowledged records.
 */
class WalCrashTest {
    private static final int GROUP_WAIT = 3;

    private record Run(int appended, int acknowledged, int recovered) {}

    private SimulatedFileSystem fs;

    private <T> T await(TickCompletableFuture<T> future) {
        for (int i = 0; i < 10_000 && future.isPending(); i++) fs.tick();
        assertFalse(future.isPending(), "still pending");
        return future.getResult();
    }

    private Run crashAndRecover(long seed) {
        Random workload = new Random(seed * 31 + 7);
        fs = new SimulatedFileSystem();
        FileIO file = await(fs.open("wal", OpenMode.CREATE_NEW));
        await(fs.syncDirectory());
        Wal wal = new Wal(file, 4096);
        await(wal.recover());
        BatchedLog log = new BatchedLog(wal, GROUP_WAIT);

        List<byte[]> appended = new ArrayList<>();
        List<TickCompletableFuture<Long>> futures = new ArrayList<>();
        int rounds = 1 + workload.nextInt(8);
        for (int round = 0; round < rounds; round++) {
            int batch = 1 + workload.nextInt(4);
            for (int i = 0; i < batch; i++) {
                byte[] payload = new byte[1 + workload.nextInt(700)];
                workload.nextBytes(payload);
                appended.add(payload);
                futures.add(log.append(payload));
            }
            int ticks = workload.nextInt(12);   // the crash can land mid-wait, mid-write or mid-sync
            for (int t = 0; t < ticks; t++) {
                fs.tick();
                log.tick();
            }
        }
        List<Long> acknowledgedOffsets = new ArrayList<>();
        for (TickCompletableFuture<Long> future : futures) {
            if (future.isCompleted()) acknowledgedOffsets.add(future.getResult());
        }
        int acknowledged = acknowledgedOffsets.size();

        fs.crash();
        fs.restart();

        Wal recoveredWal = new Wal(await(fs.open("wal", OpenMode.OPEN_EXISTING)), 4096);
        List<Wal.Entry> entries = await(recoveredWal.recover());

        assertEquals(acknowledged, entries.size(), "seed " + seed);
        for (int i = 0; i < acknowledged; i++) {
            assertArrayEquals(appended.get(i), entries.get(i).payload(), "record " + i + ", seed " + seed);
            assertEquals(acknowledgedOffsets.get(i), entries.get(i).offset(), "seed " + seed);
        }
        return new Run(appended.size(), acknowledged, entries.size());
    }

    @Test
    void recoveryReturnsExactlyTheAcknowledgedRecords() {
        int lostUnacknowledged = 0;
        int crashedWithNothingAcknowledged = 0;
        int crashedWithEverythingAcknowledged = 0;
        for (long seed = 0; seed < 500; seed++) {
            Run run = crashAndRecover(seed);
            if (run.acknowledged() < run.appended()) lostUnacknowledged++;
            if (run.acknowledged() == 0) crashedWithNothingAcknowledged++;
            if (run.acknowledged() == run.appended()) crashedWithEverythingAcknowledged++;
        }
        // Non-vacuity: the crash really did lose records, and it landed both early and late.
        assertTrue(lostUnacknowledged > 0, "no run lost a record, so the test checks nothing");
        assertTrue(crashedWithNothingAcknowledged > 0);
        assertTrue(crashedWithEverythingAcknowledged > 0);
    }

    @Test
    void theSameSeedGivesTheSameRun() {
        assertEquals(crashAndRecover(123), crashAndRecover(123));
    }
}

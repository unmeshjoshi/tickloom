package com.tickloom.algorithms.replication.quorum;

import com.tickloom.Process;
import com.tickloom.ProcessId;
import com.tickloom.ProcessParams;
import com.tickloom.fs.SimulatedFileSystem;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.PeerType;
import com.tickloom.network.SimulatedNetwork;
import com.tickloom.util.IdGen;
import com.tickloom.util.SystemClock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two INTERNAL_SET requests for one key reach a replica in the same tick. The replica must not report a write as
 * successful while that write, or the newer one that made it unnecessary, could still be lost in a crash.
 */
class QuorumReplicaConcurrentSetTest {
    private static final ProcessId REPLICA = ProcessId.of("replica");
    private static final ProcessId PROBE = ProcessId.of("probe");
    private static final byte[] KEY = "k".getBytes();

    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final SimulatedNetwork network = SimulatedNetwork.noLossNetwork(new Random(1));
    private final SimulatedFileSystem fileSystem = new SimulatedFileSystem();
    private final QuorumReplica replica = new QuorumReplica(List.of(),
            new ProcessParams(REPLICA, network, codec, 1000, new SystemClock(), new IdGen("replica", new Random(2)), fileSystem));
    private final Probe probe = new Probe(new ProcessParams(PROBE, network, codec, 1000, new SystemClock(),
            new IdGen("probe", new Random(3)), new SimulatedFileSystem()));
    private int tick;

    /** Records the INTERNAL_SET_RESPONSE messages it receives, with the tick each arrived on. */
    private class Probe extends Process {
        final List<Boolean> successes = new ArrayList<>();
        final List<Integer> ticks = new ArrayList<>();
        final List<Boolean> undurableWritesWhenReceived = new ArrayList<>();

        Probe(ProcessParams params) {
            super(params);
        }

        @Override
        protected Map<MessageType, Handler> initialiseHandlers() {
            return Map.of(QuorumMessageTypes.INTERNAL_SET_RESPONSE, message -> {
                successes.add(codec.decode(message.payload(), InternalSetResponse.class).success());
                ticks.add(tick);
                undurableWritesWhenReceived.add(replica.hasWritesNotYetDurable());
            });
        }
    }

    private void step() {
        tick++;
        network.tick();
        replica.tick();
        fileSystem.tick();
    }

    private void startReplica() {
        replica.start();
        probe.start();
        for (int i = 0; i < 100 && !replica.isRunning(); i++) step();
        assertTrue(replica.isRunning());
    }

    private void send(String value, long timestamp) throws IOException {
        InternalSetRequest request = new InternalSetRequest(KEY, value.getBytes(), timestamp);
        network.send(Message.of(PROBE, REPLICA, PeerType.SERVER, QuorumMessageTypes.INTERNAL_SET_REQUEST,
                codec.encode(request), "c-" + value));
    }

    private void stepUntilResponses(int count) {
        for (int i = 0; i < 200 && probe.successes.size() < count; i++) step();
        assertEquals(count, probe.successes.size(), "responses");
    }

    private String storedValue() {
        byte[] stored = replica.getStoredValue(KEY);
        return stored == null ? null : new String(codec.decode(stored, VersionedValue.class).value());
    }

    @Test
    void aSkippedWriteIsNotReportedSuccessfulBeforeTheNewerWriteIsDurable() throws IOException {
        startReplica();
        send("newer", 5);
        send("older", 3);   // arrives in the same tick, loses to the write already accepted

        stepUntilResponses(2);

        assertEquals(List.of(true, true), probe.successes);
        assertEquals(List.of(false, false), probe.undurableWritesWhenReceived,
                "no success reply while the write behind it could still be lost");
        assertEquals("newer", storedValue());
    }

    @Test
    void aCrashBeforeTheNewerWriteIsDurableLeavesNoSuccessReply() throws IOException {
        startReplica();
        send("newer", 5);
        send("older", 3);
        // Both requests are handled in the same tick; the newer write is accepted and still on its way to disk.
        for (int i = 0; i < 20 && !replica.hasWritesNotYetDurable(); i++) step();
        assertTrue(replica.hasWritesNotYetDurable(), "the newer write is in flight");
        assertTrue(probe.successes.isEmpty());

        replica.stop();   // the process dies with its disk, as Cluster.crashNode does
        fileSystem.crash();
        for (int i = 0; i < 50; i++) step();

        assertTrue(probe.successes.isEmpty(), "no reply was ever sent for a write a crash took back");
    }

    @Test
    void anOlderWriteFollowedByANewerOneAreBothAppliedInOrder() throws IOException {
        startReplica();
        send("older", 3);
        send("newer", 5);

        stepUntilResponses(2);

        assertEquals(List.of(true, true), probe.successes);
        assertEquals("newer", storedValue());
    }

    @Test
    void equalTimestampsKeepTheFirstWriteAndReplyOnlyOnceItIsDurable() throws IOException {
        startReplica();
        send("first", 4);
        send("second", 4);

        stepUntilResponses(2);

        assertEquals(List.of(true, true), probe.successes);
        assertEquals(List.of(false, false), probe.undurableWritesWhenReceived);
        assertEquals("first", storedValue());
    }
}

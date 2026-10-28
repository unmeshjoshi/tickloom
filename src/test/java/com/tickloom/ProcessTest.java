package com.tickloom;

import com.tickloom.future.TickCompletableFuture;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.Network;
import com.tickloom.network.PeerType;
import com.tickloom.network.SimulatedNetwork;
import com.tickloom.util.IdGen;
import com.tickloom.util.SystemClock;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessTest {

    private static class TestProcess extends Process {
        final AtomicInteger ticksHandled = new AtomicInteger(0);
        final AtomicInteger messagesHandled = new AtomicInteger(0);
        final AtomicBoolean stoppedHookCalled = new AtomicBoolean(false);
        static final MessageType TEST_TYPE = MessageType.of("TEST");

        TestProcess(ProcessId pid, Network network) {
            super(new ProcessParams(pid, network, new JsonMessageCodec(), 1, new SystemClock(), new IdGen(pid.name(), new Random())));
        }

        @Override
        public void onTick() {
            ticksHandled.incrementAndGet();
        }

        @Override
        protected void onStop() {
            stoppedHookCalled.set(true);
        }

        @Override
        protected Map<MessageType, Handler> initialiseHandlers() {
            return Map.of(TEST_TYPE, msg -> messagesHandled.incrementAndGet());
        }
    }

    @Test
    void registersItselfWithNetwork() {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        TestProcess process = new TestProcess(pid, network);
        assertEquals(ProcessState.CREATED, process.getState());

        process.start();
        assertEquals(Set.of(pid), network.registeredProcessIds());
        assertEquals(ProcessState.RUNNING, process.getState());
        assertTrue(process.isRunning());
        assertFalse(process.isStopped());
    }

    @Test
    void stopHaltsTickProcessing() {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        TestProcess process = new TestProcess(pid, network);
        process.start();

        process.tick();
        assertEquals(1, process.ticksHandled.get());

        process.stop();
        assertEquals(ProcessState.STOPPED, process.getState());
        assertTrue(process.isStopped());
        assertFalse(process.isRunning());
        assertTrue(process.stoppedHookCalled.get());

        // Further ticks should be ignored
        process.tick();
        process.tick();
        assertEquals(1, process.ticksHandled.get());
    }

    @Test
    void stopDropsIncomingMessages() {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        TestProcess process = new TestProcess(pid, network);
        process.start();

        Message msg = Message.of(ProcessId.random(), pid, PeerType.CLIENT, process.TEST_TYPE, new byte[0], "c1");
        process.onMessage(msg);
        assertEquals(1, process.messagesHandled.get());

        process.stop();

        // Message received while stopped must be dropped
        process.onMessage(msg);
        assertEquals(1, process.messagesHandled.get());
    }

    @Test
    void startRestartsStoppedProcess() {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        TestProcess process = new TestProcess(pid, network);
        process.start();
        process.stop();
        assertTrue(process.isStopped());

        // Restart
        process.start();
        assertFalse(process.isStopped());
        assertTrue(process.isRunning());

        process.tick();
        assertEquals(1, process.ticksHandled.get());
    }

    private static class SlowStartingProcess extends TestProcess {
        final TickCompletableFuture<Boolean> startFuture = new TickCompletableFuture<>();

        SlowStartingProcess(ProcessId pid, Network network) {
            super(pid, network);
        }

        @Override
        protected TickCompletableFuture<?> onStart() {
            return startFuture;
        }
    }

    @Test
    void isNotRunningUntilOnStartCompletes() {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        SlowStartingProcess process = new SlowStartingProcess(pid, network);

        process.start();
        assertEquals(ProcessState.STARTING, process.getState());

        Message msg = Message.of(ProcessId.random(), pid, PeerType.CLIENT, TestProcess.TEST_TYPE, new byte[0], "c1");
        process.onMessage(msg);
        assertEquals(0, process.messagesHandled.get(), "Messages must not be handled before startup completes");

        process.startFuture.complete(true);
        assertEquals(ProcessState.RUNNING, process.getState());

        process.onMessage(msg);
        assertEquals(1, process.messagesHandled.get());
    }

    @Test
    void closeDelegatesToStop() throws Exception {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        TestProcess process = new TestProcess(pid, network);
        process.start();

        process.close();
        assertTrue(process.isStopped());
        assertFalse(process.isRunning());
        assertTrue(process.stoppedHookCalled.get());
    }
}

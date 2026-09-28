package com.tickloom;

import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.Network;
import com.tickloom.network.PeerType;
import com.tickloom.network.SimulatedNetwork;
import com.tickloom.storage.SimulatedStorage;
import com.tickloom.util.IdGen;
import com.tickloom.util.SystemClock;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessTest {

    private static class TestProcess extends Process {
        final AtomicInteger ticksHandled = new AtomicInteger(0);
        final AtomicInteger messagesHandled = new AtomicInteger(0);
        final AtomicBoolean stoppedHookCalled = new AtomicBoolean(false);
        static final MessageType TEST_TYPE = MessageType.of("TEST");

        TestProcess(ProcessId pid, MessageBus messageBus) {
            super(new ProcessParams(pid, messageBus, new JsonMessageCodec(), 1, new SystemClock(), new IdGen(pid.name(), new Random()), new SimulatedStorage(new Random())));
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
    void registersItselfAsMessageHandler() {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        MessageBus messageBus = new MessageBus(network, new JsonMessageCodec());
        TestProcess process = new TestProcess(pid, messageBus);
        assertEquals(ProcessState.CREATED, process.getState());

        process.start();
        assertEquals(1, messageBus.getHandlers().size());
        assertEquals(messageBus.getHandlers().get(pid), process);
        assertEquals(ProcessState.RUNNING, process.getState());
        assertTrue(process.isRunning());
        assertFalse(process.isStopped());
    }

    @Test
    void stopHaltsTickProcessing() {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        MessageBus messageBus = new MessageBus(network, new JsonMessageCodec());
        TestProcess process = new TestProcess(pid, messageBus);
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
        MessageBus messageBus = new MessageBus(network, new JsonMessageCodec());
        TestProcess process = new TestProcess(pid, messageBus);
        process.start();

        Message msg = Message.of(ProcessId.random(), pid, PeerType.CLIENT, process.TEST_TYPE, new byte[0], "c1");
        process.receiveMessage(msg);
        assertEquals(1, process.messagesHandled.get());

        process.stop();

        // Message received while stopped must be dropped
        process.receiveMessage(msg);
        assertEquals(1, process.messagesHandled.get());
    }

    @Test
    void startRestartsStoppedProcess() {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        MessageBus messageBus = new MessageBus(network, new JsonMessageCodec());
        TestProcess process = new TestProcess(pid, messageBus);
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

    @Test
    void closeDelegatesToStop() throws Exception {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        MessageBus messageBus = new MessageBus(network, new JsonMessageCodec());
        TestProcess process = new TestProcess(pid, messageBus);
        process.start();

        process.close();
        assertTrue(process.isStopped());
        assertFalse(process.isRunning());
        assertTrue(process.stoppedHookCalled.get());
    }
}

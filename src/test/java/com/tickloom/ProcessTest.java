package com.tickloom;

import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageBus;
import com.tickloom.messaging.MessageType;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.Network;
import com.tickloom.network.SimulatedNetwork;
import com.tickloom.util.SystemClock;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ProcessTest {

    @Test
    void registersItselfAsMessageHandler() {
        ProcessId pid = ProcessId.random();
        Network network = SimulatedNetwork.noLossNetwork(new Random());
        MessageBus messageBus = new MessageBus(network, new JsonMessageCodec());
        Process process = new Process(pid, messageBus, null, 1, new SystemClock()) {


            @Override
            public void onTick() {

            }

            @Override
            protected Map<MessageType, Handler> initialiseHandlers() {
                return Map.of();
            }
        };

        assertEquals(1, messageBus.getHandlers().size());
        assertEquals(messageBus.getHandlers().get(pid), process);

    }

}
package com.tickloom;

import com.tickloom.messaging.MessageBus;
import com.tickloom.network.MessageCodec;
import com.tickloom.util.Clock;
import com.tickloom.util.IdGen;

public record ProcessParams(ProcessId id, MessageBus messageBus, MessageCodec messageCodec, int timeoutTicks,
                            Clock clock, IdGen idGenerator) {
}
package com.tickstep.network;

import com.tickstep.messaging.Message;

import java.io.IOException;

public interface Network {
    void tick();
    void send(Message message) throws IOException;
}

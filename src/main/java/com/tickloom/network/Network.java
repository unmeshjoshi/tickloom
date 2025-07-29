package com.tickloom.network;

import com.tickloom.messaging.Message;

import java.io.IOException;

public interface Network {
    void tick();
    void send(Message message) throws IOException;
}

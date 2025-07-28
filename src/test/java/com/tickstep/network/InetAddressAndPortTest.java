package com.tickstep.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InetAddressAndPortTest {

    @Test
    void createInetAddressAndPort() {
        InetAddressAndPort address = InetAddressAndPort.from("127.0.0.1", 8080);
        assertEquals("127.0.0.1", address.address().getHostAddress());
        assertEquals(8080, address.port());
    }

}
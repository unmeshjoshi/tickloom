package com.tickstep.network;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Objects;

public record InetAddressAndPort(InetAddress address, int port) {
    public InetAddressAndPort {
        Objects.requireNonNull(address, "Address cannot be null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
    }

    public static InetAddressAndPort from(String address, int port) {
        try {
            return new InetAddressAndPort(InetAddress.getByName(address), port);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    public static InetAddressAndPort from(InetSocketAddress remoteAddress) {
        return new InetAddressAndPort(remoteAddress.getAddress(), remoteAddress.getPort());
    }

    public InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(address, port);
    }
}

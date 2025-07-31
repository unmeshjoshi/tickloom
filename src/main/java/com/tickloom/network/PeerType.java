package com.tickloom.network;

//PeerType identifies type of the peer. It is communicated with other peers when the network
//connection is established. As of now it is passed with every message and not used.
//But once the handshake mechanism is implemented this can be exchanged only once.
public enum PeerType {
    UNKNOWN,
    CLIENT,
    SERVER,
}
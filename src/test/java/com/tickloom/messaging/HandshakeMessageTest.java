package com.tickloom.messaging;

import com.tickloom.ProcessId;
import com.tickloom.network.PeerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class HandshakeMessageTest {

    @Test
    public void helloMessageContainsRequiredFields() {
        ProcessId clientId = ProcessId.of("clientId-1");
        ProcessId serverId = ProcessId.of("server-1");
        
        // Hello message with required fields: pid, role, version=1
        // Future extensibility: we can add additional fields like:
        //   "capabilities": ["bidir", "compress=zstd"],
        //   "instanceSeq": 42  // optional, for tie-break. etc..
        String helloData = "{\"pid\":\"" + clientId.name() + "\",\"role\":\"clientId\",\"version\":1}";
        Message hello = Message.of(clientId, serverId, PeerType.CLIENT, MessageType.HELLO, helloData.getBytes(), null);
        
        assertEquals(MessageType.HELLO, hello.messageType());
        assertEquals(clientId, hello.source());
        assertEquals(serverId, hello.destination());
        assertArrayEquals(helloData.getBytes(), hello.payload());
    }

    @Test
    public void welcomeMessageContainsServerInfo() {
        ProcessId clientId = ProcessId.of("clientId-1");
        ProcessId serverId = ProcessId.of("server-1");
        
        // Welcome message with server acceptance and info
        String welcomeData = "{\"pid\":\"" + serverId.name() + "\",\"role\":\"replica\",\"version\":1,\"accepted\":true}";
        Message welcome = Message.of(serverId, clientId, PeerType.SERVER, MessageType.WELCOME, welcomeData.getBytes(), null);
        
        assertEquals(MessageType.WELCOME, welcome.messageType());
        assertEquals(serverId, welcome.source());
        assertEquals(clientId, welcome.destination());
        assertArrayEquals(welcomeData.getBytes(), welcome.payload());
    }

    @Test
    public void rejectMessageWhenConnectionDenied() {
        ProcessId clientId = ProcessId.of("clientId-1");
        ProcessId serverId = ProcessId.of("server-1");
        
        // Reject message with reason for rejection
        String rejectData = "{\"reason\":\"unsupported_version\",\"supported_versions\":[1,2]}";
        Message reject = Message.of(serverId, clientId, PeerType.SERVER, MessageType.REJECT, rejectData.getBytes(), null);
        
        assertEquals(MessageType.REJECT, reject.messageType());
        assertEquals(serverId, reject.source());
        assertEquals(clientId, reject.destination());
        assertArrayEquals(rejectData.getBytes(), reject.payload());
    }

    @Test
    public void handshakeMessagesCanHaveCorrelationIds() {
        ProcessId clientId = ProcessId.of("clientId-1");
        ProcessId serverId = ProcessId.of("server-1");
        String correlationId = "handshake-" + System.currentTimeMillis();
        
        // Hello message with correlation ID for tracking
        String helloData = "{\"pid\":\"" + clientId.name() + "\",\"role\":\"worker\",\"version\":1}";
        Message hello = Message.of(clientId, serverId, PeerType.CLIENT, MessageType.HELLO, helloData.getBytes(), correlationId);
        
        assertEquals(correlationId, hello.correlationId());
        assertEquals(MessageType.HELLO, hello.messageType());
    }

    @Test
    public void differentProcessTypesCanUseHandshake() {
        // Different process types can use the same handshake protocol
        ProcessId replicaId = ProcessId.of("replica-1");
        ProcessId workerId = ProcessId.of("worker-1");
        ProcessId clientId = ProcessId.of("clientId-1");
        
        // Replica to replica handshake
        String replicaData = "{\"pid\":\"" + replicaId.name() + "\",\"role\":\"replica\",\"version\":1}";
        Message replicaHello = Message.of(replicaId, ProcessId.of("replica-2"), PeerType.SERVER, MessageType.HELLO, replicaData.getBytes(), null);
        
        // Worker to replica handshake
        String workerData = "{\"pid\":\"" + workerId.name() + "\",\"role\":\"worker\",\"version\":1}";
        Message workerHello = Message.of(workerId, replicaId, PeerType.SERVER, MessageType.HELLO, workerData.getBytes(), null);
        
        // Client to replica handshake
        String clientData = "{\"pid\":\"" + clientId.name() + "\",\"role\":\"clientId\",\"version\":1}";
        Message clientHello = Message.of(clientId, replicaId, PeerType.CLIENT, MessageType.HELLO, clientData.getBytes(), null);
        
        assertEquals(MessageType.HELLO, replicaHello.messageType());
        assertEquals(MessageType.HELLO, workerHello.messageType());
        assertEquals(MessageType.HELLO, clientHello.messageType());
    }

    @Test
    public void connectionHandshakeProtocol() {
        ProcessId clientId = ProcessId.of("clientId-1");
        ProcessId serverId = ProcessId.of("server-1");
        
        // Step 1: Client sends Hello
        String helloData = "{\"pid\":\"" + clientId.name() + "\",\"role\":\"clientId\",\"version\":1}";
        Message hello = Message.of(clientId, serverId, PeerType.CLIENT, MessageType.HELLO, helloData.getBytes(), null);
        
        // Step 2: Server responds with Welcome (acceptance)
        String welcomeData = "{\"pid\":\"" + serverId.name() + "\",\"role\":\"replica\",\"version\":1,\"accepted\":true}";
        Message welcome = Message.of(serverId, clientId, PeerType.SERVER, MessageType.WELCOME, welcomeData.getBytes(), null);
        
        // Connection is now established - ready for data exchange
        assertEquals(MessageType.HELLO, hello.messageType());
        assertEquals(MessageType.WELCOME, welcome.messageType());
    }

    @Test
    public void futureExtensibilityExample() {
        ProcessId clientId = ProcessId.of("clientId-1");
        ProcessId serverId = ProcessId.of("server-1");
        
        // Example of how the protocol can be extended in the future
        // Current required fields: pid, role, version
        // Future optional fields: capabilities, instanceSeq, etc.
        String extendedHelloData = "{\"pid\":\"" + clientId.name() + "\",\"role\":\"clientId\",\"version\":1," +
                "\"capabilities\":[\"bidir\",\"compress=zstd\"]," +
                "\"instanceSeq\":42}";
        Message extendedHello = Message.of(clientId, serverId, PeerType.CLIENT, MessageType.HELLO, extendedHelloData.getBytes(), null);
        
        assertEquals(MessageType.HELLO, extendedHello.messageType());
        assertArrayEquals(extendedHelloData.getBytes(), extendedHello.payload());
    }
} 
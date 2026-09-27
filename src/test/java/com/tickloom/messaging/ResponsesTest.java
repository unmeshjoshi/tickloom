package com.tickloom.messaging;

import com.tickloom.ProcessId;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ResponsesTest {

    @Test
    void shouldTrackSuccessfulResponsesAndReachQuorum() {
        Responses<String> responses = new Responses<>(3, 2, resp -> resp.equals("OK"));

        assertFalse(responses.hasReachedQuorum());
        assertFalse(responses.isQuorumImpossible());

        responses.add(ProcessId.of("node1"), "OK");
        assertFalse(responses.hasReachedQuorum());

        responses.add(ProcessId.of("node2"), "OK");
        assertTrue(responses.hasReachedQuorum());
        assertEquals(2, responses.getSuccessfulResponses().size());
    }

    @Test
    void shouldTrackRejectionsAndDetectWhenQuorumImpossible() {
        Responses<String> responses = new Responses<>(3, 2, resp -> resp.equals("OK"));

        responses.add(ProcessId.of("node1"), "REJECT");
        assertEquals(1, responses.totalFailures());
        assertFalse(responses.isQuorumImpossible()); // max possible = 3 - 1 = 2 >= 2

        responses.add(ProcessId.of("node2"), "REJECT");
        assertEquals(2, responses.totalFailures());
        assertTrue(responses.isQuorumImpossible()); // max possible = 3 - 2 = 1 < 2
    }

    @Test
    void shouldTrackErrorsAndCountTowardsFailures() {
        Responses<String> responses = new Responses<>(3, 2, resp -> resp.equals("OK"));

        responses.add(ProcessId.of("node1"), "REJECT");
        responses.addError(new IOException("Connection reset"));

        assertEquals(2, responses.totalFailures());
        assertTrue(responses.isQuorumImpossible());
    }

    @Test
    void shouldDetectWhenAllResponsesReceived() {
        Responses<String> responses = new Responses<>(3, 3, resp -> resp.equals("OK"));

        responses.add(ProcessId.of("node1"), "OK");
        assertFalse(responses.isAllReceived());

        responses.add(ProcessId.of("node2"), "OK");
        assertFalse(responses.isAllReceived());

        responses.add(ProcessId.of("node3"), "OK");
        assertTrue(responses.isAllReceived());
    }

    @Test
    void shouldValidateArguments() {
        assertThrows(IllegalArgumentException.class, () -> new Responses<>(0, 1, r -> true));
        assertThrows(IllegalArgumentException.class, () -> new Responses<>(3, 0, r -> true));
        assertThrows(IllegalArgumentException.class, () -> new Responses<>(3, 4, r -> true));
    }
}

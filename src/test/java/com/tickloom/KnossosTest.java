package com.tickloom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnossosTest {
    @AfterAll
    public static void shutDownAgents() {
        Knossos.shutdownAgents();
    }

    @Test
    void shouldBeLinearizable_register() {
        // Process p0 writes "3", p1 reads "3" -> linearizable
        History h = new History();
        byte[] k = "k".getBytes(StandardCharsets.UTF_8);
        byte[] v3 = "3".getBytes(StandardCharsets.UTF_8);

        h.invoke("p0", Op.WRITE, k, v3, 1);
        h.ok    ("p0", Op.WRITE, k, v3, 2);

        h.invoke("p1", Op.READ,  k, null, 3);
        h.ok    ("p1", Op.READ,  k, v3,   4);

        String edn = h.toEdn();
        boolean ok = Knossos.checkLinearizableRegister(edn);
        assertTrue(ok, "Expected history to be linearizable");
    }

    @Test
    void shouldBeNonLinearizable_register() {
        // p0 writes "1", p1 reads "2" with no concurrent write of "2" -> non-linearizable
        History h = new History();
        byte[] k = "k".getBytes(StandardCharsets.UTF_8);
        byte[] v1 = "1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "2".getBytes(StandardCharsets.UTF_8);

        h.invoke("p0", Op.WRITE, k, v1, 1);
        h.ok    ("p0", Op.WRITE, k, v1, 2);

        h.invoke("p1", Op.READ,  k, null, 3);
        h.ok    ("p1", Op.READ,  k, v2,   4);

        String edn = h.toEdn();
        boolean ok = Knossos.checkLinearizableRegister(edn);
        assertFalse(ok, "Expected history to be NON-linearizable");
    }
}
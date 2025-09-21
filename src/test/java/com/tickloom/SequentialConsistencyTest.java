package com.tickloom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SequentialConsistencyTest {

    @Test
    void shouldBeSequentialButNotLinearizableForRegister() {
        String ednFull = "["
                + "{:type :invoke, :f :write, :process 0, :time 0,  :index 0, :name \"v0\"},"
                + "{:type :ok,     :f :write, :process 0, :time 10, :index 1, :name \"v0\"},"
                + "{:type :invoke, :f :write, :process 0, :time 20, :index 2, :name \"v1\"},"
                + "{:type :ok,     :f :write, :process 0, :time 30, :index 3, :name \"v1\"},"
                + "{:type :invoke, :f :read,  :process 1, :time 40, :index 4, :name nil},"
                + "{:type :ok,     :f :read,  :process 1, :time 50, :index 5, :name \"v0\"}"
                + "]";

        // Linearizable should fail for the full interval history
        boolean lin = Jepsen.check(ednFull, "linearizable", "register");
        assertFalse(lin);

        boolean seq = Jepsen.checkRegisterSequential(ednFull);
        assertTrue(seq);
    }

    @Test
    void shouldBeNonSequentialForImpossibleRead() {
        String edn = "["
                + "{:type :invoke, :f :write, :process 0, :time 0,  :index 0, :name \"v0\"},"
                + "{:type :ok,     :f :write, :process 0, :time 10, :index 1, :name \"v0\"},"
                + "{:type :invoke, :f :read,  :process 1, :time 20, :index 2, :name nil},"
                + "{:type :ok,     :f :read,  :process 1, :time 30, :index 3, :name \"v1\"}"
                + "]";

        boolean seq = Jepsen.checkRegisterSequential(edn);
        assertFalse(seq);
    }
}



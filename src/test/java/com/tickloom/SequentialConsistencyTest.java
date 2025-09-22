package com.tickloom;

import com.tickloom.ConsistencyChecker.ConsistencyProperty;
import com.tickloom.ConsistencyChecker.DataModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SequentialConsistencyTest {

    @Test
    void shouldBeSequentialButNotLinearizableForRegister() {
        String ednFull = "["
                + "{:type :invoke, :f :write, :process 0, :time 0,  :index 0, :value \"v0\"},"
                + "{:type :ok,     :f :write, :process 0, :time 10, :index 1, :value \"v0\"},"
                + "{:type :invoke, :f :write, :process 0, :time 20, :index 2, :value \"v1\"},"
                + "{:type :ok,     :f :write, :process 0, :time 30, :index 3, :value \"v1\"},"
                + "{:type :invoke, :f :read,  :process 1, :time 40, :index 4, :value nil},"
                + "{:type :ok,     :f :read,  :process 1, :time 50, :index 5, :value \"v0\"}"
                + "]";

        // Linearizable should fail for the full interval history
        boolean lin = ConsistencyChecker.check(ednFull, ConsistencyProperty.LINEARIZABILITY, DataModel.REGISTER);
        assertFalse(lin);

        boolean seq = ConsistencyChecker.check(ednFull, ConsistencyProperty.SEQUENTIAL_CONSISTENCY, DataModel.REGISTER);
        assertTrue(seq);
    }

    @Test
    void shouldBeNonSequentialForImpossibleRead() {
        String edn = "["
                + "{:type :invoke, :f :write, :process 0, :time 0,  :index 0, :value \"v0\"},"
                + "{:type :ok,     :f :write, :process 0, :time 10, :index 1, :value \"v0\"},"
                + "{:type :invoke, :f :read,  :process 1, :time 20, :index 2, :value nil},"
                + "{:type :ok,     :f :read,  :process 1, :time 30, :index 3, :value \"v1\"}"
                + "]";

        boolean seq = ConsistencyChecker.check(edn, ConsistencyProperty.SEQUENTIAL_CONSISTENCY, DataModel.REGISTER);
        assertFalse(seq);
    }
}



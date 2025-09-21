package com.tickloom.history;

import com.tickloom.ProcessId;

//Model - Client - Workload
//a client can provide callback that the generic clusterclient can call to get history event.
//invokeEvent   responseEvent failureEvent..


@Deprecated
//Wrapper class for History.n
//The new implementation is JepsenHistory
//we will step by step replace this class.
public class History<K, V> {
    //Make jepsen history parameterised. For KV store, we can have value as JepsenHistory.tuple.
    JepsenHistory jepsenHistory = new JepsenHistory();

    public Long getProcessIndex(ProcessId processId) {
        return jepsenHistory.getProcessIndex(processId);
    }

    public void invoke(ProcessId processId, Op op, K key, V value) {
        jepsenHistory.invoke(processId, op, value);
    }

    public void ok(ProcessId processId, Op op, K key, V value) {
        jepsenHistory.ok(processId, op, value);
    }

    public void timeout(ProcessId processId, Op op, K key, V value) {
        jepsenHistory.info(processId, op, value);
    }

    public void fail(ProcessId processId, Op op, Object key, Object value) {
        jepsenHistory.fail(processId, op, value);
    }


    // ----- Simple adapter: com.tickloom.history.History -> EDN vector of op maps -----
    public String toEdn() {
        return jepsenHistory.getEdnString();
    }
}
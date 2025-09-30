package com.tickloom.testkit;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.GetResponse;
import com.tickloom.algorithms.replication.quorum.SetResponse;
import com.tickloom.future.ListenableFuture;
import com.tickloom.history.History;
import com.tickloom.history.HistoryRecorder;
import com.tickloom.history.Op;

import java.util.function.Function;
import java.util.function.Supplier;

//V is type of jepsen history :value can either be String :value "hello" or IPersistentVector [k v]
class QuorumKVOps<RGet, V> {
    private final Function<RGet, V> readValue;
    HistoryRecorder recorder = new HistoryRecorder();

    public QuorumKVOps(Function<RGet, V> readValue) {
        this.readValue = readValue;
    }

    //test helpers
    public ListenableFuture<SetResponse> write(ProcessId id, V attemptedValue, Supplier<ListenableFuture<?>> requestInvoker) {
        return recorder.<SetResponse>invoke(id, Op.WRITE, attemptedValue, requestInvoker, (response) -> attemptedValue);
    }

    public ListenableFuture<RGet> read(ProcessId id, Supplier<ListenableFuture<RGet>> requestInvoker) {
        return recorder.<GetResponse>invoke(id, Op.READ, (String) null, requestInvoker, readValue);
    }

    public History history() {
        return recorder.getHistory();

    }
}

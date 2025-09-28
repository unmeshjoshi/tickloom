package com.tickloom.history;

import com.tickloom.ProcessId;
import com.tickloom.future.ListenableFuture;

import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

public class HistoryRecorder<K, V> {
    History<K, V> history = new History<K, V>();

    public <T> ListenableFuture invoke(ProcessId process, final Op op,
                                       V invokedValue,
                                       Supplier<ListenableFuture<?>> requestInvoker,
                                       Function<Object, V> valueFromMessage) {
        history.invoke(process, op, invokedValue);
        ListenableFuture<Object> fut = (ListenableFuture<Object>) requestInvoker.get();
        return fut.andThen((resp, ex) -> {
            try {
                if (ex == null) {
                    var v = valueFromMessage.apply(resp);
                    history.ok(process, op, v);
                    return;
                }
                recordTimeoutOrFailure(op, ex, process, invokedValue);

            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    private void recordTimeoutOrFailure(Op op, Throwable ex, ProcessId process, V historyValue) {
        if (shouldRecordTimeoutSeparately(op, ex)) {
            history.timeout(process, op, historyValue);
        } else {
            history.fail(process, op, historyValue);
        }
    }

    private static boolean shouldRecordTimeoutSeparately(Op op, Throwable ex) {
        return !(op == Op.READ) && ex instanceof TimeoutException;
    }

    public History getHistory() {
        return history;
    }
}

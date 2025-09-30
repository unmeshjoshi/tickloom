package com.tickloom.history;

import com.tickloom.ProcessId;
import com.tickloom.future.ListenableFuture;

import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * This is more like a proxy or an 'around' aspect,
 * which records Jepsen history events for the
 * requests made to the server.
 *  *
 * V is the type of :value in the jepsen history
 * It can be simple string or a tuple e.g. [key value]
 * Example history looks as follows
 * For HistoryRecorder<IPersistentVector>
 * [:process 0 :op :invoke f: :write :value ["key" "value"]]
 * For HistoryRecorcer<String>
 * [:process 0 :op :ok :f :read :value "value"]
 *
 *
 */
public class HistoryRecorder<V> {
    History<V> history = new History<V>();

    public <Response> ListenableFuture<Response> invoke(ProcessId process, final Op op,
                                                        V invokedValue,
                                                        Supplier<ListenableFuture<Response>> requestInvoker,
                                                        Function<Response, V> valueFromMessage) {
        history.invoke(process, op, invokedValue);
        ListenableFuture<Response> fut = requestInvoker.get();
        return fut.andThen((resp, ex) -> {
            try {
                if (ex == null) {
                    var v = valueFromMessage.apply(resp);
                    history.ok(process, op, v);
                    return;
                }
                recordTimeoutOrFailure(op, ex, process, invokedValue);

            } catch (Exception e) {
                e.printStackTrace();
            }
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

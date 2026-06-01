package com.tickloom.history;

import com.tickloom.ProcessId;
import com.tickloom.future.TickCompletableFuture;

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

    public <Response> TickCompletableFuture<Response> invoke(ProcessId process, final Op op,
                                                             V invokedValue,
                                                             Supplier<TickCompletableFuture<Response>> requestInvoker,
                                                             Function<Response, V> valueFromMessage) {
        history.invoke(process, op, invokedValue);
        return requestInvoker.get().whenComplete((resp, ex) -> {
                if (ex == null) {
                    var v = valueFromMessage.apply(resp);
                    history.ok(process, op, v);
                } else {
                    recordTimeoutOrFailure(op, ex, process, invokedValue);
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

    @Deprecated
    public History getHistory() {
        return history;
    }

    public JepsenHistory jepsenHistory() {
        return history.jepsenHistory;
    }
}

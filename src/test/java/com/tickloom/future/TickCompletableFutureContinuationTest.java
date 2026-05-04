package com.tickloom.future;

import com.tickloom.Continuation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Context: Why do we use Continuations with ListenableFutures?
 *
 * In Tickloom, we aim to expose ONLY `ListenableFuture` at the API level for any
 * asynchronous or distributed operation. Internal constructs like `RequestWaitingList`
 * or `AsyncQuorumCallback` are kept entirely hidden implementations.
 * Thus, complex workflows only need to coordinate a sequence of `ListenableFuture`s.
 *
 * 1. The Callback Hell (Pyramid of Doom) Problem:
 * If we rely purely on chaining futures with callbacks, the logic deeply nests
 * and state variables must be captured in inner closures:
 *
 * listAccountsAsync().handle((accounts, err1) -> {
 *     sendQuorumLockRequest(accounts).handle((lockResult, err2) -> {
 *         executeTxnAsync(lockResult).handle((txnResult, err3) -> {
 *             sendQuorumCommitRequest(txnResult).handle((commitResult, err4) -> {
 *                 // FINALLY done. Nesting gets deeper with each operation!
 *             });
 *         });
 *     });
 * });
 *
 * 2. How Continuation Solves It
 * We encapsulate the workflow as a flat state machine implementing `Continuation`.
 * Each `ListenableFuture`'s completion directly calls `resume()`. The `resume()`
 * method transitions the state and fires the next async future, allowing you to
 * trace logic linearly without nesting closures.
 *
 * class DistributedTxnWorkflow implements Continuation {
 *     int state = GET_ACCOUNTS;
 *
 *     @Override
 *     public void resume(Object result) {
 *         switch(state) {
 *             case GET_ACCOUNTS:
 *                 state = ACQUIRE_LOCKS;
 *                 listAccountsAsync().whenComplete(this);
 *                 return;
 *
 *             case ACQUIRE_LOCKS:
 *                 state = EXECUTE_TXN;
 *                 sendQuorumLockRequest(result).whenComplete(this);
 *                 return;
 *
 *             case EXECUTE_TXN:
 *                 state = COMMIT_TXN;
 *                 executeTxnAsync(result).whenComplete(this);
 *                 return;
 *
 *             case COMMIT_TXN:
 *                 finishProcess();
 *         }
 *     }
 * }
 */
public class TickCompletableFutureContinuationTest {

    // Step 1: whenComplete resumes the continuation with the result on success
    @Test
    public void continuationIsResumedWithResultWhenFutureCompletes() {
        AtomicReference<String> resumed = new AtomicReference<>();
        Continuation<String> c = result -> resumed.set(result);

        TickCompletableFuture<String> future = new TickCompletableFuture<>();
        future.whenComplete(c);
        future.complete("hello");

        assertEquals("hello", resumed.get());
    }

    // Step 2: whenComplete calls resumeWithError when the future fails
    @Test
    public void continuationIsResumedWithErrorWhenFutureFails() {
        AtomicReference<Throwable> receivedError = new AtomicReference<>();
        Continuation<String> c = new Continuation<>() {
            @Override
            public void resume(String result) {
                fail("resume() should not be called on failure");
            }

            @Override
            public void resumeWithError(Throwable error) {
                receivedError.set(error);
            }
        };

        TickCompletableFuture<String> future = new TickCompletableFuture<>();
        future.whenComplete(c);
        RuntimeException expected = new RuntimeException("boom");
        future.fail(expected);

        assertSame(expected, receivedError.get());
    }

    // Step 1b: whenComplete resumes immediately if future is already completed
    @Test
    public void continuationIsResumedImmediatelyWhenFutureAlreadyCompleted() {
        AtomicReference<String> resumed = new AtomicReference<>();
        Continuation<String> c = result -> resumed.set(result);

        TickCompletableFuture<String> future = new TickCompletableFuture<>();
        future.complete("already-done");
        future.whenComplete(c);

        assertEquals("already-done", resumed.get());
    }

    // Step 2b: resumeWithError default throws if not overridden
    @Test
    public void defaultResumeWithErrorThrowsRuntimeException() {
        Continuation<String> c = result -> {};
        RuntimeException cause = new RuntimeException("original");

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> c.resumeWithError(cause));
        assertSame(cause, thrown.getCause());
    }

    // Step 4: Continuation flattens a multi-step get-then-put workflow
    // This mirrors QuorumReplica.handleInternalSetRequest pattern
    @Test
    public void continuationFlattensGetThenPutWorkflow() {
        // Simulate async storage with manually-controlled futures
        TickCompletableFuture<String> getFuture = new TickCompletableFuture<>();
        TickCompletableFuture<Boolean> putFuture = new TickCompletableFuture<>();

        AtomicReference<String> finalResult = new AtomicReference<>();
        AtomicReference<Throwable> finalError = new AtomicReference<>();

        // The continuation-based state machine: flat, no nesting
        var workflow = new GetThenPutWorkflow(getFuture, putFuture, finalResult, finalError);
        workflow.start();

        // Step 1: storage.get() completes — value is "old", so we proceed to put
        getFuture.complete("old");
        assertNull(finalResult.get(), "Should not be done yet — put hasn't completed");

        // Step 2: storage.put() completes
        putFuture.complete(true);
        assertEquals("put-done", finalResult.get());
    }

    @Test
    public void continuationHandlesErrorInFirstStep() {
        TickCompletableFuture<String> getFuture = new TickCompletableFuture<>();
        TickCompletableFuture<Boolean> putFuture = new TickCompletableFuture<>();

        AtomicReference<String> finalResult = new AtomicReference<>();
        AtomicReference<Throwable> finalError = new AtomicReference<>();

        var workflow = new GetThenPutWorkflow(getFuture, putFuture, finalResult, finalError);
        workflow.start();

        // Step 1 fails
        getFuture.fail(new RuntimeException("storage down"));

        assertNull(finalResult.get());
        assertEquals("storage down", finalError.get().getMessage());
    }

    @Test
    public void continuationSkipsPutWhenValueIsCurrent() {
        TickCompletableFuture<String> getFuture = new TickCompletableFuture<>();
        TickCompletableFuture<Boolean> putFuture = new TickCompletableFuture<>();

        AtomicReference<String> finalResult = new AtomicReference<>();
        AtomicReference<Throwable> finalError = new AtomicReference<>();

        var workflow = new GetThenPutWorkflow(getFuture, putFuture, finalResult, finalError);
        workflow.start();

        // Existing value is "current" — workflow should skip the put
        getFuture.complete("current");

        assertEquals("already-current", finalResult.get());
    }

    /**
     * A flat state machine that replaces the nested callback pattern in
     * QuorumReplica.handleInternalSetRequest.
     *
     * Callback hell version:
     *   storage.get(key).handle((result, error) -> {
     *       if (...) { storage.put(key, value).handle((success, err) -> { ... }); }
     *   });
     *
     * Continuation version: flat switch-based state machine below.
     */
    static class GetThenPutWorkflow implements Continuation<Object> {
        private static final int GET = 0;
        private static final int PUT = 1;

        private int state = GET;
        private final TickCompletableFuture<String> getFuture;
        private final TickCompletableFuture<Boolean> putFuture;
        private final AtomicReference<String> finalResult;
        private final AtomicReference<Throwable> finalError;

        GetThenPutWorkflow(TickCompletableFuture<String> getFuture,
                           TickCompletableFuture<Boolean> putFuture,
                           AtomicReference<String> finalResult,
                           AtomicReference<Throwable> finalError) {
            this.getFuture = getFuture;
            this.putFuture = putFuture;
            this.finalResult = finalResult;
            this.finalError = finalError;
        }

        @SuppressWarnings("unchecked")
        void start() {
            state = GET;
            getFuture.whenComplete((Continuation) this);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void resume(Object result) {
            switch (state) {
                case GET:
                    String existing = (String) result;
                    if ("current".equals(existing)) {
                        finalResult.set("already-current");
                        return;
                    }
                    state = PUT;
                    putFuture.whenComplete((Continuation) this);
                    return;

                case PUT:
                    finalResult.set("put-done");
                    return;
            }
        }

        @Override
        public void resumeWithError(Throwable error) {
            finalError.set(error);
        }
    }
}

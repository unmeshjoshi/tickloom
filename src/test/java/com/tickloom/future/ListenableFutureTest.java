package com.tickloom.future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ListenableFuture.
 * Tests cover state management, error handling, result retrieval, and callback handling.
 */
class ListenableFutureTest {

    private ListenableFuture<String> future;

    @BeforeEach
    void setUp() {
        future = new ListenableFuture<>();
    }

    // Test 1: Initial state (pending)
    @Test
    @DisplayName("Should start in pending state")
    void shouldStartInPendingState() {
        assertTrue(future.isPending(), "Future should be pending initially");
        assertFalse(future.isCompleted(), "Future should not be completed initially");
        assertFalse(future.isFailed(), "Future should not be failed initially");
    }

    // Test 2: Complete with result
    @Test
    @DisplayName("Should transition to completed state when completed with result")
    void shouldTransitionToCompletedStateWhenCompletedWithResult() {
        // Given
        String expectedResult = "test-result";

        // When
        future.complete(expectedResult);

        // Then
        assertFalse(future.isPending(), "Future should not be pending after completion");
        assertTrue(future.isCompleted(), "Future should be completed after completion");
        assertFalse(future.isFailed(), "Future should not be failed after completion");
        assertEquals(expectedResult, future.getResult(), "Result should match what was provided");
    }

    // Test 3: Fail with exception
    @Test
    @DisplayName("Should transition to failed state when failed with exception")
    void shouldTransitionToFailedStateWhenFailedWithException() {
        // Given
        RuntimeException expectedException = new RuntimeException("test-error");

        // When
        future.fail(expectedException);

        // Then
        assertFalse(future.isPending(), "Future should not be pending after failure");
        assertFalse(future.isCompleted(), "Future should not be completed after failure");
        assertTrue(future.isFailed(), "Future should be failed after failure");
        assertEquals(expectedException, future.getException(), "Exception should match what was provided");
    }

    // Test 4: Double completion should throw
    @Test
    @DisplayName("Should throw exception when completing already completed future")
    void shouldThrowExceptionWhenCompletingAlreadyCompletedFuture() {
        // Given
        future.complete("first-result");

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, 
            () -> future.complete("second-result"),
            "Should throw IllegalStateException when completing already completed future");
        
        assertEquals("Future is already resolved", exception.getMessage());
        assertEquals("first-result", future.getResult(), "Original result should be preserved");
    }

    // Test 5: Double failure should throw
    @Test
    @DisplayName("Should throw exception when failing already failed future")
    void shouldThrowExceptionWhenFailingAlreadyFailedFuture() {
        // Given
        RuntimeException firstException = new RuntimeException("first-error");
        future.fail(firstException);

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> future.fail(new RuntimeException("second-error")),
            "Should throw IllegalStateException when failing already failed future");
        
        assertEquals("Future is already resolved", exception.getMessage());
        assertEquals(firstException, future.getException(), "Original exception should be preserved");
    }

    // Test 6: Complete after fail should throw
    @Test
    @DisplayName("Should throw exception when completing already failed future")
    void shouldThrowExceptionWhenCompletingAlreadyFailedFuture() {
        // Given
        RuntimeException expectedException = new RuntimeException("test-error");
        future.fail(expectedException);

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> future.complete("result"),
            "Should throw IllegalStateException when completing already failed future");
        
        assertEquals("Future is already resolved", exception.getMessage());
        assertEquals(expectedException, future.getException(), "Original exception should be preserved");
    }

    // Test 7: Fail after complete should throw
    @Test
    @DisplayName("Should throw exception when failing already completed future")
    void shouldThrowExceptionWhenFailingAlreadyCompletedFuture() {
        // Given
        future.complete("test-result");

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> future.fail(new RuntimeException("error")),
            "Should throw IllegalStateException when failing already completed future");
        
        assertEquals("Future is already resolved", exception.getMessage());
        assertEquals("test-result", future.getResult(), "Original result should be preserved");
    }

    // Test 8: Get result when completed (already tested in Test 2, but explicit test)
    @Test
    @DisplayName("Should return result when future is completed")
    void shouldReturnResultWhenFutureIsCompleted() {
        // Given
        String expectedResult = "success-result";
        future.complete(expectedResult);

        // When
        String actualResult = future.getResult();

        // Then
        assertEquals(expectedResult, actualResult, "getResult() should return the completed result");
    }

    // Test 9: Get result when pending should throw
    @Test
    @DisplayName("Should throw exception when getting result from pending future")
    void shouldThrowExceptionWhenGettingResultFromPendingFuture() {
        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> future.getResult(),
            "Should throw IllegalStateException when getting result from pending future");
        
        assertEquals("Future is not completed successfully", exception.getMessage());
    }

    // Test 10: Get result when failed should throw
    @Test
    @DisplayName("Should throw exception when getting result from failed future")
    void shouldThrowExceptionWhenGettingResultFromFailedFuture() {
        // Given
        future.fail(new RuntimeException("failure"));

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> future.getResult(),
            "Should throw IllegalStateException when getting result from failed future");
        
        assertEquals("Future is not completed successfully", exception.getMessage());
    }

    // Test 11: Get exception when failed (already tested in Test 3, but explicit test)
    @Test
    @DisplayName("Should return exception when future is failed")
    void shouldReturnExceptionWhenFutureIsFailed() {
        // Given
        RuntimeException expectedException = new RuntimeException("failure-reason");
        future.fail(expectedException);

        // When
        Throwable actualException = future.getException();

        // Then
        assertEquals(expectedException, actualException, "getException() should return the failure exception");
    }

    // Test 12: Get exception when completed should throw
    @Test
    @DisplayName("Should throw exception when getting exception from completed future")
    void shouldThrowExceptionWhenGettingExceptionFromCompletedFuture() {
        // Given
        future.complete("success");

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> future.getException(),
            "Should throw IllegalStateException when getting exception from completed future");
        
        assertEquals("Future has not failed", exception.getMessage());
    }

    // Test 13: Get exception when pending should throw
    @Test
    @DisplayName("Should throw exception when getting exception from pending future")
    void shouldThrowExceptionWhenGettingExceptionFromPendingFuture() {
        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> future.getException(),
            "Should throw IllegalStateException when getting exception from pending future");
        
        assertEquals("Future has not failed", exception.getMessage());
    }

    // Test 14: Handle callback on pending future
    @Test
    @DisplayName("Should invoke callback when future is completed after callback registration")
    void shouldInvokeCallbackWhenFutureIsCompletedAfterCallbackRegistration() {
        // Given
        AtomicReference<String> receivedResult = new AtomicReference<>();
        AtomicReference<Throwable> receivedException = new AtomicReference<>();
        
        // When
        future.whenComplete((result, exception) -> {
            receivedResult.set(result);
            receivedException.set(exception);
        });

        future.complete("callback-result");

        // Then
        assertEquals("callback-result", receivedResult.get(), "Callback should receive the result");
        assertNull(receivedException.get(), "Callback should receive null exception for success");
    }

    // Test 15: Handle callback on completed future
    @Test
    @DisplayName("Should immediately invoke callback when registering on already completed future")
    void shouldImmediatelyInvokeCallbackWhenRegisteringOnAlreadyCompletedFuture() {
        // Given
        future.complete("immediate-result");
        AtomicReference<String> receivedResult = new AtomicReference<>();
        AtomicReference<Throwable> receivedException = new AtomicReference<>();
        
        // When
        future.whenComplete((result, exception) -> {
            receivedResult.set(result);
            receivedException.set(exception);
        });

        // Then
        assertEquals("immediate-result", receivedResult.get(), "Callback should immediately receive the result");
        assertNull(receivedException.get(), "Callback should receive null exception for success");
    }

    // Test 16: Handle callback on failed future
    @Test
    @DisplayName("Should immediately invoke callback when registering on already failed future")
    void shouldImmediatelyInvokeCallbackWhenRegisteringOnAlreadyFailedFuture() {
        // Given
        RuntimeException expectedException = new RuntimeException("immediate-failure");
        future.fail(expectedException);
        AtomicReference<String> receivedResult = new AtomicReference<>();
        AtomicReference<Throwable> receivedException = new AtomicReference<>();
        
        // When
        future.whenComplete((result, exception) -> {
            receivedResult.set(result);
            receivedException.set(exception);
        });

        // Then
        assertNull(receivedResult.get(), "Callback should receive null result for failure");
        assertEquals(expectedException, receivedException.get(), "Callback should immediately receive the exception");
    }

    // Test 17: Multiple callbacks should fail
    @Test
    @DisplayName("Should throw exception when registering multiple callbacks on a pending future")
    void shouldThrowExceptionWhenRegisteringMultipleCallbacksOnAPendingFuture() {
        // Given
        future.whenComplete((result, exception) -> {});
        
        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> future.whenComplete((result, error) -> {}),
            "Should throw IllegalStateException when registering multiple callbacks on pending future");
        
        assertEquals("Only a single callback is supported for pending future", exception.getMessage());
    }

    // Test 18: Callback invocation on resolved future
    @Test
    @DisplayName("Should allow registering multiple callbacks if future is already resolved")
    void shouldAllowRegisteringMultipleCallbacksIfFutureIsAlreadyResolved() {
        // Given
        future.complete("multi-result");
        List<String> callbackResults = new ArrayList<>();
        
        // When - register multiple callbacks on COMPLETED future
        // Each whenComplete returns a new future, but we call them on the same original future
        future.whenComplete((result, exception) -> callbackResults.add("callback1: " + result));
        future.whenComplete((result, exception) -> callbackResults.add("callback2: " + result));

        // Then
        assertEquals(2, callbackResults.size(), "Both callbacks should be invoked for completed future");
    }

    @Test
    @DisplayName("Should support chaining multiple whenComplete calls")
    void shouldSupportChainingMultipleWhenCompleteCalls() {
        AtomicReference<String> result1 = new AtomicReference<>();
        AtomicReference<String> result2 = new AtomicReference<>();

        future.whenComplete((r, e) -> result1.set("1:" + r))
              .whenComplete((r, e) -> result2.set("2:" + r));

        future.complete("ok");

        assertEquals("1:ok", result1.get());
        assertEquals("2:ok", result2.get());
    }

    // Test 19: Callback with failure scenario
    @Test
    @DisplayName("Should invoke callback with exception when future fails after callback registration")
    void shouldInvokeCallbackWithExceptionWhenFutureFailsAfterCallbackRegistration() {
        // Given
        AtomicReference<String> receivedResult = new AtomicReference<>();
        AtomicReference<Throwable> receivedException = new AtomicReference<>();
        RuntimeException expectedException = new RuntimeException("callback-failure");
        
        // When
        future.whenComplete((result, exception) -> {
            receivedResult.set(result);
            receivedException.set(exception);
        });

        future.fail(expectedException);

        // Then
        assertNull(receivedResult.get(), "Callback should receive null result for failure");
    }

    @Test
    @DisplayName("Should support thenApply for result transformation")
    void shouldSupportThenApplyForResultTransformation() {
        AtomicReference<Integer> finalResult = new AtomicReference<>();

        future.thenApply(String::length)
              .whenComplete((r, e) -> finalResult.set(r));

        future.complete("hello");

        assertEquals(5, finalResult.get());
    }

    @Test
    public void thenComposeShouldChainAsyncOperations() {
        ListenableFuture<String> first = new ListenableFuture<>();
        ListenableFuture<Integer> second = new ListenableFuture<>();
        AtomicReference<Integer> finalResult = new AtomicReference<>();

        // thenCompose chains: first future's result feeds into a function
        // that returns a new future
        ListenableFuture<Integer> chained = first.thenCompose(result -> {
            // simulate an async operation that depends on the first result
            return second;
        });
        chained.whenComplete((result, exception) -> finalResult.set(result));

        // Complete the first future
        first.complete("hello");
        assertNull(finalResult.get(), "Should not be done yet — inner future hasn't completed");

        // Complete the inner future
        second.complete(42);
        assertEquals(42, finalResult.get());
    }

    @Test
    public void thenComposeShouldReturnNewFuture() {
        ListenableFuture<String> original = new ListenableFuture<>();
        ListenableFuture<String> nextStage = original.thenCompose(result -> {
            return new ListenableFuture<>();
        });

        assertNotSame(original, nextStage, "thenCompose() should return a new future, not the original");
    }

    @Test
    public void thenComposeShouldPropagateFailureFromOriginal() {
        ListenableFuture<String> original = new ListenableFuture<>();
        AtomicReference<Throwable> receivedError = new AtomicReference<>();

        ListenableFuture<Integer> chained = original.thenCompose(result -> {
            fail("Function should not be called when original fails");
            return new ListenableFuture<>();
        });
        chained.whenComplete((result, exception) -> receivedError.set(exception));

        original.fail(new RuntimeException("original failed"));

        assertNotNull(receivedError.get());
        assertEquals("original failed", receivedError.get().getMessage());
    }

    @Test
    public void thenComposeShouldPropagateFailureFromInnerFuture() {
        ListenableFuture<String> original = new ListenableFuture<>();
        ListenableFuture<Integer> inner = new ListenableFuture<>();
        AtomicReference<Throwable> receivedError = new AtomicReference<>();

        ListenableFuture<Integer> chained = original.thenCompose(result -> inner);
        chained.whenComplete((result, exception) -> receivedError.set(exception));

        original.complete("ok");
        inner.fail(new RuntimeException("inner failed"));

        assertNotNull(receivedError.get());
        assertEquals("inner failed", receivedError.get().getMessage());
    }

    @Test
    public void thenComposeShouldFailWhenFunctionThrows() {
        ListenableFuture<String> original = new ListenableFuture<>();
        AtomicReference<Throwable> receivedError = new AtomicReference<>();

        ListenableFuture<Integer> chained = original.thenCompose(result -> {
            throw new RuntimeException("function threw");
        });
        chained.whenComplete((result, exception) -> receivedError.set(exception));

        original.complete("value");

        assertNotNull(receivedError.get());
        assertEquals("function threw", receivedError.get().getMessage());
    }

    @Test
    public void thenComposeShouldSupportMultiStepChaining() {
        ListenableFuture<String> step1 = new ListenableFuture<>();
        ListenableFuture<Integer> step2 = new ListenableFuture<>();
        ListenableFuture<Boolean> step3 = new ListenableFuture<>();
        AtomicReference<Boolean> finalResult = new AtomicReference<>();

        // Chain three async steps
        ListenableFuture<Boolean> chained = step1
            .thenCompose(s -> step2)
            .thenCompose(i -> step3);
        chained.whenComplete((result, exception) -> finalResult.set(result));

        step1.complete("start");
        assertNull(finalResult.get());

        step2.complete(42);
        assertNull(finalResult.get());

        step3.complete(true);
        assertEquals(true, finalResult.get());
    }
}

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
        future.handle((result, exception) -> {
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
        future.handle((result, exception) -> {
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
        future.handle((result, exception) -> {
            receivedResult.set(result);
            receivedException.set(exception);
        });

        // Then
        assertNull(receivedResult.get(), "Callback should receive null result for failure");
        assertEquals(expectedException, receivedException.get(), "Callback should immediately receive the exception");
    }

    // Test 17: Multiple callbacks
    @Test
    @DisplayName("Should invoke all registered callbacks when future is resolved")
    void shouldInvokeAllRegisteredCallbacksWhenFutureIsResolved() {
        // Given
        List<String> callbackResults = new ArrayList<>();
        
        // When - register multiple callbacks
        future.handle((result, exception) -> callbackResults.add("callback1: " + result));
        future.handle((result, exception) -> callbackResults.add("callback2: " + result));
        future.handle((result, exception) -> callbackResults.add("callback3: " + result));
        
        future.complete("multi-result");

        // Then
        assertEquals(3, callbackResults.size(), "All callbacks should be invoked");
        assertTrue(callbackResults.contains("callback1: multi-result"), "First callback should receive result");
        assertTrue(callbackResults.contains("callback2: multi-result"), "Second callback should receive result");
        assertTrue(callbackResults.contains("callback3: multi-result"), "Third callback should receive result");
    }

    // Test 18: Callback method chaining
    @Test
    @DisplayName("Should support method chaining with handle callback")
    void shouldSupportMethodChainingWithHandleCallback() {
        // Given
        AtomicBoolean firstCallbackInvoked = new AtomicBoolean(false);
        AtomicBoolean secondCallbackInvoked = new AtomicBoolean(false);
        
        // When - chain multiple handle calls
        ListenableFuture<String> returnedFuture = future
            .handle((result, exception) -> firstCallbackInvoked.set(true))
            .handle((result, exception) -> secondCallbackInvoked.set(true));
        
        future.complete("chain-result");

        // Then
        assertSame(future, returnedFuture, "handle() should return the same future instance for chaining");
        assertTrue(firstCallbackInvoked.get(), "First callback should be invoked");
        assertTrue(secondCallbackInvoked.get(), "Second callback should be invoked");
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
        future.handle((result, exception) -> {
            receivedResult.set(result);
            receivedException.set(exception);
        });
        
        future.fail(expectedException);

        // Then
        assertNull(receivedResult.get(), "Callback should receive null result for failure");
        assertEquals(expectedException, receivedException.get(), "Callback should receive the exception");
    }


    @Test
    public void shouldSupportCompositionWithAndThen() {
        AtomicReference<String> firstHandleResult = new AtomicReference<>("");
        AtomicReference<String> secondHandleResult = new AtomicReference<>("");
        ListenableFuture<String> future = new ListenableFuture<>();
        //Each handle call puts a new callback
        future.handle((result, exception) -> {
            firstHandleResult.set(result);
        });
        //andThen creates a new future which can be then separately handled
        //But also creates a new callback on the original future which
        //invokes the function passed to andThen
        future.andThen((result, exception) -> {
            secondHandleResult.set(result);
        });
        future.complete("TestResult");

        assertEquals("TestResult", firstHandleResult.get());
        assertEquals("TestResult", secondHandleResult.get());
    }
}

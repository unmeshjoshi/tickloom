package com.tickloom.future

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.resume

/**
 * Tests for the suspend fun ListenableFuture<T>.await() extension.
 *
 * This extension bridges tickloom's ListenableFuture to Kotlin coroutines,
 * eliminating manual suspendCoroutine boilerplate in every server.
 *
 * Without await():
 *   suspendCoroutine { cont ->
 *       quorumCallback.onSuccess { cont.resume(it.values) }
 *                     .onFailure { cont.resumeWithException(it) }
 *       ...
 *   }
 *
 * With await():
 *   val responses = quorumCallback.getQuorumFuture().await()
 */
class TickCompletableFutureAwaitTest {

    /** Helper to run a suspend block synchronously (like launchCoordinator). */
    private fun runSuspend(block: suspend () -> Unit) {
        var error: Throwable? = null
        val completion = object : kotlin.coroutines.Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                error = result.exceptionOrNull()
            }
        }
        val coroutine = suspend { block() }
        coroutine.createCoroutine(completion).resume(Unit)
        error?.let { throw it }
    }

    @Test
    fun `await resumes with result when future completes`() {
        val future = TickCompletableFuture<String>()
        var result: String? = null

        runSuspend {
            // Future is pending — coroutine suspends here
            // We complete it after launching, so we need to complete before runSuspend returns
            // Actually, since this is single-threaded, we need to complete it outside
        }

        // Better approach: complete before await
        val future2 = TickCompletableFuture<String>()
        future2.complete("hello")

        runSuspend {
            result = future2.await()
        }

        assertEquals("hello", result)
    }

    @Test
    fun `await throws when future fails`() {
        val future = TickCompletableFuture<String>()
        future.fail(RuntimeException("boom"))

        var caught: Throwable? = null
        try {
            runSuspend {
                future.await()
            }
        } catch (e: Throwable) {
            caught = e
        }

        assertNotNull(caught)
        assertEquals("boom", caught!!.message)
    }

    @Test
    fun `await suspends and resumes when future completes later`() {
        val future = TickCompletableFuture<String>()
        var result: String? = null
        var suspended = false

        // Launch coroutine — it will suspend at await()
        val completion = object : kotlin.coroutines.Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(r: Result<Unit>) {}
        }
        val coroutine = suspend {
            result = future.await()
        }
        coroutine.createCoroutine(completion).resume(Unit)

        // Coroutine is now suspended, result not yet set
        assertNull(result)

        // Complete the future — this should resume the coroutine
        future.complete("deferred")

        assertEquals("deferred", result)
    }

    @Test
    fun `await suspends and resumes with error when future fails later`() {
        val future = TickCompletableFuture<String>()
        var caught: Throwable? = null

        val completion = object : kotlin.coroutines.Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(r: Result<Unit>) {
                caught = r.exceptionOrNull()
            }
        }
        val coroutine: suspend () -> Unit = {
            future.await()
        }
        coroutine.createCoroutine(completion).resume(Unit)

        assertNull(caught)

        future.fail(RuntimeException("later-boom"))

        assertNotNull(caught)
        assertEquals("later-boom", caught!!.message)
    }

    @Test
    fun `await works in sequential chain`() {
        val future1 = TickCompletableFuture<String>()
        val future2 = TickCompletableFuture<Int>()
        var finalResult: String? = null

        val completion = object : kotlin.coroutines.Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(r: Result<Unit>) {}
        }
        val coroutine: suspend () -> Unit = {
            val first = future1.await()
            val second = future2.await()
            finalResult = "$first-$second"
        }
        coroutine.createCoroutine(completion).resume(Unit)

        assertNull(finalResult)

        future1.complete("step1")
        assertNull(finalResult) // still waiting for future2

        future2.complete(42)
        assertEquals("step1-42", finalResult)
    }
}

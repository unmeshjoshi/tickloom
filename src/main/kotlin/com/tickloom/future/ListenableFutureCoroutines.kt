package com.tickloom.future

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Bridges tickloom's [ListenableFuture] to Kotlin coroutines.
 *
 * Usage:
 * ```kotlin
 * val responses = quorumCallback.getQuorumFuture().await()
 * ```
 *
 * Instead of:
 * ```kotlin
 * suspendCoroutine { cont ->
 *     quorumCallback
 *         .onSuccess { cont.resume(it.values) }
 *         .onFailure { cont.resumeWithException(it) }
 * }
 * ```
 */
suspend fun <T> ListenableFuture<T>.await(): T = suspendCoroutine { cont ->
    this.whenComplete { result, exception ->
        if (exception != null) {
            cont.resumeWithException(exception)
        } else {
            cont.resume(result)
        }
    }
}

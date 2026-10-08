package io.github.jpicklyk.mcptask.current.application.support

import kotlin.coroutines.cancellation.CancellationException

/**
 * Rethrows this throwable when it is a [CancellationException]; otherwise returns normally.
 * First statement of every catch-all in code that can run on a coroutine, so cancellation is
 * never swallowed or translated into a failure.
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

/**
 * Runs [block]; a [CancellationException] propagates, any other [Exception] is handed to [onError].
 * Inline, so [block] and [onError] may call suspend functions.
 */
inline fun <T> catchNonCancellation(
    block: () -> T,
    onError: (Exception) -> T
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
    }

/** Like [runCatching], but a [CancellationException] propagates instead of being captured. */
inline fun <T> runCatchingNonCancellation(block: () -> T): kotlin.Result<T> =
    try {
        kotlin.Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        kotlin.Result.failure(e)
    }

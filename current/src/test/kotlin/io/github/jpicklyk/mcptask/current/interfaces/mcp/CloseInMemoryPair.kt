package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tears down an SDK [Client]/[Server] pair that is wired over an in-memory `ChannelTransport`
 * pair, in an order that cannot make two callers close the same transport at once.
 *
 * Why: closing the client ends the server transport's receive channel, which makes the server
 * transport's own event loop call `close()` on a Default-pool thread. A bare
 * `client.close(); server.close()` then races that event loop on the SAME server transport. The
 * SDK's `close()` is check-then-act, so the loser throws `IllegalStateException: Can't change
 * state: expected transport state Operational, but found ShuttingDown`. When the event loop
 * loses, the exception is uncaught and kotlinx-coroutines-test pins it on whichever `runTest`
 * test runs next (`UncaughtExceptionsBeforeTest`); when the caller loses, it is thrown from
 * teardown (bug f664fd4f).
 *
 * Contract, in order:
 * 1. Snapshot the transport of every session currently in [Server.sessions] and register a close
 *    callback on each (before anything is closed, so none can be missed).
 * 2. `client.close()`.
 * 3. Wait until every snapshotted server-session transport has finished closing.
 * 4. `server.close()`; by then every transport is already closed, so it only releases the
 *    server's remaining resources (and is expected to leave [Server.sessions] empty).
 *
 * Behaviour:
 * - A server with no sessions, or a client that never connected, is fine: nothing is awaited
 *   and the call returns normally.
 * - Failure is loud: if a server-session transport has not closed within [timeout] (for example
 *   a second client is still connected to a session of [server], which this call does not
 *   close), a [kotlinx.coroutines.TimeoutCancellationException] is thrown and `server.close()`
 *   is NOT called. The call never hangs past [timeout] plus the time `client.close()` and
 *   `server.close()` take, and it never swallows an exception from either.
 * - [withTimeout] measures virtual time inside a `runTest` scope, so call this helper from
 *   `runBlocking` (as every current caller does) or run it on a real dispatcher.
 * - Idempotent: calling it again for a pair that was already torn down returns normally,
 *   because the server has no remaining sessions to await.
 * - Only sessions present when it is called are awaited; sessions created concurrently with the
 *   call are not.
 *
 * @param client the client side of the pair
 * @param server the server side of the pair
 * @param timeout upper bound for step 3; default 5 seconds
 */
internal suspend fun closeInMemoryPair(
    client: Client,
    server: Server,
    timeout: Duration = 5.seconds
) {
    val closed =
        server.sessions.values.map { session ->
            val done = CompletableDeferred<Unit>()
            session.transport?.onClose { done.complete(Unit) } ?: done.complete(Unit)
            done
        }
    client.close()
    withTimeout(timeout) { closed.awaitAll() }
    server.close()
}

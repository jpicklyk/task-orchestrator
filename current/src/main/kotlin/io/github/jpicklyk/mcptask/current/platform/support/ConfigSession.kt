package io.github.jpicklyk.mcptask.current.platform.support

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Per-request memo for the effective config resolver's layer reads (`EffectiveConfigResolver` in the config slice). The
 * memo holds the values it is handed without knowing their type, so platform names no config type: the unit runner
 * installs a fresh session per unit attempt, and the config resolver reads through it.
 *
 * **Scope: one tool call.** Installed into the coroutine context by [withConfigSession] at the
 * call boundary (the tool dispatcher's run step, `ToolDispatcher`, which every transport goes through -
 * one session per call) and torn down when that call
 * returns. A `CancellationException` from a read is never memoised: it is rethrown and the next
 * lookup for the same key reads fresh. It is NOT shared across separate calls, and NOT shared with
 * coroutines started outside this element's structured-concurrency subtree - notably, a plain
 * `kotlinx.coroutines.runBlocking { ... }` invoked from inside a session-wrapped `execute` does
 * NOT inherit the ambient [ConfigSession] (it defaults to `EmptyCoroutineContext`), so per-root
 * reads made from within such a block are unmemoised.
 *
 * Keyed by `(source instance identity, id)`, so multiple sources sharing one session get independent entries. One
 * source always stores one value type, which [memoized] relies on when it hands a stored value back. Stores
 * `kotlin.Result` of the value: success, null, AND
 * failure are all memoised: a failed per-root read is fetched at most once per session for a given
 * key, and every subsequent read for that key within the same session rethrows the SAME exception
 * rather than retrying.
 *
 * With no [ConfigSession] ambient in the coroutine context (no call installed one), every read is
 * fresh: existing direct resolver callers (tests, and any caller outside a wrapped `execute`) are unaffected by this
 * class's existence.
 */
class ConfigSession : AbstractCoroutineContextElement(ConfigSession) {
    companion object Key : CoroutineContext.Key<ConfigSession>

    private val mutex = Mutex()
    private val memo = mutableMapOf<Pair<Any, UUID>, Result<Any?>>()

    /**
     * Drops every memoised read of [rootId]'s per-root layer, so the next lookup in this session reads fresh. A write
     * that appended a config version calls this so a later op of the same call sees the new head.
     */
    internal suspend fun evict(rootId: UUID) {
        mutex.withLock { memo.keys.removeAll { it.second == rootId } }
    }

    /**
     * Returns the memoised value for `(source, rootId)`, invoking [fetch] and storing its
     * outcome (success, null, or failure) on the first call for that key within this session, and
     * replaying the same outcome (rethrowing on failure) on every subsequent call.
     *
     * D7: a [CancellationException] from [fetch] is NEVER memoised - it is rethrown as-is, and the
     * next call for the same key re-invokes [fetch] fresh. A coroutine cancellation is not a
     * property of the underlying config read; caching it would incorrectly poison every later
     * lookup of the same key for the rest of the session with an unrelated cancellation.
     */
    internal suspend fun <T> memoized(
        source: Any,
        rootId: UUID,
        fetch: suspend () -> T
    ): T {
        val key = source to rootId
        val cached = mutex.withLock { memo[key] }
        // Safe: the key holds the source's identity, and one source always stores the value type T its fetch returns.
        @Suppress("UNCHECKED_CAST")
        if (cached != null) return cached.getOrThrow() as T

        val outcome = runCatching { fetch() }
        val cancellation = outcome.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation

        mutex.withLock { memo[key] = outcome }
        return outcome.getOrThrow()
    }
}

/**
 * Runs [block] with a [ConfigSession] installed in the coroutine context, unless one is already
 * ambient - a nested `withConfigSession` call reuses the outer session rather than installing a
 * fresh, empty one, so an inner tool invocation shares its caller's memo.
 */
suspend fun <T> withConfigSession(block: suspend () -> T): T {
    val existing = coroutineContext[ConfigSession.Key]
    return if (existing != null) {
        block()
    } else {
        withContext(ConfigSession()) { block() }
    }
}

/** Evicts [rootId] from the ambient [ConfigSession] memo, if one is installed (see [ConfigSession.evict]). */
suspend fun evictConfigSession(rootId: UUID) {
    coroutineContext[ConfigSession.Key]?.evict(rootId)
}

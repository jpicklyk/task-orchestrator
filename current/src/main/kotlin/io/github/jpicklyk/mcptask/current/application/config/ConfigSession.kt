package io.github.jpicklyk.mcptask.current.application.config

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Per-request memo for [EffectiveConfigResolver]'s per-root layer reads.
 *
 * **Scope: one tool call.** Installed into the coroutine context by [withConfigSession] at the
 * call boundary ([io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter] and the
 * `advance_item`/`get_context`/`complete_tree`/`create_work_tree` tool `execute` bodies) and torn
 * down when that call returns. It is NOT shared across separate calls, and NOT shared with
 * coroutines started outside this element's structured-concurrency subtree — notably, a plain
 * `kotlinx.coroutines.runBlocking { ... }` invoked from inside a session-wrapped `execute` does
 * NOT inherit the ambient [ConfigSession] (it defaults to `EmptyCoroutineContext`), so per-root
 * reads made from within such a block are unmemoised. See `implementation-notes` on `ce346f52`
 * for the two call sites (`AdvanceItemTool`, `CreateWorkTreeTool`) where this applies today.
 *
 * Keyed by `(PerRootConfigSource instance identity, rootId)`, so multiple sources sharing one
 * session get independent entries. Stores `kotlin.Result<ConfigLayer?>` — success, null, AND
 * failure are all memoised: a failed per-root read is fetched at most once per session for a given
 * key, and every subsequent read for that key within the same session rethrows the SAME exception
 * rather than retrying.
 *
 * With no [ConfigSession] ambient in the coroutine context (no call installed one), every read is
 * fresh — existing direct [EffectiveConfigResolver] callers (tests, and any caller outside a
 * wrapped `execute`) are unaffected by this class's existence.
 */
class ConfigSession : AbstractCoroutineContextElement(ConfigSession) {
    companion object Key : CoroutineContext.Key<ConfigSession>

    private val mutex = Mutex()
    private val memo = mutableMapOf<Pair<Any, UUID>, Result<ConfigLayer?>>()

    /**
     * Returns the memoised [ConfigLayer] for `(source, rootId)`, invoking [fetch] and storing its
     * outcome (success, null, or failure) on the first call for that key within this session, and
     * replaying the same outcome (rethrowing on failure) on every subsequent call.
     */
    internal suspend fun memoized(
        source: Any,
        rootId: UUID,
        fetch: suspend () -> ConfigLayer?
    ): ConfigLayer? {
        val key = source to rootId
        val cached = mutex.withLock { memo[key] }
        val result =
            cached ?: runCatching { fetch() }.also { outcome ->
                mutex.withLock { memo[key] = outcome }
            }
        return result.getOrThrow()
    }
}

/**
 * Runs [block] with a [ConfigSession] installed in the coroutine context, unless one is already
 * ambient — a nested `withConfigSession` call reuses the outer session rather than installing a
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

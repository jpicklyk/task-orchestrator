package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

/**
 * Deterministic replacement for the fixed-`delay` / bounded-window event-collection pattern used
 * across the event test suites (item 646b12a6, O5) — item.
 *
 * ## Why a fixed wait is unnecessary
 *
 * [ApiEventBus.subscribe] registers the subscriber and its bounded [kotlinx.coroutines.channels.Channel]
 * synchronously, at call time, not when the returned [Flow] starts collecting. [DeferredEventPublisher.publishOnCommit]
 * publishes synchronously, before it returns, when no transaction is open; inside a transaction it
 * publishes at `afterCommit` (or discards on rollback) — and either way this happens before the
 * `inTransaction` call returns (see [DeferredEventPublisher]'s KDoc). So by the time a write under
 * test has returned, every event it produced has already reached the subscriber's channel. There is
 * nothing left to wait for.
 *
 * ## Usage
 *
 * `subscribe` → perform the write(s) under test → [drainDelivered] → assert on the returned list.
 * [drainDelivered] unsubscribes first (closing the channel) and then drains whatever is already
 * queued — [kotlinx.coroutines.channels.Channel.close] still delivers previously buffered elements,
 * so nothing already sent is lost, and nothing MORE can arrive after the channel is closed. The
 * [withTimeout] is a hang guard only, never a wait window: a correctly-implemented publish path
 * returns from `toList()` immediately once the channel drains and closes.
 *
 * ## Risk if publishing ever becomes asynchronous
 *
 * This helper (and the exact-count assertions built on it) are correct only while publishing
 * stays synchronous, per [DeferredEventPublisher]'s contract. If a future change made
 * `publishOnCommit` schedule work asynchronously instead, this helper would UNDER-count rather than
 * hang — it would drain and close before the async publish completed. The exact-count assertions in
 * the tests that use this helper are what catches that: a missing event fails the count assertion
 * rather than passing silently.
 */
internal suspend fun ApiEventBus.drainDelivered(
    subscriberId: String,
    flow: Flow<ApiEvent>,
): List<ApiEvent> {
    unsubscribe(subscriberId)
    return withTimeout(5_000) { flow.toList() }
}

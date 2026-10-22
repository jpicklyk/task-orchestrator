package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-context element carrying the actor responsible for the repository writes made inside
 * the current scope, so the event recorder can attribute the `events` rows those writes record
 * (their `principal_id` / `principal_kind`).
 *
 * Repository writes carry no actor of their own (only notes and transitions do), so write sites
 * install this around the per-unit write with [withEventActor]. The recorder reads it when it
 * appends, inside the unit. A null [claim] means "no actor".
 */
class EventActor(
    val claim: ActorClaim?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<EventActor>
}

/** Run [block] with [claim] as the actor attributed to any domain events its writes publish. */
suspend fun <T> withEventActor(
    claim: ActorClaim?,
    block: suspend () -> T,
): T = withContext(EventActor(claim)) { block() }

/** The actor installed by [withEventActor] in the current coroutine context, or null. */
suspend fun currentEventActor(): ActorClaim? = coroutineContext[EventActor]?.claim

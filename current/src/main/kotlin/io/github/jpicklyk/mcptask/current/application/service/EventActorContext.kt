package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-context element carrying the actor responsible for the repository writes made inside
 * the current scope, so the SSE event-publishing decorator can attribute the domain events those
 * writes emit.
 *
 * Repository writes carry no actor of their own (only `Note.actorClaim` does), so write sites
 * install this around the per-unit write with [withEventActor]. The decorator reads it at ENQUEUE
 * time (the post-commit flush has no coroutine context). A null [claim] means "no actor".
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

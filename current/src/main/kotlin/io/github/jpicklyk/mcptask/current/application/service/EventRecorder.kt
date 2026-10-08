package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventCommitListener
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventSink
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.HierarchyStore
import io.github.jpicklyk.mcptask.current.application.port.UnitElement
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Turns typed [DomainEvent]s into `events` rows and appends them through [store] (plan section 3.7).
 *
 * Each row is stamped with a fresh UUID id, `occurred_at` = the unit's instant ([Clock.unitNow], so every row of
 * one unit carries the same time), and the principal: the entity's own actor claim when the event carries one
 * (a note, a transition), else the ambient [currentEventActor], else none. `proof_status` is the entity's
 * verification status when it has one. The actor's `parent`, when present, goes into the JSON `data` as
 * `actorParent`. `req_id`, `host`, `session_id`, `run_id` and `seat` stay null until P10/W4/W5 capture them.
 *
 * Appends join the ambient unit of work, so they commit or roll back with the change. Once the unit commits,
 * [listener] is told about the rows (the SSE projection's wake-up); an append made outside any unit (tests over
 * the implicit-unit policy) is reported right away.
 */
class EventRecorder(
    private val store: EventStore,
    private val clock: Clock = Clock.SYSTEM,
    private val listener: EventCommitListener = EventCommitListener.NONE
) : EventSink {
    override suspend fun record(events: List<DomainEvent>): List<EventRecord> {
        if (events.isEmpty()) return emptyList()
        val now = clock.unitNow()
        val ambientActor = currentEventActor()
        val appended = store.append(events.map { toRecord(it, now, ambientActor) })
        if (listener !== EventCommitListener.NONE) {
            val unit = coroutineContext[UnitElement]?.unit
            if (unit != null) unit.addCommit { listener.committed(appended) } else listener.committed(appended)
        }
        return appended
    }

    private fun toRecord(
        event: DomainEvent,
        now: Instant,
        ambientActor: ActorClaim?
    ): EventRecord {
        val actor = event.entityActor ?: ambientActor
        val data = LinkedHashMap<String, JsonElement>()
        for ((key, value) in event.payload()) data[key] = toJson(value)
        if (actor?.parent != null) data["actorParent"] = JsonPrimitive(actor.parent)
        return EventRecord(
            id = UUID.randomUUID(),
            occurredAt = now,
            rootId = event.rootId,
            entityKind = event.entityKind,
            entityId = event.entityId,
            type = event.type,
            principalId = actor?.id,
            principalKind = actor?.kind?.toJsonString(),
            proofStatus = event.entityVerification?.status?.toJsonString(),
            data = JsonObject(data).toString()
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(EventRecorder::class.java)

        /** The error codes whose rejections are recorded as `*.rejected` rows (plan section 3.7). */
        val REJECTION_CODES: Set<ErrorCode> =
            setOf(ErrorCode.GATE_BLOCKED, ErrorCode.CLAIM_HELD, ErrorCode.RESOURCE_UNAVAILABLE, ErrorCode.DEPENDENCY_UNMET)

        internal fun toJson(value: Any?): JsonElement =
            when (value) {
                null -> JsonNull
                is JsonElement -> value
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is UUID -> JsonPrimitive(value.toString())
                is Enum<*> -> JsonPrimitive(value.name.lowercase())
                is Collection<*> -> JsonArray(value.map { toJson(it) })
                else -> JsonPrimitive(value.toString())
            }

        internal fun logRejectionFailure(
            type: String,
            e: Exception
        ) = logger.warn("Recording a {} row failed; the rejection itself is unchanged: {}", type, e.message)
    }
}

/**
 * The root an item's events belong to: its denormalized `rootId`, else the depth-0 ancestor found through
 * [hierarchy], else the item's own id (a root, or an orphan whose chain cannot be resolved). Never null.
 */
suspend fun eventRootOf(
    item: WorkItem,
    hierarchy: HierarchyStore
): UUID = item.rootId ?: eventRootOf(item.id, hierarchy)

/** [eventRootOf] for an item known only by id: the first id of its ancestor chain, else the id itself. */
suspend fun eventRootOf(
    itemId: UUID,
    hierarchy: HierarchyStore
): UUID = hierarchy.findAncestorChains(setOf(itemId))[itemId]?.firstOrNull()?.id ?: itemId

/**
 * Appends [event] (a `*.rejected` row) in its OWN short write unit. The rejected operation wrote nothing (or rolled
 * back), so the row cannot ride in its unit. A failure to record is logged at WARN and swallowed: the rejection the
 * caller returns is never changed by its audit row.
 */
suspend fun UnitOfWork.recordRejection(event: DomainEvent) {
    try {
        write("events.recordRejection") {
            events.record(event)
            Outcome.Ok(Unit)
        }
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        EventRecorder.logRejectionFailure(event.type, e)
    }
}

/**
 * Registers an `afterRollback` hook on this unit: when it rolls back with a [DomainError] whose code is one of
 * [EventRecorder.REJECTION_CODES], [build] turns the error into a `*.rejected` event, which [unitOfWork] records in a
 * follow-up unit ([recordRejection]). [build] may return null to skip. Other rollbacks record nothing.
 */
fun WriteScope.recordRejectionOnRollback(
    unitOfWork: UnitOfWork,
    build: (DomainError) -> DomainEvent?
) {
    afterRollback { error ->
        if (error != null && error.code in EventRecorder.REJECTION_CODES) {
            build(error)?.let { unitOfWork.recordRejection(it) }
        }
    }
}

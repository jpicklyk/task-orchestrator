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
import io.github.jpicklyk.mcptask.current.application.telemetry.CallTelemetry
import io.github.jpicklyk.mcptask.current.application.telemetry.currentCallTelemetry
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
 * `actorParent`. `req_id` and `session_id` come from the ambient [CallTelemetry] (null outside a transport call); `host`, `run_id` and `seat` stay null until W4/W5 capture them.
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
        val call = currentCallTelemetry()
        val appended = store.append(events.map { toRecord(it, now, ambientActor, call) })
        if (listener !== EventCommitListener.NONE) {
            val unit = coroutineContext[UnitElement]?.unit
            if (unit != null) unit.addCommit { listener.committed(appended) } else listener.committed(appended)
        }
        return appended
    }

    /**
     * Records [event] in the ambient unit and, when that unit was opened by a [UnitOfWork] (its owner), registers a
     * rollback hook that appends the same event in a FRESH unit of that owner. The row is so written exactly once:
     * by the ambient unit when it commits, by the follow-up unit when it rolls back (a keyed call's element unit
     * rolls back on the very rejection it records). The hook is registered BEFORE the append, so an append that
     * poisons the unit still leaves the row to the follow-up. With no ambient unit this is a plain [record].
     */
    override suspend fun recordRejection(event: DomainEvent) {
        val unit = coroutineContext[UnitElement]?.unit
        val owner = unit?.owner
        if (unit != null && owner != null) unit.addRollback { appendDetached(owner, event) }
        record(event)
    }

    private fun toRecord(
        event: DomainEvent,
        now: Instant,
        ambientActor: ActorClaim?,
        call: CallTelemetry?
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
            reqId = call?.reqId,
            sessionId = call?.sessionId,
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
        ) = logRejectionFailure(type, e.message)

        internal fun logRejectionFailure(
            type: String,
            message: String?
        ) = logger.warn("Recording a {} row failed; the rejection itself is unchanged: {}", type, message)
    }
}

/** Appends [event] in its own fresh unit of [owner] (no ambient unit exists when a rollback hook runs). */
private suspend fun appendDetached(
    owner: UnitOfWork,
    event: DomainEvent
) {
    try {
        val outcome =
            owner.write("events.recordRejection") {
                events.record(event)
                Outcome.Ok(Unit)
            }
        if (outcome is Outcome.Err) EventRecorder.logRejectionFailure(event.type, outcome.error.message)
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        EventRecorder.logRejectionFailure(event.type, e)
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
 * Appends [event] (a `*.rejected` row) so that it survives the caller's transaction. Outside any unit it is its OWN
 * short write unit. Under an ambient unit (an idempotency-keyed call runs the whole element in one, and rolls it back
 * on the rejection) it joins that unit AND registers a rollback hook that re-appends it in a fresh unit
 * ([EventSink.recordRejection]): exactly one row whether the ambient unit commits or rolls back. A failure to record
 * is logged at WARN and swallowed: the rejection the caller returns is never changed by its audit row.
 */
suspend fun UnitOfWork.recordRejection(event: DomainEvent) {
    try {
        write("events.recordRejection") {
            events.recordRejection(event)
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

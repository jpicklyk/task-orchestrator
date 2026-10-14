package io.github.jpicklyk.mcptask.current.domain.event

/**
 * The retention class of an `events` row type.
 *
 * [AUDIT] rows record what the graph IS (who moved, created, deleted, re-parented or pinned an item, which config and
 * document versions existed): they follow `RETENTION_AUDIT_EVENTS_DAYS` (default 0 = kept forever). [ACTIVITY] rows
 * record what happened around it (note writes, claims, leases, rejections): they follow `RETENTION_EVENTS_DAYS`.
 */
enum class EventClass {
    AUDIT,
    ACTIVITY
}

/**
 * The event class map: every [DomainEvent] type constant is classified here, beside the constants themselves.
 *
 * Audit is matched by exact type ([AUDIT_TYPES]) or by family prefix ([AUDIT_PREFIXES]), so a `run.*`, `decision.*` or
 * new `document.*` type is audit the day it lands, with no edit here. Activity is an explicit list
 * ([ACTIVITY_TYPES]): a new non-audit type is unclassified ([of] returns null) until it is added, and an unclassified
 * type is never pruned. `EventClassCoverageTest` fails the build on an unclassified [DomainEvent] constant.
 */
object EventClasses {
    /** Audit types matched exactly. */
    val AUDIT_TYPES: Set<String> =
        setOf(
            DomainEvent.ITEM_TRANSITIONED,
            DomainEvent.ITEM_CREATED,
            DomainEvent.ITEM_DELETED,
            DomainEvent.ITEM_REPARENTED,
            DomainEvent.ITEM_PINNED,
            DomainEvent.CONFIG_VERSION_CREATED,
            DomainEvent.DOCUMENT_CREATED,
            DomainEvent.DOCUMENT_UPDATED,
            DomainEvent.DOCUMENT_ARCHIVED,
            DomainEvent.DOCUMENT_DELETED,
            DomainEvent.DOCUMENT_ADOPTED,
            DomainEvent.NOTE_OWNERSHIP_TRANSFERRED
        ) + DomainEvent.RUN_TYPES

    /** Audit type families: any type starting with one of these is audit. */
    val AUDIT_PREFIXES: Set<String> = setOf("document.", "run.", "decision.")

    /** Activity types: every known type that is not audit. */
    val ACTIVITY_TYPES: Set<String> =
        setOf(
            DomainEvent.ITEM_UPDATED,
            DomainEvent.TRANSITION_REJECTED,
            DomainEvent.NOTE_UPSERTED,
            DomainEvent.NOTE_DELETED,
            DomainEvent.DEPENDENCY_ADDED,
            DomainEvent.DEPENDENCY_REMOVED,
            DomainEvent.CLAIM_ACQUIRED,
            DomainEvent.CLAIM_RELEASED,
            DomainEvent.CLAIM_REJECTED,
            DomainEvent.CLAIM_EXPIRED,
            DomainEvent.LEASE_ACQUIRED,
            DomainEvent.LEASE_RELEASED,
            DomainEvent.LEASE_REJECTED,
            DomainEvent.LEASE_EXPIRED,
            DomainEvent.SEAT_ASSIGNED,
            DomainEvent.SEAT_ENDED
        )

    /** The class of [type], or null when the map does not know it (an unknown type is never pruned). */
    fun of(type: String): EventClass? =
        when {
            type in AUDIT_TYPES || AUDIT_PREFIXES.any { type.startsWith(it) } -> EventClass.AUDIT
            type in ACTIVITY_TYPES -> EventClass.ACTIVITY
            else -> null
        }
}

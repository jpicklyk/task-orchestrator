package io.github.jpicklyk.mcptask.current.domain.event

import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 0da29ea1) for the event class map ([EventClasses]): scenario S11 of the frozen
 * test-plan.
 *
 * Oracle (frozen task-scope, "Event class map" and decision D2): audit = `item.transitioned/created/deleted/reparented/pinned`,
 * `config.version_created`, `document.*`, `note.ownership_transferred`, `run.*`, `decision.*`; activity = everything else; the
 * coverage test fails on an unclassified type; a type the map does not know classifies as null (never pruned at runtime). The
 * `run.*`, `decision.*` and `note.ownership_transferred` types do not exist yet, so they are classified by name and prefix.
 * The expected sets below are written out from that clause, not read from the map.
 *
 * Forbidden-construct declaration: none.
 */
class EventClassCoverageTest {
    private val auditConstants =
        setOf(
            "item.transitioned",
            "item.created",
            "item.deleted",
            "item.reparented",
            "item.pinned",
            "config.version_created",
            "document.created",
            "document.updated",
            "document.archived",
            "document.deleted",
            "document.adopted",
            "run.opened",
            "run.updated",
            "run.paused",
            "run.resumed",
            "run.ended",
            "run.marked"
        )

    /** Every constant the DomainEvent companion declared when this test was authored, grouped by the oracle clause. */
    private val activityConstants =
        setOf(
            "item.updated",
            "transition.rejected",
            "note.upserted",
            "note.deleted",
            "dependency.added",
            "dependency.removed",
            "claim.acquired",
            "claim.released",
            "claim.rejected",
            "claim.expired",
            "lease.acquired",
            "lease.released",
            "lease.rejected",
            "lease.expired"
        )

    /** Every public static String constant on [DomainEvent] shaped like an event type (`family.name`). */
    private fun declaredEventTypes(): Set<String> {
        val typePattern = Regex("^[a-z]+\\.[a-z_]+$")
        return DomainEvent::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { runCatching { it.get(null) as? String }.getOrNull() }
            .filter { typePattern.matches(it) }
            .toSet()
    }

    @Test
    fun `S11 the reflection scan sees the whole DomainEvent constant set (non-vacuity)`() {
        val declared = declaredEventTypes()
        assertTrue(
            declared.containsAll(auditConstants + activityConstants),
            "the scan must find every known constant; missing ${(auditConstants + activityConstants) - declared}, found $declared"
        )
    }

    @Test
    fun `S11 every declared DomainEvent type has a class`() {
        val unclassified = declaredEventTypes().filter { EventClasses.of(it) == null }
        assertTrue(
            unclassified.isEmpty(),
            "unclassified DomainEvent type(s) $unclassified: add each to the audit or activity side of EventClasses"
        )
    }

    @Test
    fun `S11 the audit constants classify as AUDIT`() {
        for (t in auditConstants) assertEquals(EventClass.AUDIT, EventClasses.of(t), t)
    }

    @Test
    fun `S11 every other declared constant classifies as ACTIVITY`() {
        for (t in declaredEventTypes() - auditConstants) assertEquals(EventClass.ACTIVITY, EventClasses.of(t), t)
        for (t in activityConstants) assertEquals(EventClass.ACTIVITY, EventClasses.of(t), t)
    }

    @Test
    fun `S11 claim expired is activity (its protection is a pruner guard, not a class)`() {
        assertEquals(EventClass.ACTIVITY, EventClasses.of("claim.expired"))
    }

    @Test
    fun `S11 not-yet-existing audit types classify by name and prefix`() {
        assertEquals(EventClass.AUDIT, EventClasses.of("note.ownership_transferred"))
        for (t in listOf("run.started", "run.finished", "run.anything_new")) assertEquals(EventClass.AUDIT, EventClasses.of(t), t)
        for (t in listOf("decision.recorded", "decision.superseded", "decision.anything_new")) {
            assertEquals(EventClass.AUDIT, EventClasses.of(t), t)
        }
        assertEquals(EventClass.AUDIT, EventClasses.of("document.some_future_verb"), "document.* is a family")
    }

    @Test
    fun `S11 an unknown type is null, not a guess`() {
        assertNull(EventClasses.of("zzz.unknown"))
        assertNull(EventClasses.of(""))
        assertNull(EventClasses.of("item"))
    }

    @Test
    fun `S11 probe - a family prefix does not match a longer word that merely starts with it`() {
        // "runner.started" starts with the letters "run" but is not the run.* family; "decisions.x" is not decision.*.
        assertNull(EventClasses.of("runner.started"))
        assertNull(EventClasses.of("decisions.made"))
    }

    @Test
    fun `S11 probe - classification is case sensitive`() {
        assertNull(EventClasses.of("ITEM.TRANSITIONED"))
        assertNull(EventClasses.of("Item.Transitioned"))
    }

    @Test
    fun `S11 the audit and activity type sets do not overlap and the audit constants are in the audit side`() {
        assertTrue(EventClasses.AUDIT_TYPES.intersect(EventClasses.ACTIVITY_TYPES).isEmpty(), "a type is on one side only")
        for (t in EventClasses.ACTIVITY_TYPES) assertEquals(EventClass.ACTIVITY, EventClasses.of(t), t)
        for (t in EventClasses.AUDIT_TYPES) assertEquals(EventClass.AUDIT, EventClasses.of(t), t)
        assertNotNull(EventClasses.AUDIT_PREFIXES)
        assertTrue(EventClasses.AUDIT_PREFIXES.isNotEmpty(), "run.* and decision.* are prefix-classified")
    }
}

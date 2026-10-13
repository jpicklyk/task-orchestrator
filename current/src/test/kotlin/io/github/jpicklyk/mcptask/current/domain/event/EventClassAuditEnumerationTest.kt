package io.github.jpicklyk.mcptask.current.domain.event

import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertTrue

/**
 * Guard: RetentionPruner enumerates only [EventClasses.AUDIT_TYPES], so an audit-class type matched only by prefix
 * would never be pruned under RETENTION_AUDIT_EVENTS_DAYS. Every declared audit type must be listed explicitly.
 */
class EventClassAuditEnumerationTest {
    private fun declaredEventTypes(): Set<String> {
        val typePattern = Regex("^[a-z]+[.][a-z_]+$")
        return DomainEvent::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { runCatching { it.get(null) as? String }.getOrNull() }
            .filter { typePattern.matches(it) }
            .toSet()
    }

    @Test
    fun `every run event type is enumerated in AUDIT_TYPES`() {
        val missing = DomainEvent.RUN_TYPES.filterNot { it in EventClasses.AUDIT_TYPES }
        assertTrue(DomainEvent.RUN_TYPES.size == 6 && missing.isEmpty(), "run types missing from AUDIT_TYPES: $missing")
    }

    @Test
    fun `every declared audit-class type is enumerated in AUDIT_TYPES`() {
        val declared = declaredEventTypes()
        assertTrue(declared.isNotEmpty(), "the declared-type scan found nothing")
        val missing = declared.filter { EventClasses.of(it) == EventClass.AUDIT && it !in EventClasses.AUDIT_TYPES }
        assertTrue(missing.isEmpty(), "audit types matched only by prefix, not pruned: $missing")
    }
}

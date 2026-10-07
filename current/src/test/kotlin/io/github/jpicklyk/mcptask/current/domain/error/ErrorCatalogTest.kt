package io.github.jpicklyk.mcptask.current.domain.error

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorCatalogTest {
    private data class Row(
        val code: ErrorCode,
        val wire: String,
        val kind: ErrorKind,
        val status: Int,
        val detail: KClass<out ErrorDetail>?,
    )

    // Literal oracle: envelope section 3 (status table) and section 4 (catalog table)
    private val catalog =
        listOf(
            Row(ErrorCode.INVALID_REQUEST, "invalid_request", ErrorKind.PERMANENT, 400, ErrorDetail.InvalidRequest::class),
            Row(ErrorCode.UNKNOWN_PARAMETER, "unknown_parameter", ErrorKind.PERMANENT, 400, ErrorDetail.UnknownParameter::class),
            Row(ErrorCode.INVALID_CURSOR, "invalid_cursor", ErrorKind.PERMANENT, 400, null),
            Row(ErrorCode.NOT_FOUND, "not_found", ErrorKind.PERMANENT, 404, ErrorDetail.NotFound::class),
            Row(ErrorCode.AMBIGUOUS_ID, "ambiguous_id", ErrorKind.PERMANENT, 409, ErrorDetail.AmbiguousId::class),
            Row(ErrorCode.VERSION_CONFLICT, "version_conflict", ErrorKind.PERMANENT, 409, ErrorDetail.VersionConflict::class),
            Row(ErrorCode.DUPLICATE, "duplicate", ErrorKind.PERMANENT, 409, ErrorDetail.Duplicate::class),
            Row(ErrorCode.IDEMPOTENCY_MISMATCH, "idempotency_mismatch", ErrorKind.PERMANENT, 409, ErrorDetail.IdempotencyMismatch::class),
            Row(ErrorCode.INVALID_TRANSITION, "invalid_transition", ErrorKind.PERMANENT, 409, ErrorDetail.InvalidTransition::class),
            Row(ErrorCode.GATE_BLOCKED, "gate_blocked", ErrorKind.PERMANENT, 409, ErrorDetail.GateBlocked::class),
            Row(ErrorCode.DEPENDENCY_UNMET, "dependency_unmet", ErrorKind.PERMANENT, 409, ErrorDetail.DependencyUnmet::class),
            Row(ErrorCode.CYCLE_DETECTED, "cycle_detected", ErrorKind.PERMANENT, 409, ErrorDetail.CycleDetected::class),
            Row(ErrorCode.CLAIM_HELD, "claim_held", ErrorKind.TRANSIENT, 409, ErrorDetail.ClaimHeld::class),
            Row(ErrorCode.NOT_CLAIM_HOLDER, "not_claim_holder", ErrorKind.PERMANENT, 403, ErrorDetail.NotClaimHolder::class),
            Row(ErrorCode.SEAT_FORBIDDEN, "seat_forbidden", ErrorKind.PERMANENT, 403, ErrorDetail.SeatForbidden::class),
            Row(ErrorCode.NOTE_OWNED_BY_OTHER, "note_owned_by_other", ErrorKind.PERMANENT, 403, ErrorDetail.NoteOwnedByOther::class),
            Row(ErrorCode.NOTE_TOO_LONG, "note_too_long", ErrorKind.PERMANENT, 413, ErrorDetail.NoteTooLong::class),
            Row(ErrorCode.RESOURCE_UNAVAILABLE, "resource_unavailable", ErrorKind.TRANSIENT, 409, ErrorDetail.ResourceUnavailable::class),
            Row(ErrorCode.SCHEMA_VIOLATION, "schema_violation", ErrorKind.PERMANENT, 422, ErrorDetail.SchemaViolation::class),
            Row(
                ErrorCode.SCHEMA_PINNED_CONFLICT,
                "schema_pinned_conflict",
                ErrorKind.PERMANENT,
                409,
                ErrorDetail.SchemaPinnedConflict::class
            ),
            Row(ErrorCode.CONFIG_INVALID, "config_invalid", ErrorKind.PERMANENT, 422, ErrorDetail.ConfigInvalid::class),
            Row(ErrorCode.PAYLOAD_TOO_LARGE, "payload_too_large", ErrorKind.PERMANENT, 413, ErrorDetail.PayloadTooLarge::class),
            Row(ErrorCode.UNAUTHENTICATED, "unauthenticated", ErrorKind.PERMANENT, 401, null),
            Row(ErrorCode.FORBIDDEN, "forbidden", ErrorKind.PERMANENT, 403, ErrorDetail.Forbidden::class),
            Row(ErrorCode.PARTIAL_FAILURE, "partial_failure", ErrorKind.PERMANENT, 207, null),
            Row(ErrorCode.UNAVAILABLE, "unavailable", ErrorKind.SHEDDING, 503, ErrorDetail.Unavailable::class),
            Row(ErrorCode.INTERNAL, "internal", ErrorKind.TRANSIENT, 500, null),
        )

    // Slot sets per code: dispatch declarations (task-scope D6)
    private val slots: Map<ErrorCode, Set<String>> =
        mapOf(
            ErrorCode.NOT_FOUND to setOf("kind", "id"),
            ErrorCode.AMBIGUOUS_ID to setOf("prefix"),
            ErrorCode.VERSION_CONFLICT to setOf("kind", "id", "actual"),
            ErrorCode.DUPLICATE to setOf("kind", "existingId"),
            ErrorCode.IDEMPOTENCY_MISMATCH to setOf("idempotencyKey"),
            ErrorCode.INVALID_TRANSITION to setOf("itemId", "trigger", "fromRole"),
            ErrorCode.GATE_BLOCKED to setOf("itemId"),
            ErrorCode.DEPENDENCY_UNMET to setOf("itemId"),
            ErrorCode.CLAIM_HELD to setOf("itemId"),
            ErrorCode.NOT_CLAIM_HOLDER to setOf("itemId"),
            ErrorCode.SEAT_FORBIDDEN to setOf("action", "itemId"),
            ErrorCode.NOTE_OWNED_BY_OTHER to setOf("key", "itemId"),
            ErrorCode.NOTE_TOO_LONG to setOf("key", "max"),
            ErrorCode.RESOURCE_UNAVAILABLE to setOf("itemId"),
            ErrorCode.SCHEMA_PINNED_CONFLICT to setOf("itemId", "pinnedVersion", "currentVersion"),
            ErrorCode.PAYLOAD_TOO_LARGE to setOf("max"),
            ErrorCode.FORBIDDEN to setOf("required", "scope"),
        )

    private fun row(code: ErrorCode): Row = catalog.single { it.code == code }

    private fun argsFor(code: ErrorCode): Map<String, String> = ErrorFixtures.fixArgs(code)

    // S1
    @Test
    fun `S1 catalog table lists exactly 27 codes and every ErrorCode appears once`() {
        assertEquals(27, catalog.size)
        assertEquals(27, ErrorCode.entries.size)
        assertEquals(ErrorCode.entries.toSet(), catalog.map { it.code }.toSet())
    }

    @Test
    fun `S1 wire strings equal exactly the envelope catalog and are unique`() {
        val actual = ErrorCode.entries.map { it.wire }
        assertEquals(catalog.map { it.wire }.toSet(), actual.toSet())
        assertEquals(actual.size, actual.toSet().size, "wire strings must be unique")
        for (r in catalog) assertEquals(r.wire, r.code.wire, "wire of ${r.code}")
    }

    @Test
    fun `S1 every wire is lower snake_case`() {
        val pattern = Regex("^[a-z]+(_[a-z]+)*$")
        for (code in ErrorCode.entries) {
            assertTrue(pattern.matches(code.wire), "wire '${code.wire}' of $code is not snake_case")
        }
    }

    // S2
    @Test
    fun `S2 kind of every code matches the envelope catalog`() {
        for (r in catalog) assertEquals(r.kind, r.code.kind, "kind of ${r.code}")
    }

    @Test
    fun `S2 only claim_held resource_unavailable and internal are transient and only unavailable is shedding`() {
        assertEquals(
            setOf(ErrorCode.CLAIM_HELD, ErrorCode.RESOURCE_UNAVAILABLE, ErrorCode.INTERNAL),
            ErrorCode.entries.filter { it.kind == ErrorKind.TRANSIENT }.toSet(),
        )
        assertEquals(setOf(ErrorCode.UNAVAILABLE), ErrorCode.entries.filter { it.kind == ErrorKind.SHEDDING }.toSet())
        assertEquals(23, ErrorCode.entries.count { it.kind == ErrorKind.PERMANENT })
    }

    // S3
    @Test
    fun `S3 http status of every code matches the envelope REST mapping`() {
        for (r in catalog) assertEquals(r.status, r.code.httpStatus, "status of ${r.code}")
    }

    // S4
    @Test
    fun `S4 detailClass is null exactly for invalid_cursor unauthenticated partial_failure and internal`() {
        val nullCodes = ErrorCode.entries.filter { it.detailClass == null }.toSet()
        assertEquals(
            setOf(ErrorCode.INVALID_CURSOR, ErrorCode.UNAUTHENTICATED, ErrorCode.PARTIAL_FAILURE, ErrorCode.INTERNAL),
            nullCodes,
        )
    }

    @Test
    fun `S4 detailClass of every code matches the envelope catalog`() {
        for (r in catalog) assertEquals(r.detail, r.code.detailClass, "detail of ${r.code}")
    }

    @Test
    fun `S4 the 23 detail classes are distinct and are exactly the ErrorDetail subtypes`() {
        val declared = ErrorCode.entries.mapNotNull { it.detailClass?.java }
        assertEquals(23, declared.size)
        assertEquals(23, declared.toSet().size, "each detail class used by one code only")
        val subtypes =
            ErrorDetail::class.java.declaredClasses
                .filter { ErrorDetail::class.java.isAssignableFrom(it) && it != ErrorDetail::class.java }
                .toSet()
        assertEquals(subtypes, declared.toSet(), "ErrorDetail subtypes must be exactly the detail classes of the codes")
    }

    // S5
    @Test
    fun `S5 every permanent code except partial_failure has a non-blank single line template`() {
        for (code in ErrorCode.entries) {
            if (code.kind != ErrorKind.PERMANENT || code == ErrorCode.PARTIAL_FAILURE) continue
            val template = assertNotNull(ErrorFixTemplates.template(code), "template of $code")
            assertTrue(template.isNotBlank(), "template of $code is blank")
            assertFalse(template.contains("\n"), "template of $code has a newline")
            assertFalse(template.contains("\r"), "template of $code has a carriage return")
        }
    }

    @Test
    fun `S5 partial_failure has no template and no slots`() {
        assertNull(ErrorFixTemplates.template(ErrorCode.PARTIAL_FAILURE))
        assertEquals(emptySet(), ErrorFixTemplates.slots(ErrorCode.PARTIAL_FAILURE))
    }

    @Test
    fun `S5 any template that exists is a single non-blank line`() {
        for (code in ErrorCode.entries) {
            val t = ErrorFixTemplates.template(code) ?: continue
            assertTrue(t.isNotBlank(), "template of $code")
            assertFalse(t.contains("\n"), "template of $code")
        }
    }

    @Test
    fun `S5 slot sets match the contract for codes with slots`() {
        for ((code, expected) in slots) assertEquals(expected, ErrorFixTemplates.slots(code), "slots of $code")
        for (code in ErrorCode.entries.filter { it !in slots }) {
            assertEquals(emptySet(), ErrorFixTemplates.slots(code), "slots of $code")
        }
    }

    // S6
    @Test
    fun `S6 render substitutes every slot and leaves no braces for every code with a template`() {
        for (code in ErrorCode.entries) {
            if (ErrorFixTemplates.template(code) == null) continue
            val args = argsFor(code)
            val out = ErrorFixTemplates.render(code, args)
            assertFalse(out.contains("{"), "render of $code left '{': $out")
            assertFalse(out.contains("}"), "render of $code left '}': $out")
            for ((slot, value) in args) assertTrue(out.contains(value), "render of $code lacks the value of slot $slot: $out")
        }
    }

    @Test
    fun `S6 render of a template without slots needs no args`() {
        val out = ErrorFixTemplates.render(ErrorCode.INVALID_REQUEST, emptyMap())
        assertTrue(out.isNotBlank())
        assertFalse(out.contains("{"))
    }

    @Test
    fun `S6 an argument value containing braces is substituted verbatim`() {
        val out = ErrorFixTemplates.render(ErrorCode.GATE_BLOCKED, mapOf("itemId" to "a-b-c"))
        assertTrue(out.contains("a-b-c"))
    }

    // S13
    @Test
    fun `S13 render rejects a missing argument`() {
        assertFailsWith<IllegalArgumentException> { ErrorFixTemplates.render(ErrorCode.NOT_FOUND, mapOf("kind" to "item")) }
        assertFailsWith<IllegalArgumentException> { ErrorFixTemplates.render(ErrorCode.NOT_FOUND, emptyMap()) }
    }

    @Test
    fun `S13 render rejects an extra argument`() {
        assertFailsWith<IllegalArgumentException> {
            ErrorFixTemplates.render(ErrorCode.NOT_FOUND, argsFor(ErrorCode.NOT_FOUND) + ("extra" to "x"))
        }
        assertFailsWith<IllegalArgumentException> {
            ErrorFixTemplates.render(ErrorCode.INVALID_REQUEST, mapOf("extra" to "x"))
        }
    }

    @Test
    fun `S13 render rejects a code that has no template`() {
        assertFailsWith<IllegalArgumentException> { ErrorFixTemplates.render(ErrorCode.PARTIAL_FAILURE, emptyMap()) }
    }

    // S16 (oracle: envelope section 2 line 73)
    @Test
    fun `S16 ErrorKind has exactly the three envelope values`() {
        assertEquals(setOf("TRANSIENT", "PERMANENT", "SHEDDING"), ErrorKind.entries.map { it.name }.toSet())
        assertEquals(3, ErrorKind.entries.size)
    }

    @Test
    fun `S16 toJsonString is the lowercase envelope wire value`() {
        assertEquals("transient", ErrorKind.TRANSIENT.toJsonString())
        assertEquals("permanent", ErrorKind.PERMANENT.toJsonString())
        assertEquals("shedding", ErrorKind.SHEDDING.toJsonString())
    }

    @Test
    fun `S16 fromString round-trips and is case-insensitive`() {
        for (k in ErrorKind.entries) {
            assertEquals(k, ErrorKind.fromString(k.toJsonString()))
            assertEquals(k, ErrorKind.fromString(k.name))
        }
        assertEquals(ErrorKind.PERMANENT, ErrorKind.fromString("Permanent"))
        assertFailsWith<IllegalArgumentException> { ErrorKind.fromString("retry") }
        assertFailsWith<IllegalArgumentException> { ErrorKind.fromString("") }
    }

    @Test
    fun `S16 ErrorKind lives in the domain error package`() {
        assertEquals("io.github.jpicklyk.mcptask.current.domain.error", ErrorKind::class.java.packageName)
    }

    @Test
    fun `row lookup sanity`() {
        assertEquals(409, row(ErrorCode.GATE_BLOCKED).status)
    }
}

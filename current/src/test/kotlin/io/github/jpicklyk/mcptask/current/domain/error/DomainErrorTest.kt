package io.github.jpicklyk.mcptask.current.domain.error

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test fixtures shared by the error-package tests. Every detail satisfies its documented init invariant. */
internal object ErrorFixtures {
    val id1: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val id2: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    /** A valid detail for [code], or null when the code defines none (envelope section 4). */
    fun detail(code: ErrorCode): ErrorDetail? =
        when (code) {
            ErrorCode.INVALID_REQUEST -> ErrorDetail.InvalidRequest(listOf(FieldViolation("title", "must not be blank")))
            ErrorCode.UNKNOWN_PARAMETER -> ErrorDetail.UnknownParameter(listOf("bogus"))
            ErrorCode.NOT_FOUND -> ErrorDetail.NotFound(EntityKind.ITEM, id1.toString())
            ErrorCode.AMBIGUOUS_ID -> ErrorDetail.AmbiguousId("ab12", listOf(id1, id2))
            ErrorCode.VERSION_CONFLICT -> ErrorDetail.VersionConflict(EntityKind.ITEM, id1.toString(), 3L, 4L)
            ErrorCode.DUPLICATE -> ErrorDetail.Duplicate(EntityKind.DEPENDENCY, null, id2.toString())
            ErrorCode.IDEMPOTENCY_MISMATCH -> ErrorDetail.IdempotencyMismatch("key-1")
            ErrorCode.INVALID_TRANSITION -> ErrorDetail.InvalidTransition(id1, "queue", "complete", listOf("start"))
            ErrorCode.GATE_BLOCKED -> ErrorDetail.GateBlocked(id1, "queue", listOf(MissingNote("task-scope", "queue", "planner")))
            ErrorCode.DEPENDENCY_UNMET -> ErrorDetail.DependencyUnmet(id1, listOf(Blocker(id2, "work")))
            ErrorCode.CYCLE_DETECTED -> ErrorDetail.CycleDetected(listOf(id1, id2, id1))
            ErrorCode.CLAIM_HELD -> ErrorDetail.ClaimHeld(id1, Instant.parse("2026-10-07T12:00:00Z"), 5000L)
            ErrorCode.NOT_CLAIM_HOLDER -> ErrorDetail.NotClaimHolder(id1)
            ErrorCode.SEAT_FORBIDDEN -> ErrorDetail.SeatForbidden(id1, "reviewer", "write", listOf("planner"))
            ErrorCode.NOTE_OWNED_BY_OTHER -> ErrorDetail.NoteOwnedByOther(id1, "task-scope", null)
            ErrorCode.NOTE_TOO_LONG -> ErrorDetail.NoteTooLong("task-scope", 100, 101)
            ErrorCode.RESOURCE_UNAVAILABLE -> ErrorDetail.ResourceUnavailable(id1, listOf(ResourceRef("gradle", "exclusive")), null)
            ErrorCode.SCHEMA_VIOLATION -> ErrorDetail.SchemaViolation(null, "unknown type", null)
            ErrorCode.SCHEMA_PINNED_CONFLICT -> ErrorDetail.SchemaPinnedConflict(id1, "v1", "v2")
            ErrorCode.CONFIG_INVALID -> ErrorDetail.ConfigInvalid(listOf(ConfigViolation("traits[0]", "unknown key")))
            ErrorCode.PAYLOAD_TOO_LARGE -> ErrorDetail.PayloadTooLarge(1000L, 1001L)
            ErrorCode.FORBIDDEN -> ErrorDetail.Forbidden(ForbiddenScope.ROOT, "root-1")
            ErrorCode.UNAVAILABLE -> ErrorDetail.Unavailable(250L)
            ErrorCode.INVALID_CURSOR, ErrorCode.UNAUTHENTICATED, ErrorCode.PARTIAL_FAILURE, ErrorCode.INTERNAL -> null
        }

    /** fix args filling exactly the slot set of [code]. */
    fun fixArgs(code: ErrorCode): Map<String, String> =
        ErrorFixTemplates.slots(code).associateWith { slot -> literalFixArgs[code]?.get(slot) ?: "val-$slot" }

    /** Literal values agreeing with [detail]; deliberately independent of the production slot mapping. */
    private val literalFixArgs: Map<ErrorCode, Map<String, String>> =
        mapOf(
            ErrorCode.NOT_FOUND to mapOf("kind" to "item", "id" to "11111111-1111-1111-1111-111111111111"),
            ErrorCode.AMBIGUOUS_ID to mapOf("prefix" to "ab12"),
            ErrorCode.VERSION_CONFLICT to mapOf("kind" to "item", "id" to "11111111-1111-1111-1111-111111111111", "actual" to "4"),
            ErrorCode.DUPLICATE to mapOf("kind" to "dependency", "existingId" to "22222222-2222-2222-2222-222222222222"),
            ErrorCode.IDEMPOTENCY_MISMATCH to mapOf("idempotencyKey" to "key-1"),
            ErrorCode.INVALID_TRANSITION to
                mapOf("itemId" to "11111111-1111-1111-1111-111111111111", "trigger" to "complete", "fromRole" to "queue"),
            ErrorCode.GATE_BLOCKED to mapOf("itemId" to "11111111-1111-1111-1111-111111111111"),
            ErrorCode.DEPENDENCY_UNMET to mapOf("itemId" to "11111111-1111-1111-1111-111111111111"),
            ErrorCode.CLAIM_HELD to mapOf("itemId" to "11111111-1111-1111-1111-111111111111"),
            ErrorCode.NOT_CLAIM_HOLDER to mapOf("itemId" to "11111111-1111-1111-1111-111111111111"),
            ErrorCode.SEAT_FORBIDDEN to mapOf("itemId" to "11111111-1111-1111-1111-111111111111", "action" to "write"),
            ErrorCode.NOTE_OWNED_BY_OTHER to mapOf("itemId" to "11111111-1111-1111-1111-111111111111", "key" to "task-scope"),
            ErrorCode.NOTE_TOO_LONG to mapOf("key" to "task-scope", "max" to "100"),
            ErrorCode.RESOURCE_UNAVAILABLE to mapOf("itemId" to "11111111-1111-1111-1111-111111111111"),
            ErrorCode.SCHEMA_PINNED_CONFLICT to
                mapOf("itemId" to "11111111-1111-1111-1111-111111111111", "pinnedVersion" to "v1", "currentVersion" to "v2"),
            ErrorCode.PAYLOAD_TOO_LARGE to mapOf("max" to "1000"),
            ErrorCode.FORBIDDEN to mapOf("scope" to "root", "required" to "root-1")
        )

    fun error(code: ErrorCode): DomainError = DomainError(code, "Something happened.", detail(code), fixArgs(code))
}

class DomainErrorTest {
    // S7 (oracle: envelope section 4 gate_blocked row, section 2 fix rule; task-scope D5)
    @Test
    fun `S7 gate_blocked error is permanent and renders fix containing the item id`() {
        val itemId = ErrorFixtures.id1
        val err =
            DomainError(
                ErrorCode.GATE_BLOCKED,
                "Item cannot leave queue: 1 required note is unfilled.",
                ErrorDetail.GateBlocked(itemId, "queue", listOf(MissingNote("task-scope", "queue"))),
                mapOf("itemId" to itemId.toString()),
            )
        assertEquals(ErrorKind.PERMANENT, err.kind)
        val fix = assertNotNull(err.fix)
        assertTrue(fix.contains(itemId.toString()), "fix must contain the itemId arg but was: $fix")
        assertFalse(fix.contains("{"))
        assertFalse(fix.contains("}"))
    }

    @Test
    fun `S7 kind of every error equals the kind of its code`() {
        for (code in ErrorCode.entries) {
            assertEquals(code.kind, ErrorFixtures.error(code).kind, "kind of $code")
        }
    }

    @Test
    fun `S7 every permanent error except partial_failure has a non-blank fix and partial_failure has none`() {
        for (code in ErrorCode.entries) {
            val err = ErrorFixtures.error(code)
            if (code == ErrorCode.PARTIAL_FAILURE) {
                assertNull(err.fix, "partial_failure carries no fix")
            } else if (code.kind == ErrorKind.PERMANENT) {
                val fix = assertNotNull(err.fix, "fix of $code")
                assertTrue(fix.isNotBlank(), "fix of $code")
            }
        }
    }

    @Test
    fun `S7 data class equality holds for identical construction`() {
        assertEquals(ErrorFixtures.error(ErrorCode.NOT_FOUND), ErrorFixtures.error(ErrorCode.NOT_FOUND))
    }

    // S12 (oracle: envelope section 2 detail rule "omitted when the code defines none"; task-scope D5)
    @Test
    fun `S12 every code constructs with a valid detail and matching fix args`() {
        for (code in ErrorCode.entries) {
            val err = ErrorFixtures.error(code)
            assertEquals(code, err.code)
            if (ErrorFixtures.detail(code) == null) assertNull(err.detail) else assertNotNull(err.detail)
        }
    }

    @Test
    fun `S12 NOT_FOUND with a GateBlocked detail is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DomainError(
                ErrorCode.NOT_FOUND,
                "Not found.",
                ErrorFixtures.detail(ErrorCode.GATE_BLOCKED),
                ErrorFixtures.fixArgs(ErrorCode.NOT_FOUND),
            )
        }
    }

    @Test
    fun `S12 NOT_FOUND with a null detail is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DomainError(ErrorCode.NOT_FOUND, "Not found.", null, ErrorFixtures.fixArgs(ErrorCode.NOT_FOUND))
        }
    }

    @Test
    fun `S12 INTERNAL with any detail is rejected and without detail is accepted`() {
        assertFailsWith<IllegalArgumentException> {
            DomainError(ErrorCode.INTERNAL, "Boom.", ErrorFixtures.detail(ErrorCode.NOT_FOUND))
        }
        assertFailsWith<IllegalArgumentException> {
            DomainError(ErrorCode.INTERNAL, "Boom.", ErrorFixtures.detail(ErrorCode.UNAVAILABLE))
        }
        assertNull(DomainError(ErrorCode.INTERNAL, "Boom.").detail)
    }

    @Test
    fun `S12 a detail of the wrong class or a null detail is rejected for every code that defines a detail`() {
        for (code in ErrorCode.entries) {
            if (ErrorFixtures.detail(code) == null) continue
            val wrong =
                ErrorCode.entries
                    .filter { it != code }
                    .mapNotNull { ErrorFixtures.detail(it) }
                    .first { !code.detailClass!!.isInstance(it) }
            assertFailsWith<IllegalArgumentException>("wrong detail for $code") {
                DomainError(code, "Msg.", wrong, ErrorFixtures.fixArgs(code))
            }
            assertFailsWith<IllegalArgumentException>("null detail for $code") {
                DomainError(code, "Msg.", null, ErrorFixtures.fixArgs(code))
            }
        }
    }

    @Test
    fun `S12 a detail on a code that defines none is rejected`() {
        val anyDetail = ErrorFixtures.detail(ErrorCode.NOT_FOUND)
        for (code in ErrorCode.entries) {
            if (ErrorFixtures.detail(code) != null) continue
            assertFailsWith<IllegalArgumentException>("detail on $code") {
                DomainError(code, "Msg.", anyDetail, ErrorFixtures.fixArgs(code))
            }
        }
    }

    @Test
    fun `S12 blank message is rejected for empty and whitespace messages`() {
        assertFailsWith<IllegalArgumentException> { DomainError(ErrorCode.INTERNAL, "") }
        assertFailsWith<IllegalArgumentException> { DomainError(ErrorCode.INTERNAL, "   ") }
    }

    @Test
    fun `S12 fixArgs missing a slot is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DomainError(ErrorCode.NOT_FOUND, "Not found.", ErrorFixtures.detail(ErrorCode.NOT_FOUND), mapOf("kind" to "item"))
        }
        assertFailsWith<IllegalArgumentException> {
            DomainError(ErrorCode.NOT_FOUND, "Not found.", ErrorFixtures.detail(ErrorCode.NOT_FOUND), emptyMap())
        }
    }

    @Test
    fun `S12 fixArgs with an extra key is rejected`() {
        val args = ErrorFixtures.fixArgs(ErrorCode.NOT_FOUND) + ("extra" to "x")
        assertFailsWith<IllegalArgumentException> {
            DomainError(ErrorCode.NOT_FOUND, "Not found.", ErrorFixtures.detail(ErrorCode.NOT_FOUND), args)
        }
        assertFailsWith<IllegalArgumentException> {
            DomainError(ErrorCode.INTERNAL, "Boom.", null, mapOf("extra" to "x"))
        }
    }

    // S14 detail init invariants (oracle: task-scope D4, declared require clauses)
    @Test
    fun `S14 AmbiguousId requires at least two candidates`() {
        assertFailsWith<IllegalArgumentException> { ErrorDetail.AmbiguousId("ab", listOf(ErrorFixtures.id1)) }
        assertFailsWith<IllegalArgumentException> { ErrorDetail.AmbiguousId("ab", emptyList()) }
        ErrorDetail.AmbiguousId("ab", listOf(ErrorFixtures.id1, ErrorFixtures.id2))
    }

    @Test
    fun `S14 NoteTooLong requires actual greater than max`() {
        assertFailsWith<IllegalArgumentException> { ErrorDetail.NoteTooLong("k", 100, 100) }
        assertFailsWith<IllegalArgumentException> { ErrorDetail.NoteTooLong("k", 100, 99) }
        ErrorDetail.NoteTooLong("k", 100, 101)
    }

    @Test
    fun `S14 PayloadTooLarge requires actual greater than max`() {
        assertFailsWith<IllegalArgumentException> { ErrorDetail.PayloadTooLarge(10L, 10L) }
        assertFailsWith<IllegalArgumentException> { ErrorDetail.PayloadTooLarge(10L, 9L) }
        ErrorDetail.PayloadTooLarge(10L, 11L)
    }

    @Test
    fun `S14 VersionConflict requires expected different from actual`() {
        assertFailsWith<IllegalArgumentException> { ErrorDetail.VersionConflict(EntityKind.ITEM, "x", 3L, 3L) }
        ErrorDetail.VersionConflict(EntityKind.ITEM, "x", 3L, 4L)
        ErrorDetail.VersionConflict(EntityKind.ITEM, "x", 4L, 3L)
    }

    @Test
    fun `S14 collection details reject an empty collection`() {
        val id = ErrorFixtures.id1
        assertFailsWith<IllegalArgumentException> { ErrorDetail.InvalidRequest(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ErrorDetail.UnknownParameter(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ErrorDetail.GateBlocked(id, "queue", emptyList()) }
        assertFailsWith<IllegalArgumentException> { ErrorDetail.DependencyUnmet(id, emptyList()) }
        assertFailsWith<IllegalArgumentException> { ErrorDetail.CycleDetected(emptyList()) }
        assertFailsWith<IllegalArgumentException> { ErrorDetail.ResourceUnavailable(id, emptyList(), null) }
        assertFailsWith<IllegalArgumentException> { ErrorDetail.ConfigInvalid(emptyList()) }
    }

    @Test
    fun `S14 absent optional values are legal while empty collections are not`() {
        ErrorDetail.ResourceUnavailable(ErrorFixtures.id1, listOf(ResourceRef("r", "advisory")), null)
        ErrorDetail.NoteOwnedByOther(ErrorFixtures.id1, "k", null)
        ErrorDetail.SchemaViolation(null, "why", null)
    }
}

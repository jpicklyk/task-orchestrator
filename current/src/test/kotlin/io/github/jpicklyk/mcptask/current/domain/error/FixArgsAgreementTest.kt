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

/**
 * Item 3ff20f1d, seat test-author: fix args must agree with the typed detail (task-scope D1) and the
 * PAYLOAD_TOO_LARGE fix template carries a unit (D3).
 *
 * Oracles (frozen in the queue-phase test-plan, written before any implementation existed):
 *  - D1 canonical form of a detail value as a fix arg: an enum is its lowercase name (the envelope section 4 wire
 *    forms item|note|dependency|document|run|root and root|tag|capability); a UUID, Long or Int is `toString()`;
 *    a String is as-is. The comparison is exact (no trim, no case-folding).
 *  - D1 a slot whose detail property is null leaves that one arg free.
 *  - D3 the PAYLOAD_TOO_LARGE template reads "Reduce the payload to at most {max} bytes."
 *
 * Every expected value below is a hand-written literal; none is computed by production code. The set of slots a
 * code has is read from the existing ErrorCatalogTest slot table (public evidence); each case below lists the
 * values of every non-null detail property by name and is narrowed to the code's slot set at use.
 */
class FixArgsAgreementTest {
    private val itemId = "abcdef01-2345-6789-abcd-ef0123456789"
    private val otherId = "fedcba98-7654-3210-fedc-ba9876543210"
    private val itemUuid = UUID.fromString(itemId)
    private val otherUuid = UUID.fromString(otherId)

    /** A valid detail plus the canonical arg value of each of its non-null properties, by property name. */
    private class Case(
        val detail: ErrorDetail,
        val values: Map<String, String>
    )

    private val cases: Map<ErrorCode, Case> =
        mapOf(
            ErrorCode.NOT_FOUND to
                Case(ErrorDetail.NotFound(EntityKind.ITEM, itemId), mapOf("kind" to "item", "id" to itemId)),
            ErrorCode.AMBIGUOUS_ID to
                Case(ErrorDetail.AmbiguousId("ab12", listOf(itemUuid, otherUuid)), mapOf("prefix" to "ab12")),
            ErrorCode.VERSION_CONFLICT to
                Case(
                    ErrorDetail.VersionConflict(EntityKind.NOTE, "note-7", 3L, 4L),
                    mapOf("kind" to "note", "id" to "note-7", "expected" to "3", "actual" to "4")
                ),
            ErrorCode.DUPLICATE to
                Case(
                    ErrorDetail.Duplicate(EntityKind.DEPENDENCY, "dep-1", otherId),
                    mapOf("kind" to "dependency", "id" to "dep-1", "existingId" to otherId)
                ),
            ErrorCode.IDEMPOTENCY_MISMATCH to
                Case(ErrorDetail.IdempotencyMismatch("key-1"), mapOf("idempotencyKey" to "key-1")),
            ErrorCode.INVALID_TRANSITION to
                Case(
                    ErrorDetail.InvalidTransition(itemUuid, "queue", "complete", listOf("start")),
                    mapOf("itemId" to itemId, "fromRole" to "queue", "trigger" to "complete")
                ),
            ErrorCode.GATE_BLOCKED to
                Case(
                    ErrorDetail.GateBlocked(itemUuid, "queue", listOf(MissingNote("task-scope", "queue", "planner"))),
                    mapOf("itemId" to itemId, "role" to "queue")
                ),
            ErrorCode.DEPENDENCY_UNMET to
                Case(ErrorDetail.DependencyUnmet(itemUuid, listOf(Blocker(otherUuid, "work"))), mapOf("itemId" to itemId)),
            ErrorCode.CLAIM_HELD to
                Case(
                    ErrorDetail.ClaimHeld(itemUuid, Instant.parse("2026-10-07T12:00:00Z"), 5000L),
                    mapOf("itemId" to itemId)
                ),
            ErrorCode.NOT_CLAIM_HOLDER to
                Case(ErrorDetail.NotClaimHolder(itemUuid), mapOf("itemId" to itemId)),
            ErrorCode.SEAT_FORBIDDEN to
                Case(
                    ErrorDetail.SeatForbidden(itemUuid, "reviewer", "write", listOf("planner")),
                    mapOf("itemId" to itemId, "seat" to "reviewer", "action" to "write")
                ),
            ErrorCode.NOTE_OWNED_BY_OTHER to
                Case(
                    ErrorDetail.NoteOwnedByOther(itemUuid, "task-scope", "planner"),
                    mapOf("itemId" to itemId, "key" to "task-scope")
                ),
            ErrorCode.NOTE_TOO_LONG to
                Case(
                    ErrorDetail.NoteTooLong("task-scope", 100, 101),
                    mapOf("key" to "task-scope", "max" to "100", "actual" to "101")
                ),
            ErrorCode.RESOURCE_UNAVAILABLE to
                Case(
                    ErrorDetail.ResourceUnavailable(itemUuid, listOf(ResourceRef("gradle", "exclusive")), 250L),
                    mapOf("itemId" to itemId)
                ),
            ErrorCode.SCHEMA_PINNED_CONFLICT to
                Case(
                    ErrorDetail.SchemaPinnedConflict(itemUuid, "v1", "v2"),
                    mapOf("itemId" to itemId, "pinnedVersion" to "v1", "currentVersion" to "v2")
                ),
            ErrorCode.PAYLOAD_TOO_LARGE to
                Case(ErrorDetail.PayloadTooLarge(1000L, 1001L), mapOf("max" to "1000", "actual" to "1001")),
            ErrorCode.FORBIDDEN to
                Case(ErrorDetail.Forbidden(ForbiddenScope.ROOT, "root-1"), mapOf("scope" to "root", "required" to "root-1"))
        )

    private fun slotted(): List<ErrorCode> = ErrorCode.entries.filter { ErrorFixTemplates.slots(it).isNotEmpty() }

    /** Agreeing args for [code]: the case values narrowed to the code's slot set, then [overrides] applied. */
    private fun args(
        code: ErrorCode,
        overrides: Map<String, String> = emptyMap()
    ): Map<String, String> = cases.getValue(code).values.filterKeys { it in ErrorFixTemplates.slots(code) } + overrides

    private fun build(
        code: ErrorCode,
        detail: ErrorDetail?,
        args: Map<String, String>
    ) = DomainError(code, "Something happened.", detail, args)

    private fun buildCase(
        code: ErrorCode,
        overrides: Map<String, String> = emptyMap()
    ) = build(code, cases.getValue(code).detail, args(code, overrides))

    private fun assertRejectedNaming(
        slot: String,
        what: String,
        block: () -> Unit
    ) {
        val ex = assertFailsWith<IllegalArgumentException>(what) { block() }
        assertTrue(ex.message.orEmpty().contains(slot), "$what: message must name slot '$slot' but was: ${ex.message}")
    }

    // S1 happy: every slotted code constructs with agreeing args and the fix contains each arg value
    @Test
    fun `Q34-S1 the case table covers exactly the codes that have slots`() {
        assertEquals(17, cases.size)
        assertEquals(cases.keys, slotted().toSet(), "slotted codes vs the case table")
    }

    @Test
    fun `Q34-S1 every slotted code constructs with agreeing args and its fix contains every arg value`() {
        for (code in slotted()) {
            val err = buildCase(code)
            val fix = assertNotNull(err.fix, "fix of $code")
            for ((slot, value) in err.fixArgs) {
                assertTrue(fix.contains(value), "fix of $code lacks the value of slot $slot ($value): $fix")
            }
            assertFalse(fix.contains("{"), "fix of $code left a brace: $fix")
        }
    }

    // S2 fail: every slot whose detail value is non-null must agree
    @Test
    fun `Q34-S2 a slot arg that differs from its non-null detail value is rejected naming the slot`() {
        for (code in slotted()) {
            val case = cases.getValue(code)
            val checkable = ErrorFixTemplates.slots(code).filter { it in case.values }
            assertTrue(checkable.isNotEmpty(), "$code has at least one slot with a non-null detail value")
            for (slot in checkable) {
                val good = case.values.getValue(slot)
                for (bad in listOf(good + "!", "")) {
                    assertRejectedNaming(slot, "$code slot $slot arg '$bad' (detail value '$good')") {
                        buildCase(code, mapOf(slot to bad))
                    }
                }
            }
        }
    }

    // S3 fail: casing is exact
    @Test
    fun `Q34-S3 an enum slot must be the lowercase wire form for every EntityKind`() {
        for (kind in EntityKind.entries) {
            val wire = kind.name.lowercase()
            val ok = DomainError(ErrorCode.NOT_FOUND, "Not found.", ErrorDetail.NotFound(kind, "x1"), mapOf("kind" to wire, "id" to "x1"))
            assertEquals(kind, (ok.detail as ErrorDetail.NotFound).kind)
            for (bad in listOf(kind.name, wire.replaceFirstChar { it.uppercase() })) {
                assertRejectedNaming("kind", "NotFound($kind) kind arg '$bad'") {
                    DomainError(ErrorCode.NOT_FOUND, "Not found.", ErrorDetail.NotFound(kind, "x1"), mapOf("kind" to bad, "id" to "x1"))
                }
            }
        }
    }

    @Test
    fun `Q34-S3 the Forbidden scope arg must be the lowercase wire form for every ForbiddenScope`() {
        for (scope in ForbiddenScope.entries) {
            val wire = scope.name.lowercase()
            DomainError(ErrorCode.FORBIDDEN, "No.", ErrorDetail.Forbidden(scope, "r"), mapOf("scope" to wire, "required" to "r"))
            for (bad in listOf(scope.name, wire.replaceFirstChar { it.uppercase() })) {
                assertRejectedNaming("scope", "Forbidden($scope) scope arg '$bad'") {
                    DomainError(ErrorCode.FORBIDDEN, "No.", ErrorDetail.Forbidden(scope, "r"), mapOf("scope" to bad, "required" to "r"))
                }
            }
        }
    }

    @Test
    fun `Q34-S3 a uuid slot with uppercased hex letters is rejected and the lowercase form is accepted`() {
        // itemId has hex letters, so upper-casing it changes the string.
        assertTrue(itemId != itemId.uppercase(), "fixture: the uuid contains letters")
        build(ErrorCode.NOT_CLAIM_HOLDER, ErrorDetail.NotClaimHolder(itemUuid), mapOf("itemId" to itemId))
        assertRejectedNaming("itemId", "uppercase uuid arg") {
            build(ErrorCode.NOT_CLAIM_HOLDER, ErrorDetail.NotClaimHolder(itemUuid), mapOf("itemId" to itemId.uppercase()))
        }
    }

    @Test
    fun `Q34-S3 a String slot is compared as-is with no trim and no case folding`() {
        val detail = ErrorDetail.IdempotencyMismatch("key-1")
        build(ErrorCode.IDEMPOTENCY_MISMATCH, detail, mapOf("idempotencyKey" to "key-1"))
        for (bad in listOf("KEY-1", "Key-1", "key-1 ", " key-1", "key-1\n")) {
            assertRejectedNaming("idempotencyKey", "idempotencyKey arg '${bad.replace("\n", "\\n")}'") {
                build(ErrorCode.IDEMPOTENCY_MISMATCH, detail, mapOf("idempotencyKey" to bad))
            }
        }
    }

    // S4 fail: no unit suffix, padding or alternate numeric spelling on number slots
    @Test
    fun `Q34-S4 a Long slot with a unit suffix padding or alternate spelling is rejected`() {
        for (bad in listOf("1000 bytes", " 1000", "1000 ", "1,000", "1000.0", "01000", "+1000", "1e3")) {
            assertRejectedNaming("max", "PayloadTooLarge max arg '$bad'") {
                build(ErrorCode.PAYLOAD_TOO_LARGE, ErrorDetail.PayloadTooLarge(1000L, 1001L), mapOf("max" to bad))
            }
        }
        build(ErrorCode.PAYLOAD_TOO_LARGE, ErrorDetail.PayloadTooLarge(1000L, 1001L), mapOf("max" to "1000"))
    }

    @Test
    fun `Q34-S4 an Int slot with a unit word is rejected and the plain number is accepted`() {
        val detail = ErrorDetail.NoteTooLong("k", 100, 101)
        build(ErrorCode.NOTE_TOO_LONG, detail, mapOf("key" to "k", "max" to "100"))
        for (bad in listOf("100 characters", "100 chars", " 100", "100 ", "100.0")) {
            assertRejectedNaming("max", "NoteTooLong max arg '$bad'") {
                build(ErrorCode.NOTE_TOO_LONG, detail, mapOf("key" to "k", "max" to bad))
            }
        }
    }

    @Test
    fun `Q34-S4 a Long slot is its plain decimal string`() {
        val detail = ErrorDetail.VersionConflict(EntityKind.ITEM, "v", 3L, 4L)
        val base = mapOf("kind" to "item", "id" to "v", "actual" to "4")
        build(ErrorCode.VERSION_CONFLICT, detail, base)
        for (bad in listOf("4.0", "04", "four", "3")) {
            assertRejectedNaming("actual", "VersionConflict actual arg '$bad'") {
                build(ErrorCode.VERSION_CONFLICT, detail, base + ("actual" to bad))
            }
        }
    }

    // S5 edge: a null detail property frees only its own slot
    @Test
    fun `Q34-S5 a null NotFound id leaves the id arg free but the kind arg stays checked`() {
        val detail = ErrorDetail.NotFound(EntityKind.NOTE, null)
        for (free in listOf("named in the request", "x", itemId, "null")) {
            val err = build(ErrorCode.NOT_FOUND, detail, mapOf("kind" to "note", "id" to free))
            assertTrue(assertNotNull(err.fix).contains(free), "free id '$free' is rendered")
        }
        assertRejectedNaming("kind", "null id does not free the kind slot") {
            build(ErrorCode.NOT_FOUND, detail, mapOf("kind" to "item", "id" to "named in the request"))
        }
        assertRejectedNaming("kind", "null id does not free the kind slot (wire casing)") {
            build(ErrorCode.NOT_FOUND, detail, mapOf("kind" to "NOTE", "id" to "named in the request"))
        }
    }

    @Test
    fun `Q34-S5 a null Duplicate existingId leaves its arg free but the kind arg stays checked`() {
        val detail = ErrorDetail.Duplicate(EntityKind.DEPENDENCY, null, null)
        val free =
            mapOf("kind" to "dependency", "existingId" to "x", "id" to "free")
                .filterKeys { it in ErrorFixTemplates.slots(ErrorCode.DUPLICATE) }
        build(ErrorCode.DUPLICATE, detail, free)
        assertRejectedNaming("kind", "null existingId does not free the kind slot") {
            build(ErrorCode.DUPLICATE, detail, free + ("kind" to "document"))
        }
    }

    @Test
    fun `Q34-S5 a non-null Duplicate existingId is still checked when only the id is null`() {
        val detail = ErrorDetail.Duplicate(EntityKind.DEPENDENCY, null, otherId)
        val ok =
            mapOf("kind" to "dependency", "existingId" to otherId, "id" to "free")
                .filterKeys { it in ErrorFixTemplates.slots(ErrorCode.DUPLICATE) }
        build(ErrorCode.DUPLICATE, detail, ok)
        assertRejectedNaming("existingId", "non-null existingId must agree") {
            build(ErrorCode.DUPLICATE, detail, ok + ("existingId" to "x"))
        }
    }

    // S6 edge: slotless codes, and absent is not the same as null-valued
    @Test
    fun `Q34-S6 every code without slots constructs with an empty arg map`() {
        val slotless = ErrorCode.entries.filter { ErrorFixTemplates.slots(it).isEmpty() }
        assertTrue(ErrorCode.INVALID_REQUEST in slotless && ErrorCode.INTERNAL in slotless, "fixture: slotless set was $slotless")
        for (code in slotless) {
            val err = DomainError(code, "Something happened.", ErrorFixtures.detail(code), emptyMap())
            assertEquals(code, err.code)
        }
    }

    @Test
    fun `Q34-S6 a slotless code still rejects any extra arg`() {
        assertFailsWith<IllegalArgumentException> {
            DomainError(ErrorCode.INTERNAL, "Boom.", null, mapOf("extra" to "x"))
        }
        assertFailsWith<IllegalArgumentException> {
            DomainError(ErrorCode.INVALID_REQUEST, "Bad.", ErrorFixtures.detail(ErrorCode.INVALID_REQUEST), mapOf("extra" to "x"))
        }
    }

    @Test
    fun `Q34-S6 an omitted slot key is rejected even when its detail value is null`() {
        assertFailsWith<IllegalArgumentException> {
            build(ErrorCode.NOT_FOUND, ErrorDetail.NotFound(EntityKind.ITEM, null), mapOf("kind" to "item"))
        }
        assertFailsWith<IllegalArgumentException> {
            build(ErrorCode.NOT_FOUND, ErrorDetail.NotFound(EntityKind.ITEM, null), emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            build(ErrorCode.NOT_FOUND, ErrorDetail.NotFound(EntityKind.ITEM, itemId), mapOf("kind" to "item"))
        }
    }

    // S7 / D3: the PAYLOAD_TOO_LARGE template carries a unit
    @Test
    fun `Q34-S7 the PAYLOAD_TOO_LARGE fix renders the max with the bytes unit exactly once`() {
        assertEquals(setOf("max"), ErrorFixTemplates.slots(ErrorCode.PAYLOAD_TOO_LARGE))
        val out = ErrorFixTemplates.render(ErrorCode.PAYLOAD_TOO_LARGE, mapOf("max" to "1000"))
        assertEquals("Reduce the payload to at most 1000 bytes.", out)
        assertTrue(out.contains("1000 bytes"))
        assertFalse(out.contains("bytes bytes"))
    }

    @Test
    fun `Q34-S7 a DomainError for PAYLOAD_TOO_LARGE exposes the same unit-bearing fix`() {
        val err = DomainError(ErrorCode.PAYLOAD_TOO_LARGE, "Too big.", ErrorDetail.PayloadTooLarge(65536L, 65537L), mapOf("max" to "65536"))
        assertEquals("Reduce the payload to at most 65536 bytes.", err.fix)
        assertNull(DomainError(ErrorCode.PARTIAL_FAILURE, "Some failed.").fix)
    }
}

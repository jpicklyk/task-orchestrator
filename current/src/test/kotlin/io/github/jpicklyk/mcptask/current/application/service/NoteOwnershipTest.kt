package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independently authored unit tests (test-author seat, item 190f1bdd) of [NoteOwnership.decide], the pure ownership decision of
 * R21, against the frozen queue-phase `task-scope` ("What to build" item 2, decisions D5-D8, AC2-AC4) and `test-plan`
 * (S3-S10, probes). No implementation source, diff or commit was opened; the signature is the one the dispatch supplied.
 *
 * Oracle (task-scope item 2, order of evaluation):
 *  (a) `takeover && !takeoverAllowed` -> `forbidden{scope: capability, required: "admin"}`
 *  (b) `takeover && caller.assignmentId != null` -> `invalid_request` at `/takeover`
 *  (c) rule 1: `entry.shared == true` -> Allowed
 *  (d) rule 2: a refusing `SeatRule` is returned as is; `takeover` is never consulted
 *  (e) rule 3 (decision.md Ruling 3, steps 5-9): no existing note -> Allowed; a note with a NULL `ownerId` -> `Adopted(its
 *      stored actor id)` for a caller (Allowed for a null caller), whatever `takeover` says; `(ownerId, ownerSeat) ==
 *      (caller principal, caller seat)`, exact and case-sensitive -> Allowed; otherwise `takeover` -> `Transferred(from, to)`;
 *      otherwise `note_owned_by_other` without `ownerSeat` when null and with the owner id in no message, detail or fix
 *      argument. A null caller against an owned note is refused (fail closed). The owner is the stored `ownerId`, never the
 *      actor claim.
 *  D6: a takeover where rule 3 would already allow (own note, shared key, no note) is a no-op (Allowed, not Transferred).
 *
 * Every refusal here is paired with a control that differs only in the single input the rule turns on, so each negative
 * assertion can fail without the rule (the control shows the same call shape is otherwise accepted).
 */
class NoteOwnershipTest {
    private val itemId: UUID = UUID.fromString("c1111111-1111-4111-8111-111111111111")
    private val key = "spec"
    private val ownerId = "owner-zq7x"
    private val otherId = "caller-w4k9"
    private val adminId = "admin-p2m6"

    private fun claim(id: String) = ActorClaim(id = id, kind = ActorKind.SUBAGENT)

    /** A 4.0-owned note: the stored owner and the last writer are the same principal (decision Dprime fixture rule). */
    private fun noteOwnedBy(owner: String?) =
        Note(itemId = itemId, key = key, role = "work", body = "body", actorClaim = owner?.let { claim(it) }, ownerId = owner)

    /** A pre-4.0 note (decision Ruling 7 fixture `legacy(actor)`): a 3.x actor claim, noop verification, and no owner. */
    private fun legacy(actor: String?) =
        Note(
            itemId = itemId,
            key = key,
            role = "work",
            body = "body",
            actorClaim = actor?.let { claim(it) },
            verification = VerificationResult(VerificationStatus.UNCHECKED, "noop"),
            ownerId = null
        )

    private fun Outcome<NoteOwnership.Decision>.assertAdopted(
        legacyActor: String?,
        label: String = ""
    ) = assertEquals(NoteOwnership.Decision.Adopted(legacyActor), decision(), label)

    private fun entry(shared: Boolean) = NoteSchemaEntry(key = key, role = Role.WORK, shared = shared)

    private fun decide(
        existing: Note?,
        caller: ClaimHolder?,
        entry: NoteSchemaEntry? = null,
        callerSeat: String? = null,
        takeover: Boolean = false,
        takeoverAllowed: Boolean = false,
        seatRule: SeatRule = SeatRule.NONE,
        ownerSeat: String? = null
    ): Outcome<NoteOwnership.Decision> =
        NoteOwnership.decide(
            itemId = itemId,
            key = key,
            existing = existing,
            entry = entry,
            caller = caller,
            callerSeat = callerSeat,
            takeover = takeover,
            takeoverAllowed = takeoverAllowed,
            seatRule = seatRule,
            ownerSeat = ownerSeat
        )

    private fun Outcome<NoteOwnership.Decision>.decision(): NoteOwnership.Decision {
        if (this is Outcome.Ok<NoteOwnership.Decision>) return value
        fail("expected Ok but was $this")
    }

    private fun Outcome<NoteOwnership.Decision>.error(): DomainError = (this as? Outcome.Err)?.error ?: fail("expected Err but was $this")

    private fun Outcome<NoteOwnership.Decision>.assertAllowed(label: String = "") =
        assertEquals(NoteOwnership.Decision.Allowed, decision(), label)

    private fun Outcome<NoteOwnership.Decision>.assertOwnedByOther(
        ownerSeat: String? = null,
        label: String = ""
    ): DomainError {
        val error = error()
        assertEquals(ErrorCode.NOTE_OWNED_BY_OTHER, error.code, "$label: ${error.message}")
        assertEquals(ErrorDetail.NoteOwnedByOther(itemId, key, ownerSeat), error.detail, label)
        return error
    }

    private fun refusingSeatRule(ownerSeat: String) =
        SeatRule { item, k, _, _ ->
            SeatVerdict.Refuse(
                DomainError(
                    ErrorCode.NOTE_OWNED_BY_OTHER,
                    "A seat owns this note.",
                    ErrorDetail.NoteOwnedByOther(item, k, ownerSeat),
                    mapOf("itemId" to item.toString(), "key" to k)
                )
            )
        }

    // ------------------------------------------------------------------ rule 3: first writer

    @Test
    fun `S2 no existing note is allowed for any caller including none, and the owner is allowed on their own note`() {
        decide(existing = null, caller = ClaimHolder(otherId)).assertAllowed("first write by anyone")
        decide(existing = null, caller = null).assertAllowed("a null caller may create")
        decide(existing = noteOwnedBy(ownerId), caller = ClaimHolder(ownerId)).assertAllowed("the owner rewrites")
    }

    @Test
    fun `S3 a different principal on an owned non-shared note is note_owned_by_other with no ownerSeat and no owner id anywhere`() {
        val error = decide(existing = noteOwnedBy(ownerId), caller = ClaimHolder(otherId)).assertOwnedByOther(label = "non-owner")

        assertTrue(ownerId !in error.message, "no owner id in the message: ${error.message}")
        assertTrue(ownerId !in error.detail.toString(), "no owner id in the detail: ${error.detail}")
        assertTrue(error.fix?.contains(ownerId) != true, "no owner id in the fix: ${error.fix}")
        assertTrue(error.fixArgs.values.none { ownerId in it }, "no owner id in the fix arguments: ${error.fixArgs}")
        // control: the same call by the owner is allowed, so the refusal above is the ownership compare and nothing else
        decide(existing = noteOwnedBy(ownerId), caller = ClaimHolder(ownerId)).assertAllowed("control: owner")
    }

    @Test
    fun `S3 probe the owner compare is exact - a different case or a padded id is a different principal`() {
        val existing = noteOwnedBy("Agent-One")
        val padded = "Agent-One" + Char(10)
        for (spelling in listOf("agent-one", "AGENT-ONE", "Agent-One ", " Agent-One", padded)) {
            decide(existing, ClaimHolder(spelling)).assertOwnedByOther(label = "spelling of length ${spelling.length}: [$spelling]")
        }
        decide(existing, ClaimHolder("Agent-One")).assertAllowed("control: the exact spelling")
    }

    @Test
    fun `S3 a null caller against an owned note is refused - fail closed - and allowed against a missing or unowned note`() {
        decide(noteOwnedBy(ownerId), caller = null).assertOwnedByOther(label = "null caller")
        decide(null, caller = null).assertAllowed("control: nothing to own")
        decide(noteOwnedBy(null), caller = null).assertAllowed("control: a NULL-actor note is unowned")
    }

    @Test
    fun `S5 an unowned note is adopted by any caller, and the same note with an owner refuses`() {
        decide(noteOwnedBy(null), ClaimHolder(otherId)).assertAdopted(null, "unowned note, any caller")
        decide(noteOwnedBy(null), ClaimHolder(ownerId)).assertAdopted(null, "unowned note, another caller")
        // the negative assertion's fixture condition: the only difference is the stored owner id
        decide(noteOwnedBy(ownerId), ClaimHolder(otherId)).assertOwnedByOther(label = "same note with an owner")
    }

    @Test
    fun `S5 probe a legacy note with a 3x actor is adopted by a 4 0 caller whatever its actor id - the actor is never the owner`() {
        // control: the actor id alone does not make a note owned - the same note with ownerId set refuses the other caller
        decide(legacy(ownerId), ClaimHolder(otherId)).assertAdopted(ownerId, "legacy actor, different caller")
        decide(legacy(ownerId), ClaimHolder(ownerId)).assertAdopted(ownerId, "legacy actor, same-named caller is still an adoption")
        decide(noteOwnedBy(ownerId), ClaimHolder(otherId)).assertOwnedByOther(label = "control: owner set")
    }

    @Test
    fun `S3 a seat-aware compare - the owner id alone is not enough when the owner seat differs, and ownerSeat is reported`() {
        val existing = noteOwnedBy(ownerId)

        decide(existing, ClaimHolder(ownerId), callerSeat = "implementer", ownerSeat = "implementer").assertAllowed("same id, same seat")
        decide(existing, ClaimHolder(ownerId), callerSeat = "reviewer", ownerSeat = "implementer")
            .assertOwnedByOther(ownerSeat = "implementer", label = "same id, other seat")
        decide(existing, ClaimHolder(ownerId), callerSeat = null, ownerSeat = "implementer")
            .assertOwnedByOther(ownerSeat = "implementer", label = "same id, caller without a seat")
        decide(existing, ClaimHolder(ownerId), callerSeat = "implementer", ownerSeat = null)
            .assertOwnedByOther(label = "same id, owner without a seat")
        decide(existing, ClaimHolder(ownerId), callerSeat = "Implementer", ownerSeat = "implementer")
            .assertOwnedByOther(ownerSeat = "implementer", label = "seat compare is case-sensitive")
    }

    @Test
    fun `S3 a caller with a seat assignment and no takeover is judged by the same owner compare`() {
        val assigned = ClaimHolder(ownerId, assignmentId = UUID.randomUUID())

        decide(noteOwnedBy(ownerId), assigned).assertAllowed("the owner under an assignment, no takeover")
        decide(noteOwnedBy(ownerId), ClaimHolder(otherId, UUID.randomUUID())).assertOwnedByOther(label = "a non-owner under an assignment")
    }

    // ------------------------------------------------------------------ R22: the (principal, seat) pair under a real seat context

    @Test
    fun `R22 a seat holder is judged by the pair - same seat is allowed, another seat of the principal is refused with ownerSeat`() {
        val assignment = ClaimHolder(ownerId, assignmentId = UUID.fromString("5ea75ea7-0000-4000-8000-000000000001"))
        val existing = noteOwnedBy(ownerId)

        decide(existing, assignment, callerSeat = "implementer", ownerSeat = "implementer").assertAllowed("same pair")
        decide(existing, assignment, callerSeat = "checker", ownerSeat = "implementer")
            .assertOwnedByOther(ownerSeat = "implementer", label = "same principal, another seat")
        // control: the other principal under the owner's seat name is refused too, so the principal is half of the pair
        decide(existing, ClaimHolder(otherId, assignment.assignmentId), callerSeat = "implementer", ownerSeat = "implementer")
            .assertOwnedByOther(ownerSeat = "implementer", label = "another principal, same seat name")
    }

    @Test
    fun `R22 a seated caller adopts an unowned note and creates a missing one, and is refused on an owned one`() {
        val assignment = ClaimHolder(otherId, assignmentId = UUID.fromString("5ea75ea7-0000-4000-8000-000000000002"))

        decide(noteOwnedBy(null), assignment, callerSeat = "implementer").assertAdopted(null, "unowned note, seated caller")
        decide(null, assignment, callerSeat = "implementer").assertAllowed("no note, seated caller")
        // control: the same caller against a note that has an owner is refused
        decide(noteOwnedBy(ownerId), assignment, callerSeat = "implementer").assertOwnedByOther(label = "owned note, seated caller")
    }

    @Test
    fun `R22 a takeover under a seat assignment is invalid_request at slash takeover, or forbidden without the capability`() {
        val assignment = ClaimHolder(adminId, assignmentId = UUID.fromString("5ea75ea7-0000-4000-8000-000000000003"))

        val allowedCap =
            decide(
                noteOwnedBy(ownerId),
                assignment,
                callerSeat = "implementer",
                takeover = true,
                takeoverAllowed = true
            ).error()
        assertEquals(ErrorCode.INVALID_REQUEST, allowedCap.code)
        assertEquals("/takeover", assertIs<ErrorDetail.InvalidRequest>(allowedCap.detail).fields.single().field)

        val noCap = decide(noteOwnedBy(ownerId), assignment, callerSeat = "implementer", takeover = true, takeoverAllowed = false).error()
        assertEquals(ErrorCode.FORBIDDEN, noCap.code, "the capability check precedes the assignment check")

        // control: the same takeover without the assignment transfers
        assertEquals(
            NoteOwnership.Decision.Transferred(ownerId, adminId),
            decide(noteOwnedBy(ownerId), ClaimHolder(adminId), takeover = true, takeoverAllowed = true).decision()
        )
    }

    // ------------------------------------------------------------------ rule 1: shared

    @Test
    fun `S4 a shared entry allows a non-owner and a null caller, and a non-shared or absent entry does not`() {
        val owned = noteOwnedBy(ownerId)

        decide(owned, ClaimHolder(otherId), entry = entry(shared = true)).assertAllowed("shared, non-owner")
        decide(owned, caller = null, entry = entry(shared = true)).assertAllowed("shared, null caller")
        decide(owned, ClaimHolder(otherId), entry = entry(shared = false)).assertOwnedByOther(label = "shared=false")
        decide(owned, ClaimHolder(otherId), entry = null).assertOwnedByOther(label = "off-schema key (no entry) is non-shared")
    }

    @Test
    fun `S4 shared wins over a refusing seat rule`() {
        val owned = noteOwnedBy(ownerId)

        decide(owned, ClaimHolder(otherId), entry = entry(shared = true), seatRule = refusingSeatRule("implementer"))
            .assertAllowed("rule 1 precedes rule 2")
        decide(owned, ClaimHolder(otherId), entry = entry(shared = false), seatRule = refusingSeatRule("implementer"))
            .assertOwnedByOther(ownerSeat = "implementer", label = "control: without shared the seat rule refuses")
    }

    // ------------------------------------------------------------------ takeover

    @Test
    fun `S6 an allowed takeover by a non-owner is Transferred from the stored actor to the caller`() {
        val decision =
            decide(noteOwnedBy(ownerId), ClaimHolder(adminId), takeover = true, takeoverAllowed = true).decision()

        assertEquals(NoteOwnership.Decision.Transferred(from = ownerId, to = adminId), decision)
        // control: the same call without takeover is a refusal, so the transfer is what takeover adds
        decide(noteOwnedBy(ownerId), ClaimHolder(adminId), takeover = false, takeoverAllowed = true)
            .assertOwnedByOther(label = "takeoverAllowed alone transfers nothing")
    }

    @Test
    fun `S7 takeover without the capability is forbidden capability admin, even when no transfer would be needed`() {
        val cases =
            mapOf(
                "another principal's note" to noteOwnedBy(ownerId),
                "the caller's own note" to noteOwnedBy(otherId),
                "a missing note" to null,
                "an unowned note" to noteOwnedBy(null)
            )
        for ((label, existing) in cases) {
            val error = decide(existing, ClaimHolder(otherId), takeover = true, takeoverAllowed = false).error()
            assertEquals(ErrorCode.FORBIDDEN, error.code, label)
            assertEquals(ErrorDetail.Forbidden(ForbiddenScope.CAPABILITY, "admin"), error.detail, label)
        }
        // control: the same inputs with the capability are not forbidden
        decide(noteOwnedBy(otherId), ClaimHolder(otherId), takeover = true, takeoverAllowed = true).assertAllowed("control: allowed")
    }

    @Test
    fun `S7 takeover true without the capability is forbidden even on a shared key`() {
        val shared = entry(shared = true)

        val error = decide(noteOwnedBy(ownerId), ClaimHolder(otherId), entry = shared, takeover = true, takeoverAllowed = false).error()
        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertEquals(ErrorDetail.Forbidden(ForbiddenScope.CAPABILITY, "admin"), error.detail)
        // control: the shared key lets the same non-owner write when takeover is not asked for
        decide(noteOwnedBy(ownerId), ClaimHolder(otherId), entry = shared).assertAllowed("control: shared, no takeover")
    }

    @Test
    fun `S8 takeover under a seat assignment is invalid_request at slash takeover, before ownership is looked at`() {
        val assigned = ClaimHolder(adminId, assignmentId = UUID.randomUUID())

        for ((label, existing) in mapOf(
            "owned by another" to noteOwnedBy(ownerId),
            "own note" to noteOwnedBy(adminId),
            "missing" to null
        )) {
            val error = decide(existing, assigned, takeover = true, takeoverAllowed = true).error()
            assertEquals(ErrorCode.INVALID_REQUEST, error.code, label)
            val detail = assertIs<ErrorDetail.InvalidRequest>(error.detail, label)
            assertEquals("/takeover", detail.fields.single().field, label)
        }
        // control: the same caller without an assignment may take over
        assertEquals(
            NoteOwnership.Decision.Transferred(ownerId, adminId),
            decide(noteOwnedBy(ownerId), ClaimHolder(adminId), takeover = true, takeoverAllowed = true).decision()
        )
    }

    @Test
    fun `S8 the capability check comes before the assignment check`() {
        val assigned = ClaimHolder(otherId, assignmentId = UUID.randomUUID())

        val error = decide(noteOwnedBy(ownerId), assigned, takeover = true, takeoverAllowed = false).error()

        assertEquals(ErrorCode.FORBIDDEN, error.code, "step (a) precedes step (b)")
        assertEquals(ErrorDetail.Forbidden(ForbiddenScope.CAPABILITY, "admin"), error.detail)
    }

    @Test
    fun `S9 a takeover where rule 3 already allows is a no-op - Allowed, never Transferred`() {
        val caller = ClaimHolder(adminId)

        decide(null, caller, takeover = true, takeoverAllowed = true).assertAllowed("missing note")
        decide(noteOwnedBy(adminId), caller, takeover = true, takeoverAllowed = true).assertAllowed("own note")
        decide(noteOwnedBy(ownerId), caller, entry = entry(shared = true), takeover = true, takeoverAllowed = true)
            .assertAllowed("shared key")
        // control: the one shape that does transfer
        assertIs<NoteOwnership.Decision.Transferred>(
            decide(noteOwnedBy(ownerId), caller, takeover = true, takeoverAllowed = true).decision()
        )
        // an unowned note is not a no-op: a takeover of it is an adoption (O7), never Allowed
        decide(noteOwnedBy(null), caller, takeover = true, takeoverAllowed = true).assertAdopted(null, "unowned note")
    }

    @Test
    fun `S9 probe takeover over a seat-owned note compares seats too - own id with a different owner seat transfers`() {
        val decision =
            decide(
                noteOwnedBy(adminId),
                ClaimHolder(adminId),
                callerSeat = null,
                takeover = true,
                takeoverAllowed = true,
                ownerSeat = "implementer"
            ).decision()

        assertEquals(NoteOwnership.Decision.Transferred(from = adminId, to = adminId), decision)
    }

    // ------------------------------------------------------------------ rule 2: the seat rule

    @Test
    fun `S10 a refusing seat rule is returned as is, carrying its ownerSeat, even for an ADMIN takeover`() {
        val seatRule = refusingSeatRule("implementer")

        val withTakeover = decide(noteOwnedBy(ownerId), ClaimHolder(adminId), takeover = true, takeoverAllowed = true, seatRule = seatRule)
        withTakeover.assertOwnedByOther(ownerSeat = "implementer", label = "takeover cannot override rule 2")

        val withoutTakeover = decide(noteOwnedBy(ownerId), ClaimHolder(otherId), seatRule = seatRule)
        withoutTakeover.assertOwnedByOther(ownerSeat = "implementer", label = "plain non-owner")

        // control: NONE allows the same takeover, so the refusal above is the seat rule and not the ownership compare
        assertIs<NoteOwnership.Decision.Transferred>(
            decide(noteOwnedBy(ownerId), ClaimHolder(adminId), takeover = true, takeoverAllowed = true, seatRule = SeatRule.NONE).decision()
        )
    }

    @Test
    fun `S10 a refusing seat rule refuses even the first writer - it is not an ownership compare`() {
        val seatRule = refusingSeatRule("reviewer")

        decide(noteOwnedBy(ownerId), ClaimHolder(ownerId), seatRule = seatRule).assertOwnedByOther(ownerSeat = "reviewer", label = "owner")
        decide(null, ClaimHolder(ownerId), seatRule = seatRule).assertOwnedByOther(ownerSeat = "reviewer", label = "missing note")
        decide(noteOwnedBy(null), ClaimHolder(ownerId), seatRule = seatRule).assertOwnedByOther(ownerSeat = "reviewer", label = "unowned")
    }

    @Test
    fun `S10 the seat rule is handed the item, key, existing note and caller seat`() {
        var seen: List<Any?>? = null
        val spy =
            SeatRule { item, k, existing, callerSeat ->
                seen = listOf(item, k, existing, callerSeat)
                SeatVerdict.Defer
            }
        val existing = noteOwnedBy(ownerId)

        decide(existing, ClaimHolder(ownerId), callerSeat = "implementer", ownerSeat = "implementer", seatRule = spy).assertAllowed()

        assertEquals(listOf<Any?>(itemId, key, existing, "implementer"), seen)
    }

    @Test
    fun `S10 SeatRule NONE defers and never refuses or allows`() {
        assertEquals<SeatVerdict>(SeatVerdict.Defer, SeatRule.NONE.check(itemId, key, noteOwnedBy(ownerId), "implementer"))
        assertEquals<SeatVerdict>(SeatVerdict.Defer, SeatRule.NONE.check(itemId, key, null, null))
    }

    @Test
    fun `R1 an Allow verdict is decisive - the owner compare is not consulted for a non-owner and nothing transfers`() {
        val allow = SeatRule { _, _, _, _ -> SeatVerdict.Allow }
        val defer = SeatRule { _, _, _, _ -> SeatVerdict.Defer }

        decide(noteOwnedBy(ownerId), ClaimHolder(otherId), seatRule = allow).assertAllowed("Allow admits a non-owner without a transfer")
        // control: Defer on the same call leaves rule 3 in charge, so the admission above is the verdict and not the fixture
        decide(noteOwnedBy(ownerId), ClaimHolder(otherId), seatRule = defer).assertOwnedByOther(label = "Defer")
    }

    @Test
    fun `probe repeating the same refused decision repeats the same refusal`() {
        val first = decide(noteOwnedBy(ownerId), ClaimHolder(otherId))
        val second = decide(noteOwnedBy(ownerId), ClaimHolder(otherId))

        assertEquals(first.error().code, second.error().code)
        assertEquals(first.error().detail, second.error().detail)
    }

    // ------------------------------------------------------------------ decision Dprime O1-O10 (decision.md Ruling 7)

    @Test
    fun `O1 a legacy note is adopted by a 4 0 caller and carries its 3x actor as legacyActor`() {
        decide(legacy("planner:eadaaa4e"), ClaimHolder("api:t")).assertAdopted("planner:eadaaa4e")
        // control: the same call on a note owned by the 3.x actor id is a refusal, so adoption is about the NULL owner
        decide(noteOwnedBy("planner:eadaaa4e"), ClaimHolder("api:t")).assertOwnedByOther(label = "control")
    }

    @Test
    fun `O2 a legacy note with a NULL actor is adopted with a null legacyActor`() {
        decide(legacy(null), ClaimHolder("api:t")).assertAdopted(null)
    }

    @Test
    fun `O3 a forged 4 0 shaped legacy actor id is never an owner - the other caller adopts`() {
        decide(legacy("api:t"), ClaimHolder("api:u")).assertAdopted("api:t")
        // control: with ownerId api:t the same caller is refused
        decide(noteOwnedBy("api:t"), ClaimHolder("api:u")).assertOwnedByOther(label = "control")
    }

    @Test
    fun `O4 a legacy local actor is adopted by another principal`() {
        decide(legacy("local"), ClaimHolder("api:u")).assertAdopted("local")
    }

    @Test
    fun `O5 the owner comes from ownerId and never from the actor claim`() {
        val note = Note(itemId = itemId, key = key, role = "work", body = "b", actorClaim = claim("api:b"), ownerId = "api:a")

        decide(note, ClaimHolder("api:b")).assertOwnedByOther(label = "the last writer is not the owner")
        decide(note, ClaimHolder("api:a")).assertAllowed("the owner, who is not the last writer")
    }

    @Test
    fun `O6 an allowed takeover of an owned note is Transferred from the owner id`() {
        val note = Note(itemId = itemId, key = key, role = "work", body = "b", actorClaim = claim("api:a"), ownerId = "api:a")

        assertEquals(
            NoteOwnership.Decision.Transferred("api:a", "api:x"),
            decide(note, ClaimHolder("api:x"), takeover = true, takeoverAllowed = true).decision()
        )
    }

    @Test
    fun `O7 an allowed takeover of a legacy note is an adoption and never a Transferred`() {
        val decision = decide(legacy("planner:eadaaa4e"), ClaimHolder("api:x"), takeover = true, takeoverAllowed = true).decision()

        assertEquals(NoteOwnership.Decision.Adopted("planner:eadaaa4e"), decision)
        assertTrue(decision !is NoteOwnership.Decision.Transferred)
    }

    @Test
    fun `O8 takeover of a legacy note without the capability is forbidden capability admin`() {
        val error = decide(legacy("planner:eadaaa4e"), ClaimHolder("api:x"), takeover = true, takeoverAllowed = false).error()

        assertEquals(ErrorCode.FORBIDDEN, error.code)
        assertEquals(ErrorDetail.Forbidden(ForbiddenScope.CAPABILITY, "admin"), error.detail)
        // control: the same call without takeover is an adoption, so the refusal above is the eager capability check
        decide(legacy("planner:eadaaa4e"), ClaimHolder("api:x"), takeover = false, takeoverAllowed = false)
            .assertAdopted("planner:eadaaa4e")
    }

    @Test
    fun `O9 a shared entry allows a legacy note without adopting it`() {
        decide(legacy("planner:eadaaa4e"), ClaimHolder("api:t"), entry = entry(shared = true)).assertAllowed("shared key")
        // control: without shared the same call adopts
        decide(legacy("planner:eadaaa4e"), ClaimHolder("api:t"), entry = entry(shared = false)).assertAdopted("planner:eadaaa4e")
    }

    @Test
    fun `O10 a null caller is allowed on a legacy note and refused on an owned one`() {
        decide(legacy("planner:eadaaa4e"), caller = null).assertAllowed("legacy note, null caller")
        val owned = Note(itemId = itemId, key = key, role = "work", body = "b", actorClaim = claim("api:a"), ownerId = "api:a")
        decide(owned, caller = null).assertOwnedByOther(label = "owned note, null caller")
    }
}

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceConstraint
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceMode
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen task-scope/test-plan/task-scope-addendum notes on
 * item 09cd604f (stage A2a) -- scenarios S2, S3, S4, S5, S6, plus the addendum's Probes list, at the
 * GatePredicate level directly (never through a tool or the MCP surface -- that is
 * IndependenceGateMcpTest's job).
 *
 * NEW-SURFACE: every declaration this file touches (GatePredicate.violationsForStart,
 * GatePredicate.violationsForComplete, GatePredicate.blocksAdvance, IndependencePolicy,
 * IndependenceMode, IndependenceConstraint, IndependenceViolation) is introduced by this item,
 * so no plain revert can yield behavioral red -- per the dispatch contract's Test author protocol
 * rule 7, red-proof for every scenario here is orchestrator-run against the addendum's M1-M6/M11/M13
 * mutation recipes; this file keeps every new declaration and does not attempt its own revert.
 *
 * Oracle: task-scope-addendum "Frozen semantics (predicate)" and "Scenario detail" sections,
 * applied by hand to each fixture below -- never read from GatePredicate's own source.
 *
 * Harness: note() builds Note fixtures directly (in-memory, no DB) for scenarios that only need
 * actorClaim/verification/createdAt values the test itself controls. S4 additionally seeds and
 * reads back through the REAL NoteStore (SQLite, via DefaultRepositoryProvider -- the same
 * production repository SQLiteNoteRepository wires) because the addendum requires S4's identity
 * read "from the DB, never from an in-memory object" -- proofClaims is the field the addendum
 * cites as DB-persisted (SQLiteNoteRepository.kt:110), unlike VerificationResult.verifiedSubject
 * which is documented as never persisted.
 */
class IndependencePredicateTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val itemId: UUID = UUID.randomUUID()

    companion object {
        private const val S_KEY = "implementation-notes"
        private const val N_KEY = "test-manifest"
    }

    // Mirrors the item's own worked example: implementer (S) / test-author (N), N declares
    // independent_of: [implementer]. Both notes are WORK-phase.
    private val schema =
        WorkItemSchema(
            type = "indep-predicate-test",
            notes =
                listOf(
                    NoteSchemaEntry(key = S_KEY, role = Role.WORK, required = true, seat = "implementer"),
                    NoteSchemaEntry(
                        key = N_KEY,
                        role = Role.WORK,
                        required = true,
                        seat = "test-author",
                        independentOf = listOf("implementer")
                    )
                )
        )

    private fun note(
        key: String,
        actorId: String? = null,
        actorKind: ActorKind = ActorKind.SUBAGENT,
        body: String = "filled",
        verification: VerificationResult? = null,
        createdAt: Instant = Instant.now()
    ): Note =
        Note(
            itemId = itemId,
            key = key,
            role = "work",
            body = body,
            actorClaim = actorId?.let { ActorClaim(id = it, kind = actorKind) },
            verification = verification,
            createdAt = createdAt
        )

    // ──────────────────────────────────────────────
    // S1-addendum (predicate level) -- no independent_of anywhere -> null even in REJECT
    // ──────────────────────────────────────────────

    @Test
    fun `S1-addendum a schema with no independent_of anywhere returns null even in REJECT`() {
        val plainSchema =
            WorkItemSchema(type = "plain", notes = listOf(NoteSchemaEntry(key = "plain-note", role = Role.WORK, required = true)))
        val notes = listOf(note("plain-note", actorId = "a"))
        val policy = IndependencePolicy(mode = IndependenceMode.REJECT)
        assertNull(GatePredicate.violationsForComplete(plainSchema, notes, policy))
        assertNull(GatePredicate.violationsForStart(plainSchema, Role.WORK, notes, policy))
    }

    // ──────────────────────────────────────────────
    // S6 -- mode OFF never computes, regardless of fixture
    // ──────────────────────────────────────────────

    @Test
    fun `S6 mode OFF returns null for a same-actor fixture on both start and complete, never blocks`() {
        val notes = listOf(note(S_KEY, actorId = "same"), note(N_KEY, actorId = "same"))
        val policy = IndependencePolicy(mode = IndependenceMode.OFF)
        assertNull(GatePredicate.violationsForComplete(schema, notes, policy))
        assertNull(GatePredicate.violationsForStart(schema, Role.WORK, notes, policy))
        assertFalse(GatePredicate.blocksAdvance(null, policy))
    }

    // ──────────────────────────────────────────────
    // S2 -- same actor
    // ──────────────────────────────────────────────

    @Test
    fun `S2 same actor id on N and its declared S seat -- same_actor in WARN and REJECT, only REJECT blocks`() {
        val notes = listOf(note(S_KEY, actorId = "sentinel-actor-Z9"), note(N_KEY, actorId = "sentinel-actor-Z9"))

        val warnPolicy = IndependencePolicy(IndependenceMode.WARN)
        val warnViolations = GatePredicate.violationsForComplete(schema, notes, warnPolicy)
        assertEquals(
            listOf(
                IndependenceViolation(
                    key = N_KEY,
                    seat = "test-author",
                    constraint = IndependenceConstraint.SAME_ACTOR,
                    conflictingSeat = "implementer"
                )
            ),
            warnViolations
        )
        assertFalse(GatePredicate.blocksAdvance(warnViolations, warnPolicy), "WARN never blocks")

        val rejectPolicy = IndependencePolicy(IndependenceMode.REJECT)
        val rejectViolations = GatePredicate.violationsForComplete(schema, notes, rejectPolicy)
        assertEquals(warnViolations, rejectViolations, "the computed violations themselves do not depend on mode")
        assertTrue(GatePredicate.blocksAdvance(rejectViolations, rejectPolicy))
    }

    // ──────────────────────────────────────────────
    // S3 -- fail-closed on missing actors, never skipped
    // ──────────────────────────────────────────────

    @Test
    fun `S3a N actor-less, S has an actor -- missing_actor on N with no conflictingSeat`() {
        val notes = listOf(note(S_KEY, actorId = "s-agent"), note(N_KEY, actorId = null))
        val violations = GatePredicate.violationsForComplete(schema, notes, IndependencePolicy(IndependenceMode.WARN))
        assertEquals(
            listOf(
                IndependenceViolation(
                    key = N_KEY,
                    seat = "test-author",
                    constraint = IndependenceConstraint.MISSING_ACTOR,
                    conflictingSeat = null
                )
            ),
            violations
        )
    }

    @Test
    fun `S3b S actor-less, N has an actor -- missing_actor with conflictingSeat naming S`() {
        val notes = listOf(note(S_KEY, actorId = null), note(N_KEY, actorId = "n-agent"))
        val violations = GatePredicate.violationsForComplete(schema, notes, IndependencePolicy(IndependenceMode.WARN))
        assertEquals(
            listOf(
                IndependenceViolation(
                    key = N_KEY,
                    seat = "test-author",
                    constraint = IndependenceConstraint.MISSING_ACTOR,
                    conflictingSeat = "implementer"
                )
            ),
            violations
        )
    }

    @Test
    fun `S3c both N and S actor-less -- both missing_actor entries, N's own check first`() {
        val notes = listOf(note(S_KEY, actorId = null), note(N_KEY, actorId = null))
        val violations = GatePredicate.violationsForComplete(schema, notes, IndependencePolicy(IndependenceMode.WARN))!!
        assertEquals(2, violations.size, "violations: $violations")
        assertEquals(IndependenceConstraint.MISSING_ACTOR, violations[0].constraint)
        assertNull(violations[0].conflictingSeat, "N's own missing-actor check carries no conflictingSeat")
        assertEquals(IndependenceConstraint.MISSING_ACTOR, violations[1].constraint)
        assertEquals("implementer", violations[1].conflictingSeat)
    }

    @Test
    fun `S3d distinct present actor ids -- empty violations list, REJECT does not block`() {
        val notes = listOf(note(S_KEY, actorId = "agent-s"), note(N_KEY, actorId = "agent-n"))
        val rejectPolicy = IndependencePolicy(IndependenceMode.REJECT)
        val violations = GatePredicate.violationsForComplete(schema, notes, rejectPolicy)
        assertEquals(emptyList(), violations)
        assertFalse(GatePredicate.blocksAdvance(violations, rejectPolicy))
    }

    // ──────────────────────────────────────────────
    // S4 -- require_verified, identity precedence, read back from the REAL note repository
    // ──────────────────────────────────────────────

    private fun buildRepositoryProvider(): DefaultRepositoryProvider = db.repositoryProvider()

    // Orchestrator arbitration fix (59a0d98e round): the original version upserted notes against
    // the class-level `itemId` with no WorkItem ever created for it, and ignored the upsert
    // Result -- findByItemId silently returned [] and every S4 assertion vacuously "passed" on an
    // empty violations list. Now a real WorkItem is created first and its OWN id is used, and each
    // upsert's Result is asserted Success so a dropped write can never masquerade as "no violations".
    private fun readBackNotes(vararg notes: Note): List<Note> =
        runBlocking {
            val repo = buildRepositoryProvider()
            val itemResult = repo.workItemRepository().create(WorkItem(title = "S4 fixture item", type = "indep-predicate-test"))
            assertNotNull(itemResult, "fixture item creation must succeed: $itemResult")
            val realItemId = itemResult.id
            notes.forEach { note ->
                val upserted = repo.noteRepository().upsert(note.copy(itemId = realItemId))
                assertNotNull(upserted, "note upsert must succeed: $upserted")
            }
            (repo.noteRepository().findByItemId(realItemId)!!)
        }

    @Test
    fun `S4 require_verified -- every non-VERIFIED status on S produces an unverified entry, read back from the DB`() {
        listOf(
            VerificationStatus.ABSENT,
            VerificationStatus.UNCHECKED,
            VerificationStatus.REJECTED,
            VerificationStatus.UNAVAILABLE
        ).forEach { status ->
            val sNote = note(S_KEY, actorId = "agent-s", verification = VerificationResult(status = status))
            val nNote =
                note(
                    N_KEY,
                    actorId = "agent-n",
                    verification = VerificationResult(status = VerificationStatus.VERIFIED, proofClaims = ProofClaims(sub = "did:n"))
                )
            val readBack = readBackNotes(sNote, nNote)
            val violations =
                GatePredicate.violationsForComplete(schema, readBack, IndependencePolicy(IndependenceMode.WARN, requireVerified = true))!!
            assertTrue(
                violations.any { it.constraint == IndependenceConstraint.UNVERIFIED && it.conflictingSeat == "implementer" },
                "status=$status must produce an unverified entry naming S: $violations"
            )
        }
    }

    @Test
    fun `S4 require_verified -- a note with no verification at all also produces an unverified entry`() {
        val sNote = note(S_KEY, actorId = "agent-s")
        val nNote =
            note(
                N_KEY,
                actorId = "agent-n",
                verification = VerificationResult(status = VerificationStatus.VERIFIED, proofClaims = ProofClaims(sub = "did:n"))
            )
        val readBack = readBackNotes(sNote, nNote)
        val violations =
            GatePredicate.violationsForComplete(
                schema,
                readBack,
                IndependencePolicy(IndependenceMode.WARN, requireVerified = true)
            )!!
        assertTrue(violations.any { it.constraint == IndependenceConstraint.UNVERIFIED }, "violations: $violations")
    }

    @Test
    fun `S4 require_verified -- both notes VERIFIED with distinct proofClaims sub -- no violations`() {
        val sNote =
            note(
                S_KEY,
                actorId = "agent-s",
                verification = VerificationResult(status = VerificationStatus.VERIFIED, proofClaims = ProofClaims(sub = "did:s"))
            )
        val nNote =
            note(
                N_KEY,
                actorId = "agent-n",
                verification = VerificationResult(status = VerificationStatus.VERIFIED, proofClaims = ProofClaims(sub = "did:n"))
            )
        val readBack = readBackNotes(sNote, nNote)
        val violations =
            GatePredicate.violationsForComplete(
                schema,
                readBack,
                IndependencePolicy(IndependenceMode.REJECT, requireVerified = true)
            )
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `S4 identity precedence -- two VERIFIED notes, different actorClaim ids, same stored proofClaims sub -- same_actor`() {
        val sNote =
            note(
                S_KEY,
                actorId = "agent-a",
                verification = VerificationResult(status = VerificationStatus.VERIFIED, proofClaims = ProofClaims(sub = "did:shared"))
            )
        val nNote =
            note(
                N_KEY,
                actorId = "agent-b",
                verification = VerificationResult(status = VerificationStatus.VERIFIED, proofClaims = ProofClaims(sub = "did:shared"))
            )
        val readBack = readBackNotes(sNote, nNote)
        val violations =
            GatePredicate.violationsForComplete(
                schema,
                readBack,
                IndependencePolicy(IndependenceMode.REJECT, requireVerified = true)
            )!!
        assertEquals(1, violations.size, "violations: $violations")
        assertEquals(
            IndependenceConstraint.SAME_ACTOR,
            violations[0].constraint,
            "proofClaims.sub identity must win over distinct actorClaim.id: $violations"
        )
    }

    @Test
    fun `S4 require_verified false -- the same unverified fixtures produce no violations`() {
        val sNote = note(S_KEY, actorId = "agent-s", verification = VerificationResult(status = VerificationStatus.ABSENT))
        val nNote = note(N_KEY, actorId = "agent-n", verification = VerificationResult(status = VerificationStatus.UNCHECKED))
        val readBack = readBackNotes(sNote, nNote)
        val violations =
            GatePredicate.violationsForComplete(
                schema,
                readBack,
                IndependencePolicy(IndependenceMode.REJECT, requireVerified = false)
            )
        assertEquals(emptyList(), violations)
    }

    // Probe: a pre-V17 VERIFIED row with no persisted proofClaims falls back to actorClaim.id.
    @Test
    fun `probe -- a VERIFIED note with no persisted proofClaims falls back to actorClaim id for identity`() {
        val notes =
            listOf(
                note(S_KEY, actorId = "same-agent", verification = VerificationResult(status = VerificationStatus.VERIFIED)),
                note(N_KEY, actorId = "same-agent", verification = VerificationResult(status = VerificationStatus.VERIFIED))
            )
        val violations =
            GatePredicate.violationsForComplete(
                schema,
                notes,
                IndependencePolicy(IndependenceMode.REJECT, requireVerified = true)
            )!!
        assertEquals(1, violations.size, "violations: $violations")
        assertEquals(IndependenceConstraint.SAME_ACTOR, violations[0].constraint)
    }

    // ──────────────────────────────────────────────
    // S5 -- temporal-only waiver (DECISION A2-D1 = B, FINAL)
    // ──────────────────────────────────────────────

    @Test
    fun `S5 waiver -- N created strictly after the same-identity S note is waived and never blocks REJECT`() {
        val base = Instant.now()
        val sNote = note(S_KEY, actorId = "same-agent", createdAt = base)
        val nNote =
            note(N_KEY, actorId = "same-agent", createdAt = base.plusSeconds(60), body = "independence: temporal-only\nfilled details")
        val rejectPolicy = IndependencePolicy(IndependenceMode.REJECT)
        val violations = GatePredicate.violationsForComplete(schema, listOf(sNote, nNote), rejectPolicy)!!
        assertEquals(1, violations.size, "violations: $violations")
        assertEquals(IndependenceConstraint.SAME_ACTOR, violations[0].constraint)
        assertEquals(true, violations[0].waived)
        assertFalse(GatePredicate.blocksAdvance(violations, rejectPolicy), "a fully-waived violation list must never block REJECT")
    }

    @Test
    fun `S5 waiver -- ordering fails (N not strictly after S) -- ordinary same_actor, blocks REJECT`() {
        val base = Instant.now()
        val sNote = note(S_KEY, actorId = "same-agent", createdAt = base)
        val nNote =
            note(N_KEY, actorId = "same-agent", createdAt = base.minusSeconds(1), body = "independence: temporal-only\nfilled details")
        val rejectPolicy = IndependencePolicy(IndependenceMode.REJECT)
        val violations = GatePredicate.violationsForComplete(schema, listOf(sNote, nNote), rejectPolicy)!!
        assertEquals(1, violations.size, "violations: $violations")
        assertEquals(false, violations[0].waived)
        assertTrue(GatePredicate.blocksAdvance(violations, rejectPolicy))
    }

    @Test
    fun `S5 waiver never waives missing_actor, even with the marker present and correct ordering`() {
        val base = Instant.now()
        val sNote = note(S_KEY, actorId = null, createdAt = base)
        val nNote = note(N_KEY, actorId = "n-agent", createdAt = base.plusSeconds(60), body = "independence: temporal-only\nfilled details")
        val rejectPolicy = IndependencePolicy(IndependenceMode.REJECT)
        val violations = GatePredicate.violationsForComplete(schema, listOf(sNote, nNote), rejectPolicy)!!
        assertEquals(IndependenceConstraint.MISSING_ACTOR, violations[0].constraint, "violations: $violations")
        assertFalse(violations[0].waived, "missing_actor must never be marked waived")
        assertTrue(GatePredicate.blocksAdvance(violations, rejectPolicy))
    }

    // Orchestrator red-proof finding: M11b survived -- the waiver must ALSO never waive N's OWN
    // missing_actor entry (rule (1), no conflictingSeat), distinct from the S-actor-less case above
    // (rule (3), conflictingSeat present). Here N ITSELF is actor-less, N's body still opens with
    // the temporal-only marker, and ordering is otherwise valid (N.createdAt after S.createdAt) --
    // since N has no actor at all, rule (4) same_actor never even evaluates, so the ONLY entry is
    // N's own rule-(1) missing_actor check, which the waiver must leave unwaived.
    @Test
    fun `S5 waiver never waives N's own missing_actor entry, even with the marker present and valid ordering`() {
        val base = Instant.now()
        val sNote = note(S_KEY, actorId = "s-agent", createdAt = base)
        val nNote = note(N_KEY, actorId = null, createdAt = base.plusSeconds(60), body = "independence: temporal-only\nfilled details")
        val rejectPolicy = IndependencePolicy(IndependenceMode.REJECT)
        val violations = GatePredicate.violationsForComplete(schema, listOf(sNote, nNote), rejectPolicy)!!
        assertEquals(1, violations.size, "violations: $violations")
        assertEquals(IndependenceConstraint.MISSING_ACTOR, violations[0].constraint)
        assertNull(violations[0].conflictingSeat, "N's own missing-actor check carries no conflictingSeat")
        assertFalse(violations[0].waived, "the waiver must never waive N's own missing_actor entry")
        assertTrue(GatePredicate.blocksAdvance(violations, rejectPolicy), "REJECT must still block on an unwaived missing_actor entry")
    }

    @Test
    fun `probe -- waiver first-line variants -- an exact, case-sensitive, trimmed first line (one trailing CR stripped) is honored`() {
        val base = Instant.now()

        fun waived(body: String): Boolean {
            val sNote = note(S_KEY, actorId = "same", createdAt = base)
            val nNote = note(N_KEY, actorId = "same", createdAt = base.plusSeconds(60), body = body)
            val violations =
                GatePredicate.violationsForComplete(
                    schema,
                    listOf(sNote, nNote),
                    IndependencePolicy(IndependenceMode.REJECT)
                )!!
            return violations.single { it.constraint == IndependenceConstraint.SAME_ACTOR }.waived
        }
        assertTrue(
            waived("independence: temporal-only\r\nrest of body"),
            "CRLF: exactly one trailing \\r must be stripped from the first line"
        )
        assertTrue(waived("independence: temporal-only"), "a lone first line with nothing following is still honored")
        assertFalse(waived("Independence: temporal-only"), "wrong case must not be honored")
        assertTrue(
            waived(" independence: temporal-only"),
            "the addendum's recipe trims the first line before comparing, so a leading space IS honored"
        )
        assertFalse(waived("preamble\nindependence: temporal-only"), "the marker must be the FIRST line, not the second")
        assertFalse(waived("independence: temporal-only-ish"), "a near-miss suffix must not be honored")
    }

    // ──────────────────────────────────────────────
    // Probes -- seat targeting, collapsing, case/whitespace, blank-body exclusion
    // ──────────────────────────────────────────────

    @Test
    fun `probe -- independent_of naming a seat no note declares produces no entry for that seat`() {
        val schemaX =
            WorkItemSchema(
                type = "probe",
                notes =
                    listOf(
                        NoteSchemaEntry(
                            key = N_KEY,
                            role = Role.WORK,
                            required = true,
                            seat = "test-author",
                            independentOf = listOf("ghost-seat")
                        )
                    )
            )
        val notes = listOf(note(N_KEY, actorId = "n"))
        val violations = GatePredicate.violationsForComplete(schemaX, notes, IndependencePolicy(IndependenceMode.WARN))
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `probe -- independent_of naming N's own seat excludes N itself but still compares other same-seat notes`() {
        val schemaSelf =
            WorkItemSchema(
                type = "probe",
                notes =
                    listOf(
                        NoteSchemaEntry(key = "other-note", role = Role.WORK, required = true, seat = "test-author"),
                        NoteSchemaEntry(
                            key = N_KEY,
                            role = Role.WORK,
                            required = true,
                            seat = "test-author",
                            independentOf = listOf("test-author")
                        )
                    )
            )
        val notes = listOf(note("other-note", actorId = "same"), note(N_KEY, actorId = "same"))
        val violations = GatePredicate.violationsForComplete(schemaSelf, notes, IndependencePolicy(IndependenceMode.WARN))!!
        assertEquals(1, violations.size, "N must never be compared against itself: $violations")
        assertEquals(IndependenceConstraint.SAME_ACTOR, violations[0].constraint)
    }

    @Test
    fun `probe -- a duplicate seat in independent_of collapses to at most one entry`() {
        val schemaDup =
            WorkItemSchema(
                type = "probe",
                notes =
                    listOf(
                        NoteSchemaEntry(key = S_KEY, role = Role.WORK, required = true, seat = "implementer"),
                        NoteSchemaEntry(
                            key = N_KEY,
                            role = Role.WORK,
                            required = true,
                            seat = "test-author",
                            independentOf = listOf("implementer", "implementer")
                        )
                    )
            )
        val notes = listOf(note(S_KEY, actorId = "same"), note(N_KEY, actorId = "same"))
        val violations = GatePredicate.violationsForComplete(schemaDup, notes, IndependencePolicy(IndependenceMode.WARN))!!
        assertEquals(1, violations.size, "a duplicate seat must collapse to one entry, not two: $violations")
    }

    @Test
    fun `probe -- ids differing only by case or whitespace are treated as distinct actors (honest limit)`() {
        val notes = listOf(note(S_KEY, actorId = "Agent-X"), note(N_KEY, actorId = "agent-x"))
        val violations = GatePredicate.violationsForComplete(schema, notes, IndependencePolicy(IndependenceMode.WARN))
        assertEquals(emptyList(), violations, "identity compare is exact, case-sensitive string equality")
    }

    @Test
    fun `probe -- a blank-body S-note is not FILLED and is excluded from comparison`() {
        val notes = listOf(note(S_KEY, actorId = "same", body = ""), note(N_KEY, actorId = "same"))
        val violations = GatePredicate.violationsForComplete(schema, notes, IndependencePolicy(IndependenceMode.WARN))
        assertEquals(emptyList(), violations, "an unfilled S-note must never be compared")
    }

    // ──────────────────────────────────────────────
    // currentRole scoping for violationsForStart vs. all-phase violationsForComplete
    // ──────────────────────────────────────────────

    @Test
    fun `violationsForStart only evaluates N notes whose declared role equals currentRole -- complete evaluates all phases`() {
        val schemaMultiPhase =
            WorkItemSchema(
                type = "multi-phase",
                notes =
                    listOf(
                        NoteSchemaEntry(key = S_KEY, role = Role.WORK, required = true, seat = "implementer"),
                        NoteSchemaEntry(
                            key = N_KEY,
                            role = Role.WORK,
                            required = true,
                            seat = "test-author",
                            independentOf = listOf("implementer")
                        ),
                        NoteSchemaEntry(
                            key = "review-note",
                            role = Role.REVIEW,
                            required = true,
                            seat = "reviewer",
                            independentOf = listOf("implementer")
                        )
                    )
            )
        val notes =
            listOf(
                note(S_KEY, actorId = "same"),
                note(N_KEY, actorId = "same"),
                Note(
                    itemId = itemId,
                    key = "review-note",
                    role = "review",
                    body = "filled",
                    actorClaim = ActorClaim(id = "same", kind = ActorKind.SUBAGENT)
                )
            )

        val startViolations =
            GatePredicate.violationsForStart(
                schemaMultiPhase,
                Role.WORK,
                notes,
                IndependencePolicy(IndependenceMode.WARN)
            )!!
        assertEquals(listOf(N_KEY), startViolations.map { it.key }, "start on WORK must evaluate only WORK-phase N notes, not REVIEW's")

        val completeViolations = GatePredicate.violationsForComplete(schemaMultiPhase, notes, IndependencePolicy(IndependenceMode.WARN))!!
        assertEquals(setOf(N_KEY, "review-note"), completeViolations.map { it.key }.toSet(), "complete must evaluate notes from ALL phases")
    }

    // ──────────────────────────────────────────────
    // A2 review follow-ups (orchestrator, HEAD 0b2633ac round)
    // ──────────────────────────────────────────────

    // F3 -- N itself (not just S) can be unverified: rule (2) of the addendum's deterministic
    // ordering ("requireVerified and N status != VERIFIED (incl. null verification) ->
    // constraint:unverified"), own check, no conflictingSeat -- distinct from rule (5)'s
    // conflictingSeat-bearing S-unverified case already covered by the S4 tests above.
    @Test
    fun `F3 N itself not VERIFIED under require_verified -- own unverified entry, no conflictingSeat`() {
        val sNote =
            note(
                S_KEY,
                actorId = "agent-s",
                verification = VerificationResult(status = VerificationStatus.VERIFIED, proofClaims = ProofClaims(sub = "did:s"))
            )
        val nNote = note(N_KEY, actorId = "agent-n", verification = VerificationResult(status = VerificationStatus.UNCHECKED))
        val violations =
            GatePredicate.violationsForComplete(
                schema,
                listOf(sNote, nNote),
                IndependencePolicy(IndependenceMode.WARN, requireVerified = true)
            )!!
        assertEquals(1, violations.size, "violations: $violations")
        assertEquals(IndependenceConstraint.UNVERIFIED, violations[0].constraint)
        assertNull(violations[0].conflictingSeat, "N's own unverified check carries no conflictingSeat")
    }

    // N3 -- the waiver requires N.createdAt STRICTLY AFTER every same-identity S-note's createdAt
    // (task-scope-addendum, DECISION A2-D1=B). Equal timestamps do not satisfy "strictly after".
    @Test
    fun `N3 waiver -- equal createdAt (N == S) is NOT strictly after -- not waived, REJECT still blocks`() {
        val same = Instant.now()
        val sNote = note(S_KEY, actorId = "same-agent", createdAt = same)
        val nNote = note(N_KEY, actorId = "same-agent", createdAt = same, body = "independence: temporal-only\nfilled details")
        val rejectPolicy = IndependencePolicy(IndependenceMode.REJECT)
        val violations = GatePredicate.violationsForComplete(schema, listOf(sNote, nNote), rejectPolicy)!!
        assertEquals(1, violations.size, "violations: $violations")
        assertFalse(violations[0].waived, "equal createdAt must not satisfy the strictly-after ordering requirement")
        assertTrue(GatePredicate.blocksAdvance(violations, rejectPolicy))
    }
}

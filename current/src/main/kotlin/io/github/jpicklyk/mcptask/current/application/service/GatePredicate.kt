package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.IndependenceConstraint
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceMode
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * Pure, JSON-free gate-check predicate shared by the MCP advance tool and the REST advance route.
 *
 * The gate predicate answers two questions: given a [WorkItemSchema], the item's current [Role],
 * and the set of note keys that are currently "filled" (non-blank body), (1) which REQUIRED note
 * schema entries are still missing for the relevant trigger, and (2) (A2) which independent_of
 * independence-attestation violations exist for the relevant trigger.
 *
 * Two trigger semantics mirror the historical inline logic in AdvanceItemTool:
 * - start: only required notes for the item's CURRENT role must be filled.
 * - complete (and cascade-to-terminal): ALL required notes across ALL phases must be filled.
 *
 * The function returns the structured list of missing [NoteSchemaEntry] objects (preserving key,
 * description, guidance, and skill), so each caller can build its own response shape
 * (MCP JSON missingNotes array, or REST gateMissingNotes DTO). No JSON is produced here.
 *
 * A note is "filled" when it exists with a non-blank body -- see [filledNoteKeys].
 */
object GatePredicate {
    /**
     * The frozen waiver marker (A2-D1): a declaring note's body first line (split on newline, one
     * trailing carriage return stripped, then trimmed) equal to exactly this string, case-sensitive,
     * marks its same_actor findings waived: true when the note's createdAt is strictly after every
     * conflicting note's createdAt sharing its identity. Never waives missing_actor or unverified.
     */
    const val TEMPORAL_ONLY_WAIVER = "independence: temporal-only"

    /**
     * Compute the set of note keys that count as "filled" -- i.e. notes that exist with a
     * non-blank body. This is the single source of truth for fill-check logic, matching
     * NoteSchemaJsonHelpers.buildFilledKeys.
     */
    fun filledNoteKeys(notes: List<Note>): Set<String> = notes.filter { it.body.isNotBlank() }.map { it.key }.toSet()

    /**
     * Required notes missing for a start trigger: only entries whose role matches
     * [currentRole] are considered.
     *
     * @return missing required entries for the current phase, in schema order. Empty when the
     *   gate passes (or when the schema declares no required notes for the current phase).
     */
    fun missingForStart(
        schema: WorkItemSchema,
        currentRole: Role,
        filledKeys: Set<String>
    ): List<NoteSchemaEntry> {
        val requiredForCurrentPhase = schema.notes.filter { it.role == currentRole && it.required }
        return requiredForCurrentPhase.filter { it.key !in filledKeys }
    }

    /**
     * Required notes missing for a complete trigger (or a cascade-to-terminal): ALL required
     * entries across every phase are considered.
     *
     * @return missing required entries across all phases, in schema order. Empty when the gate
     *   passes (or when the schema declares no required notes at all).
     */
    fun missingForComplete(
        schema: WorkItemSchema,
        filledKeys: Set<String>
    ): List<NoteSchemaEntry> {
        val allRequired = schema.notes.filter { it.required }
        return allRequired.filter { it.key !in filledKeys }
    }

    /**
     * A2 independence violations for a start trigger: only declaring entries (non-empty
     * independentOf) whose role equals [currentRole] are evaluated.
     *
     * @return null iff [policy]'s mode is OFF, or [schema] has no entry with non-empty
     *   independentOf in ANY phase; otherwise a (possibly empty) list.
     */
    fun violationsForStart(
        schema: WorkItemSchema,
        currentRole: Role,
        notes: List<Note>,
        policy: IndependencePolicy
    ): List<IndependenceViolation>? = computeViolations(schema, notes, policy, roleFilter = currentRole)

    /**
     * A2 independence violations for a complete trigger (or a cascade-to-terminal/start): every
     * declaring entry, across ALL phases, is evaluated.
     *
     * @return null iff [policy]'s mode is OFF, or [schema] has no entry with non-empty
     *   independentOf in ANY phase; otherwise a (possibly empty) list.
     */
    fun violationsForComplete(
        schema: WorkItemSchema,
        notes: List<Note>,
        policy: IndependencePolicy
    ): List<IndependenceViolation>? = computeViolations(schema, notes, policy, roleFilter = null)

    /**
     * Whether [violations] blocks the transition under [policy]: only in [IndependenceMode.REJECT],
     * and only when at least one entry is not [IndependenceViolation.waived]. A null or empty
     * [violations] never blocks.
     */
    fun blocksAdvance(
        violations: List<IndependenceViolation>?,
        policy: IndependencePolicy
    ): Boolean {
        if (violations.isNullOrEmpty()) return false
        if (policy.mode != IndependenceMode.REJECT) return false
        return violations.any { !it.waived }
    }

    /**
     * Shared computation for [violationsForStart] ([roleFilter] = the current role) and
     * [violationsForComplete] ([roleFilter] = null, meaning every phase).
     *
     * Deterministic per-N order: for each declaring entry N (schema order, filtered by
     * [roleFilter] when non-null) whose note is filled -- (1) N actor-less -> missing_actor;
     * (2) requireVerified and N not VERIFIED -> unverified; then for each seat S in N's
     * independentOf (duplicates collapsed, first wins) with at least one FILLED note owned by S
     * (excluding N itself) -- (3) any S-note actor-less -> missing_actor (conflictingSeat=S);
     * (4) N has an actor and any S-note's identity equals N's identity -> same_actor
     * (conflictingSeat=S, waiver applied here); (5) requireVerified and any S-note not VERIFIED ->
     * unverified (conflictingSeat=S). At most one entry per (key, constraint, conflictingSeat).
     */
    private fun computeViolations(
        schema: WorkItemSchema,
        notes: List<Note>,
        policy: IndependencePolicy,
        roleFilter: Role?
    ): List<IndependenceViolation>? {
        if (policy.mode == IndependenceMode.OFF) return null
        val declaringEntries = schema.notes.filter { it.independentOf.isNotEmpty() }
        if (declaringEntries.isEmpty()) return null

        val notesByKey = notes.associateBy { it.key }
        val result = mutableListOf<IndependenceViolation>()
        val seen = mutableSetOf<Triple<String, IndependenceConstraint, String?>>()

        fun addEntry(violation: IndependenceViolation) {
            if (seen.add(Triple(violation.key, violation.constraint, violation.conflictingSeat))) {
                result.add(violation)
            }
        }

        for (entry in declaringEntries) {
            if (roleFilter != null && entry.role != roleFilter) continue
            val n = notesByKey[entry.key] ?: continue
            if (n.body.isBlank()) continue

            if (n.actorClaim == null) {
                addEntry(IndependenceViolation(entry.key, entry.seat, IndependenceConstraint.MISSING_ACTOR, null))
            }
            if (policy.requireVerified && !isVerified(n)) {
                addEntry(IndependenceViolation(entry.key, entry.seat, IndependenceConstraint.UNVERIFIED, null))
            }

            val nIdentity = identityOf(n)
            for (seat in entry.independentOf.distinct()) {
                val sNotes = filledNotesForSeat(schema, notes, seat, entry.key)
                if (sNotes.isEmpty()) continue

                if (sNotes.any { it.actorClaim == null }) {
                    addEntry(IndependenceViolation(entry.key, entry.seat, IndependenceConstraint.MISSING_ACTOR, seat))
                }
                if (n.actorClaim != null) {
                    val matchingIdentity = sNotes.filter { identityOf(it) == nIdentity }
                    if (matchingIdentity.isNotEmpty()) {
                        val waived =
                            isTemporalOnlyWaiver(n.body) && matchingIdentity.all { n.createdAt.isAfter(it.createdAt) }
                        addEntry(
                            IndependenceViolation(
                                entry.key,
                                entry.seat,
                                IndependenceConstraint.SAME_ACTOR,
                                seat,
                                waived = waived
                            )
                        )
                    }
                }
                if (policy.requireVerified && sNotes.any { !isVerified(it) }) {
                    addEntry(IndependenceViolation(entry.key, entry.seat, IndependenceConstraint.UNVERIFIED, seat))
                }
            }
        }

        return result
    }

    /** Every FILLED note owned (by resolved schema entry) by [seat], excluding the note keyed [excludeKey]. */
    private fun filledNotesForSeat(
        schema: WorkItemSchema,
        notes: List<Note>,
        seat: String,
        excludeKey: String
    ): List<Note> {
        val keysForSeat =
            schema.notes
                .filter { it.seat == seat }
                .map { it.key }
                .toSet()
        if (keysForSeat.isEmpty()) return emptyList()
        return notes.filter { it.key in keysForSeat && it.key != excludeKey && it.body.isNotBlank() }
    }

    /**
     * Identity of a note: verification.proofClaims.sub when verification.status == VERIFIED
     * and that sub is non-null, else actorClaim.id (may be null when the note is actor-less).
     */
    private fun identityOf(note: Note): String? {
        val verification = note.verification
        if (verification != null && verification.status == VerificationStatus.VERIFIED) {
            verification.proofClaims?.sub?.let { return it }
        }
        return note.actorClaim?.id
    }

    private fun isVerified(note: Note): Boolean = note.verification?.status == VerificationStatus.VERIFIED

    /** True when [body]'s first line, CR-stripped and trimmed, equals [TEMPORAL_ONLY_WAIVER] exactly. */
    private fun isTemporalOnlyWaiver(body: String): Boolean {
        val firstLine = body.substringBefore('\n')
        val stripped = if (firstLine.endsWith("\r")) firstLine.dropLast(1) else firstLine
        return stripped.trim() == TEMPORAL_ONLY_WAIVER
    }
}

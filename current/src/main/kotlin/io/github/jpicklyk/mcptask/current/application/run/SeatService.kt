package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.port.AssigneeHolder
import io.github.jpicklyk.mcptask.current.application.port.InFlightSeat
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.RunSeatPort
import io.github.jpicklyk.mcptask.current.application.port.RunSeatStore
import io.github.jpicklyk.mcptask.current.application.port.SeatTokenMatch
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.ClaimService
import io.github.jpicklyk.mcptask.current.application.support.sha256Hex
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.ClaimReleaseReason
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatLink
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatOutcome
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Assigns [seat] of member [itemId] of run [runId] to the principal [principalId] (the coordinator's own principal). */
data class SeatAssignCommand(
    val runId: UUID,
    val itemId: UUID,
    val seat: String,
    val hints: Map<String, String> = emptyMap(),
    val principalId: String
)

/** A stored assignment and its seat [token], returned exactly once (only its hash is kept). */
data class IssuedSeat(
    val seat: RunSeat,
    val token: String
)

/** An ended assignment as stored, and the items whose claims the end released. */
data class SeatEndResult(
    val seat: RunSeat,
    val released: List<UUID>
)

/**
 * The single owner of seat-assignment writes and seat events (plan section 4.1, R22). Every write is ONE
 * [UnitOfWork.write] unit (it joins an ambient one) that records its `seat.*` event; an [Outcome.Err] rolls it back.
 *
 * ## Tokens
 *
 * A seat token is 32 random bytes, base64url without padding (43 characters); only its lowercase hex SHA-256 is stored.
 * It is bound to the principal that received it ([RunSeat.principalId]) and dies with its assignment: [resolve] accepts
 * a token only while its assignment is active AND the presenting principal is the bound one.
 *
 * ## Coordinator
 *
 * `open` creates the run-scoped `coordinator` pseudo-seat ([openCoordinator]); its token is the run's only coordinator
 * credential. It is not a member seat: [activeCount] does not count it and a close's in-flight list never names it.
 *
 * Also the [RunSeatPort] the run close uses (delegated to the store).
 */
class SeatService(
    private val repositoryProvider: RepositoryProvider,
    private val unitOfWork: UnitOfWork,
    private val claimService: ClaimService,
    private val configResolver: EffectiveConfigResolver,
    private val random: SecureRandom = SecureRandom()
) : RunSeatPort {
    private val store: RunSeatStore get() = repositoryProvider.runSeatStore()

    // -------------------------------------------------------------------------
    // RunSeatPort (the run close)
    // -------------------------------------------------------------------------

    override suspend fun activeCount(runId: UUID): Int = store.activeCount(runId)

    override suspend fun assigneeHolders(runId: UUID): Set<AssigneeHolder> = store.assigneeHolders(runId)

    override suspend fun endActive(
        runId: UUID,
        outcome: String
    ): List<InFlightSeat> = store.endActive(runId, outcome)

    // -------------------------------------------------------------------------
    // coordinator
    // -------------------------------------------------------------------------

    /** Creates the coordinator pseudo-seat of the just-opened [run] for [principalId] (the opener) and returns its token. */
    suspend fun openCoordinator(
        run: Run,
        principalId: String
    ): Outcome<IssuedSeat> =
        unitOfWork.write(OP_OPEN_COORDINATOR) {
            val token = newToken()
            val stored =
                store.create(
                    RunSeat(
                        runId = run.id,
                        itemId = null,
                        seat = RunSeat.COORDINATOR,
                        attempt = 1,
                        principalId = principalId,
                        tokenHash = hash(token),
                        assignedAt = now
                    )
                )
            Outcome.Ok(IssuedSeat(stored, token))
        }

    // -------------------------------------------------------------------------
    // assign
    // -------------------------------------------------------------------------

    /**
     * Assigns a seat (task-scope D5, D6), after the caller checked the run's scope and the coordinator token. In order:
     * the run is not closed (`run_closed`) or paused (`run_paused`); [SeatAssignCommand.seat] is not `coordinator`
     * (`invalid_request /seat`); the item is a member of the run (`invalid_request /itemId`); the seat is one of the
     * item's schema seats, or `implementer`/`planner` for a seat-less or schema-free item (`not_found` kind `seat`).
     *
     * An active assignment of the same (run, item, seat) is superseded: it ends with outcome `superseded` and its
     * holder's claims are released (reason `superseded`) in the same unit. The new attempt is one more than the highest
     * attempt of that triple over every row, ended ones included. Records `seat.assigned`.
     *
     * Once those checks pass, [admission] decides the run limits (R27: the attempt cap, then `maxActiveSeats`) before
     * anything is written; its refusal is returned as is.
     */
    suspend fun assign(
        command: SeatAssignCommand,
        admission: SeatAdmission = SeatAdmission.OPEN
    ): Outcome<IssuedSeat> =
        unitOfWork.write(OP_ASSIGN) {
            val runs = stores.runStore()
            val run = runs.getById(command.runId) ?: return@write err(runNotFound(command.runId))
            if (run.status == RunStatus.CLOSED) return@write err(RunService.runClosed(run))
            if (run.status == RunStatus.PAUSED) return@write err(RunService.runPaused(run))
            if (command.seat == RunSeat.COORDINATOR) {
                return@write err(invalid(FIELD_SEAT, "coordinator is not an item seat", command.seat))
            }
            if (command.itemId !in runs.members(run.id)) {
                return@write err(invalid(FIELD_ITEM_ID, "is not a member of the run", command.itemId.toString()))
            }
            val item =
                stores.workItemRepository().getById(command.itemId)
                    ?: return@write err(invalid(FIELD_ITEM_ID, "is not a member of the run", command.itemId.toString()))
            val schemaSeats =
                configResolver
                    .resolveSchema(item)
                    ?.seats
                    ?.map { it.name }
                    .orEmpty()
            val allowed = if (schemaSeats.isEmpty()) RunSeat.DEFAULT_SEATS else schemaSeats.toSet()
            if (command.seat !in allowed) return@write err(seatNotFound(command.seat))

            val previous = store.findActive(run.id, command.itemId, command.seat)
            val attempt = store.maxAttempt(run.id, command.itemId, command.seat) + 1
            admission.admit(this, run, command.itemId, command.seat, attempt, supersedes = previous != null)?.let {
                return@write err(it)
            }
            if (previous != null) {
                store.end(previous.id, RunSeatOutcome.SUPERSEDED, now)
                releaseClaimsOf(previous, ClaimReleaseReason.SUPERSEDED)?.let { return@write it }
            }
            val token = newToken()
            val stored =
                store.create(
                    RunSeat(
                        runId = run.id,
                        itemId = command.itemId,
                        seat = command.seat,
                        attempt = attempt,
                        principalId = command.principalId,
                        tokenHash = hash(token),
                        hints = command.hints,
                        assignedAt = now
                    )
                )
            events.record(
                DomainEvent.SeatAssigned(stored.id, run.rootId, run.id, command.itemId, stored.seat, stored.attempt, stored.hints)
            )
            Outcome.Ok(IssuedSeat(stored, token))
        }

    // -------------------------------------------------------------------------
    // end_seat
    // -------------------------------------------------------------------------

    /**
     * Ends the active member-seat assignment [assignmentId] (task-scope D8) with [outcome] (`done`, `failed` or
     * `abandoned`), an optional [summary] (at most 500 characters) and up to 10 [links]. The ending holder's claims are
     * released (reason `released`) in the same unit and reported; leases are untouched. Records `seat.ended`. An
     * assignment that is no longer active (a concurrent end) is `unauthenticated`, the dead token's answer.
     */
    suspend fun endSeat(
        assignmentId: UUID,
        outcome: RunSeatOutcome,
        summary: String?,
        links: List<RunSeatLink>
    ): Outcome<SeatEndResult> =
        unitOfWork.write(OP_END) {
            require(outcome in REPORTABLE_OUTCOMES) { "outcome $outcome is server-only" }
            endViolations(summary, links)?.let { return@write err(it) }
            val seat = store.getById(assignmentId)
            if (seat == null || !seat.isActive || seat.itemId == null) return@write err(tokenRefused())
            val ended = store.end(seat.id, outcome, now, summary, links) ?: return@write err(tokenRefused())
            val released = ArrayList<UUID>()
            for (held in stores.workItemRepository().findHeldBy(seat.principalId, seat.id)) {
                when (val cleared = claimService.clearClaim(held.id, ClaimReleaseReason.RELEASED)) {
                    is Outcome.Err -> return@write cleared
                    is Outcome.Ok -> if (cleared.value) released += held.id
                }
            }
            val rootId = stores.runStore().getById(seat.runId)?.rootId ?: return@write err(runNotFound(seat.runId))
            events.record(
                DomainEvent.SeatEnded(ended.id, rootId, ended.runId, seat.itemId, ended.seat, ended.attempt, outcome, summary, links)
            )
            Outcome.Ok(SeatEndResult(ended, released.sortedBy { it.toString() }))
        }

    // -------------------------------------------------------------------------
    // token resolution, liveness, usage, reads
    // -------------------------------------------------------------------------

    /**
     * The active assignment whose token is [token], when [principalId] is the principal it is bound to; null for an
     * unknown, ended or foreign token (the caller answers all three the same way).
     */
    suspend fun resolve(
        token: String,
        principalId: String
    ): RunSeat? = resolveMatch(token, principalId)?.seat

    /** [resolve] with the server-derived [SeatTokenMatch.runLimited] hint, from the same single token lookup. */
    suspend fun resolveMatch(
        token: String,
        principalId: String
    ): SeatTokenMatch? {
        if (token.length != TOKEN_LENGTH) return null
        val match = unitOfWork.read { stores.runSeatStore().findTokenMatch(hash(token)) } ?: return null
        return match.takeIf { it.seat.isActive && it.seat.principalId == principalId }
    }

    /**
     * Marks [seat] as seen now (task-scope D9), in its own small unit: `last_seen_at`, and for the coordinator also the
     * run's `coordinator_seen_at`. Skipped while the stored `last_seen_at` is less than [TOUCH_INTERVAL] old.
     */
    suspend fun touch(seat: RunSeat) {
        unitOfWork.write(OP_TOUCH) {
            val current = store.getById(seat.id)
            if (current == null || !current.isActive || isFresh(current.lastSeenAt, now)) return@write Outcome.Ok(Unit)
            store.touch(current.id, now)
            if (current.isCoordinator) stores.runStore().touchCoordinator(current.runId, now)
            Outcome.Ok(Unit)
        }
    }

    /**
     * Records the absolute usage a seat reported (task-scope D9): per field, the higher of the stored and the reported
     * value is kept. The run's `reported_tokens` / `reported_cost_micro` are then set to the SUM over every assignment
     * of the run, ended ones included (R27), without bumping the run's version. Joins the caller's unit.
     */
    suspend fun reportUsage(
        assignmentId: UUID,
        tokens: Long?,
        costMicroUsd: Long?
    ): Outcome<RunSeat?> =
        unitOfWork.write(OP_USAGE) {
            val seat = store.recordUsage(assignmentId, tokens, costMicroUsd)
            if (seat != null) {
                val totals = store.usageTotals(seat.runId)
                stores.runStore().setReportedUsage(seat.runId, totals.tokens, totals.costMicroUsd)
            }
            Outcome.Ok(seat)
        }

    /** The assignments of [runId], oldest first: on [itemId] only when given, ended ones only when [includeEnded]. */
    suspend fun list(
        runId: UUID,
        itemId: UUID?,
        includeEnded: Boolean
    ): List<RunSeat> = unitOfWork.read { stores.runSeatStore().list(runId, itemId, includeEnded) }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun isFresh(
        lastSeen: Instant?,
        now: Instant
    ): Boolean = lastSeen != null && Duration.between(lastSeen, now) < TOUCH_INTERVAL

    /** Clears every claim [seat]'s holder pair holds, with [reason]; the first failure, else null. */
    private suspend fun WriteScope.releaseClaimsOf(
        seat: RunSeat,
        reason: ClaimReleaseReason
    ): Outcome<IssuedSeat>? {
        for (held in stores.workItemRepository().findHeldBy(seat.principalId, seat.id)) {
            val cleared = claimService.clearClaim(held.id, reason)
            if (cleared is Outcome.Err) return Outcome.Err(cleared.error)
        }
        return null
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun <T> err(error: DomainError): Outcome<T> = Outcome.Err(error)

    companion object {
        private const val OP_OPEN_COORDINATOR = "SeatService.openCoordinator"
        private const val OP_ASSIGN = "SeatService.assign"
        private const val OP_END = "SeatService.endSeat"
        private const val OP_TOUCH = "SeatService.touch"
        private const val OP_USAGE = "SeatService.reportUsage"

        private const val FIELD_SEAT = "/seat"
        private const val FIELD_ITEM_ID = "/itemId"

        /** Random bytes in a seat token. */
        const val TOKEN_BYTES = 32

        /** Characters of a seat token: 32 bytes in base64url without padding. */
        const val TOKEN_LENGTH = 43

        /** A token-carrying write touches `last_seen_at` only when the stored value is at least this old. */
        val TOUCH_INTERVAL: Duration = Duration.ofSeconds(5)

        /** The outcomes a seat may report itself. */
        val REPORTABLE_OUTCOMES: Set<RunSeatOutcome> = setOf(RunSeatOutcome.DONE, RunSeatOutcome.FAILED, RunSeatOutcome.ABANDONED)

        /** The message of every refused seat token: unknown, ended and foreign alike, with no detail. */
        const val TOKEN_REFUSED_MESSAGE = "The seat token is not accepted."

        /** The lowercase hex SHA-256 of [token]: the only form a token is stored or looked up in. */
        fun hash(token: String): String = sha256Hex(token.toByteArray(Charsets.UTF_8))

        /** The one `unauthenticated` refusal of a seat token: identical for an unknown, ended or foreign token. */
        fun tokenRefused(): DomainError = DomainError(ErrorCode.UNAUTHENTICATED, TOKEN_REFUSED_MESSAGE)

        /** `not_found` kind `seat`: [seat] is not a seat the item can be assigned. */
        fun seatNotFound(seat: String): DomainError =
            DomainError(
                code = ErrorCode.NOT_FOUND,
                message = "Seat '$seat' is not a seat of the item's schema.",
                detail = ErrorDetail.NotFound(EntityKind.SEAT, seat),
                fixArgs = mapOf("kind" to "seat", "id" to seat)
            )

        /** The `invalid_request` of an `end_seat` [summary] or [links] over the limits, or null. */
        fun endViolations(
            summary: String?,
            links: List<RunSeatLink>
        ): DomainError? {
            val out = mutableListOf<FieldViolation>()
            if (summary != null && summary.length > RunSeat.MAX_SUMMARY_LENGTH) {
                out += FieldViolation("/summary", "must be at most ${RunSeat.MAX_SUMMARY_LENGTH} characters", summary.length.toString())
            }
            if (links.size > RunSeat.MAX_LINKS) {
                out += FieldViolation("/links", "at most ${RunSeat.MAX_LINKS} links", links.size.toString())
            }
            links.forEachIndexed { i, link ->
                if (link.kind !in RunSeat.LINK_KINDS) {
                    out += FieldViolation("/links/$i/kind", "must be one of: ${RunSeat.LINK_KINDS.joinToString(", ")}", link.kind.take(100))
                }
                if (link.ref.isBlank() || link.ref.length > RunSeat.MAX_LINK_REF_LENGTH) {
                    out +=
                        FieldViolation(
                            "/links/$i/ref",
                            "must be 1 to ${RunSeat.MAX_LINK_REF_LENGTH} non-blank characters",
                            link.ref.take(100)
                        )
                }
            }
            return if (out.isEmpty()) null else invalidFields(out)
        }

        private fun invalid(
            field: String,
            reason: String,
            received: String?
        ): DomainError = invalidFields(listOf(FieldViolation(field, reason, received)))

        private fun invalidFields(fields: List<FieldViolation>): DomainError {
            val sorted = fields.sortedBy { it.field }
            return DomainError(
                code = ErrorCode.INVALID_REQUEST,
                message = "The request has ${sorted.size} invalid field(s): ${sorted.joinToString(", ") { it.field }}.",
                detail = ErrorDetail.InvalidRequest(sorted)
            )
        }

        private fun runNotFound(id: UUID): DomainError =
            DomainError(
                code = ErrorCode.NOT_FOUND,
                message = "Run $id not found",
                detail = ErrorDetail.NotFound(EntityKind.RUN, id.toString()),
                fixArgs = mapOf("kind" to "run", "id" to id.toString())
            )
    }
}

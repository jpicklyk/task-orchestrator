package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.RunStore
import io.github.jpicklyk.mcptask.current.application.port.ServerDecision
import io.github.jpicklyk.mcptask.current.application.port.ServerDecisionRaiser
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunLimitDecisions
import io.github.jpicklyk.mcptask.current.domain.model.RunPauseReason
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The run limits (plan section 3.9, R27): the attempt cap and the active-seat cap.
 *
 * ## Assign ([admit], through [assign])
 *
 * The attempt cap wins over the seat cap. The `(attemptsPerSeat + allowances + 1)`th attempt of an (item, seat), where
 * allowances counts that (item, seat)'s `attempt-cap` decisions answered `allow_one`, is refused with `run_paused`
 * (reason `attempts`, naming the decision); the pause and the `attempt-cap` decision (dedupe
 * `attempts:<runId>:<itemId>:<seat>:<attempt>`, options `allow_one`/`skip_item`/`stop`) are written by a FRESH unit once
 * the assign's own unit has ended, so they land although the assign rolls back. An otherwise admissible assign that
 * would not supersede an active seat is refused with transient `run_at_capacity` while `maxActiveSeats` member seats are
 * active; superseding keeps the count, so it is allowed at capacity.
 */
class RunLimits(
    private val repositoryProvider: RepositoryProvider,
    private val unitOfWork: UnitOfWork,
    private val runService: RunService,
    private val seatService: SeatService,
    private val decisions: ServerDecisionRaiser
) : SeatAdmission {
    private val logger = LoggerFactory.getLogger(RunLimits::class.java)
    private val runs: RunStore get() = repositoryProvider.runStore()

    /** [SeatService.assign] with this run's attempt cap and seat cap enforced. */
    suspend fun assign(command: SeatAssignCommand): Outcome<IssuedSeat> = seatService.assign(command, this)

    override suspend fun admit(
        scope: WriteScope,
        run: Run,
        itemId: UUID,
        seat: String,
        attempt: Int,
        supersedes: Boolean
    ): DomainError? {
        val budget = run.budget ?: return null
        val cap = budget.attemptsPerSeat
        if (cap != null && attempt > cap) {
            val allowed = cap + allowances(run.id, itemId, seat)
            if (attempt > allowed) return attemptCapBreach(scope, run, itemId, seat, attempt, allowed)
        }
        val max = budget.maxActiveSeats
        if (max != null && !supersedes) {
            val active = repositoryProvider.runSeatStore().activeCount(run.id)
            if (active >= max) return runAtCapacity(run.id, active, max)
        }
        return null
    }

    /** The `attempt-cap` decisions of (run, item, seat) answered `allow_one`: one extra attempt each. */
    private suspend fun allowances(
        runId: UUID,
        itemId: UUID,
        seat: String
    ): Int {
        val prefix = RunLimitDecisions.attemptDedupePrefix(runId, itemId, seat)
        return repositoryProvider
            .decisionRequestStore()
            .list(runId = runId, itemId = itemId, statuses = setOf(DecisionRequestStatus.ANSWERED))
            .count {
                it.decisionClass == RunLimitDecisions.ATTEMPT_CAP &&
                    it.dedupeKey.startsWith(prefix) &&
                    it.answer?.option == RunLimitDecisions.ALLOW_ONE
            }
    }

    /**
     * The refusal of an assign over the attempt cap. The decision id is chosen now, so the refusal can name it; the
     * decision and the pause are written once the caller's unit ends, committed or rolled back ([pauseForAttempts]).
     */
    private fun attemptCapBreach(
        scope: WriteScope,
        run: Run,
        itemId: UUID,
        seat: String,
        attempt: Int,
        allowed: Int
    ): DomainError {
        val decisionId = UUID.randomUUID()
        val write: suspend () -> Unit = { pauseForAttempts(run.id, itemId, seat, attempt, allowed, decisionId) }
        scope.afterRollback { write() }
        scope.afterCommit { write() }
        return RunService.runPaused(
            run.copy(status = RunStatus.PAUSED, pauseReason = RunPauseReason.ATTEMPTS, pauseDecisionId = decisionId)
        )
    }

    /**
     * Raises the `attempt-cap` decision [decisionId] and pauses run [runId] (`attempts`) with it, in a fresh unit, when
     * the run is still OPEN. A failure is logged at WARN: the refusal the caller already has is unchanged.
     */
    private suspend fun pauseForAttempts(
        runId: UUID,
        itemId: UUID,
        seat: String,
        attempt: Int,
        allowed: Int,
        decisionId: UUID
    ) {
        try {
            val outcome =
                unitOfWork.write(OP_ATTEMPT_PAUSE) {
                    val run = runs.getById(runId) ?: return@write Outcome.Ok(Unit)
                    if (run.status != RunStatus.OPEN) return@write Outcome.Ok(Unit)
                    val raised =
                        when (val r = decisions.raiseServer(attemptDecision(run, itemId, seat, attempt, allowed, decisionId))) {
                            is Outcome.Err -> return@write r
                            is Outcome.Ok -> r.value
                        }
                    when (val paused = runService.pauseForLimit(run.id, RunPauseReason.ATTEMPTS, raised.id)) {
                        is Outcome.Err -> paused
                        is Outcome.Ok -> Outcome.Ok(Unit)
                    }
                }
            if (outcome is Outcome.Err) logger.warn("Attempt-cap pause of run {} was not written: {}", runId, outcome.error.message)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.warn("Attempt-cap pause of run {} was not written: {}", runId, e.message)
        }
    }

    private fun attemptDecision(
        run: Run,
        itemId: UUID,
        seat: String,
        attempt: Int,
        allowed: Int,
        decisionId: UUID
    ): ServerDecision {
        val context =
            buildJsonObject {
                put("itemId", itemId.toString())
                put("seat", seat)
                put("attempt", attempt)
                put("allowed", allowed)
                run.budget?.attemptsPerSeat?.let { put("attemptsPerSeat", it) }
            }
        return ServerDecision(
            id = decisionId,
            rootId = run.rootId,
            runId = run.id,
            itemId = itemId,
            decisionClass = RunLimitDecisions.ATTEMPT_CAP,
            question = "Seat '$seat' of item $itemId has used its $allowed attempt(s) in run ${run.label ?: run.id}. Allow one more?",
            options =
                listOf(
                    DecisionRequestOption(
                        RunLimitDecisions.ALLOW_ONE,
                        "Allow one more attempt",
                        "Raises this seat's cap by one and resumes the run."
                    ),
                    DecisionRequestOption(
                        RunLimitDecisions.SKIP_ITEM,
                        "Skip the item",
                        "Removes the item from the run and resumes the run."
                    ),
                    DecisionRequestOption(RunLimitDecisions.STOP, "Stop the run", "Closes the run as budget_exhausted.")
                ),
            recommended = null,
            context = context.toString(),
            dedupeKey = RunLimitDecisions.attemptDedupeKey(run.id, itemId, seat, attempt)
        )
    }

    companion object {
        private const val OP_ATTEMPT_PAUSE = "RunLimits.pauseForAttempts"

        /** Transient `run_at_capacity`: [active] member seats are active on run [runId], its cap [max]. */
        fun runAtCapacity(
            runId: UUID,
            active: Int,
            max: Int
        ): DomainError =
            DomainError(
                code = ErrorCode.RUN_AT_CAPACITY,
                message = "Run $runId has $active of $max seats active; retry the assign when a seat ends",
                detail = ErrorDetail.RunAtCapacity(runId, active, max),
                fixArgs = mapOf("runId" to runId.toString(), "active" to active.toString(), "max" to max.toString())
            )
    }
}

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

/** A budget a run has crossed: the [dimension] (`tokens` or `costUsd`), the enforced [used] figure and its [limit]. */
data class BudgetBreach(
    val dimension: String,
    val used: Long,
    val limit: Long
)

/**
 * The run limits (plan section 3.9, R27): the token and cost budget, the attempt cap and the active-seat cap.
 *
 * ## Budget
 *
 * [checkBudget] is the in-unit check every token-carrying write (heartbeat, assign, end_seat, transition, raise) runs
 * after its own change, in its own unit. The enforced figures are `max(reportedTokens, observedTokens)` for tokens and
 * `reportedCostMicroUsd` for cost; a breach is `used > limit` (equality is not a breach). On a crossing of an OPEN run it
 * raises a `run-budget` decision (dedupe `budget:<runId>:<n>`, n the breach ordinal over the run's budget decisions,
 * options `extend`/`stop`, recommended `stop`, context the numbers) and pauses the run (`budget`) with it; both commit
 * with the caller's write, which still succeeds. A run already paused for its budget answers its pause decision again,
 * recording nothing. A run without a token or cost limit is never checked past its row read.
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
 *
 * The observed counter is written off-path by the call-log writer, which never runs a check: a crossing it causes is
 * found by the next token-carrying write.
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

    /**
     * The budget check of run [runId] (see the class rules); joins the caller's unit. Returns the id of the decision the
     * run is paused on for its budget (just raised, or the standing one), else null. [runLimited] is the server-derived
     * hint from token resolution (the run has any budget field): false answers null with no read, and the default true
     * keeps every caller without the hint checking.
     */
    suspend fun checkBudget(
        runId: UUID,
        runLimited: Boolean = true
    ): Outcome<UUID?> =
        if (!runLimited) {
            Outcome.Ok(null)
        } else {
            checkBudgetInUnit(runId)
        }

    private suspend fun checkBudgetInUnit(runId: UUID): Outcome<UUID?> =
        unitOfWork.write(OP_CHECK) {
            val run = runs.getById(runId) ?: return@write Outcome.Ok(null)
            val budget = run.budget
            if (budget == null || (budget.tokens == null && budget.costMicroUsd == null)) return@write Outcome.Ok(null)
            when (run.status) {
                RunStatus.CLOSED -> return@write Outcome.Ok(null)
                RunStatus.PAUSED ->
                    return@write Outcome.Ok(if (run.pauseReason == RunPauseReason.BUDGET) run.pauseDecisionId else null)
                RunStatus.OPEN -> Unit
            }
            val breach = breach(run) ?: return@write Outcome.Ok(null)
            val ordinal =
                stores
                    .decisionRequestStore()
                    .list(runId = run.id)
                    .count { it.decisionClass == RunLimitDecisions.RUN_BUDGET } + 1
            val raised =
                when (val r = decisions.raiseServer(budgetDecision(run, breach, ordinal))) {
                    is Outcome.Err -> return@write r
                    is Outcome.Ok -> r.value
                }
            when (val paused = runService.pauseForLimit(run.id, RunPauseReason.BUDGET, raised.id)) {
                is Outcome.Err -> return@write paused
                is Outcome.Ok -> Unit
            }
            Outcome.Ok(raised.id)
        }

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

    private fun budgetDecision(
        run: Run,
        breach: BudgetBreach,
        ordinal: Int
    ): ServerDecision {
        val cost = breach.dimension == RunLimitDecisions.DIMENSION_COST
        val shown = { v: Long -> if (cost) "${usd(v)} USD" else "$v tokens" }
        val context =
            buildJsonObject {
                put("dimension", breach.dimension)
                if (cost) {
                    put("used", JsonPrimitive(usdNumber(breach.used)))
                    put("limit", JsonPrimitive(usdNumber(breach.limit)))
                } else {
                    put("used", breach.used)
                    put("limit", breach.limit)
                }
                put("reportedTokens", run.reportedTokens)
                put("observedTokens", run.observedTokens)
                put("reportedCostUsd", JsonPrimitive(usdNumber(run.reportedCostMicroUsd)))
                run.budget?.tokens?.let { put("tokenLimit", it) }
                run.budget?.costMicroUsd?.let { put("costLimitUsd", JsonPrimitive(usdNumber(it))) }
            }
        return ServerDecision(
            id = UUID.randomUUID(),
            rootId = run.rootId,
            runId = run.id,
            itemId = null,
            decisionClass = RunLimitDecisions.RUN_BUDGET,
            question =
                "Run ${run.label ?: run.id} has used ${shown(breach.used)} against a budget of ${shown(breach.limit)}. " +
                    "Extend the budget or stop the run?",
            options =
                listOf(
                    DecisionRequestOption(
                        RunLimitDecisions.EXTEND,
                        "Extend the budget",
                        "Raises the ${breach.dimension} budget by the answer's amount and resumes the run."
                    ),
                    DecisionRequestOption(
                        RunLimitDecisions.STOP,
                        "Stop the run",
                        "Closes the run as budget_exhausted, ending its seats and releasing their claims."
                    )
                ),
            recommended = RunLimitDecisions.STOP,
            context = context.toString(),
            dedupeKey = RunLimitDecisions.budgetDedupeKey(run.id, ordinal)
        )
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
        private const val OP_CHECK = "RunLimits.checkBudget"
        private const val OP_ATTEMPT_PAUSE = "RunLimits.pauseForAttempts"

        /**
         * The budget [run] has crossed, or null: tokens first (enforced `max(reported, observed)`), then cost (enforced
         * reported). A breach is `used > limit`.
         */
        fun breach(run: Run): BudgetBreach? {
            val budget = run.budget ?: return null
            val tokens = maxOf(run.reportedTokens, run.observedTokens)
            budget.tokens?.let { limit -> if (tokens > limit) return BudgetBreach(RunLimitDecisions.DIMENSION_TOKENS, tokens, limit) }
            budget.costMicroUsd?.let { limit ->
                if (run.reportedCostMicroUsd > limit) return BudgetBreach(RunLimitDecisions.DIMENSION_COST, run.reportedCostMicroUsd, limit)
            }
            return null
        }

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

        private fun usdNumber(micro: Long): BigDecimal = BigDecimal.valueOf(micro, 6).stripTrailingZeros()

        private fun usd(micro: Long): String = usdNumber(micro).toPlainString()
    }
}

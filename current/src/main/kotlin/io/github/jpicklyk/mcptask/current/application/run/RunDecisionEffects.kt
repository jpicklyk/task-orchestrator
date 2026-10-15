package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.port.DecisionEffects
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.RunLimitDecisions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * The server-class answer effects (plan section 3.9, R27), run inside the answer's unit:
 *
 * - `run-budget` `extend{amount}` raises the breached budget (named by the decision context's `dimension`: `tokens`,
 *   a whole number, or `costUsd`, US dollars) by `amount` and resumes the run; `stop` closes it `budget_exhausted` with
 *   the full close cascade.
 * - `attempt-cap` `allow_one` resumes the run (the answered decision itself is the (item, seat) allowance `RunLimits`
 *   counts); `skip_item` removes the item from the run and resumes it; `stop` closes it `budget_exhausted`.
 *
 * Each acts only while the run is paused by exactly the answered decision; any other answer, class or run state does
 * nothing. An `extend` amount that does not fit the dimension refuses the answer (`invalid_request /amount`).
 */
class RunDecisionEffects(
    private val runService: RunService
) : DecisionEffects {
    override suspend fun onAnswered(decision: DecisionRequest) {
        onAnswered(decision, null)?.let { throw IllegalStateException(it.message) }
    }

    override suspend fun onAnswered(
        decision: DecisionRequest,
        amount: Double?
    ): DomainError? {
        val runId = decision.runId ?: return null
        val option = decision.answer?.option ?: return null
        val closedBy = decision.answer?.answeredById
        return when (decision.decisionClass) {
            RunLimitDecisions.RUN_BUDGET ->
                when (option) {
                    RunLimitDecisions.EXTEND -> extend(decision, runId, amount)
                    RunLimitDecisions.STOP -> errorOf(runService.closeAfterDecision(runId, decision.id, closedBy))
                    else -> null
                }
            RunLimitDecisions.ATTEMPT_CAP ->
                when (option) {
                    RunLimitDecisions.ALLOW_ONE -> errorOf(runService.resumeAfterDecision(runId, decision.id))
                    RunLimitDecisions.SKIP_ITEM ->
                        errorOf(runService.resumeAfterDecision(runId, decision.id, removeItem = decision.itemId))
                    RunLimitDecisions.STOP -> errorOf(runService.closeAfterDecision(runId, decision.id, closedBy))
                    else -> null
                }
            else -> null
        }
    }

    private suspend fun extend(
        decision: DecisionRequest,
        runId: UUID,
        amount: Double?
    ): DomainError? {
        if (amount == null || !amount.isFinite() || amount <= 0) return invalidAmount("must be a positive number to extend", amount)
        return if (dimensionOf(decision) == RunLimitDecisions.DIMENSION_COST) {
            val micro = ConfigValidator.costMicroUsd(amount)
            if (micro == null || micro <= 0) return invalidAmount("must be a positive number of US dollars", amount)
            errorOf(runService.resumeAfterDecision(runId, decision.id, extendCostMicroUsd = micro))
        } else {
            if (amount % 1.0 != 0.0 || amount >= Long.MAX_VALUE.toDouble()) {
                return invalidAmount("must be a whole number of tokens", amount)
            }
            errorOf(runService.resumeAfterDecision(runId, decision.id, extendTokens = amount.toLong()))
        }
    }

    private fun <T> errorOf(outcome: Outcome<T>): DomainError? = (outcome as? Outcome.Err)?.error

    companion object {
        /** The budget dimension a `run-budget` decision names in its context; `tokens` when it names none. */
        fun dimensionOf(decision: DecisionRequest): String {
            val context = decision.context ?: return RunLimitDecisions.DIMENSION_TOKENS
            val parsed =
                try {
                    Json.parseToJsonElement(context) as? JsonObject
                } catch (e: IllegalArgumentException) {
                    null
                }
            val named = (parsed?.get("dimension") as? JsonPrimitive)?.takeIf { it.isString }?.content
            return if (named == RunLimitDecisions.DIMENSION_COST) RunLimitDecisions.DIMENSION_COST else RunLimitDecisions.DIMENSION_TOKENS
        }

        private fun invalidAmount(
            reason: String,
            amount: Double?
        ): DomainError =
            DomainError(
                code = ErrorCode.INVALID_REQUEST,
                message = "Invalid /amount: $reason",
                detail = ErrorDetail.InvalidRequest(listOf(FieldViolation("/amount", reason, amount?.toString())))
            )
    }
}

package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.port.DecisionEffects
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.RunLimitDecisions

/**
 * The server-class answer effects (plan section 3.9, R27), run inside the answer's unit:
 *
 * - `run-budget` (the 4.0.0 token and cost budget decision, never raised again; stored rows still answer) `extend`
 *   resumes the run with no budget change; `stop` closes it `budget_exhausted` with the full close cascade.
 * - `attempt-cap` `allow_one` resumes the run (the answered decision itself is the (item, seat) allowance `RunLimits`
 *   counts); `skip_item` removes the item from the run and resumes it; `stop` closes it `budget_exhausted`.
 *
 * Each acts only while the run is paused by exactly the answered decision; any other answer, class or run state does
 * nothing.
 */
class RunDecisionEffects(
    private val runService: RunService
) : DecisionEffects {
    override suspend fun onAnswered(decision: DecisionRequest) {
        onAnsweredOrRefuse(decision)?.let { throw IllegalStateException(it.message) }
    }

    override suspend fun onAnsweredOrRefuse(decision: DecisionRequest): DomainError? {
        val runId = decision.runId ?: return null
        val option = decision.answer?.option ?: return null
        val closedBy = decision.answer?.answeredById
        return when (decision.decisionClass) {
            RunLimitDecisions.RUN_BUDGET ->
                when (option) {
                    RunLimitDecisions.EXTEND -> errorOf(runService.resumeAfterDecision(runId, decision.id))
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

    private fun <T> errorOf(outcome: Outcome<T>): DomainError? = (outcome as? Outcome.Err)?.error
}

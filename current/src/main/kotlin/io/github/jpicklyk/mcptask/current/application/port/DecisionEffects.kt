package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest

/**
 * Called once per answer, inside the answer's unit, after the answered row is stored and before `decision.answered` is
 * recorded. [decision] is the answered request as stored. A thrown exception rolls the whole answer back. [NONE] does
 * nothing. The server-class effects (R27, `RunDecisionEffects`) act on the run the answered `run-budget` or
 * `attempt-cap` request paused.
 */
fun interface DecisionEffects {
    suspend fun onAnswered(decision: DecisionRequest)

    /**
     * What the answer service calls: [onAnswered] with the answer's extend [amount] (null when the answer carried none).
     * A returned error refuses the answer (the whole answer unit rolls back and the caller gets the error). The default
     * runs [onAnswered] and accepts.
     */
    suspend fun onAnswered(
        decision: DecisionRequest,
        amount: Double?
    ): DomainError? {
        onAnswered(decision)
        return null
    }

    companion object {
        val NONE: DecisionEffects = DecisionEffects { }
    }
}

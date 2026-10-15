package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest

/**
 * Called once per answer, inside the answer's unit, after the answered row is stored and before `decision.answered` is
 * recorded. [decision] is the answered request as stored. A thrown exception rolls the whole answer back. [NONE] does
 * nothing (the default until a later item supplies the server-class effects).
 */
fun interface DecisionEffects {
    suspend fun onAnswered(decision: DecisionRequest)

    companion object {
        val NONE: DecisionEffects = DecisionEffects { }
    }
}

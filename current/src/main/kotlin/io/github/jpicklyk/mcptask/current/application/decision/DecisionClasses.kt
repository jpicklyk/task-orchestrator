package io.github.jpicklyk.mcptask.current.application.decision

import io.github.jpicklyk.mcptask.current.application.config.DecisionClassConfig
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.RunLimitDecisions

/**
 * One effective decision class: who may answer ([answerers]), the default [severity], the expiry in seconds
 * ([expiresAfterSeconds], null = never overdue), the expiry behaviour ([onExpiry], always `escalate`), whether an
 * answer may be free text ([freeform]), and whether only the server raises it ([serverRaised]: the tool `raise`
 * refuses it).
 */
data class DecisionClass(
    val name: String,
    val answerers: DecisionRequestAnswerers,
    val severity: DecisionRequestSeverity = DecisionRequestSeverity.NORMAL,
    val expiresAfterSeconds: Long? = null,
    val onExpiry: String = DecisionRequest.ON_EXPIRY_ESCALATE,
    val freeform: Boolean = false,
    val serverRaised: Boolean = false
)

/**
 * The built-in decision classes and the merge of the configured `decision_classes:` over them (plan section 3.3).
 *
 * Built-ins: `run-budget` and `attempt-cap` (director-or-human, high, server-raised), `deviation` (director-or-human,
 * normal, expires after 14,400 s, escalate) and `question` (any-actor, low, freeform). A configured entry overrides a
 * built-in per FIELD (an unset field keeps the built-in value); a configured class that is not built in takes the
 * defaults director-or-human, normal, no expiry, not freeform. The answerers of `run-budget` and `attempt-cap` can be
 * raised to human-only but never lowered to any-actor: the validator rejects it, and [resolve] ignores it should it
 * ever reach here.
 */
object DecisionClasses {
    const val RUN_BUDGET = RunLimitDecisions.RUN_BUDGET
    const val ATTEMPT_CAP = RunLimitDecisions.ATTEMPT_CAP
    const val DEVIATION = "deviation"
    const val QUESTION = "question"

    /** The `deviation` expiry: four hours. */
    const val DEVIATION_EXPIRES_AFTER_SECONDS = 14_400L

    /** The classes whose answerers may never be `any-actor`. */
    val NEVER_ANY_ACTOR: Set<String> = setOf(RUN_BUDGET, ATTEMPT_CAP)

    val BUILT_IN: Map<String, DecisionClass> =
        listOf(
            DecisionClass(
                RUN_BUDGET,
                DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
                DecisionRequestSeverity.HIGH,
                serverRaised = true
            ),
            DecisionClass(
                ATTEMPT_CAP,
                DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
                DecisionRequestSeverity.HIGH,
                serverRaised = true
            ),
            DecisionClass(
                DEVIATION,
                DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
                DecisionRequestSeverity.NORMAL,
                expiresAfterSeconds = DEVIATION_EXPIRES_AFTER_SECONDS
            ),
            DecisionClass(QUESTION, DecisionRequestAnswerers.ANY_ACTOR, DecisionRequestSeverity.LOW, freeform = true)
        ).associateBy { it.name }

    /** The effective classes: [BUILT_IN] with [declared] merged over it per field (see the class rules), by name. */
    fun resolve(declared: Map<String, DecisionClassConfig>): Map<String, DecisionClass> {
        val out = LinkedHashMap(BUILT_IN)
        for ((name, config) in declared) {
            val base = BUILT_IN[name] ?: DecisionClass(name, DecisionRequestAnswerers.DIRECTOR_OR_HUMAN)
            val answerers =
                config.answerers
                    ?.takeUnless { it == DecisionRequestAnswerers.ANY_ACTOR && name in NEVER_ANY_ACTOR }
                    ?: base.answerers
            out[name] =
                base.copy(
                    answerers = answerers,
                    severity = config.severity ?: base.severity,
                    expiresAfterSeconds = config.expiresAfterSeconds ?: base.expiresAfterSeconds,
                    onExpiry = config.onExpiry ?: base.onExpiry,
                    freeform = config.freeform ?: base.freeform
                )
        }
        return out
    }
}

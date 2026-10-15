package io.github.jpicklyk.mcptask.current.domain.model

/**
 * The server-raised decisions a run limit produces (plan section 3.9, R27): their class names and option keys, shared by
 * the side that raises them (the run limits) and the side that answers them (the decision service and its effects).
 */
object RunLimitDecisions {
    /** Raised when a token-carrying write finds the run over its token or cost budget. */
    const val RUN_BUDGET = "run-budget"

    /** Raised when an assign would exceed the attempts allowed for one (item, seat). */
    const val ATTEMPT_CAP = "attempt-cap"

    /** `run-budget`: raise the breached budget by the answer's `amount` and resume. */
    const val EXTEND = "extend"

    /** `run-budget` and `attempt-cap`: close the run with `budget_exhausted`. */
    const val STOP = "stop"

    /** `attempt-cap`: allow that (item, seat) one more attempt and resume. */
    const val ALLOW_ONE = "allow_one"

    /** `attempt-cap`: remove the item from the run and resume. */
    const val SKIP_ITEM = "skip_item"

    /** The budget dimension a `run-budget` decision is about: tokens. */
    const val DIMENSION_TOKENS = "tokens"

    /** The budget dimension a `run-budget` decision is about: cost in US dollars. */
    const val DIMENSION_COST = "costUsd"

    /** The id and kind recorded as the raiser of a server-raised decision. */
    const val SERVER_RAISER = "server"

    /** The dedupe key of the [n]th budget breach of run [runId] (1-based, counted over the run's budget decisions). */
    fun budgetDedupeKey(
        runId: java.util.UUID,
        n: Int
    ): String = "budget:$runId:$n"

    /** The dedupe key of the attempt-cap decision for [attempt] of [seat] on [itemId] in run [runId]. */
    fun attemptDedupeKey(
        runId: java.util.UUID,
        itemId: java.util.UUID,
        seat: String,
        attempt: Int
    ): String = "attempts:$runId:$itemId:$seat:$attempt"

    /** The dedupe-key prefix every attempt-cap decision of [seat] on [itemId] in run [runId] starts with. */
    fun attemptDedupePrefix(
        runId: java.util.UUID,
        itemId: java.util.UUID,
        seat: String
    ): String = "attempts:$runId:$itemId:$seat:"
}

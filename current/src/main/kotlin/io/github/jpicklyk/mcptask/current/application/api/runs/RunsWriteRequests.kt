package io.github.jpicklyk.mcptask.current.application.api.runs

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatOutcome
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Why a caller closes a run. `budget_exhausted` is server-only and is not accepted. */
@Serializable
enum class RunCloseReason(
    val domain: RunTerminalReason
) {
    @SerialName("completed")
    COMPLETED(RunTerminalReason.COMPLETED),

    @SerialName("cancelled")
    CANCELLED(RunTerminalReason.CANCELLED),

    @SerialName("failed")
    FAILED(RunTerminalReason.FAILED),

    @SerialName("abandoned")
    ABANDONED(RunTerminalReason.ABANDONED)
}

/** A run budget as the caller writes it. Every field is optional; an unset field is unlimited (or, on update, unchanged). */
@Serializable
@Doc("A run budget. Every field is optional; an unset field is unlimited. On update a field can only be lowered or newly set.")
data class RunBudgetRequest(
    @Doc("Token limit.") val tokens: Long? = null,
    @Doc("Cost limit in US dollars, a decimal such as 12.5.") val costUsd: Double? = null,
    @Doc("Most attempts one seat may take.") val attemptsPerSeat: Int? = null,
    @Doc("Most seats active at once.") val maxActiveSeats: Int? = null
)

private const val BUDGET_DOC =
    "Run budget: tokens, costUsd, attemptsPerSeat, maxActiveSeats; absent means no cap. On open it may not exceed a " +
        "configured ceiling; on update a field can only be lowered or newly set."
private const val HINTS_DOC = "Short structured coordinator hints, string to string. On update they replace the stored hints."
private const val ITEM_DOC = "Item id: a UUID or a hex prefix of 8 to 32 characters."
private const val LINKS_DOC = "Up to 10 {kind, ref} pointers; kind is item, note, doc, commit or url."
private const val ROOT_DOC = "The root item the run belongs to. Item id: a UUID or a hex prefix of 8 to 32 characters."

/** How the calling seat ended: the outcomes a seat reports itself (`superseded` and `run_closed` are server-only). */
@Serializable
enum class SeatEndOutcome(
    val domain: RunSeatOutcome
) {
    @SerialName("done")
    DONE(RunSeatOutcome.DONE),

    @SerialName("failed")
    FAILED(RunSeatOutcome.FAILED),

    @SerialName("abandoned")
    ABANDONED(RunSeatOutcome.ABANDONED)
}

/** `runs_write open`: starts a run over a set of items of one root. */
@Serializable
data class RunsOpenRequest(
    @Doc(ROOT_DOC) val rootId: IdRef,
    @Doc("Member items, at most 500. Each is an item of the root that has a lifecycle and is in no other open run.")
    val items: List<IdRef> = emptyList(),
    @Doc("An item the run is scoped to. $ITEM_DOC") val scopeId: IdRef? = null,
    @Doc("A short label, at most 64 characters.") val label: String? = null,
    @Doc("A title, at most 200 characters.") val title: String? = null,
    @Doc(BUDGET_DOC) val budget: RunBudgetRequest? = null,
    @Doc("Seconds without a coordinator sign of life before the run reads as stalled. Default from config.")
    val stallAfterSeconds: Int? = null,
    @Doc(HINTS_DOC) val hints: Map<String, String>? = null
)

/** `runs_write update`: changes an open run. */
@Serializable
data class RunsUpdateRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef,
    @Doc("Items to add to the run. $ITEM_DOC") val addItems: List<IdRef> = emptyList(),
    @Doc("Items to remove from the run. $ITEM_DOC") val removeItems: List<IdRef> = emptyList(),
    @Doc(BUDGET_DOC) val budget: RunBudgetRequest? = null,
    @Doc(HINTS_DOC) val hints: Map<String, String>? = null,
    @Doc("Fails with version_conflict unless the run is at this version.") val expectedVersion: Long? = null
)

/** `runs_write close`: ends a run. */
@Serializable
data class RunsCloseRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef,
    @Doc("Why the run ends: completed, cancelled, failed or abandoned. completed is refused while a seat is active.")
    val reason: RunCloseReason
)

/** `runs_write pause`: pauses an open run. */
@Serializable
data class RunsPauseRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef
)

/** `runs_write resume`: resumes a manually paused run. */
@Serializable
data class RunsResumeRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef
)

/** `runs_write assign`: the coordinator assigns a seat of a member item and receives the seat token. */
@Serializable
data class RunsAssignRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef,
    @Doc(ITEM_DOC) val itemId: IdRef,
    @Doc(
        "A seat of the item's schema (implementer or planner when it has none). Returns a seat token; re-assigning supersedes."
    )
    val seat: String,
    @Doc(HINTS_DOC) val hints: Map<String, String>? = null
)

/** `runs_write end_seat`: the calling seat ends its own assignment. */
@Serializable
data class RunsEndSeatRequest(
    @Doc("How the calling seat ended.") val outcome: SeatEndOutcome,
    @Doc("Closing summary, at most 500 characters.") val summary: String? = null,
    @Doc(LINKS_DOC) val links: List<JsonObject>? = null
)

/** A decision severity as the caller writes it. */
@Serializable
enum class DecisionSeverityRequest(
    val domain: DecisionRequestSeverity
) {
    @SerialName("high")
    HIGH(DecisionRequestSeverity.HIGH),

    @SerialName("normal")
    NORMAL(DecisionRequestSeverity.NORMAL),

    @SerialName("low")
    LOW(DecisionRequestSeverity.LOW)
}

/**
 * `runs_write raise`: raises a decision request. One of [itemId], [runId] or [rootId] is required; under a seat token
 * an absent [runId] / [itemId] defaults to the token's run / item.
 */
@Serializable
data class RunsRaiseRequest(
    @Doc("Decision class (for example question or deviation); it decides who may answer.")
    val `class`: String,
    @Doc("The question, at most 500 characters.") val question: String,
    @Doc("1 to 8 choices, each {key, label, consequence}.") val options: List<JsonObject>,
    @Doc("Key of the recommended option.") val recommended: String? = null,
    @Doc(LINKS_DOC) val links: List<JsonObject>? = null,
    @Doc("Background for the answerer, at most 1000 characters.") val context: String? = null,
    @Doc("Re-raising the same key returns the open or answered decision instead of a new one.") val dedupeKey: String,
    @Doc(ITEM_DOC) val itemId: IdRef? = null,
    @Doc(RUN_ID_DOC) val runId: IdRef? = null,
    @Doc(ROOT_DOC) val rootId: IdRef? = null,
    @Doc("Default normal.") val severity: DecisionSeverityRequest? = null,
    @Doc("true: also block itemId as awaiting_decision in the same unit.") val block: Boolean? = null
)

/** `runs_write ack`: acknowledges an open decision. */
@Serializable
data class RunsAckRequest(
    @Doc(DECISION_ID_DOC) val decisionId: IdRef
)

/** `runs_write answer`: answers a pending decision. */
@Serializable
data class RunsAnswerRequest(
    @Doc(DECISION_ID_DOC) val decisionId: IdRef,
    @Doc("Key of the chosen option.") val option: String? = null,
    @Doc("Free-form answer, at most 1000 characters; freeform classes only.") val text: String? = null,
    @Doc("Why this answer, at most 1000 characters.") val rationale: String? = null
)

/** `runs_write withdraw`: withdraws a pending decision. */
@Serializable
data class RunsWithdrawRequest(
    @Doc(DECISION_ID_DOC) val decisionId: IdRef
)

/** `runs_write mark`: sets a named mark on a run. */
@Serializable
data class RunsMarkRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef,
    @Doc("The mark key: lowercase letters, digits, - and _, starting with a letter, at most 64 characters.") val key: String
)

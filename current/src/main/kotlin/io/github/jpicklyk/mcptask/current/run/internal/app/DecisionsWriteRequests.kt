package io.github.jpicklyk.mcptask.current.run.internal.app

import io.github.jpicklyk.mcptask.current.model.run.DecisionDecidedBy
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

private const val ITEM_DOC = "Item id: UUID or 8-32 hex."
private const val LINKS_DOC = "Up to 10 {kind, ref} pointers; kind is item, note, doc, commit or url."
private const val ROOT_DOC = "The root item the run belongs to. Item id: UUID or 8-32 hex."

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

/** Who a caller says decided an answer: the user, or the director (an agent that is not the user). */
@Serializable
enum class DecisionDecidedByRequest(
    val domain: DecisionDecidedBy
) {
    @SerialName("user")
    USER(DecisionDecidedBy.USER),

    @SerialName("director")
    DIRECTOR(DecisionDecidedBy.DIRECTOR)
}

/**
 * `decisions_write raise`: raises a decision request. One of [itemId], [runId] or [rootId] is required; under a seat token
 * an absent [runId] / [itemId] defaults to the token's run / item.
 */
@Serializable
data class DecisionsRaiseRequest(
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
    @Doc("Class default.") val severity: DecisionSeverityRequest? = null,
    @Doc("true: also block itemId as awaiting_decision in the same unit.") val block: Boolean? = null
)

/** `decisions_write ack`: acknowledges an open decision. */
@Serializable
data class DecisionsAckRequest(
    @Doc(DECISION_ID_DOC) val decisionId: IdRef
)

/** `decisions_write delegate`: routes a pending decision to the director. */
@Serializable
data class DecisionsDelegateRequest(
    @Doc(DECISION_ID_DOC) val decisionId: IdRef
)

/** `decisions_write escalate`: routes a pending decision to the user. */
@Serializable
data class DecisionsEscalateRequest(
    @Doc(DECISION_ID_DOC) val decisionId: IdRef,
    @Doc("Why the user is needed, 1 to 500 characters.") val reason: String
)

/** `decisions_write answer`: answers a pending decision. */
@Serializable
data class DecisionsAnswerRequest(
    @Doc(DECISION_ID_DOC) val decisionId: IdRef,
    @Doc("Key of the chosen option.") val option: String? = null,
    @Doc("Free-form answer, at most 1000 characters; freeform classes only.") val text: String? = null,
    @Doc("Why this answer, at most 1000 characters.") val rationale: String? = null,
    @Doc("Who decided: user or director. Absent: derived from the caller; a claim the credential contradicts is refused.")
    val decidedBy: DecisionDecidedByRequest? = null,
    @Doc(LINKS_DOC) val links: List<JsonObject>? = null
)

/** `decisions_write withdraw`: withdraws a pending decision. */
@Serializable
data class DecisionsWithdrawRequest(
    @Doc(DECISION_ID_DOC) val decisionId: IdRef
)

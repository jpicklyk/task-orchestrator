package io.github.jpicklyk.mcptask.current.application.decision

import io.github.jpicklyk.mcptask.current.application.api.AnswerProvenance
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatLink
import java.util.UUID

/**
 * Who is acting on a decision request, as the answerer matrix (plan section 3.3) sees it. Built only by the tool
 * layer from the call context, so the matrix ([admits]) is a pure function of this value.
 *
 * @property principalId The audited actor id (`api:<id>`, or `local`).
 * @property kind The credential kind; null when the call carries no transport principal (a test-built one).
 * @property proofStatus How the credential was proven; null as for [kind].
 * @property director True when the director profile served the call and it carries no seat token.
 * @property seat The seat of the call's seat token, or null without one.
 * @property runId The run of the call's seat token, or null without one.
 * @property assignmentId The assignment of the call's seat token, or null without one.
 * @property provenance The call's answer provenance, or null when it has no transport principal.
 */
data class DecisionCaller(
    val principalId: String,
    val kind: PrincipalKind?,
    val proofStatus: ProofStatus?,
    val director: Boolean,
    val seat: String?,
    val runId: UUID?,
    val assignmentId: UUID?,
    val provenance: AnswerProvenance?
) {
    /** The kind to record for this caller: the credential kind, `agent` when there is none (the local principal). */
    val kindWire: String get() = kind?.wire ?: PrincipalKind.AGENT.wire

    companion object {
        /** The proofs a human answer must carry: a real credential proof, never `none` or `rejected`. */
        val HUMAN_PROOFS: Set<ProofStatus> = setOf(ProofStatus.VERIFIED, ProofStatus.VERIFIED_STALE, ProofStatus.ISSUED)
    }
}

/** True when [caller] is a human: a human credential, no seat token, and a proof in [DecisionCaller.HUMAN_PROOFS]. */
fun DecisionCaller.isHuman(): Boolean = kind == PrincipalKind.HUMAN && seat == null && proofStatus in DecisionCaller.HUMAN_PROOFS

/** True when [caller] is the director: the director profile served the call and it carries no seat token. */
fun DecisionCaller.isDirector(): Boolean = director && seat == null

/**
 * The answerer matrix (plan section 3.3): whether [caller] may answer, or acknowledge, a request of these answerers.
 * `human-only` admits a human ([isHuman]); `director-or-human` a human or the director ([isDirector]); `any-actor`
 * every principal.
 */
fun DecisionRequestAnswerers.admits(caller: DecisionCaller): Boolean =
    when (this) {
        DecisionRequestAnswerers.HUMAN_ONLY -> caller.isHuman()
        DecisionRequestAnswerers.DIRECTOR_OR_HUMAN -> caller.isHuman() || caller.isDirector()
        DecisionRequestAnswerers.ANY_ACTOR -> true
    }

/** The `forbidden` `required` text of these answerers: the principal kinds that could have acted, sorted. */
fun DecisionRequestAnswerers.required(): String =
    when (this) {
        DecisionRequestAnswerers.HUMAN_ONLY -> "human"
        DecisionRequestAnswerers.DIRECTOR_OR_HUMAN -> "director,human"
        DecisionRequestAnswerers.ANY_ACTOR -> "any"
    }

/**
 * Raises a decision request under root [rootId], about run [runId] and member [itemId] when given (the caller has
 * resolved them, checked their scope and that they agree on the root). [severity] null takes the class default.
 * [raisedById], [raisedByKind] and [raisedBySeat] record who raised it. [id], when given, is the id a new request takes
 * (a server raise names its decision before storing it); null draws a random one.
 */
data class DecisionRaiseCommand(
    val rootId: UUID,
    val runId: UUID? = null,
    val itemId: UUID? = null,
    val decisionClass: String,
    val question: String,
    val options: List<DecisionRequestOption>,
    val recommended: String? = null,
    val links: List<RunSeatLink> = emptyList(),
    val context: String? = null,
    val dedupeKey: String,
    val severity: DecisionRequestSeverity? = null,
    val raisedById: String,
    val raisedByKind: String,
    val raisedBySeat: String? = null,
    val id: UUID? = null
)

/** The request a raise returns, and whether it already existed ([existing] true: a re-raise matched it). */
data class DecisionRaiseResult(
    val decision: DecisionRequest,
    val existing: Boolean
)

/**
 * Answers a decision request with an [option] key and/or free [text] (freeform classes only), and a [rationale].
 * [amount] is the extend amount of a `run-budget` `extend` answer (R27), in the breached dimension: tokens or US dollars.
 */
data class DecisionAnswerCommand(
    val option: String? = null,
    val text: String? = null,
    val rationale: String? = null,
    val amount: Double? = null
)

package io.github.jpicklyk.mcptask.current.run

import io.github.jpicklyk.mcptask.current.config.DecisionClassConfig
import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.EntityKind
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.error.FieldViolation
import io.github.jpicklyk.mcptask.current.model.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.event.DomainEvent
import io.github.jpicklyk.mcptask.current.model.run.DecisionAnswerBasis
import io.github.jpicklyk.mcptask.current.model.run.DecisionDecidedBy
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequest
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestAnswer
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.model.run.DecisionWaitingOn
import io.github.jpicklyk.mcptask.current.model.run.RunSeatLink
import io.github.jpicklyk.mcptask.current.model.run.RunLimitDecisions
import io.github.jpicklyk.mcptask.current.model.run.RunSeat
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionClass
import io.github.jpicklyk.mcptask.current.run.internal.app.DecisionClasses
import io.github.jpicklyk.mcptask.current.run.internal.app.ServerDecision
import io.github.jpicklyk.mcptask.current.run.internal.app.ServerDecisionRaiser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The single owner of decision-request writes and decision events (plan section 4.1, R24). Every operation is ONE
 * [UnitOfWork.write] unit (it joins an ambient one) that records its `decision.*` event; an [Outcome.Err] rolls the
 * whole unit back.
 *
 * ## Lifecycle
 *
 * [raise] creates an OPEN request, or returns the matching non-withdrawn one (`existing`). [acknowledge] moves OPEN to
 * ACKNOWLEDGED (an acknowledged request is returned unchanged). [answer] moves OPEN or ACKNOWLEDGED to ANSWERED, once
 * and for good. [withdraw] moves OPEN or ACKNOWLEDGED to WITHDRAWN. Acknowledging, answering or withdrawing an
 * ANSWERED or WITHDRAWN request is `decision_closed`. Every write bumps the version.
 *
 * [delegate] and [escalate] route a pending request ([DecisionRequest.waitingOn] director or user) without changing its
 * status; a repeat of the same routing is returned unchanged (no event). An answer or a withdrawal clears the routing.
 * An answer records its provenance, derived from the caller's seat and credential kind (never the serving profile):
 * who decided ([DecisionDecidedBy]) and why ([DecisionAnswerBasis]); a seat answer records none.
 *
 * ## Who
 *
 * Acknowledge and answer need the request's answerers to admit the caller ([admits]); withdraw needs the raiser (same
 * principal and seat) or the run token of the request's run, and a server-raised request (`run-budget`,
 * `attempt-cap`) cannot be withdrawn at all: it stays pending until answered (a run close withdraws no decision). A
 * refusal is `forbidden` (`principal_kind`), checked before the status. The caller has already checked that the request's root is in scope.
 *
 * ## Raise
 *
 * The caller resolves the root, run and item, checks the scope and that they agree. Then, in order: the item must
 * have a lifecycle (`invalid_request /itemId`); the class must exist (`invalid_request /class`, naming the classes)
 * and, unless [raise] is called with `serverRaised`, must not be server-raised; the fields must be within their limits
 * and the payload within [DecisionRequest.MAX_PAYLOAD_BYTES] (`payload_too_large`). A request with the same (root,
 * scope key, dedupe key) that is not withdrawn is returned when its class, question and option keys match, else the
 * raise is `idempotency_mismatch` naming the dedupe key.
 *
 * [effects] runs inside each answer's unit (server-class effects, R27); an error it returns refuses the answer. As a
 * [RunDecisionPort] the service counts a run's pending requests for `run.ended`; as a [ServerDecisionRaiser] it raises
 * the server-raised classes for the run limits (R27).
 */
class DecisionService(
    private val repositoryProvider: RunStoreProvider,
    private val unitOfWork: UnitOfWork<RunStoreProvider>,
    private val decisionClasses: suspend (UUID) -> Map<String, DecisionClassConfig>,
    private val effects: DecisionEffects = DecisionEffects.NONE
) : RunDecisionPort,
    ServerDecisionRaiser {
    private val store: DecisionRequestStore get() = repositoryProvider.decisionRequestStore()
    private val items get() = repositoryProvider.workItemRepository()

    /** The effective decision classes of root [rootId]: the built-ins with the configured classes merged over them. */
    suspend fun classes(rootId: UUID): Map<String, DecisionClass> = DecisionClasses.resolve(decisionClasses(rootId))

    // -------------------------------------------------------------------------
    // raise
    // -------------------------------------------------------------------------

    /** Raises a request (see the class rules). [serverRaised] true lets a server-raised class through (R27). */
    suspend fun raise(
        command: DecisionRaiseCommand,
        serverRaised: Boolean = false
    ): Outcome<DecisionRaiseResult> =
        unitOfWork.write(OP_RAISE) {
            if (command.itemId != null) {
                val item = items.getById(command.itemId) ?: return@write err(itemNotFound(command.itemId))
                if (item.role ==
                    null
                ) {
                    return@write err(invalid(FIELD_ITEM_ID, "has no lifecycle; a decision needs an item with one", command.itemId))
                }
            }

            val classes = classes(command.rootId)
            val decisionClass =
                classes[command.decisionClass]
                    ?: return@write err(
                        invalid(FIELD_CLASS, "must be one of: ${classes.keys.sorted().joinToString(", ")}", command.decisionClass)
                    )
            if (decisionClass.serverRaised && !serverRaised) {
                return@write err(invalid(FIELD_CLASS, "is server-raised; it cannot be raised by a caller", command.decisionClass))
            }

            val violations = raiseViolations(command)
            if (violations.isNotEmpty()) return@write err(invalidFields(violations, "Some decision fields are invalid"))
            val bytes = payloadBytes(command)
            if (bytes > DecisionRequest.MAX_PAYLOAD_BYTES) return@write err(payloadTooLarge(bytes))

            val scopeKey = command.itemId?.toString() ?: command.runId?.toString() ?: DecisionRequest.SCOPE_ROOT
            val existing = store.findByKey(command.rootId, scopeKey, command.dedupeKey)
            if (existing != null) {
                if (!sameRequest(existing, command)) return@write err(dedupeMismatch(command.dedupeKey))
                return@write Outcome.Ok(DecisionRaiseResult(existing, existing = true))
            }

            val decision =
                DecisionRequest(
                    id = command.id ?: UUID.randomUUID(),
                    rootId = command.rootId,
                    runId = command.runId,
                    itemId = command.itemId,
                    decisionClass = decisionClass.name,
                    answerers = decisionClass.answerers,
                    severity = command.severity ?: decisionClass.severity,
                    dedupeKey = command.dedupeKey,
                    scopeKey = scopeKey,
                    question = command.question,
                    options = command.options,
                    recommended = command.recommended,
                    links = command.links,
                    context = command.context,
                    raisedById = command.raisedById,
                    raisedByKind = command.raisedByKind,
                    raisedBySeat = command.raisedBySeat,
                    raisedAt = now,
                    expiresAt = decisionClass.expiresAfterSeconds?.let { now.plusSeconds(it) },
                    onExpiry = decisionClass.onExpiry
                )
            val stored = store.create(decision)
            events.record(
                DomainEvent.DecisionRaised(
                    stored.id,
                    stored.rootId,
                    stored.decisionClass,
                    stored.severity,
                    stored.runId,
                    stored.itemId,
                    stored.dedupeKey
                )
            )
            Outcome.Ok(DecisionRaiseResult(stored, existing = false))
        }

    // -------------------------------------------------------------------------
    // acknowledge
    // -------------------------------------------------------------------------

    /** Acknowledges request [decisionId] for [caller]; an acknowledged request is returned unchanged (no event). */
    suspend fun acknowledge(
        decisionId: UUID,
        caller: DecisionCaller
    ): Outcome<DecisionRequest> =
        unitOfWork.write(OP_ACK) {
            val decision = store.getById(decisionId) ?: return@write err(decisionNotFound(decisionId))
            if (!decision.answerers.admits(caller)) return@write err(refuse(decision.answerers.required()))
            when (decision.status) {
                DecisionRequestStatus.ACKNOWLEDGED -> return@write Outcome.Ok(decision)
                DecisionRequestStatus.ANSWERED, DecisionRequestStatus.WITHDRAWN -> return@write err(decisionClosed(decision))
                DecisionRequestStatus.OPEN -> Unit
            }
            val changed =
                decision.copy(
                    status = DecisionRequestStatus.ACKNOWLEDGED,
                    ackById = caller.principalId,
                    ackByKind = caller.kindWire,
                    ackAt = now
                )
            val stored = store.update(changed, decision.version) ?: return@write err(conflictAfterRead(decision))
            events.record(
                DomainEvent.DecisionAcknowledged(
                    stored.id,
                    stored.rootId,
                    stored.decisionClass,
                    stored.severity,
                    stored.runId,
                    stored.itemId,
                    stored.dedupeKey
                )
            )
            Outcome.Ok(stored)
        }

    // -------------------------------------------------------------------------
    // delegate and escalate
    // -------------------------------------------------------------------------

    /**
     * Routes pending request [decisionId] to the director (`waitingOn` director, no reason). The caller must be admitted
     * by the request's answerers and the class must let the director answer (a human-only class cannot be delegated:
     * `invalid_request /decisionId`). Already delegated: returned unchanged, no event.
     */
    suspend fun delegate(
        decisionId: UUID,
        caller: DecisionCaller
    ): Outcome<DecisionRequest> =
        unitOfWork.write(OP_DELEGATE) {
            val decision = store.getById(decisionId) ?: return@write err(decisionNotFound(decisionId))
            if (!decision.answerers.admits(caller)) return@write err(refuse(decision.answerers.required()))
            if (!decision.status.isPending) return@write err(decisionClosed(decision))
            if (decision.answerers == DecisionRequestAnswerers.HUMAN_ONLY) {
                return@write err(
                    invalid(FIELD_DECISION_ID, "is human-only; the director could not answer it, so it cannot be delegated", decisionId)
                )
            }
            if (decision.waitingOn == DecisionWaitingOn.DIRECTOR) return@write Outcome.Ok(decision)
            val stored =
                store.update(decision.copy(waitingOn = DecisionWaitingOn.DIRECTOR, waitingReason = null), decision.version)
                    ?: return@write err(conflictAfterRead(decision))
            events.record(
                DomainEvent.DecisionDelegated(
                    stored.id,
                    stored.rootId,
                    stored.decisionClass,
                    stored.severity,
                    stored.runId,
                    stored.itemId,
                    stored.dedupeKey,
                    DecisionWaitingOn.DIRECTOR.wire
                )
            )
            Outcome.Ok(stored)
        }

    /**
     * Routes pending request [decisionId] to the user (`waitingOn` user) with [reason]. The caller must be admitted by
     * the request's answerers, or be the director (it may hand a human-only decision to the user). The same routing with
     * the same reason is returned unchanged, no event; a different reason updates it.
     */
    suspend fun escalate(
        decisionId: UUID,
        reason: String,
        caller: DecisionCaller
    ): Outcome<DecisionRequest> =
        unitOfWork.write(OP_ESCALATE) {
            val decision = store.getById(decisionId) ?: return@write err(decisionNotFound(decisionId))
            if (!decision.answerers.admits(caller) && !caller.isDirector()) return@write err(refuse(decision.answerers.required()))
            if (!decision.status.isPending) return@write err(decisionClosed(decision))
            if (reason.isBlank() || reason.length > DecisionRequest.MAX_WAITING_REASON_LENGTH) {
                return@write err(
                    invalid(FIELD_REASON, "must be 1 to ${DecisionRequest.MAX_WAITING_REASON_LENGTH} characters", reason.length)
                )
            }
            if (decision.waitingOn == DecisionWaitingOn.USER && decision.waitingReason == reason) return@write Outcome.Ok(decision)
            val stored =
                store.update(decision.copy(waitingOn = DecisionWaitingOn.USER, waitingReason = reason), decision.version)
                    ?: return@write err(conflictAfterRead(decision))
            events.record(
                DomainEvent.DecisionEscalated(
                    stored.id,
                    stored.rootId,
                    stored.decisionClass,
                    stored.severity,
                    stored.runId,
                    stored.itemId,
                    stored.dedupeKey,
                    DecisionWaitingOn.USER.wire
                )
            )
            Outcome.Ok(stored)
        }

    // -------------------------------------------------------------------------
    // answer
    // -------------------------------------------------------------------------

    /**
     * Answers request [decisionId] for [caller] with [command]. The option must be a key of the request's options; free
     * text is accepted only for a freeform class (resolved now; a class no longer configured is not freeform), which
     * needs an option or a text, while any other class needs an option. The answer records the caller's provenance;
     * a caller without one is `unauthenticated`. Provenance is derived ([derivedProvenance]); an answer clears the routing.
     */
    suspend fun answer(
        decisionId: UUID,
        command: DecisionAnswerCommand,
        caller: DecisionCaller
    ): Outcome<DecisionRequest> =
        unitOfWork.write(OP_ANSWER) {
            val decision = store.getById(decisionId) ?: return@write err(decisionNotFound(decisionId))
            if (!decision.answerers.admits(caller)) return@write err(refuse(decision.answerers.required()))
            if (!decision.status.isPending) return@write err(decisionClosed(decision))

            val freeform = classes(decision.rootId)[decision.decisionClass]?.freeform == true
            val violations = answerViolations(decision, command, freeform)
            if (violations.isNotEmpty()) return@write err(invalidFields(violations, "The answer is invalid"))
            val decided =
                when (val derived = derivedProvenance(decision, command, caller)) {
                    is Derivation.Refused -> return@write err(invalidFields(listOf(derived.violation), "The answer is invalid"))
                    is Derivation.Decided -> derived
                }
            val provenance = caller.provenance ?: return@write err(unauthenticated())

            val answeredAt = now
            val answer =
                DecisionRequestAnswer(
                    option = command.option,
                    text = command.text,
                    rationale = command.rationale,
                    answeredById = provenance.principalId,
                    answeredByKind = provenance.kind.wire,
                    answeredByProof = provenance.proofStatus.wire,
                    host = provenance.host,
                    sessionId = provenance.sessionId,
                    runId = provenance.runId,
                    seat = provenance.seat,
                    profile = provenance.profile,
                    answeredAt = answeredAt,
                    afterExpiry = decision.expiresAt != null && !answeredAt.isBefore(decision.expiresAt),
                    decidedBy = decided.decidedBy,
                    basis = decided.basis,
                    relayed = decided.relayed,
                    withinAutonomy = decided.withinAutonomy,
                    links = command.links
                )
            val changed =
                decision.copy(status = DecisionRequestStatus.ANSWERED, answer = answer, waitingOn = null, waitingReason = null)
            val stored = store.update(changed, decision.version) ?: return@write err(conflictAfterRead(decision))
            effects.onAnsweredOrRefuse(stored)?.let { return@write err(it) }
            events.record(
                DomainEvent.DecisionAnswered(
                    stored.id,
                    stored.rootId,
                    stored.decisionClass,
                    stored.severity,
                    stored.runId,
                    stored.itemId,
                    stored.dedupeKey,
                    option = answer.option,
                    afterExpiry = answer.afterExpiry,
                    kind = answer.answeredByKind,
                    proofStatus = answer.answeredByProof,
                    profile = answer.profile,
                    decidedBy = answer.decidedBy?.wire,
                    basis = answer.basis?.wire,
                    relayed = answer.relayed,
                    withinAutonomy = answer.withinAutonomy
                )
            )
            Outcome.Ok(stored)
        }

    // -------------------------------------------------------------------------
    // withdraw
    // -------------------------------------------------------------------------

    /**
     * Withdraws request [decisionId]: its raiser or the run token of its run, while it is pending. A
     * server-raised request is never withdrawable.
     */
    suspend fun withdraw(
        decisionId: UUID,
        caller: DecisionCaller
    ): Outcome<DecisionRequest> =
        unitOfWork.write(OP_WITHDRAW) {
            val decision = store.getById(decisionId) ?: return@write err(decisionNotFound(decisionId))
            if (!mayWithdraw(decision, caller)) return@write err(refuse(WITHDRAW_REQUIRED))
            if (!decision.status.isPending) return@write err(decisionClosed(decision))
            val withdrawn = decision.copy(status = DecisionRequestStatus.WITHDRAWN, waitingOn = null, waitingReason = null)
            val stored = store.update(withdrawn, decision.version) ?: return@write err(conflictAfterRead(decision))
            events.record(
                DomainEvent.DecisionWithdrawn(
                    stored.id,
                    stored.rootId,
                    stored.decisionClass,
                    stored.severity,
                    stored.runId,
                    stored.itemId,
                    stored.dedupeKey
                )
            )
            Outcome.Ok(stored)
        }

    // -------------------------------------------------------------------------
    // ServerDecisionRaiser
    // -------------------------------------------------------------------------

    /** Raises [decision] as the server ([raise] with `serverRaised`), recorded as raised by `server`. */
    override suspend fun raiseServer(decision: ServerDecision): Outcome<DecisionRequest> =
        when (
            val raised =
                raise(
                    DecisionRaiseCommand(
                        id = decision.id,
                        rootId = decision.rootId,
                        runId = decision.runId,
                        itemId = decision.itemId,
                        decisionClass = decision.decisionClass,
                        question = decision.question,
                        options = decision.options,
                        recommended = decision.recommended,
                        context = decision.context,
                        dedupeKey = decision.dedupeKey,
                        raisedById = RunLimitDecisions.SERVER_RAISER,
                        raisedByKind = RunLimitDecisions.SERVER_RAISER
                    ),
                    serverRaised = true
                )
        ) {
            is Outcome.Err -> raised
            is Outcome.Ok -> Outcome.Ok(raised.value.decision)
        }

    // -------------------------------------------------------------------------
    // RunDecisionPort
    // -------------------------------------------------------------------------

    /**
     * The pending (open or acknowledged) requests of [runId]. A provider that serves no decision store (its
     * `decisionRequestStore()` throws [UnsupportedOperationException], as the interface default does) has none.
     */
    override suspend fun openCount(runId: UUID): Int = RunDecisionPort.over(repositoryProvider).openCount(runId)

    // -------------------------------------------------------------------------
    // Checks
    // -------------------------------------------------------------------------

    /** What an answer records about who decided: [Decided] (every member null for a seat answer), or a [Refused] claim. */
    private sealed interface Derivation {
        data class Decided(
            val decidedBy: DecisionDecidedBy?,
            val basis: DecisionAnswerBasis?,
            val relayed: Boolean?,
            val withinAutonomy: Boolean?
        ) : Derivation

        data class Refused(
            val violation: FieldViolation
        ) : Derivation
    }

    /**
     * The decision provenance of an answer by [caller], derived from its seat and credential kind and the request's
     * routing, never from the serving profile (the MCP and REST rows must match). A human decided as the user; a seat
     * records none; any other caller is the director (it is not the user), deciding within its autonomy, or by
     * delegation when the request waits on the director, unless it says it relayed the user's decision. A claim the
     * credential contradicts is refused, and while the request waits on the user only the user may answer it.
     */
    private fun derivedProvenance(
        decision: DecisionRequest,
        command: DecisionAnswerCommand,
        caller: DecisionCaller
    ): Derivation {
        val claimed = command.decidedBy
        val derived =
            when {
                caller.isHuman() -> {
                    if (claimed == DecisionDecidedBy.DIRECTOR) return refusedClaim("a human answers as the user")
                    Derivation.Decided(DecisionDecidedBy.USER, null, relayed = false, withinAutonomy = null)
                }
                caller.seat != null -> {
                    if (claimed != null) return refusedClaim("a seat answer records no decidedBy")
                    Derivation.Decided(null, null, null, null)
                }
                claimed == DecisionDecidedBy.USER ->
                    Derivation.Decided(DecisionDecidedBy.USER, DecisionAnswerBasis.RELAYED, relayed = true, withinAutonomy = null)
                else -> {
                    val within = withinAutonomy(decision)
                    Derivation.Decided(
                        DecisionDecidedBy.DIRECTOR,
                        if (within) DecisionAnswerBasis.DELEGATION else DecisionAnswerBasis.AUTONOMY,
                        relayed = false,
                        withinAutonomy = within
                    )
                }
            }
        if (decision.waitingOn == DecisionWaitingOn.USER && derived.decidedBy != DecisionDecidedBy.USER) {
            return refusedClaim("the request waits on the user; delegate it to the director first, or answer as the user")
        }
        return derived
    }

    private fun refusedClaim(reason: String): Derivation.Refused = Derivation.Refused(FieldViolation(FIELD_DECIDED_BY, reason))

    /** Whether the director answers [decision] within its autonomy: today, when the request was delegated to it. */
    private fun withinAutonomy(decision: DecisionRequest): Boolean = decision.waitingOn == DecisionWaitingOn.DIRECTOR

    private fun mayWithdraw(
        decision: DecisionRequest,
        caller: DecisionCaller
    ): Boolean {
        // A server-raised request (run-budget, attempt-cap) holds its run paused; no caller withdraws it (R27).
        if (isServerRaised(decision)) return false
        val raiser = caller.principalId == decision.raisedById && caller.seat == decision.raisedBySeat
        val orchestrator = caller.seat == RunSeat.ORCHESTRATOR && decision.runId != null && caller.runId == decision.runId
        return raiser || orchestrator
    }

    /** True for a request the server raised: recorded as raised by `server`, or of a built-in server-raised class. */
    private fun isServerRaised(decision: DecisionRequest): Boolean =
        decision.raisedByKind == RunLimitDecisions.SERVER_RAISER ||
            DecisionClasses.BUILT_IN[decision.decisionClass]?.serverRaised == true

    /** The field limits of a raise (task-scope D10), as JSON-pointer violations; empty when every field is valid. */
    private fun raiseViolations(command: DecisionRaiseCommand): List<FieldViolation> {
        val out = mutableListOf<FieldViolation>()
        if (command.dedupeKey.isEmpty() || command.dedupeKey.length > DecisionRequest.MAX_DEDUPE_KEY_LENGTH) {
            out +=
                FieldViolation(
                    "/dedupeKey",
                    "must be 1 to ${DecisionRequest.MAX_DEDUPE_KEY_LENGTH} characters",
                    command.dedupeKey.length.toString()
                )
        }
        if (command.question.isBlank() || command.question.length > DecisionRequest.MAX_QUESTION_LENGTH) {
            out +=
                FieldViolation(
                    "/question",
                    "must be 1 to ${DecisionRequest.MAX_QUESTION_LENGTH} characters",
                    command.question.length.toString()
                )
        }
        val options = command.options
        if (options.isEmpty() || options.size > DecisionRequest.MAX_OPTIONS) {
            out += FieldViolation("/options", "must hold 1 to ${DecisionRequest.MAX_OPTIONS} options", options.size.toString())
        }
        val seen = HashSet<String>()
        options.forEachIndexed { i, option ->
            if (!DecisionRequest.OPTION_KEY.matches(option.key)) {
                out += FieldViolation("/options/$i/key", "must match ${DecisionRequest.OPTION_KEY.pattern}", option.key.take(TRUNCATE))
            } else if (!seen.add(option.key)) {
                out += FieldViolation("/options/$i/key", "duplicates an earlier option key", option.key)
            }
            if (option.label.isBlank() || option.label.length > DecisionRequest.MAX_OPTION_LABEL_LENGTH) {
                out +=
                    FieldViolation(
                        "/options/$i/label",
                        "must be 1 to ${DecisionRequest.MAX_OPTION_LABEL_LENGTH} characters",
                        option.label.length.toString()
                    )
            }
            val consequence = option.consequence
            if (consequence != null && consequence.length > DecisionRequest.MAX_OPTION_CONSEQUENCE_LENGTH) {
                out +=
                    FieldViolation(
                        "/options/$i/consequence",
                        "must be at most ${DecisionRequest.MAX_OPTION_CONSEQUENCE_LENGTH} characters",
                        consequence.length.toString()
                    )
            }
        }
        val recommended = command.recommended
        if (recommended != null && options.none { it.key == recommended }) {
            out += FieldViolation("/recommended", "must be the key of one of the options", recommended.take(TRUNCATE))
        }
        out += linkViolations(command.links)
        val context = command.context
        if (context != null && context.length > DecisionRequest.MAX_CONTEXT_LENGTH) {
            out += FieldViolation("/context", "must be at most ${DecisionRequest.MAX_CONTEXT_LENGTH} characters", context.length.toString())
        }
        return out
    }

    /** The limits of a raise's or an answer's links, as JSON-pointer violations; empty when every link is valid. */
    private fun linkViolations(links: List<RunSeatLink>): List<FieldViolation> {
        val out = mutableListOf<FieldViolation>()
        if (links.size > RunSeat.MAX_LINKS) {
            out += FieldViolation("/links", "at most ${RunSeat.MAX_LINKS} links", links.size.toString())
        }
        links.forEachIndexed { i, link ->
            if (link.kind !in RunSeat.LINK_KINDS) {
                out +=
                    FieldViolation("/links/$i/kind", "must be one of: ${RunSeat.LINK_KINDS.joinToString(", ")}", link.kind.take(TRUNCATE))
            }
            if (link.ref.isBlank() || link.ref.length > RunSeat.MAX_LINK_REF_LENGTH) {
                out += FieldViolation("/links/$i/ref", "must be 1 to ${RunSeat.MAX_LINK_REF_LENGTH} characters", link.ref.take(TRUNCATE))
            }
        }
        return out
    }

    private fun answerViolations(
        decision: DecisionRequest,
        command: DecisionAnswerCommand,
        freeform: Boolean
    ): List<FieldViolation> {
        val out = mutableListOf<FieldViolation>()
        val option = command.option
        val text = command.text
        if (option != null && decision.options.none { it.key == option }) {
            out += FieldViolation("/option", "must be one of: ${decision.options.joinToString(", ") { it.key }}", option.take(TRUNCATE))
        }
        if (text != null) {
            if (!freeform) {
                out += FieldViolation("/text", "class is not freeform; answer with an option", text.length.toString())
            } else if (text.isBlank() || text.length > DecisionRequest.MAX_TEXT_LENGTH) {
                out += FieldViolation("/text", "must be 1 to ${DecisionRequest.MAX_TEXT_LENGTH} characters", text.length.toString())
            }
        }
        if (option == null) {
            if (!freeform) {
                out += FieldViolation("/option", "is required")
            } else if (text == null) {
                out += FieldViolation("/option", "an option or a text is required")
            }
        }
        val rationale = command.rationale
        if (rationale != null && rationale.length > DecisionRequest.MAX_RATIONALE_LENGTH) {
            out +=
                FieldViolation(
                    "/rationale",
                    "must be at most ${DecisionRequest.MAX_RATIONALE_LENGTH} characters",
                    rationale.length.toString()
                )
        }
        out += linkViolations(command.links)
        return out
    }

    /** Whether [existing] is the same request [command] raises: the same class, question and option keys in order. */
    private fun sameRequest(
        existing: DecisionRequest,
        command: DecisionRaiseCommand
    ): Boolean =
        existing.decisionClass == command.decisionClass &&
            existing.question == command.question &&
            existing.options.map { it.key } == command.options.map { it.key }

    /** The conflict after a read found [decision]: it moved under us (`version_conflict`) or vanished (`not_found`). */
    private suspend fun conflictAfterRead(decision: DecisionRequest): DomainError {
        val current = store.getById(decision.id) ?: return decisionNotFound(decision.id)
        if (current.version == decision.version) return decisionNotFound(decision.id)
        return DomainError(
            code = ErrorCode.VERSION_CONFLICT,
            message = "Decision ${decision.id} is at version ${current.version}, not ${decision.version}",
            detail = ErrorDetail.VersionConflict(EntityKind.DECISION, decision.id.toString(), decision.version, current.version),
            fixArgs = mapOf("kind" to "decision", "id" to decision.id.toString(), "actual" to current.version.toString())
        )
    }

    private fun <T> err(error: DomainError): Outcome<T> = Outcome.Err(error)

    companion object {
        private const val OP_RAISE = "DecisionService.raise"
        private const val OP_ACK = "DecisionService.acknowledge"
        private const val OP_ANSWER = "DecisionService.answer"
        private const val OP_WITHDRAW = "DecisionService.withdraw"
        private const val OP_DELEGATE = "DecisionService.delegate"
        private const val OP_ESCALATE = "DecisionService.escalate"

        private const val FIELD_ITEM_ID = "/itemId"
        private const val FIELD_CLASS = "/class"
        private const val FIELD_DECISION_ID = "/decisionId"
        private const val FIELD_REASON = "/reason"
        private const val FIELD_DECIDED_BY = "/decidedBy"

        /** How much of an offending value a violation echoes back. */
        private const val TRUNCATE = 100

        /** The principal kinds that may withdraw a request. */
        const val WITHDRAW_REQUIRED = "coordinator,raiser"

        /**
         * The size, in UTF-8 bytes, of the compact JSON of the request payload `{question, options, recommended,
         * links, context}` (absent members left out), the measure [DecisionRequest.MAX_PAYLOAD_BYTES] caps.
         */
        fun payloadBytes(command: DecisionRaiseCommand): Int {
            val json =
                buildJsonObject {
                    put("question", command.question)
                    put(
                        "options",
                        JsonArray(
                            command.options.map { option ->
                                buildJsonObject {
                                    put("key", option.key)
                                    put("label", option.label)
                                    option.consequence?.let { put("consequence", it) }
                                }
                            }
                        )
                    )
                    command.recommended?.let { put("recommended", it) }
                    if (command.links.isNotEmpty()) {
                        put(
                            "links",
                            JsonArray(
                                command.links.map { JsonObject(mapOf("kind" to JsonPrimitive(it.kind), "ref" to JsonPrimitive(it.ref))) }
                            )
                        )
                    }
                    command.context?.let { put("context", it) }
                }
            return json.toString().toByteArray(Charsets.UTF_8).size
        }

        fun decisionNotFound(id: UUID): DomainError =
            DomainError(
                code = ErrorCode.NOT_FOUND,
                message = "No decision matches $id.",
                detail = ErrorDetail.NotFound(EntityKind.DECISION, id.toString()),
                fixArgs = mapOf("kind" to "decision", "id" to id.toString())
            )

        fun decisionClosed(decision: DecisionRequest): DomainError =
            DomainError(
                code = ErrorCode.DECISION_CLOSED,
                message = "Decision ${decision.id} is ${decision.status.wire}",
                detail = ErrorDetail.DecisionClosed(decision.id, decision.status.wire),
                fixArgs = mapOf("decisionId" to decision.id.toString(), "status" to decision.status.wire)
            )

        /**
         * The refusal naming the principal kinds [required] that could have acted: the same error ScopeGuard.forbidden
         * builds for the `principal_kind` scope, built here so this package does not depend on ScopeGuard.
         */
        fun refuse(required: String): DomainError =
            DomainError(
                code = ErrorCode.FORBIDDEN,
                message = "The credential does not grant $required for the principal_kind scope.",
                detail = ErrorDetail.Forbidden(ForbiddenScope.PRINCIPAL_KIND, required),
                fixArgs = mapOf("scope" to "principal_kind", "required" to required)
            )

        private fun itemNotFound(id: UUID): DomainError =
            DomainError(
                code = ErrorCode.NOT_FOUND,
                message = "WorkItem not found with id: $id",
                detail = ErrorDetail.NotFound(EntityKind.ITEM, id.toString()),
                fixArgs = mapOf("kind" to "item", "id" to id.toString())
            )

        private fun dedupeMismatch(dedupeKey: String): DomainError =
            DomainError(
                code = ErrorCode.IDEMPOTENCY_MISMATCH,
                message = "The dedupe key was already used for a different decision (class, question or options differ)",
                detail = ErrorDetail.IdempotencyMismatch(dedupeKey),
                fixArgs = mapOf("idempotencyKey" to dedupeKey)
            )

        private fun payloadTooLarge(actual: Int): DomainError =
            DomainError(
                code = ErrorCode.PAYLOAD_TOO_LARGE,
                message = "The decision payload is $actual bytes, over the ${DecisionRequest.MAX_PAYLOAD_BYTES} byte cap",
                detail = ErrorDetail.PayloadTooLarge(DecisionRequest.MAX_PAYLOAD_BYTES.toLong(), actual.toLong()),
                fixArgs = mapOf("max" to DecisionRequest.MAX_PAYLOAD_BYTES.toString())
            )

        private fun unauthenticated(): DomainError =
            DomainError(ErrorCode.UNAUTHENTICATED, "An answer needs a credential-established caller")

        private fun invalid(
            field: String,
            reason: String,
            received: Any?
        ): DomainError = invalidFields(listOf(FieldViolation(field, reason, received?.toString())), "Invalid $field: $reason")

        private fun invalidFields(
            violations: List<FieldViolation>,
            message: String
        ): DomainError =
            DomainError(
                code = ErrorCode.INVALID_REQUEST,
                message = message,
                detail = ErrorDetail.InvalidRequest(violations)
            )
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The wire views of a decision request (task-scope D8). Absent members are omitted, never null.
 *
 * Provenance redaction: a request stores who raised, acknowledged and answered it, and the answer's host and session;
 * those principal ids, the host and the session id appear only when the reader may see principals
 * (`principalsVisible`, ADMIN), and only in [full]. Every view shows the kinds, proofs, seats and profile.
 */
internal object DecisionView {
    /** How much of the question a summary shows. */
    const val SUMMARY_QUESTION_LENGTH = 120

    /** One row of `runs_read decisions`. */
    fun summary(
        decision: DecisionRequest,
        now: Instant
    ): JsonObject =
        buildJsonObject {
            put("id", decision.id.toString())
            put("class", decision.decisionClass)
            put("severity", decision.severity.wire)
            put("effectiveSeverity", decision.effectiveSeverity(now).wire)
            put("status", decision.status.wire)
            put("overdue", decision.isOverdue(now))
            put("question", decision.question.take(SUMMARY_QUESTION_LENGTH))
            decision.runId?.let { put("runId", it.toString()) }
            decision.itemId?.let { put("itemId", it.toString()) }
            put("raisedAt", decision.raisedAt.toString())
            decision.expiresAt?.let { put("expiresAt", it.toString()) }
        }

    /** The full request: `runs_read decision` and the result of every decision write. */
    fun full(
        decision: DecisionRequest,
        now: Instant,
        principalsVisible: Boolean
    ): JsonObject =
        buildJsonObject {
            put("id", decision.id.toString())
            put("rootId", decision.rootId.toString())
            decision.runId?.let { put("runId", it.toString()) }
            decision.itemId?.let { put("itemId", it.toString()) }
            put("class", decision.decisionClass)
            put("answerers", decision.answerers.wire)
            put("severity", decision.severity.wire)
            put("effectiveSeverity", decision.effectiveSeverity(now).wire)
            put("status", decision.status.wire)
            put("overdue", decision.isOverdue(now))
            put("dedupeKey", decision.dedupeKey)
            put("question", decision.question)
            put(
                "options",
                JsonArray(
                    decision.options.map { option ->
                        buildJsonObject {
                            put("key", option.key)
                            put("label", option.label)
                            option.consequence?.let { put("consequence", it) }
                        }
                    }
                )
            )
            decision.recommended?.let { put("recommended", it) }
            if (decision.links.isNotEmpty()) {
                put(
                    "links",
                    JsonArray(
                        decision.links.map { link ->
                            buildJsonObject {
                                put("kind", link.kind)
                                put("ref", link.ref)
                            }
                        }
                    )
                )
            }
            decision.context?.let { put("context", it) }
            put(
                "raisedBy",
                buildJsonObject {
                    if (principalsVisible) put("id", decision.raisedById)
                    put("kind", decision.raisedByKind)
                    decision.raisedBySeat?.let { put("seat", it) }
                }
            )
            put("raisedAt", decision.raisedAt.toString())
            decision.expiresAt?.let { put("expiresAt", it.toString()) }
            put("onExpiry", decision.onExpiry)
            decision.ackAt?.let { at ->
                put(
                    "ack",
                    buildJsonObject {
                        if (principalsVisible) decision.ackById?.let { put("id", it) }
                        decision.ackByKind?.let { put("kind", it) }
                        put("at", at.toString())
                    }
                )
            }
            decision.answer?.let { answer ->
                put(
                    "answer",
                    buildJsonObject {
                        answer.option?.let { put("option", it) }
                        answer.text?.let { put("text", it) }
                        answer.rationale?.let { put("rationale", it) }
                        put("at", answer.answeredAt.toString())
                        put("afterExpiry", answer.afterExpiry)
                        put(
                            "provenance",
                            buildJsonObject {
                                if (principalsVisible) put("principalId", answer.answeredById)
                                put("kind", answer.answeredByKind)
                                put("proofStatus", answer.answeredByProof)
                                if (principalsVisible) answer.host?.let { put("host", it) }
                                if (principalsVisible) answer.sessionId?.let { put("sessionId", it) }
                                answer.seat?.let { put("seat", it) }
                                answer.runId?.let { put("runId", it.toString()) }
                                answer.profile?.let { put("profile", it) }
                            }
                        )
                    }
                )
            }
            put("version", decision.version)
        }

    /** One entry of `items_read context` `decisions[]`: `{id, class, status, option?}`. */
    fun contextEntry(decision: DecisionRequest): JsonObject =
        buildJsonObject {
            put("id", decision.id.toString())
            put("class", decision.decisionClass)
            put("status", decision.status.wire)
            decision.answer?.option?.let { put("option", it) }
        }
}

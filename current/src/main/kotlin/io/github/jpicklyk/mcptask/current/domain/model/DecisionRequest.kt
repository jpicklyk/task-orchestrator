package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/** Where a decision request is: [OPEN] and [ACKNOWLEDGED] wait for an answer; [ANSWERED] and [WITHDRAWN] are final. */
enum class DecisionRequestStatus(
    val wire: String
) {
    OPEN("open"),
    ACKNOWLEDGED("acknowledged"),
    ANSWERED("answered"),
    WITHDRAWN("withdrawn");

    /** True for a status that still waits for an answer (open or acknowledged). */
    val isPending: Boolean get() = this == OPEN || this == ACKNOWLEDGED

    companion object {
        fun fromWire(value: String): DecisionRequestStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** How urgent a decision request is. [escalated] is one step up (an overdue decision reads one step more urgent). */
enum class DecisionRequestSeverity(
    val wire: String
) {
    HIGH("high"),
    NORMAL("normal"),
    LOW("low");

    /** One step up: low to normal, normal to high, high stays high. */
    fun escalated(): DecisionRequestSeverity =
        when (this) {
            LOW -> NORMAL
            NORMAL -> HIGH
            HIGH -> HIGH
        }

    companion object {
        fun fromWire(value: String): DecisionRequestSeverity? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Who may answer (and acknowledge) a decision request (plan section 3.3). [HUMAN_ONLY]: a human credential without a
 * seat token and with a real proof; [DIRECTOR_OR_HUMAN]: that, or a call served by the director profile without a seat
 * token; [ANY_ACTOR]: every principal. The predicate itself lives with the caller type in the application layer.
 */
enum class DecisionRequestAnswerers(
    val wire: String
) {
    HUMAN_ONLY("human-only"),
    DIRECTOR_OR_HUMAN("director-or-human"),
    ANY_ACTOR("any-actor");

    companion object {
        fun fromWire(value: String): DecisionRequestAnswerers? = entries.firstOrNull { it.wire == value }
    }
}

/** One choice of a decision request: [key] (`^[a-z][a-z0-9_-]{0,31}$`), [label] and what choosing it means. */
data class DecisionRequestOption(
    val key: String,
    val label: String,
    val consequence: String? = null
)

/**
 * The recorded answer of a decision request and its provenance: the chosen [option] and/or free [text] (freeform
 * classes only), the [rationale], who answered ([answeredById], [answeredByKind], [answeredByProof]), through what
 * ([host], [sessionId], [runId], [seat], [profile]), when, and whether it came at or after the expiry ([afterExpiry]).
 * [answeredById], [host] and [sessionId] are shown to an ADMIN reader only.
 */
data class DecisionRequestAnswer(
    val option: String? = null,
    val text: String? = null,
    val rationale: String? = null,
    val answeredById: String,
    val answeredByKind: String,
    val answeredByProof: String,
    val host: String? = null,
    val sessionId: String? = null,
    val runId: UUID? = null,
    val seat: String? = null,
    val profile: String? = null,
    val answeredAt: Instant,
    val afterExpiry: Boolean = false
)

/**
 * One decision request (`decision_requests`): a bounded question a run, a seat or a coordinator raised under root
 * [rootId], optionally about run [runId] and member [itemId], waiting for an answer from [answerers].
 *
 * [scopeKey] is the item id text when [itemId] is set, else the run id text, else [SCOPE_ROOT]; at most one
 * non-withdrawn request exists per (root, scope key, [dedupeKey]). [answerers] is a snapshot of the class at raise
 * time. [expiresAt] is null when the class sets no expiry. [version] is the optimistic-concurrency counter, 1 at raise.
 */
data class DecisionRequest(
    val id: UUID = UUID.randomUUID(),
    val rootId: UUID,
    val runId: UUID? = null,
    val itemId: UUID? = null,
    val decisionClass: String,
    val answerers: DecisionRequestAnswerers,
    val severity: DecisionRequestSeverity,
    val status: DecisionRequestStatus = DecisionRequestStatus.OPEN,
    val dedupeKey: String,
    val scopeKey: String,
    val question: String,
    val options: List<DecisionRequestOption>,
    val recommended: String? = null,
    val links: List<RunSeatLink> = emptyList(),
    val context: String? = null,
    val raisedById: String,
    val raisedByKind: String,
    val raisedBySeat: String? = null,
    val raisedAt: Instant,
    val expiresAt: Instant? = null,
    val onExpiry: String = ON_EXPIRY_ESCALATE,
    val ackById: String? = null,
    val ackByKind: String? = null,
    val ackAt: Instant? = null,
    val answer: DecisionRequestAnswer? = null,
    val version: Long = 1
) {
    init {
        require((status == DecisionRequestStatus.ANSWERED) == (answer != null)) { "an answered request has an answer, no other has one" }
    }

    /** True when the request still waits for an answer and [now] is at or past its expiry. */
    fun isOverdue(now: Instant): Boolean = status.isPending && expiresAt != null && !now.isBefore(expiresAt)

    /** The severity a reader sees at [now]: one step up while overdue, else the stored severity. */
    fun effectiveSeverity(now: Instant): DecisionRequestSeverity = if (isOverdue(now)) severity.escalated() else severity

    companion object {
        /** The scope key of a request that names neither an item nor a run. */
        const val SCOPE_ROOT = "root"

        /** The only expiry behaviour: an overdue request reads one severity step up. */
        const val ON_EXPIRY_ESCALATE = "escalate"

        const val MAX_CLASS_LENGTH = 64
        const val MAX_DEDUPE_KEY_LENGTH = 128
        const val MAX_QUESTION_LENGTH = 500
        const val MAX_OPTIONS = 8
        const val MAX_OPTION_LABEL_LENGTH = 120
        const val MAX_OPTION_CONSEQUENCE_LENGTH = 300
        const val MAX_CONTEXT_LENGTH = 1000
        const val MAX_TEXT_LENGTH = 1000
        const val MAX_RATIONALE_LENGTH = 1000

        /** The cap, in UTF-8 bytes, of the compact JSON of `{question, options, recommended, links, context}`. */
        const val MAX_PAYLOAD_BYTES = 4096

        /** An option key: a lowercase letter, then up to 31 lowercase letters, digits, `_` or `-`. */
        val OPTION_KEY: Regex = Regex("^[a-z][a-z0-9_-]{0,31}$")

        /** A decision class name: a lowercase letter, then up to 63 lowercase letters, digits, `_` or `-`. */
        val CLASS_NAME: Regex = Regex("^[a-z][a-z0-9_-]{0,63}$")
    }
}

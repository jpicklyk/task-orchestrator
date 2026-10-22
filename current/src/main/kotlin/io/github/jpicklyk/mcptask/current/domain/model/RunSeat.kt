package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/** Whether a seat assignment is still running. */
enum class RunSeatStatus(
    val wire: String
) {
    ACTIVE("active"),
    ENDED("ended");

    companion object {
        fun fromWire(value: String): RunSeatStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * How a seat assignment ended. [DONE], [FAILED] and [ABANDONED] are what a seat reports itself (`end_seat`);
 * [SUPERSEDED] (a re-assignment of the same seat) and [RUN_CLOSED] (the run's close) are server-only.
 */
enum class RunSeatOutcome(
    val wire: String
) {
    DONE("done"),
    FAILED("failed"),
    ABANDONED("abandoned"),
    SUPERSEDED("superseded"),
    RUN_CLOSED("run_closed");

    companion object {
        fun fromWire(value: String): RunSeatOutcome? = entries.firstOrNull { it.wire == value }
    }
}

/** One pointer a seat leaves when it ends: [kind] is one of [RunSeat.LINK_KINDS], [ref] what it points at. */
data class RunSeatLink(
    val kind: String,
    val ref: String
)

/**
 * One seat assignment of a run (`run_seats`): [seat] on member [itemId], attempt [attempt], held by the principal
 * [principalId] that the assignment's token is bound to. The token itself is never kept; [tokenHash] is its lowercase hex
 * SHA-256.
 *
 * The [COORDINATOR] pseudo-seat is the one run-scoped row: its [itemId] is null. [usageTokens] and [usageCostMicro]
 * (micro-USD) are the highest absolute usage the seat reported.
 */
data class RunSeat(
    val id: UUID = UUID.randomUUID(),
    val runId: UUID,
    val itemId: UUID?,
    val seat: String,
    val attempt: Int,
    val principalId: String,
    val tokenHash: String,
    val status: RunSeatStatus = RunSeatStatus.ACTIVE,
    val outcome: RunSeatOutcome? = null,
    val summary: String? = null,
    val links: List<RunSeatLink> = emptyList(),
    val hints: Map<String, String> = emptyMap(),
    val assignedAt: Instant,
    val endedAt: Instant? = null,
    val lastSeenAt: Instant? = null,
    val usageTokens: Long = 0,
    val usageCostMicro: Long = 0
) {
    init {
        require(seat.isNotEmpty() && seat.length <= MAX_SEAT_LENGTH) { "seat must be 1 to $MAX_SEAT_LENGTH characters" }
        require(attempt >= 1) { "attempt must be at least 1" }
        require((itemId == null) == (seat == COORDINATOR)) { "only the coordinator seat is run-scoped (no item)" }
        require((status == RunSeatStatus.ACTIVE) == (outcome == null && endedAt == null)) {
            "an active seat has no outcome or end time, an ended one has both"
        }
        require(summary == null || summary.length <= MAX_SUMMARY_LENGTH) { "summary must be at most $MAX_SUMMARY_LENGTH characters" }
        require(links.size <= MAX_LINKS) { "at most $MAX_LINKS links" }
        require(usageTokens >= 0 && usageCostMicro >= 0) { "usage must not be negative" }
    }

    /** True for the run-scoped coordinator pseudo-seat. */
    val isCoordinator: Boolean get() = seat == COORDINATOR

    val isActive: Boolean get() = status == RunSeatStatus.ACTIVE

    companion object {
        /** The run-scoped pseudo-seat `open` creates; it is never a seat of an item's schema. */
        const val COORDINATOR = "coordinator"

        /** The seats a seat-less (or schema-free) item may be assigned. */
        val DEFAULT_SEATS: Set<String> = setOf("implementer", "planner")

        const val MAX_SEAT_LENGTH = 64
        const val MAX_SUMMARY_LENGTH = 500
        const val MAX_LINKS = 10
        const val MAX_LINK_REF_LENGTH = 200

        /** The kinds a seat link may name. */
        val LINK_KINDS: Set<String> = setOf("item", "note", "doc", "commit", "url")
    }
}

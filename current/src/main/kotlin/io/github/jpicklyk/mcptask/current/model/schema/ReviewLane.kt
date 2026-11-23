package io.github.jpicklyk.mcptask.current.model.schema

/**
 * What a review lane reports (`lane.output` on a review note entry).
 *
 * - [VERDICT] (default) -- the lane's reviewer returns a verdict, and it counts toward the item verdict.
 * - [FINDINGS] -- the lane returns findings only; it never sets or changes the item verdict.
 *
 * @property wire the exact config and wire spelling.
 */
enum class LaneOutput(
    val wire: String
) {
    VERDICT("verdict"),
    FINDINGS("findings");

    companion object {
        /** Parses a config string; exact, case-sensitive match on [wire]. */
        fun fromConfigString(value: String): LaneOutput? = entries.find { it.wire == value }
    }
}

/**
 * A review note entry's declared lane contract (`lane` on a review-role note entry).
 *
 * A lane is a group of review notes sharing a seat and skills; this declares what the group reports and which
 * other review notes' lanes run before it.
 *
 * @property output what the lane reports; see [LaneOutput].
 * @property after Keys of other review notes whose lanes run first (ordering only; never halts or skips). The
 *   server checks shape only: whether a key exists in the effective schema is the consumer's check.
 */
data class ReviewLane(
    val output: LaneOutput = LaneOutput.VERDICT,
    val after: List<String> = emptyList()
)

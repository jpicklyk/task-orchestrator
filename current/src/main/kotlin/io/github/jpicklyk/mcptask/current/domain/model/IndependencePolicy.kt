package io.github.jpicklyk.mcptask.current.domain.model

/**
 * The `independence.mode` enforcement level (A2 -- independence attestation gate).
 *
 * - [OFF] -- the independence predicate is never evaluated; `violations` is absent from every
 *   surface for schemas that would otherwise declare `independent_of`.
 * - [WARN] (default) -- violations are computed and reported, but never block a transition.
 * - [REJECT] -- a non-waived violation blocks `start`/`complete` (and the terminal/start cascade
 *   gates); see [io.github.jpicklyk.mcptask.current.application.service.GatePredicate.blocksAdvance].
 */
enum class IndependenceMode {
    OFF,
    WARN,
    REJECT;

    companion object {
        /** Parses a config string; recognizes only the lowercase names (`off`/`warn`/`reject`). */
        fun fromConfigString(value: String): IndependenceMode? = entries.find { it.name.lowercase() == value }
    }
}

/**
 * Resolved `independence:` config block for one root (A2). See `GlobalConfigLookup`/`LayeredConfig`
 * for layering (per-root wins WHOLESALE over global, mirroring `note_limits`).
 *
 * @property mode enforcement level; default [IndependenceMode.WARN].
 * @property requireVerified when true, a declaring note (or a conflicting seat's note) whose
 *   verification status is not [VerificationStatus.VERIFIED] is also reported as an
 *   [IndependenceConstraint.UNVERIFIED] violation. Default false.
 */
data class IndependencePolicy(
    val mode: IndependenceMode = IndependenceMode.WARN,
    val requireVerified: Boolean = false
) {
    companion object {
        val DEFAULT: IndependencePolicy = IndependencePolicy()
    }
}

/** The kind of independence conflict a single [IndependenceViolation] reports. */
enum class IndependenceConstraint {
    /** A declaring note and a conflicting seat's note share the same actor identity. */
    SAME_ACTOR,

    /** A declaring note, or a conflicting seat's note, has no actor claim at all (fail-closed). */
    MISSING_ACTOR,

    /** `require_verified` is set and a relevant note's verification status is not VERIFIED. */
    UNVERIFIED;

    fun toJsonString(): String = name.lowercase()
}

/**
 * One structured independence-gate finding, actor-free by construction (never an actor id, proof,
 * or claim on any surface, MCP or REST).
 *
 * @property key the declaring note's key (the note whose `independent_of` triggered this check).
 * @property seat the declaring note's owning seat, or null when the schema is not seat-aware.
 * @property constraint which check failed.
 * @property conflictingSeat the seat this violation was raised against, or null for a
 *   [IndependenceConstraint.MISSING_ACTOR]/[IndependenceConstraint.UNVERIFIED] finding raised
 *   against the declaring note itself (not a specific conflicting seat).
 * @property waived true only for a [IndependenceConstraint.SAME_ACTOR] finding covered by the
 *   `independence: temporal-only` waiver (A2-D1); a waived entry never blocks in any mode.
 */
data class IndependenceViolation(
    val key: String,
    val seat: String?,
    val constraint: IndependenceConstraint,
    val conflictingSeat: String?,
    val waived: Boolean = false
)

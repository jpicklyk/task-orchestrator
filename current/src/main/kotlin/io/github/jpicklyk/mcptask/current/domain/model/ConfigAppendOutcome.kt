package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant

/** Outcome of appending a config version, computed in the same unit as the write. */
sealed class ConfigAppendOutcome {
    /** [appended] is false for an identical re-push: the head is returned unchanged and nothing was written. */
    data class Applied(
        val version: ConfigVersion,
        val appended: Boolean
    ) : ConfigAppendOutcome()

    /** The content's fingerprint classified as SUPERSEDED (fast-forward guard); nothing was written. */
    data class Superseded(
        val currentVersion: Int,
        val currentCreatedAt: Instant
    ) : ConfigAppendOutcome()

    /** An expected version / fingerprint did not match the head; nothing was written. */
    data class PreconditionFailed(
        val currentVersion: Int,
        val currentFingerprint: String
    ) : ConfigAppendOutcome()
}

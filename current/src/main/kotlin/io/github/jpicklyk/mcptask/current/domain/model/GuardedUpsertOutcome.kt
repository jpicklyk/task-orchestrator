package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant

/**
 * Outcome of [io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository.upsertGuarded] —
 * the compare-and-set guard evaluation and the write happen inside ONE transaction, so this is
 * always computed from the same row version that either was, or was not, written.
 *
 * Distinct from [io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushResult],
 * which is the transport-facing outcome the push pipeline maps this onto (see
 * [io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService.push]).
 */
sealed interface GuardedUpsertOutcome {
    /** The write succeeded; [config] is the newly stored row. */
    data class Applied(
        val config: ProjectConfig
    ) : GuardedUpsertOutcome

    /**
     * The caller's `expectedFingerprint` did not match the row's fingerprint AT THE POINT the
     * write would have happened (a concurrent-write / If-Match guard) — [currentFingerprint] is
     * the row's actual fingerprint at that point, for a caller-facing mismatch message and a
     * refreshed ETag.
     */
    data class PreconditionFailed(
        val currentFingerprint: String
    ) : GuardedUpsertOutcome

    /**
     * The incoming content's fingerprint classified as
     * [io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation.SUPERSEDED] against the
     * row's fingerprint history AT THE POINT the write would have happened (the fast-forward /
     * known-old guard) — [currentUpdatedAt] is the row's actual `updatedAt` at that point.
     */
    data class Superseded(
        val currentUpdatedAt: Instant
    ) : GuardedUpsertOutcome
}

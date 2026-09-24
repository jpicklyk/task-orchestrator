package io.github.jpicklyk.mcptask.current.domain.repository

import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.GuardedUpsertOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ProjectConfig
import java.util.UUID

/**
 * Persists per-root config YAML documents (see [io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ProjectConfigTable]).
 *
 * One row per root: [upsert] replaces the existing row for a given [UUID] root item rather than
 * inserting a second row (`root_item_id` is unique). Deleting the root item cascades to delete
 * its row (FK `ON DELETE CASCADE`) — [delete] here is for explicit config-only removal.
 */
interface ProjectConfigRepository {
    /**
     * Inserts or replaces the config row for [rootItemId] with [configYaml], computing and
     * storing a SHA-256 fingerprint of its bytes. Returns the stored [ProjectConfig] (with the
     * computed fingerprint and the write timestamp) on success.
     *
     * Unconditional — no compare-and-set guard. Prefer [upsertGuarded] for any caller that needs
     * to evaluate an If-Match / fast-forward guard against the SAME row version it then writes;
     * this method is kept for callers (and existing tests) that genuinely want an unconditional
     * write, and as the plain building block [upsertGuarded] itself is built from.
     */
    suspend fun upsert(
        rootItemId: UUID,
        configYaml: String
    ): Result<ProjectConfig>

    /**
     * Inserts or replaces the config row for [rootItemId] with [configYaml], evaluating the
     * fast-forward (known-old) guard and/or the [expectedFingerprint] compare-and-set guard
     * against the SAME row version the write then applies to — both the guard read and the write
     * happen inside one transaction, closing the read-then-write race a separate guard-read call
     * followed by a separate [upsert] call would leave open.
     *
     * Guard evaluation order (both against the row as read inside this transaction):
     * 1. If [rejectSuperseded] is true and the row exists: classify [configYaml]'s fingerprint
     *    against the row's current fingerprint + history. A [FingerprintRelation.SUPERSEDED]
     *    classification returns [GuardedUpsertOutcome.Superseded] — the row is NOT written.
     * 2. If [expectedFingerprint] is non-null and the row exists: compare it against the row's
     *    current fingerprint. A mismatch returns [GuardedUpsertOutcome.PreconditionFailed] — the
     *    row is NOT written. [expectedFingerprint] is ignored when no row exists yet (a first push
     *    is a create with nothing to compare against).
     * 3. Otherwise the row is written (inserted if absent, updated if present) and
     *    [GuardedUpsertOutcome.Applied] is returned.
     *
     * A transaction that loses a race between its guard read and its write (another writer
     * committed first) is retried internally, bounded, re-evaluating the guards against the
     * winner's row; exhausting the retry budget returns [Result.Error].
     */
    suspend fun upsertGuarded(
        rootItemId: UUID,
        configYaml: String,
        expectedFingerprint: String? = null,
        rejectSuperseded: Boolean = false
    ): Result<GuardedUpsertOutcome>

    /** Returns the full stored config for [rootItemId] (yaml + fingerprint + updatedAt), or null if no row exists. */
    suspend fun get(rootItemId: UUID): Result<ProjectConfig?>

    /**
     * Returns only the stored fingerprint for [rootItemId], or null if no row exists.
     *
     * A cheap companion to [get] that avoids reading the `config_yaml` TEXT column — used by
     * [io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService]'s cache to
     * decide, on every read, whether a re-parse is needed without paying for the full document
     * when the cached fingerprint still matches.
     */
    suspend fun getFingerprint(rootItemId: UUID): Result<String?>

    /** Deletes the config row for [rootItemId], if any. Returns true if a row was deleted. */
    suspend fun delete(rootItemId: UUID): Result<Boolean>

    /**
     * Computes the SHA-256 fingerprint of [configYaml] — the exact algorithm [upsert] uses when
     * persisting. Exposed as a pure, non-persisting method so callers (notably
     * [io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService.push]'s
     * fast-forward guard) can classify an incoming payload's fingerprint against the stored state
     * BEFORE deciding whether to write it, without duplicating the hash algorithm.
     */
    fun computeFingerprint(configYaml: String): String

    /**
     * Classifies [fingerprint] against [rootItemId]'s stored current fingerprint and fingerprint
     * history — see [FingerprintRelation] for the three outcomes. A NULL/absent history (a
     * pre-V11 row, or no row at all for [rootItemId]) classifies any non-current fingerprint as
     * [FingerprintRelation.UNKNOWN] — identical to pre-history behavior until history accumulates
     * on subsequent [upsert] calls.
     */
    suspend fun classifyFingerprint(
        rootItemId: UUID,
        fingerprint: String
    ): Result<FingerprintRelation>
}

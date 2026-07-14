package io.github.jpicklyk.mcptask.current.domain.repository

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
     */
    suspend fun upsert(
        rootItemId: UUID,
        configYaml: String
    ): Result<ProjectConfig>

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
}

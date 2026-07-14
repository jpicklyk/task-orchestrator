package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A stored per-root config document: the raw YAML text for one project root, plus a content
 * fingerprint used to detect changes for hot-reload.
 *
 * @property rootItemId the depth-0 WorkItem this config is scoped to
 * @property configYaml the raw YAML document as written by the caller
 * @property fingerprint SHA-256 hex digest of [configYaml]'s UTF-8 bytes
 * @property updatedAt when this row was last upserted
 */
data class ProjectConfig(
    val rootItemId: UUID,
    val configYaml: String,
    val fingerprint: String,
    val updatedAt: Instant
)

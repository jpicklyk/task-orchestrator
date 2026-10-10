package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/**
 * One immutable stored config version. [canonicalJson] / [modelVersion] are NULL only on rows copied by the
 * V24 migration until the `config-canonicalize` data step fills them (the one in-place write).
 *
 * @property rootId the depth-0 WorkItem this version belongs to; null only for [ConfigScope.SERVER]
 * @property version per-scope sequence number, starting at 1
 * @property fingerprint SHA-256 hex of the BOM-stripped, CRLF-normalized [yaml]
 */
data class ConfigVersion(
    val id: UUID,
    val scope: ConfigScope,
    val rootId: UUID?,
    val version: Int,
    val yaml: String,
    val fingerprint: String,
    val canonicalJson: String?,
    val modelVersion: Int?,
    val source: ConfigVersionSource,
    val principalId: String?,
    val reqId: String?,
    val createdAt: Instant,
    val warnings: List<String>
)

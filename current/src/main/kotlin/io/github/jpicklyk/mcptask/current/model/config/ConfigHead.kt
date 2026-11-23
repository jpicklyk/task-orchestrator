package io.github.jpicklyk.mcptask.current.model.config

import java.util.UUID

/** The current version of one config scope (a lightweight projection of the head's version row). */
data class ConfigHead(
    val scope: ConfigScope,
    val rootId: UUID?,
    val versionId: UUID,
    val version: Int,
    val fingerprint: String,
    val source: ConfigVersionSource
)

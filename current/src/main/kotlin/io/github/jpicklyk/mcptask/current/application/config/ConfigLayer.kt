package io.github.jpicklyk.mcptask.current.application.config

import java.util.UUID

/** Distinguishes which layer a resolved value (schema, fingerprint, ...) came from. */
enum class ConfigSource { PER_ROOT, GLOBAL }

/**
 * A single parsed [document] plus the [fingerprint] it was parsed from and which [source] layer
 * (global file vs. a project root's pushed document) produced it.
 *
 * [fingerprint] is the parse-time fingerprint for the global layer (computed once over the exact
 * bytes read at startup) or the per-root row fingerprint (refreshed on every hot-reload check) for
 * the per-root layer; `null` only when no fingerprint concept applies (never expected in
 * production — both current global and per-root loaders always compute one when a document is
 * present).
 *
 * [versionId] / [version] name the stored `config_versions` row the layer was built from (the head it was read
 * at, or the pinned version it was loaded by id); both null for a layer that is not a stored version (a fixed test
 * document, or a source that does not version its config).
 */
data class ConfigLayer(
    val document: ConfigDocument,
    val fingerprint: String?,
    val source: ConfigSource,
    val versionId: UUID? = null,
    val version: Int? = null,
)

package io.github.jpicklyk.mcptask.current.application.config

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
 */
data class ConfigLayer(
    val document: ConfigDocument,
    val fingerprint: String?,
    val source: ConfigSource,
)

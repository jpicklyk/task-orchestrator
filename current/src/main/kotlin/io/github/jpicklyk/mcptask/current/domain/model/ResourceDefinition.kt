package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Registry entry for a shared external resource (credential, environment, etc.), declared under
 * the top-level `resources:` key in `.taskorchestrator/config.yaml`:
 *
 * ```yaml
 * resources:
 *   staging-db-credential:
 *     description: "Shared staging database credential"
 *     defaultTtlSeconds: 1800
 *     maxHolders: 1
 * ```
 *
 * This is the server-global lock namespace: a resource `key` referenced by a trait's
 * [ResourceRequirement] resolves its default TTL and description against this registry — see
 * [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext.resolveResourceRegistry]
 * for the per-root/global layering (global wins on key collision, the inverse of trait-note
 * layering, since the lock namespace itself is server-global).
 *
 * @property key Resource identifier. Must match `^[a-z0-9][a-z0-9\-_./]*$`, max 128 characters — see
 *   [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser].
 * @property description Human-readable description of the resource.
 * @property defaultTtlSeconds Default lease TTL in seconds used when a trait's [ResourceRequirement]
 *   doesn't override it with its own `ttlSeconds`. Valid range enforced at config load: 1..86400
 *   (24h) — an out-of-range or non-numeric YAML value is a load warning, and the entry falls back to
 *   3600s rather than being rejected outright.
 * @property maxHolders Maximum concurrent holders. v1 only supports exactly 1 (single-holder
 *   exclusive lock) — a config value greater than 1 is a load ERROR (the entry is skipped, not
 *   clamped) since fan-in lease semantics are not yet implemented.
 */
data class ResourceDefinition(
    val key: String,
    val description: String = "",
    val defaultTtlSeconds: Int = 3600,
    val maxHolders: Int = 1
)

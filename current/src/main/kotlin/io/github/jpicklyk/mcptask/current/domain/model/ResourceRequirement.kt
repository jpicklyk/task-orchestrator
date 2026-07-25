package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Declares a shared external resource (credential, environment, etc.) that a trait requires.
 *
 * Declared per-trait in `.taskorchestrator/config.yaml` under `traits.<name>.resources:`, in either
 * short form (a bare key string, `EXCLUSIVE` mode, no ttl override) or long form (a map with `key`,
 * optional `mode`, optional `ttlSeconds`) — see
 * [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser.parseRoot].
 *
 * This type carries only the *declaration*; nothing in this task enforces it. Enforcement (a
 * server-side TTL lease acquired at work-phase entry for [ResourceMode.EXCLUSIVE] requirements) is
 * implemented by a follow-on task and layers on top of
 * [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext.resolveResourceRequirements].
 *
 * @property key Resource identifier. Matched against the optional top-level `resources:` registry
 *   ([ResourceDefinition]) to resolve a default TTL and description; also the lease/lock namespace
 *   key used by future enforcement. Referencing a key absent from the registry is not an error — it
 *   is enforced with built-in defaults (see the "undeclared resource" warning emitted by the parser).
 * @property mode [ResourceMode.EXCLUSIVE] (default) is intended for a server-enforced TTL lease
 *   acquired at work-phase entry (enforcement implemented by a follow-on task, not this one);
 *   [ResourceMode.ADVISORY] is audit-only and is never enforced, only recorded.
 * @property ttlSeconds Optional override of the resolved [ResourceDefinition.defaultTtlSeconds] for
 *   this specific trait's requirement. Null means "use the registry default" (or the parser's
 *   built-in default of 3600s when the key isn't registered).
 */
data class ResourceRequirement(
    val key: String,
    val mode: ResourceMode = ResourceMode.EXCLUSIVE,
    val ttlSeconds: Int? = null
)

/**
 * Enforcement mode for a [ResourceRequirement].
 *
 * - [EXCLUSIVE]: server-enforced single-holder TTL lease acquired at work-phase entry (enforcement
 *   implemented by a follow-on task).
 * - [ADVISORY]: audit-only — recorded but never enforced or leased.
 */
enum class ResourceMode { EXCLUSIVE, ADVISORY }

package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Declares a shared external resource (credential, environment, etc.) that a trait requires.
 *
 * Declared per-trait in `.taskorchestrator/config.yaml` under `traits.<name>.resources:`, in either
 * short form (a bare key string, `EXCLUSIVE` mode, no ttl override) or long form (a map with `key`,
 * optional `mode`, optional `ttlSeconds`) — see
 * [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser.parseRoot].
 *
 * This type carries only the *declaration*; it enforces nothing itself. Enforcement of
 * [ResourceMode.EXCLUSIVE] requirements (a server-side TTL lease acquired at work-phase entry) is in the
 * transition policy's lease gate (`GateId.LEASE`, evaluated by `TransitionPolicy`, which decides `acquireLeases` for a transition into
 * `work`) and the lease writes of `ClaimService.acquireLeases`, driven by `AdvanceService`; the requirements it
 * enforces come from
 * [io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext.resolveResourceRequirements].
 *
 * @property key Resource identifier. Matched against the optional top-level `resources:` registry
 *   ([ResourceDefinition]) to resolve a default TTL and description; also the lease/lock namespace
 *   key the lease gate enforces. At admission, a key absent from the registry is rejected by
 *   `ConfigValidator` ("resource '<key>' is not declared in the server resource registry"), which both
 *   `ConfigService.push`/`validate` and the boot import `ConfigService.importDocument` run. A version
 *   already stored (parsed in `ParseMode.MIGRATION`, which never rejects) still loads and is enforced
 *   with built-in defaults, plus the parser's deferred "undeclared resource" finding.
 * @property mode [ResourceMode.EXCLUSIVE] (default) is a server-enforced TTL lease
 *   acquired at work-phase entry (see the class description for where it is enforced);
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
 * - [EXCLUSIVE]: server-enforced single-holder TTL lease acquired at work-phase entry (enforced by the
 *   transition policy's lease gate; `RESOURCE_LEASES_ENFORCED` is the deployment kill switch).
 * - [ADVISORY]: audit-only — recorded but never enforced or leased.
 */
enum class ResourceMode {
    EXCLUSIVE,
    ADVISORY;

    /** The lowercase wire name (`exclusive`, `advisory`) error details report. */
    val wire: String get() = name.lowercase()
}

package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Declares who/what should pick up a phase of work: an orchestration routing signal, not
 * enforced by the server itself.
 *
 * Declared per trait per phase in `.taskorchestrator/config.yaml` under
 * `traits.<name>.dispatch.<phase>:`, where `<phase>` is one of `queue`, `work`, `review`:
 *
 * ```yaml
 * traits:
 *   delegated:
 *     dispatch:
 *       work:   { agent: task-orchestrator:implementer }
 *       review: { agent: task-orchestrator:reviewer, effort: high }
 * ```
 *
 * This type carries only the *declaration*; nothing in this task validates that [agent] names a
 * real agent or dispatches anything — an orchestrator or skill reading the resolved profile off
 * `advance_item`/`get_context`/`query_items` decides what to do with it.
 *
 * At least one field must be set for a profile to be recorded — see
 * [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser.parseRoot], which
 * drops (with a load warning) a profile that would otherwise be empty (e.g. every field blank,
 * non-string, or an invalid `effort`). No field is validated beyond that at construction time.
 *
 * @property agent Opaque agent identifier (e.g. `"task-orchestrator:implementer"`), meaningful to
 *   whatever orchestrator or skill reads it. Not validated against any registry.
 * @property model Opaque model identifier/alias, meaningful to the dispatching orchestrator.
 * @property effort One of `low`, `medium`, `high`, `xhigh`, `max`, matched case-sensitively. An
 *   invalid value is dropped at parse time with a load warning — never stored on this type.
 */
data class DispatchProfile(
    val agent: String? = null,
    val model: String? = null,
    val effort: String? = null
)

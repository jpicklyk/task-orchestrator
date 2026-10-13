package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * One parsed config document — the shared, layer-agnostic shape produced by parsing either the
 * global `.taskorchestrator/config.yaml` file or a single per-root pushed config YAML document.
 *
 * Absence is always encoded as `null` (or an empty map/list where a map/list is the natural
 * "nothing here" value): a `null` [noteLimitsMode] means "this document does not opine on
 * note_limits", not "warn" — a caller layering this document over another reads the `null` to
 * decide whether to fall through, and applies its own default (e.g. `"warn"`) only once it knows
 * no layer opined at all. This replaces the split `noteLimitsMode` / `noteLimitsModeExplicit`
 * pair on `YamlSchemaParser.ParsedConfig`.
 *
 * This type is pure data: no infrastructure, no interfaces, no `kotlinx.serialization` imports —
 * only domain model types and Kotlin stdlib collections, so `application` -> `infrastructure`/
 * `interfaces` layering is never at risk from this file (enforced by `LayeringTest`).
 *
 * The 3.x `schema_resolution` key is no longer modeled: a document carrying it still parses, with one
 * finding saying the key was removed, and the key has no effect.
 *
 * @property warnings the warnings as plain text, one per finding ([warningViolations] with the paths dropped).
 * @property errors the findings that [ParseMode.STRICT] classified as errors (a former soft skip), each with its JSON
 *   Pointer path; always empty for a document parsed in [ParseMode.MIGRATION].
 * @property warningViolations the warnings with their JSON Pointer paths: every finding in [ParseMode.MIGRATION],
 *   only the advisory ones in [ParseMode.STRICT].
 * @property traitResources per-trait resource requirements; a trait with no `resources:` key is
 *   absent from the map entirely (not mapped to an empty list).
 * @property traitDispatch per-trait, per-phase dispatch routing profiles; a trait with no
 *   `dispatch:` key, or none of whose phase entries parsed, is absent from the map entirely.
 * @property noteLimitsMode the resolved `note_limits.mode` string ("warn"/"reject") when the
 *   top-level `note_limits` key is present in the document (even if its own `mode` sub-key was
 *   itself absent, empty, or invalid — in which case this still holds the resolved default);
 *   `null` only when the top-level `note_limits` key itself is absent from the document.
 * @property statusLabels `null` when the document has no top-level `status_labels` key at all;
 *   otherwise the parsed trigger->label map. A value of `null` for a given trigger key means
 *   "explicitly clear this trigger's label" — only an absent KEY in this map falls through to
 *   another layer.
 * @property actorAuthenticationSection the raw YAML value of the top-level `actor_authentication`
 *   key (a map, a scalar, or `null` for an explicit `actor_authentication: null`), or `null` when
 *   the key is entirely absent. Carried as raw YAML rather than a typed shape so this document
 *   doesn't need to duplicate `YamlActorAuthenticationConfigService`'s validation rules — the
 *   service that reads this field still owns parsing it into an
 *   [io.github.jpicklyk.mcptask.current.domain.model.ActorAuthenticationConfig].
 * @property presentSections the top-level keys of the document, in document order (a
 *   `LinkedHashSet`), untransformed.
 * @property traitSeats per-trait `seats:` lists (A1a); a trait with no `seats:` key at all is
 *   absent from the map entirely — mirroring [traitResources]/[traitDispatch] — NOT mapped to an
 *   empty list. [LayeredConfig.traitSeats] uses presence in [traits] (not this map) to decide
 *   whether a per-root document's declaration of a trait shadows the global one wholesale.
 * @property traitDispatchBySeat per-trait, per-phase, per-seat dispatch override maps (A1a),
 *   parsed from `traits.<name>.dispatch.<phase>.seats:`. A SIBLING map to [traitDispatch] — never
 *   folded into it — so every existing `traitDispatch` reference (main and test) is unaffected by
 *   this addition. A trait with no phase carrying a `seats:` sub-key is absent from this map
 *   entirely.
 * @property independence the resolved top-level `independence:` block (A2), or `null` when the
 *   document has no `independence` key at all — a layering caller uses that `null` to fall through
 *   to another layer (mirrors [noteLimitsMode]'s null-means-absent contract), applying
 *   [IndependencePolicy.DEFAULT] only once it knows no layer opined at all. A present-but-malformed
 *   `independence` block still resolves to a non-null [IndependencePolicy] (defaults applied
 *   per-field), with a load warning.
 */
data class ConfigDocument(
    val workItemSchemas: Map<String, WorkItemSchema>,
    val traits: Map<String, List<NoteSchemaEntry>>,
    val traitResources: Map<String, List<ResourceRequirement>> = emptyMap(),
    val traitDispatch: Map<String, Map<Role, DispatchProfile>> = emptyMap(),
    val resourceRegistry: Map<String, ResourceDefinition> = emptyMap(),
    val noteLimitsMode: String? = null,
    val independence: IndependencePolicy? = null,
    val statusLabels: Map<String, String?>? = null,
    val actorAuthenticationSection: Any? = null,
    val presentSections: Set<String> = emptySet(),
    val warnings: List<String> = emptyList(),
    val errors: List<ConfigViolation> = emptyList(),
    val warningViolations: List<ConfigViolation> = emptyList(),
    val traitSeats: Map<String, List<SeatDefinition>> = emptyMap(),
    val traitDispatchBySeat: Map<String, Map<Role, Map<String, SeatDispatchOverride>>> = emptyMap(),
    val runs: RunsConfig? = null,
) {
    companion object {
        /** The empty document: no schemas, no traits, no opinion on any other facet. */
        val EMPTY: ConfigDocument = ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap())
    }
}

/**
 * The top-level `runs:` block. In one document a null field means "this document does not set it" (a layering caller
 * falls through to the next layer); [LayeredConfig.runsConfig] resolves each field separately and applies the
 * defaults only when no layer sets it: [stallAfterSeconds] 600, every budget ceiling unlimited. Budgets are opt-in:
 * a null [budgets] (or a null field inside it) never caps anything. `cost_usd` is held in micro-USD.
 */
data class RunsConfig(
    val stallAfterSeconds: Int? = null,
    val budgets: RunBudget? = null,
)

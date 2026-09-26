package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
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
 * `schemaResolution` is parsed here but, in this item (C1), has no effect anywhere — every
 * consumer that will eventually honor [SchemaResolutionMode] is later work; C1 is pure
 * restructuring of the parse step only.
 *
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
 * @property schemaResolution this document's opted-in [SchemaResolutionMode], or `null` when the
 *   top-level `schema_resolution` key is absent, non-string, or does not match one of
 *   [SchemaResolutionMode.fromConfigString]'s recognized values. Parsed but inert in C1 — see
 *   class kdoc.
 * @property actorAuthenticationSection the raw YAML value of the top-level `actor_authentication`
 *   key (a map, a scalar, or `null` for an explicit `actor_authentication: null`), or `null` when
 *   the key is entirely absent. Carried as raw YAML rather than a typed shape so this document
 *   doesn't need to duplicate `YamlActorAuthenticationConfigService`'s validation rules — the
 *   service that reads this field still owns parsing it into an
 *   [io.github.jpicklyk.mcptask.current.domain.model.ActorAuthenticationConfig].
 * @property presentSections the top-level keys of the document, in document order (a
 *   `LinkedHashSet`), untransformed — used to compute `ignoredSections` for a per-root config push
 *   without hand-maintaining a separate literal set.
 */
data class ConfigDocument(
    val workItemSchemas: Map<String, WorkItemSchema>,
    val traits: Map<String, List<NoteSchemaEntry>>,
    val traitResources: Map<String, List<ResourceRequirement>> = emptyMap(),
    val traitDispatch: Map<String, Map<Role, DispatchProfile>> = emptyMap(),
    val resourceRegistry: Map<String, ResourceDefinition> = emptyMap(),
    val noteLimitsMode: String? = null,
    val statusLabels: Map<String, String?>? = null,
    val schemaResolution: SchemaResolutionMode? = null,
    val actorAuthenticationSection: Any? = null,
    val presentSections: Set<String> = emptySet(),
    val warnings: List<String> = emptyList(),
) {
    companion object {
        /** The empty document: no schemas, no traits, no opinion on any other facet. */
        val EMPTY: ConfigDocument = ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap())

        /**
         * Top-level `configYaml` keys honored by the per-root resolution layer. Replaces
         * `ProjectConfigPushService.HONORED_TOP_LEVEL_SECTIONS` (a hand-maintained literal set) —
         * this is the single source of truth other top-level keys (e.g. `actor_authentication`,
         * which stays global-only) are checked against to compute `ignoredSections`.
         *
         * Deliberately EXACTLY the 7 sections honored today: `schema_resolution` is parsed (see
         * [ConfigDocument.schemaResolution]) but intentionally left OUT of this set in C1 — adding
         * it here would change a per-root push's `ignoredSections` output, which is a later item's
         * job (AR-39 rollout), not this pure-restructuring change's.
         */
        val PER_ROOT_HONORED_SECTIONS: Set<String> =
            setOf(
                "work_item_schemas",
                "note_schemas",
                "traits",
                "project",
                "note_limits",
                "status_labels",
                "resources",
            )
    }
}

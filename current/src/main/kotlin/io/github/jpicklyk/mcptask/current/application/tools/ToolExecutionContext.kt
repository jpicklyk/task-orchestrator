package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.McpLoggingService
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.NoOpMcpLoggingService
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Execution context provided to all MCP tools during invocation.
 *
 * Provides typed access to all repository interfaces via the [RepositoryProvider].
 * Tools receive this context in their [ToolDefinition.execute] method,
 * enabling them to interact with the persistence layer without direct
 * knowledge of the repository implementations.
 */
class ToolExecutionContext(
    val repositoryProvider: RepositoryProvider,
    private val noteSchemaService: NoteSchemaService = NoOpNoteSchemaService,
    private val statusLabelService: StatusLabelService = NoOpStatusLabelService,
    private val mcpLoggingService: McpLoggingService = NoOpMcpLoggingService,
    private val actorVerifier: ActorVerifier = NoOpActorVerifier,
    val degradedModePolicy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
    val idempotencyCache: IdempotencyCache = IdempotencyCache(),
    val nextItemRecommender: NextItemRecommender =
        NextItemRecommender(
            repositoryProvider.workItemRepository(),
            repositoryProvider.dependencyRepository()
        ),
    private val perRootConfigService: PerRootConfigService? = null,
) {
    /** Access to WorkItem CRUD and query operations. */
    fun workItemRepository(): WorkItemRepository = repositoryProvider.workItemRepository()

    /** Access to Note upsert, query, and delete operations. */
    fun noteRepository(): NoteRepository = repositoryProvider.noteRepository()

    /** Access to Dependency graph operations (synchronous, non-suspend). */
    fun dependencyRepository(): DependencyRepository = repositoryProvider.dependencyRepository()

    /** Access to RoleTransition audit trail operations. */
    fun roleTransitionRepository(): RoleTransitionRepository = repositoryProvider.roleTransitionRepository()

    /** Access to Note schema configuration service. */
    fun noteSchemaService(): NoteSchemaService = noteSchemaService

    /** Access to the status label configuration service. */
    fun statusLabelService(): StatusLabelService = statusLabelService

    /** Access to the MCP protocol-level logging service. */
    fun mcpLoggingService(): McpLoggingService = mcpLoggingService

    /** Access to the actor claim verification service. */
    fun actorVerifier(): ActorVerifier = actorVerifier

    /** Access to the atomic work-tree creation executor. */
    fun workTreeExecutor(): WorkTreeExecutor = repositoryProvider.workTreeExecutor()

    /** Access to per-root config (raw YAML document) CRUD operations. */
    fun projectConfigRepository(): ProjectConfigRepository = repositoryProvider.projectConfigRepository()

    /**
     * Resolves the effective [WorkItemSchema] for a [WorkItem], including trait note merging.
     *
     * ## Root layering
     *
     * When [item] has a non-null `rootId` and this context was constructed with a
     * [perRootConfigService], every lookup below first consults that root's pushed config
     * (via [PerRootConfigService]) before falling back to the global `.taskorchestrator/config.yaml`
     * loader ([noteSchemaService]). A null `rootId` (legacy pre-backfill rows, or no
     * [perRootConfigService] wired) skips the per-root layer entirely — zero extra calls, behavior
     * byte-identical to before this layering was added.
     *
     * Per-key fallback table — both the type step and the tag step are **whole-algorithm-first**:
     * the entire per-root resolution (exact match, then per-root `"default"`) runs to completion
     * before the global layer is consulted at all:
     *
     * | Resolution step | Precedence |
     * |---|---|
     * | Type lookup | per-root exact type -> per-root `"default"` -> global exact type -> global `"default"` (the last step is [NoteSchemaService.getSchemaForType]'s own internal fallback) |
     * | Tag lookup | per-root first-matching-tag -> per-root `"default"` -> global first-matching-tag -> global `"default"` (see [resolvePerRootTagMatch]) |
     * | Trait notes | `perRoot.getTraitNotes(rootId, name) ?: global.getTraitNotes(name)`, per trait name |
     *
     * This intentionally means a per-root `"default"` schema wins over a global *exact* type match:
     * once a root has pushed its own config, that config is treated as the root's complete
     * self-description for gate purposes, not a patch over the global floor. A project that wants
     * zero required notes for every item type can push a per-root config with an empty default
     * schema (`work_item_schemas: { default: { notes: [] } } }`) to fence off the global config
     * entirely — see `config-format.md` for the schema-free / non-dev project pattern.
     *
     * Note length limits ([NoteSchemaService.getNoteLimitsMode]) and anything else not exposed by
     * [PerRootConfigService] stay global-only — [PerRootConfigService] does not expose them.
     *
     * Resolution order (unchanged from before layering, now with the per-root prefix above):
     * 1. If the item has a `type`, look up the schema by type (per-root layer first, see table above).
     * 2. If no type or no type-based schema found, look up by tags (per-root layer first, see table above)
     *    (first matching tag wins; falls back to default schema if no tag matches).
     * 3. Returns null if no schema matches (schema-free mode).
     *
     * After base schema resolution, trait notes are merged in:
     * - Default traits from the matched schema's [WorkItemSchema.defaultTraits]
     * - Per-item traits from the item's `properties` JSON (`traits` array via [PropertiesHelper])
     * - Base schema note keys always win; first-trait-in-order wins for duplicate trait keys
     */
    suspend fun resolveSchema(item: WorkItem): WorkItemSchema? {
        val service = noteSchemaService()
        val snapshot = snapshotFor(item.rootId)
        val baseSchema = resolveBaseSchemaWithSource(item, snapshot, service)?.first ?: return null
        return mergeTraits(item, baseSchema, snapshot, service)
    }

    /**
     * Same resolution as [resolveSchema], but also reports which config layer supplied the BASE
     * schema (the type/tag lookup step — see [resolveBaseSchemaWithSource]) and that layer's
     * config fingerprint. Trait notes are merged in identically to [resolveSchema] and may
     * originate from a different layer than the base schema (see [mergeTraits]); [ResolvedSchema.source]
     * and [ResolvedSchema.fingerprint] describe the base schema's provenance only — this is what
     * `query_items`'s `schema` operation needs for its `configSource`/`configFingerprint` fields.
     */
    suspend fun resolveSchemaWithSource(item: WorkItem): ResolvedSchema? {
        val service = noteSchemaService()
        val snapshot = snapshotFor(item.rootId)
        val (baseSchema, source) = resolveBaseSchemaWithSource(item, snapshot, service) ?: return null
        val merged = mergeTraits(item, baseSchema, snapshot, service)
        return ResolvedSchema(merged, source, fingerprintFor(source, snapshot, service))
    }

    /**
     * Type-only counterpart to [resolveSchemaWithSource] for callers that have a type name but no
     * [WorkItem] (e.g. `query_items(operation="schema", type=...)`). Mirrors [resolveTypeAgainstLayers]
     * only — no tag fallback (there's no item to carry tags) and no trait merging (there's no item
     * to carry `properties`). Returns null when neither layer defines [type].
     */
    suspend fun resolveTypeSchema(
        type: String,
        rootId: UUID?
    ): ResolvedSchema? {
        val service = noteSchemaService()
        val snapshot = snapshotFor(rootId)
        val (schema, source) = resolveTypeAgainstLayers(type, snapshot, service) ?: return null
        return ResolvedSchema(schema, source, fingerprintFor(source, snapshot, service))
    }

    /**
     * Returns true if the resolved (trait-merged) schema for [item] has a REVIEW phase.
     * Convenience wrapper around [resolveSchema] + [WorkItemSchema.hasReviewPhase].
     * Returns false when no schema matches (schema-free mode — skip REVIEW).
     */
    suspend fun resolveHasReviewPhase(item: WorkItem): Boolean = resolveSchema(item)?.hasReviewPhase() ?: false

    /**
     * Layered `note_limits.mode` resolution: [rootId]'s per-root config wins when it explicitly
     * configures `note_limits` (see [PerRootConfigService.getNoteLimitsMode] for the absent-vs-explicit
     * distinction); otherwise falls back to the global [noteSchemaService]'s mode. A null [rootId] or
     * no wired [perRootConfigService] skips the per-root layer entirely — byte-identical to the
     * pre-layering global-only behavior.
     */
    suspend fun resolveNoteLimitsMode(rootId: UUID?): String {
        val perRootMode = snapshotFor(rootId)?.noteLimitsModeExplicit
        return perRootMode ?: noteSchemaService().getNoteLimitsMode()
    }

    /**
     * Layered status-label resolution for a single [trigger]: [rootId]'s per-root `status_labels`
     * map wins ONLY when it explicitly contains [trigger] as a key (its value may itself be null,
     * meaning "this root explicitly clears the label for this trigger" — see
     * [PerRootConfigService.getStatusLabels]); a trigger key absent from the per-root map (including
     * when there is no per-root `status_labels` section at all) falls through to the global
     * [statusLabelService]. A null [rootId] or no wired [perRootConfigService] skips the per-root
     * layer entirely.
     */
    suspend fun resolveStatusLabel(
        trigger: String,
        rootId: UUID?
    ): String? = resolveStatusLabels(listOf(trigger), rootId)[trigger]

    /**
     * Batched counterpart to [resolveStatusLabel]: resolves every trigger in [triggers] against
     * [rootId]'s layered status-label config from a SINGLE per-root snapshot fetch instead of one
     * fetch per trigger — callers resolving more than one trigger for the same item (e.g.
     * `AdvanceItemTool`'s root-aware [StatusLabelService], which needs the primary trigger plus the
     * system-internal "cascade" trigger) should call this once rather than [resolveStatusLabel] in
     * a loop. Per-trigger precedence is identical to [resolveStatusLabel]: an explicit per-root key
     * for that trigger wins (including an explicit `null` value — see
     * [PerRootConfigService.getStatusLabels]); a trigger absent from the per-root map falls through
     * to the global [statusLabelService].
     */
    suspend fun resolveStatusLabels(
        triggers: Collection<String>,
        rootId: UUID?
    ): Map<String, String?> {
        val perRootLabels = snapshotFor(rootId)?.statusLabels
        val global = statusLabelService()
        return triggers.associateWith { trigger ->
            if (perRootLabels != null && perRootLabels.containsKey(trigger)) {
                perRootLabels[trigger]
            } else {
                global.resolveLabel(trigger)
            }
        }
    }

    /**
     * Returns the union of trait names available for the given [rootIds], per-root traits first
     * (in [rootIds] iteration order), followed by the global trait list — deduplicated, preserving
     * first-seen order. Used by response hints (e.g. `availableTraits` on item creation) so callers
     * discover per-root traits alongside the global ones without a separate lookup.
     *
     * Roots with no pushed config (or no [perRootConfigService] wired at all) contribute nothing —
     * this degrades to the plain global trait list, unchanged from before this method existed.
     */
    suspend fun availableTraits(rootIds: Collection<UUID>): List<String> {
        val perRoot = perRootConfigService
        val perRootTraits =
            if (perRoot != null) {
                rootIds.flatMap { rootId ->
                    perRoot
                        .getSnapshot(rootId)
                        ?.traits
                        ?.keys
                        .orEmpty()
                }
            } else {
                emptyList()
            }
        return (perRootTraits + noteSchemaService().getAvailableTraits()).distinct()
    }

    /**
     * Fetches [rootId]'s [PerRootConfigService.Snapshot] in ONE call, or null when [rootId] is
     * null, no [perRootConfigService] is wired, or the root has no per-root config (no row, or a
     * row that fails to parse). Every per-root-aware resolver below (schema/tag/trait lookup,
     * note-limits mode, status labels, fingerprint) takes this ALREADY-fetched snapshot as a
     * parameter instead of independently calling the single-facet accessors on
     * [PerRootConfigService] — each of those would otherwise re-invoke [PerRootConfigService.resolve]
     * on its own, costing a redundant fingerprint-read per facet even when the cache is warm.
     */
    private suspend fun snapshotFor(rootId: UUID?): PerRootConfigService.Snapshot? = rootId?.let { perRootConfigService?.getSnapshot(it) }

    /**
     * Returns the fingerprint associated with a resolved schema's [source], from the SAME
     * [snapshot] used to resolve it (PER_ROOT) or from the global loader (GLOBAL) — the tiny shared
     * helper behind [resolveSchemaWithSource] and [resolveTypeSchema], replacing what used to be a
     * duplicated 4-line `when (source)` expression in each.
     */
    private fun fingerprintFor(
        source: SchemaSource,
        snapshot: PerRootConfigService.Snapshot?,
        service: NoteSchemaService
    ): String? =
        when (source) {
            SchemaSource.PER_ROOT -> snapshot?.fingerprint
            SchemaSource.GLOBAL -> service.getConfigFingerprint()
        }

    /**
     * Resolves the base schema (no trait merging), returning which layer ([SchemaSource]) supplied
     * it alongside the schema itself. Type-first lookup with tag fallback, each layered
     * per-root-then-global against the ALREADY-fetched [snapshot] — see [resolveSchema]'s KDoc for
     * the full fallback table.
     */
    private fun resolveBaseSchemaWithSource(
        item: WorkItem,
        snapshot: PerRootConfigService.Snapshot?,
        service: NoteSchemaService
    ): Pair<WorkItemSchema, SchemaSource>? {
        // Type-first lookup: whole-algorithm-first per layer. Run the ENTIRE per-root layer
        // (exact type match, then per-root "default") before ever consulting the global layer.
        // Only when neither per-root key matches (no config row for this root, or a row with
        // neither the exact type nor "default" defined) do we fall through to the global lookup
        // (which has its own type -> "default" fallback — see class KDoc above).
        item.type?.let { type ->
            resolveTypeAgainstLayers(type, snapshot, service)?.let { return it }
        }

        // Tag fallback: run the per-root schema map through the SAME first-tag-match/"default"
        // algorithm first; only fall through to the global tag algorithm when the per-root layer
        // has no config row for this root, or no tag (nor "default") matches within it.
        val tags = item.tagList()
        if (snapshot != null) {
            resolvePerRootTagMatch(tags, snapshot)?.let { return it to SchemaSource.PER_ROOT }
        }

        // Tag fallback: find the matched tag, then look up the full WorkItemSchema
        // to preserve lifecycleMode and defaultTraits from config
        val tagNotes = service.getSchemaForTags(tags) ?: return null
        val matchedType =
            if (tags.isEmpty()) {
                "default"
            } else {
                tags.firstOrNull { tag -> service.getSchemaForTags(listOf(tag)) != null } ?: "default"
            }
        // Retrieve the full WorkItemSchema (with lifecycle/defaultTraits) if available.
        // Re-use tagNotes from above to avoid a redundant getSchemaForTags call in the fallback.
        val resolved = service.getSchemaForType(matchedType) ?: WorkItemSchema(type = matchedType, notes = tagNotes)
        return resolved to SchemaSource.GLOBAL
    }

    /**
     * Runs the type-lookup step shared by [resolveBaseSchemaWithSource] and [resolveTypeSchema]:
     * per-root exact type -> per-root `"default"` -> global exact type (which has its own internal
     * `-> global "default"` fallback — see [resolveSchema]'s KDoc table), against the
     * ALREADY-fetched [snapshot]. Whole-algorithm-first: the entire per-root probe runs to
     * completion before the global layer is consulted at all. Returns null when neither layer
     * defines [type].
     */
    private fun resolveTypeAgainstLayers(
        type: String,
        snapshot: PerRootConfigService.Snapshot?,
        service: NoteSchemaService
    ): Pair<WorkItemSchema, SchemaSource>? {
        if (snapshot != null) {
            val perRootMatch = snapshot.workItemSchemas[type] ?: snapshot.workItemSchemas["default"]
            if (perRootMatch != null) return perRootMatch to SchemaSource.PER_ROOT
        }
        return service.getSchemaForType(type)?.let { it to SchemaSource.GLOBAL }
    }

    /**
     * Runs the same "first matching tag wins, else `default`" algorithm [resolveBaseSchemaWithSource]
     * uses against the global service, but against the ALREADY-fetched [snapshot]'s per-root schema
     * map instead. Per-root schema map keys (type/tag names) and their [WorkItemSchema] values come
     * straight from [PerRootConfigService.Snapshot.workItemSchemas], which already carries
     * lifecycle/defaultTraits — no separate "fetch notes, then re-fetch the full schema" step is
     * needed here (unlike the global path, which has two parallel maps for historical reasons).
     *
     * Returns null when no tag (nor `"default"`) matches within [snapshot] — meaning "defer to the
     * global tag algorithm".
     */
    private fun resolvePerRootTagMatch(
        tags: List<String>,
        snapshot: PerRootConfigService.Snapshot
    ): WorkItemSchema? {
        for (tag in tags) {
            snapshot.workItemSchemas[tag]?.let { return it }
        }
        return snapshot.workItemSchemas["default"]
    }

    /**
     * Merges trait notes into the base schema. Collects default_traits from config +
     * per-item traits from properties JSON, looks up notes for each (the ALREADY-fetched [snapshot]
     * for [item]'s root taking precedence over the global trait definition — see [resolveSchema]'s
     * KDoc), and appends to the base schema notes (base key wins on duplicates).
     */
    private fun mergeTraits(
        item: WorkItem,
        baseSchema: WorkItemSchema,
        snapshot: PerRootConfigService.Snapshot?,
        service: NoteSchemaService
    ): WorkItemSchema {
        val defaultTraits = baseSchema.defaultTraits
        val itemTraits = PropertiesHelper.extractTraits(item.properties)
        val allTraits = (defaultTraits + itemTraits).distinct()

        if (allTraits.isEmpty()) return baseSchema

        val traitNotes = mutableListOf<NoteSchemaEntry>()
        for (traitName in allTraits) {
            val perRootNotes = snapshot?.traits?.get(traitName)
            val notes = perRootNotes ?: service.getTraitNotes(traitName)
            if (notes == null) {
                logger.warn("Unknown trait '{}' on item '{}'; skipping", traitName, item.id)
                continue
            }
            traitNotes.addAll(notes)
        }

        if (traitNotes.isEmpty()) return baseSchema

        // Base note keys win; first-trait-in-order wins for duplicate trait keys
        val existingKeys = baseSchema.notes.map { it.key }.toMutableSet()
        val mergedNotes = baseSchema.notes.toMutableList()
        for (note in traitNotes) {
            if (note.key !in existingKeys) {
                mergedNotes.add(note)
                existingKeys.add(note.key)
            }
        }

        return baseSchema.copy(notes = mergedNotes)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ToolExecutionContext::class.java)
    }
}

/** Which config layer supplied a resolved schema — see [ToolExecutionContext.resolveSchemaWithSource]. */
enum class SchemaSource { PER_ROOT, GLOBAL }

/**
 * A resolved [WorkItemSchema] together with which layer supplied its base schema and that layer's
 * config fingerprint (null when the supplying layer has no fingerprint available, e.g. no global
 * config loaded). Returned by [ToolExecutionContext.resolveSchemaWithSource] and
 * [ToolExecutionContext.resolveTypeSchema].
 */
data class ResolvedSchema(
    val schema: WorkItemSchema,
    val source: SchemaSource,
    val fingerprint: String?
)

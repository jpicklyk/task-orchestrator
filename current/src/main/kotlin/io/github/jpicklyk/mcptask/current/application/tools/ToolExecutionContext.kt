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
        val baseSchema = resolveBaseSchema(item, service) ?: return null
        return mergeTraits(item, baseSchema, service)
    }

    /**
     * Returns true if the resolved (trait-merged) schema for [item] has a REVIEW phase.
     * Convenience wrapper around [resolveSchema] + [WorkItemSchema.hasReviewPhase].
     * Returns false when no schema matches (schema-free mode — skip REVIEW).
     */
    suspend fun resolveHasReviewPhase(item: WorkItem): Boolean = resolveSchema(item)?.hasReviewPhase() ?: false

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
                rootIds.flatMap { rootId -> perRoot.getAllTraits(rootId)?.keys.orEmpty() }
            } else {
                emptyList()
            }
        return (perRootTraits + noteSchemaService().getAvailableTraits()).distinct()
    }

    /**
     * Resolves the base schema without trait merging. Type-first lookup with tag fallback, each
     * layered per-root-then-global — see [resolveSchema]'s KDoc for the full fallback table.
     */
    private suspend fun resolveBaseSchema(
        item: WorkItem,
        service: NoteSchemaService
    ): WorkItemSchema? {
        val rootId = item.rootId
        val perRoot = perRootConfigService

        // Type-first lookup: whole-algorithm-first per layer. Run the ENTIRE per-root layer
        // (exact type match, then per-root "default") before ever consulting the global layer.
        // Only when neither per-root key matches (no config row for this root, or a row with
        // neither the exact type nor "default" defined) do we fall through to the global lookup
        // (which has its own type -> "default" fallback — see class KDoc above).
        item.type?.let { type ->
            val perRootMatch =
                if (rootId != null && perRoot != null) {
                    perRoot.getSchemaForType(rootId, type) ?: perRoot.getSchemaForType(rootId, "default")
                } else {
                    null
                }
            (perRootMatch ?: service.getSchemaForType(type))?.let { return it }
        }

        // Tag fallback: run the per-root schema map through the SAME first-tag-match/"default"
        // algorithm first; only fall through to the global tag algorithm when the per-root layer
        // has no config row for this root, or no tag (nor "default") matches within it.
        val tags = item.tagList()
        if (rootId != null && perRoot != null) {
            resolvePerRootTagMatch(rootId, tags, perRoot)?.let { return it }
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
        return service.getSchemaForType(matchedType)
            ?: WorkItemSchema(type = matchedType, notes = tagNotes)
    }

    /**
     * Runs the same "first matching tag wins, else `default`" algorithm [resolveBaseSchema] uses
     * against the global service, but against [rootId]'s per-root schema map instead. Per-root
     * schema map keys (type/tag names) and their [WorkItemSchema] values come straight from
     * [PerRootConfigService.getSchemas], which already carries lifecycle/defaultTraits — no
     * separate "fetch notes, then re-fetch the full schema" step is needed here (unlike the global
     * path, which has two parallel maps for historical reasons).
     *
     * Returns null when there is no per-root config row for [rootId], or no tag (nor `"default"`)
     * matches within it — either case means "defer to the global tag algorithm".
     */
    private suspend fun resolvePerRootTagMatch(
        rootId: UUID,
        tags: List<String>,
        perRoot: PerRootConfigService
    ): WorkItemSchema? {
        val schemas = perRoot.getSchemas(rootId) ?: return null
        for (tag in tags) {
            schemas[tag]?.let { return it }
        }
        return schemas["default"]
    }

    /**
     * Merges trait notes into the base schema. Collects default_traits from config +
     * per-item traits from properties JSON, looks up notes for each (per-root config for
     * [item]'s root taking precedence over the global trait definition — see [resolveSchema]'s
     * KDoc), and appends to the base schema notes (base key wins on duplicates).
     */
    private suspend fun mergeTraits(
        item: WorkItem,
        baseSchema: WorkItemSchema,
        service: NoteSchemaService
    ): WorkItemSchema {
        val rootId = item.rootId
        val perRoot = perRootConfigService
        val defaultTraits = baseSchema.defaultTraits
        val itemTraits = PropertiesHelper.extractTraits(item.properties)
        val allTraits = (defaultTraits + itemTraits).distinct()

        if (allTraits.isEmpty()) return baseSchema

        val traitNotes = mutableListOf<NoteSchemaEntry>()
        for (traitName in allTraits) {
            val perRootNotes = if (rootId != null && perRoot != null) perRoot.getTraitNotes(rootId, traitName) else null
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

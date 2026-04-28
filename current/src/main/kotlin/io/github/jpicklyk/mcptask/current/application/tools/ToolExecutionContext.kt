package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.service.McpLoggingService
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
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import org.slf4j.LoggerFactory

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
    val degradedModePolicy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED
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

    /**
     * Resolves the effective [WorkItemSchema] for a [WorkItem], including trait note merging.
     *
     * Resolution order:
     * 1. If the item has a `type`, look up the schema by type via [NoteSchemaService.getSchemaForType].
     * 2. If no type or no type-based schema found, look up by tags via [NoteSchemaService.getSchemaForTags]
     *    (first matching tag wins; falls back to default schema if no tag matches).
     * 3. Returns null if no schema matches (schema-free mode).
     *
     * After base schema resolution, trait notes are merged in:
     * - Default traits from the matched schema's [WorkItemSchema.defaultTraits]
     * - Per-item traits from the item's `properties` JSON (`traits` array via [PropertiesHelper])
     * - Base schema note keys always win; first-trait-in-order wins for duplicate trait keys
     */
    fun resolveSchema(item: WorkItem): WorkItemSchema? {
        val service = noteSchemaService()
        val baseSchema = resolveBaseSchema(item, service) ?: return null
        return mergeTraits(item, baseSchema, service)
    }

    /**
     * Returns true if the resolved (trait-merged) schema for [item] has a REVIEW phase.
     * Convenience wrapper around [resolveSchema] + [WorkItemSchema.hasReviewPhase].
     * Returns false when no schema matches (schema-free mode — skip REVIEW).
     */
    fun resolveHasReviewPhase(item: WorkItem): Boolean = resolveSchema(item)?.hasReviewPhase() ?: false

    /**
     * Resolves the base schema without trait merging. Type-first lookup with tag fallback.
     */
    private fun resolveBaseSchema(
        item: WorkItem,
        service: NoteSchemaService
    ): WorkItemSchema? {
        // Type-first lookup
        item.type?.let { type ->
            service.getSchemaForType(type)?.let { return it }
        }
        // Tag fallback: find the matched tag, then look up the full WorkItemSchema
        // to preserve lifecycleMode and defaultTraits from config
        val tags = item.tagList()
        if (service.getSchemaForTags(tags) == null) return null
        val matchedType =
            if (tags.isEmpty()) {
                "default"
            } else {
                tags.firstOrNull { tag -> service.getSchemaForTags(listOf(tag)) != null } ?: "default"
            }
        // Retrieve the full WorkItemSchema (with lifecycle/defaultTraits) if available
        return service.getSchemaForType(matchedType)
            ?: WorkItemSchema(type = matchedType, notes = service.getSchemaForTags(tags) ?: emptyList())
    }

    /**
     * Merges trait notes into the base schema. Collects default_traits from config +
     * per-item traits from properties JSON, looks up notes for each, and appends
     * to the base schema notes (base key wins on duplicates).
     */
    private fun mergeTraits(
        item: WorkItem,
        baseSchema: WorkItemSchema,
        service: NoteSchemaService
    ): WorkItemSchema {
        val defaultTraits = baseSchema.defaultTraits
        val itemTraits = PropertiesHelper.extractTraits(item.properties)
        val allTraits = (defaultTraits + itemTraits).distinct()

        if (allTraits.isEmpty()) return baseSchema

        val traitNotes = mutableListOf<NoteSchemaEntry>()
        for (traitName in allTraits) {
            val notes = service.getTraitNotes(traitName)
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

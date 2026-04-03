package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.McpLoggingService
import io.github.jpicklyk.mcptask.current.application.service.NoOpMcpLoggingService
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider

/**
 * Execution context provided to all MCP tools during invocation.
 *
 * Provides typed access to all repository interfaces via the [RepositoryProvider].
 * Tools receive this context in their [ToolDefinition.execute] method,
 * enabling them to interact with the persistence layer without direct
 * knowledge of the repository implementations.
 *
 * Additional services (e.g., StatusProgressionService, CascadeService) will be
 * added to this context as Phase 1 progresses.
 */
class ToolExecutionContext(
    val repositoryProvider: RepositoryProvider,
    private val noteSchemaService: NoteSchemaService = NoOpNoteSchemaService,
    private val statusLabelService: StatusLabelService = NoOpStatusLabelService,
    private val mcpLoggingService: McpLoggingService = NoOpMcpLoggingService
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

    /** Access to the atomic work-tree creation executor. */
    fun workTreeExecutor(): WorkTreeExecutor = repositoryProvider.workTreeExecutor()

    /**
     * Resolves the [WorkItemSchema] for a [WorkItem] using type-first lookup with tag fallback.
     *
     * Resolution order:
     * 1. If the item has a `type`, look up the schema by type via [NoteSchemaService.getSchemaForType].
     * 2. If no type or no type-based schema found, look up by tags via [NoteSchemaService.getSchemaForTags]
     *    (first matching tag wins; falls back to default schema if no tag matches).
     * 3. Returns null if no schema matches (schema-free mode).
     */
    fun resolveSchema(item: WorkItem): WorkItemSchema? {
        val service = noteSchemaService()
        // Type-first lookup
        item.type?.let { type ->
            service.getSchemaForType(type)?.let { return it }
        }
        // Tag fallback: replicate existing getSchemaForTags behavior
        // getSchemaForTags already handles first-match and default schema fallback internally
        val tags = item.tagList()
        val entries = service.getSchemaForTags(tags) ?: return null
        // Determine the matched key: first tag that matches a schema, or "default" if tags were empty
        val matchedType = if (tags.isEmpty()) {
            "default"
        } else {
            tags.firstOrNull { tag -> service.getSchemaForTags(listOf(tag)) != null } ?: "default"
        }
        return WorkItemSchema(type = matchedType, notes = entries)
    }

    /**
     * Returns true if the resolved schema for [item] has a REVIEW phase.
     * Convenience wrapper around [resolveSchema] + [WorkItemSchema.hasReviewPhase].
     * Returns false when no schema matches (schema-free mode — skip REVIEW).
     */
    fun resolveHasReviewPhase(item: WorkItem): Boolean = resolveSchema(item)?.hasReviewPhase() ?: false
}

package io.github.jpicklyk.mcptask.current.domain.model

/**
 * A schema describing the expected notes and lifecycle behavior for a type of work item.
 *
 * Schemas are declared in `.taskorchestrator/config.yaml` and associated with work item types.
 * They control gate enforcement during role transitions by specifying which notes must be filled
 * at each workflow phase.
 *
 * @property type The work item type this schema applies to (e.g., "feature-task", "bug-fix")
 * @property lifecycleMode Controls automatic vs. manual lifecycle transitions for matching items
 * @property notes The ordered list of note schema entries required across all phases
 */
data class WorkItemSchema(
    val type: String,
    val lifecycleMode: LifecycleMode = LifecycleMode.AUTO,
    val notes: List<NoteSchemaEntry> = emptyList(),
    val defaultTraits: List<String> = emptyList()
) {
    /**
     * Returns true if this schema has any note entry assigned to the REVIEW phase.
     * Used to determine whether a `start` trigger from WORK should advance to REVIEW or
     * jump directly to TERMINAL.
     */
    fun hasReviewPhase(): Boolean = notes.any { it.role == Role.REVIEW }

    /**
     * Returns the list of required note entries for the given [role].
     * Non-required entries are excluded.
     *
     * @param role The workflow phase to filter by
     * @return Required [NoteSchemaEntry] items for that phase
     */
    fun requiredNotesForRole(role: Role): List<NoteSchemaEntry> = notes.filter { it.role == role && it.required }
}

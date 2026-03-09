package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role

/**
 * Test-only NoteSchemaService backed by an in-memory map.
 *
 * Use the companion object constants for common schema configurations,
 * or construct with a custom map for specialized test scenarios.
 */
class TestNoteSchemaService(
    private val schemas: Map<String, List<NoteSchemaEntry>> = emptyMap()
) : NoteSchemaService {
    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = tags.firstNotNullOfOrNull { schemas[it] }

    companion object {
        /** Feature implementation schema with queue, work, and review phase notes. */
        val FEATURE_IMPLEMENTATION =
            TestNoteSchemaService(
                mapOf(
                    "feature-implementation" to
                        listOf(
                            NoteSchemaEntry(
                                key = "requirements",
                                role = Role.QUEUE,
                                required = true,
                                description = "Functional requirements",
                            ),
                            NoteSchemaEntry(
                                key = "design",
                                role = Role.QUEUE,
                                required = true,
                                description = "Design decisions",
                            ),
                            NoteSchemaEntry(
                                key = "implementation-notes",
                                role = Role.WORK,
                                required = true,
                                description = "Implementation details"
                            ),
                            NoteSchemaEntry(key = "review-checklist", role = Role.REVIEW, required = true, description = "Review findings")
                        )
                )
            )

        /** Feature task schema — lighter gates for child work items under a feature container. */
        val FEATURE_TASK =
            TestNoteSchemaService(
                mapOf(
                    "feature-task" to
                        listOf(
                            NoteSchemaEntry(
                                key = "task-scope",
                                role = Role.QUEUE,
                                required = true,
                                description = "What to build — target files, acceptance criteria, constraints"
                            ),
                            NoteSchemaEntry(
                                key = "implementation-notes",
                                role = Role.WORK,
                                required = true,
                                description = "Context handoff — deviations, surprises, decisions"
                            ),
                            NoteSchemaEntry(
                                key = "review-checklist",
                                role = Role.REVIEW,
                                required = true,
                                description = "Task-level quality gate — scope alignment and test coverage"
                            ),
                            NoteSchemaEntry(
                                key = "session-tracking",
                                role = Role.WORK,
                                required = true,
                                description = "Session context for retrospective"
                            )
                        )
                )
            )

        /** Bug fix schema — queue and work only, no review phase. */
        val BUG_FIX =
            TestNoteSchemaService(
                mapOf(
                    "bug-fix" to
                        listOf(
                            NoteSchemaEntry(
                                key = "root-cause",
                                role = Role.QUEUE,
                                required = true,
                                description = "Root cause analysis",
                            ),
                            NoteSchemaEntry(
                                key = "fix-details",
                                role = Role.WORK,
                                required = true,
                                description = "Fix implementation details",
                            )
                        )
                )
            )

        /** Schema-free (no gates enforced). */
        val NONE = TestNoteSchemaService()
    }
}

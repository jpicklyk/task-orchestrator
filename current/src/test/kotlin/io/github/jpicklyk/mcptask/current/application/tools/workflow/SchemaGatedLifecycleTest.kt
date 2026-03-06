package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

/**
 * Integration tests validating the complete schema-gated lifecycle (queue -> work -> review -> terminal)
 * with gate enforcement at each transition. Uses a real H2 in-memory database and inline NoteSchemaService
 * for test isolation.
 */
class SchemaGatedLifecycleTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var transitionTool: AdvanceItemTool

    @BeforeEach
    fun setUp() {
        val dbName = "schema_gated_lifecycle_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()

        val repositoryProvider = DefaultRepositoryProvider(databaseManager)

        // Inline NoteSchemaService that mirrors real config.yaml schemas
        val noteSchemaService = object : NoteSchemaService {
            private val schemas = mapOf(
                "feature-implementation" to listOf(
                    NoteSchemaEntry(key = "specification", role = "queue", required = true, description = "Problem statement, approach, and implementation plan."),
                    NoteSchemaEntry(key = "implementation-notes", role = "work", required = true, description = "Context handoff for downstream agents."),
                    NoteSchemaEntry(key = "review-checklist", role = "review", required = true, description = "Quality gate - plan alignment, test quality, and simplification review.")
                ),
                "bug-fix" to listOf(
                    NoteSchemaEntry(key = "diagnosis", role = "queue", required = true, description = "Reproduction, root cause, and fix approach."),
                    NoteSchemaEntry(key = "implementation-notes", role = "work", required = true, description = "Context handoff for downstream agents."),
                    NoteSchemaEntry(key = "review-checklist", role = "review", required = true, description = "Quality gate - fix alignment, test quality, and simplification review.")
                )
            )

            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? {
                return tags.firstNotNullOfOrNull { schemas[it] }
            }
        }

        context = ToolExecutionContext(repositoryProvider, noteSchemaService)
        transitionTool = AdvanceItemTool()
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private suspend fun createItem(title: String, tags: String? = null): WorkItem {
        val item = WorkItem(title = title, tags = tags)
        val result = context.workItemRepository().create(item)
        return (result as Result.Success).data
    }

    private suspend fun createNote(itemId: UUID, key: String, role: String, body: String = "Filled content for $key"): Note {
        val note = Note(itemId = itemId, key = key, role = role, body = body)
        val result = context.noteRepository().upsert(note)
        return (result as Result.Success).data
    }

    private suspend fun getItem(itemId: UUID): WorkItem {
        return (context.workItemRepository().getById(itemId) as Result.Success).data
    }

    private fun buildTransitionParams(vararg transitions: JsonObject): JsonObject {
        return buildJsonObject {
            put("transitions", buildJsonArray {
                transitions.forEach { add(it) }
            })
        }
    }

    private fun transitionObj(itemId: UUID, trigger: String): JsonObject {
        return buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
        }
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response but got: $result")
        return obj["data"] as JsonObject
    }

    private fun extractResults(result: JsonElement): JsonArray {
        return extractData(result)["results"]!!.jsonArray
    }

    private fun assertGateRejection(result: JsonElement, expectedMissingKey: String? = null) {
        val results = extractResults(result)
        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean, "Expected gate rejection but transition was applied")
        val error = r["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("Gate check failed"), "Expected gate check error but got: $error")
        if (expectedMissingKey != null) {
            assertTrue(error.contains(expectedMissingKey), "Expected error to mention '$expectedMissingKey' but got: $error")
        }
    }

    private fun assertTransitionSuccess(result: JsonElement, expectedNewRole: String) {
        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean, "Expected successful transition but it was rejected: ${r["error"]}")
        assertEquals(expectedNewRole, r["newRole"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 1. feature-implementation schema gates
    // ──────────────────────────────────────────────

    @Test
    fun `feature-implementation schema enforces gates at every phase transition`(): Unit = runBlocking {
        val item = createItem("Feature task", tags = "feature-implementation")

        // Attempt QUEUE -> WORK without filling queue-phase note -> gate rejection
        val start1 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertGateRejection(start1, "specification")

        // Fill the queue-phase note
        createNote(item.id, key = "specification", role = "queue")

        // Retry QUEUE -> WORK -> success
        val start2 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(start2, "work")

        // Attempt WORK -> REVIEW without filling work-phase note -> gate rejection
        val start3 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertGateRejection(start3, "implementation-notes")

        // Fill the work-phase note
        createNote(item.id, key = "implementation-notes", role = "work")

        // Retry WORK -> REVIEW -> success
        val start4 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(start4, "review")

        // Attempt REVIEW -> TERMINAL without filling review-phase note -> gate rejection
        val start5 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertGateRejection(start5, "review-checklist")

        // Fill the review-phase note
        createNote(item.id, key = "review-checklist", role = "review")

        // Retry REVIEW -> TERMINAL -> success
        val start6 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(start6, "terminal")

        // Verify final role is TERMINAL
        val finalItem = getItem(item.id)
        assertEquals(Role.TERMINAL, finalItem.role)
    }

    // ──────────────────────────────────────────────
    // 2. bug-fix schema gates
    // ──────────────────────────────────────────────

    @Test
    fun `bug-fix schema enforces gates at every phase transition`(): Unit = runBlocking {
        val item = createItem("Bug fix task", tags = "bug-fix")

        // Attempt QUEUE -> WORK without filling queue-phase note -> gate rejection
        val start1 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertGateRejection(start1, "diagnosis")

        // Fill the queue-phase note
        createNote(item.id, key = "diagnosis", role = "queue")

        // Retry QUEUE -> WORK -> success
        val start2 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(start2, "work")

        // Attempt WORK -> REVIEW without filling work-phase note -> gate rejection
        val start3 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertGateRejection(start3, "implementation-notes")

        // Fill the work-phase note
        createNote(item.id, key = "implementation-notes", role = "work")

        // Retry WORK -> REVIEW -> success
        val start4 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(start4, "review")

        // Attempt REVIEW -> TERMINAL without filling review-phase note -> gate rejection
        val start5 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertGateRejection(start5, "review-checklist")

        // Fill the review-phase note
        createNote(item.id, key = "review-checklist", role = "review")

        // Retry REVIEW -> TERMINAL -> success
        val start6 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(start6, "terminal")

        // Verify final role is TERMINAL
        val finalItem = getItem(item.id)
        assertEquals(Role.TERMINAL, finalItem.role)
    }

    // ──────────────────────────────────────────────
    // 3. Schema-free mode (no tags)
    // ──────────────────────────────────────────────

    @Test
    fun `item with no schema tag advances freely without gates`(): Unit = runBlocking {
        val item = createItem("Untagged task")

        // QUEUE -> WORK (no gates, succeeds immediately)
        val start1 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(start1, "work")

        // WORK -> TERMINAL (schema-free mode skips review)
        val start2 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(start2, "terminal")

        // Verify final role is TERMINAL
        val finalItem = getItem(item.id)
        assertEquals(Role.TERMINAL, finalItem.role)
    }

    // ──────────────────────────────────────────────
    // 4. Empty note body treated as unfilled
    // ──────────────────────────────────────────────

    @Test
    fun `item with schema tag and empty note body is treated as unfilled`(): Unit = runBlocking {
        val item = createItem("Feature with empty note", tags = "feature-implementation")

        // Create a note with empty body
        createNote(item.id, key = "specification", role = "queue", body = "")

        // Attempt QUEUE -> WORK -> gate rejection (empty body should not satisfy)
        val start1 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertGateRejection(start1, "specification")
    }

    // ──────────────────────────────────────────────
    // 5. Complete trigger requires all phase notes
    // ──────────────────────────────────────────────

    @Test
    fun `complete trigger requires all phase notes filled`(): Unit = runBlocking {
        val item = createItem("Feature for complete test", tags = "feature-implementation")

        // Attempt complete from QUEUE with no notes -> gate rejection
        val complete1 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "complete")),
            context
        )
        assertGateRejection(complete1, "specification")

        // Fill only the queue-phase note
        createNote(item.id, key = "specification", role = "queue")

        // Attempt complete again -> still fails (work and review notes missing)
        val complete2 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "complete")),
            context
        )
        assertGateRejection(complete2, "implementation-notes")

        // Fill all 3 notes
        createNote(item.id, key = "implementation-notes", role = "work")
        createNote(item.id, key = "review-checklist", role = "review")

        // Attempt complete -> succeeds, role is TERMINAL
        val complete3 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "complete")),
            context
        )
        assertTransitionSuccess(complete3, "terminal")

        // Verify final role is TERMINAL
        val finalItem = getItem(item.id)
        assertEquals(Role.TERMINAL, finalItem.role)
    }
}

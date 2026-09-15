package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration coverage for item 473e4f49 ("start cascades bypass the parent note gate") through
 * the real [AdvanceItemTool] JSON contract, backed by an H2 in-memory database.
 *
 * Mirrors [SchemaGatedLifecycleTest]'s harness (real H2 DB + inline [NoteSchemaService], no mocks)
 * but is scoped to the three scenarios the item's frozen test-plan note assigns to this file:
 * S1 (core suppression), S2 (happy path once filled), S4 (reopen cascade keeps bypassing the gate
 * — the documented start/reopen asymmetry).
 */
class StartCascadeGateIntegrationTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var transitionTool: AdvanceItemTool

    @BeforeEach
    fun setUp() {
        val dbName = "start_cascade_gate_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()

        val repositoryProvider = DefaultRepositoryProvider(databaseManager)

        // Inline NoteSchemaService mirroring the real "feature-implementation" config.yaml schema.
        val noteSchemaService =
            object : NoteSchemaService {
                private val schemas =
                    mapOf(
                        "feature-implementation" to
                            listOf(
                                NoteSchemaEntry(
                                    key = "specification",
                                    role = Role.QUEUE,
                                    required = true,
                                    description = "Problem statement, approach, and implementation plan."
                                ),
                                NoteSchemaEntry(
                                    key = "implementation-notes",
                                    role = Role.WORK,
                                    required = true,
                                    description = "Context handoff for downstream agents."
                                ),
                                NoteSchemaEntry(
                                    key = "review-checklist",
                                    role = Role.REVIEW,
                                    required = true,
                                    description = "Quality gate - plan alignment, test quality, and simplification review."
                                )
                            )
                    )

                override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = tags.firstNotNullOfOrNull { schemas[it] }
            }

        context = ToolExecutionContext(repositoryProvider, noteSchemaService)
        transitionTool = AdvanceItemTool()
    }

    // ──────────────────────────────────────────────
    // Helpers (mirrors SchemaGatedLifecycleTest's conventions)
    // ──────────────────────────────────────────────

    private suspend fun createItem(
        title: String,
        tags: String? = null,
        parentId: UUID? = null
    ): WorkItem {
        val depth =
            if (parentId != null) {
                val parentResult = context.workItemRepository().getById(parentId)
                (parentResult as Result.Success).data.depth + 1
            } else {
                0
            }
        val item = WorkItem(title = title, tags = tags, parentId = parentId, depth = depth)
        val result = context.workItemRepository().create(item)
        return (result as Result.Success).data
    }

    private suspend fun createNote(
        itemId: UUID,
        key: String,
        role: Role,
        body: String = "Filled content for $key"
    ): Note {
        val note = Note(itemId = itemId, key = key, role = role.name.lowercase(), body = body)
        val result = context.noteRepository().upsert(note)
        return (result as Result.Success).data
    }

    private suspend fun getItem(itemId: UUID): WorkItem = (context.workItemRepository().getById(itemId) as Result.Success).data

    private fun buildTransitionParams(vararg transitions: JsonObject): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    transitions.forEach { add(it) }
                }
            )
        }

    private fun transitionObj(
        itemId: UUID,
        trigger: String
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
        }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response but got: $result")
        return obj["data"] as JsonObject
    }

    private fun extractResults(result: JsonElement): JsonArray = extractData(result)["results"]!!.jsonArray

    private fun firstResult(result: JsonElement): JsonObject = extractResults(result)[0].jsonObject

    private fun assertTransitionSuccess(
        result: JsonElement,
        expectedNewRole: String
    ): JsonObject {
        val r = firstResult(result)
        assertTrue(r["applied"]!!.jsonPrimitive.boolean, "Expected successful transition but it was rejected: ${r["error"]}")
        assertEquals(expectedNewRole, r["newRole"]!!.jsonPrimitive.content)
        return r
    }

    private fun singleCascade(r: JsonObject): JsonObject {
        val cascades = r["cascadeEvents"]!!.jsonArray
        assertEquals(1, cascades.size, "expected exactly one cascade event")
        return cascades[0].jsonObject
    }

    // ──────────────────────────────────────────────
    // S1 — core: start cascade is suppressed when the parent has an
    // unfilled required queue-phase note; the child still advances.
    // ──────────────────────────────────────────────

    @Test
    fun `S1 start cascade suppressed when parent has unfilled required queue note, child still advances`(): Unit =
        runBlocking {
            val parent = createItem("Gated parent", tags = "feature-implementation")
            // Deliberately do NOT fill the parent's "specification" note.
            val child = createItem("Child feature", tags = "feature-implementation", parentId = parent.id)
            createNote(child.id, key = "specification", role = Role.QUEUE)

            val result =
                transitionTool.execute(
                    buildTransitionParams(transitionObj(child.id, "start")),
                    context
                )

            val r = assertTransitionSuccess(result, "work")
            assertEquals(Role.WORK, getItem(child.id).role, "the child's own advance must succeed")

            // The parent must NOT have cascaded — role stays QUEUE.
            assertEquals(Role.QUEUE, getItem(parent.id).role)

            val cascade = singleCascade(r)
            assertEquals(parent.id.toString(), cascade["itemId"]!!.jsonPrimitive.content)
            assertEquals("work", cascade["targetRole"]!!.jsonPrimitive.content)
            assertFalse(cascade["applied"]!!.jsonPrimitive.boolean, "cascade must not be applied")
            assertTrue(cascade["gateBlocked"]!!.jsonPrimitive.boolean, "cascade must report gateBlocked")
            val missingKeys = cascade["missingNotes"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }.toSet()
            assertTrue(missingKeys.contains("specification"), "missingNotes must name the unfilled parent note")
        }

    // ──────────────────────────────────────────────
    // S2 — happy path: once the parent's required queue note is filled,
    // the start cascade proceeds and the parent enters WORK.
    // ──────────────────────────────────────────────

    @Test
    fun `S2 start cascade proceeds once the parent required queue note is filled`(): Unit =
        runBlocking {
            val parent = createItem("Noted parent", tags = "feature-implementation")
            createNote(parent.id, key = "specification", role = Role.QUEUE)
            val child = createItem("Child feature", tags = "feature-implementation", parentId = parent.id)
            createNote(child.id, key = "specification", role = Role.QUEUE)

            val result =
                transitionTool.execute(
                    buildTransitionParams(transitionObj(child.id, "start")),
                    context
                )

            val r = assertTransitionSuccess(result, "work")
            assertEquals(Role.WORK, getItem(parent.id).role, "the parent must cascade into WORK")

            val cascade = singleCascade(r)
            assertTrue(cascade["applied"]!!.jsonPrimitive.boolean, "cascade must be applied")
            assertNull(cascade["gateBlocked"], "an applied cascade must not carry gateBlocked")
            assertNull(cascade["missingNotes"], "an applied cascade must not carry missingNotes")
        }

    // ──────────────────────────────────────────────
    // S4 — asymmetry: a REOPEN cascade still bypasses the note gate,
    // even when the parent (schema-tagged) has zero notes filled.
    // ──────────────────────────────────────────────

    @Test
    fun `S4 reopen cascade bypasses the note gate even when the parent has zero notes filled`(): Unit =
        runBlocking {
            val parent = createItem("Terminal parent", tags = "feature-implementation")
            createNote(parent.id, key = "specification", role = Role.QUEUE)
            createNote(parent.id, key = "implementation-notes", role = Role.WORK)
            createNote(parent.id, key = "review-checklist", role = Role.REVIEW)

            val child = createItem("Only child", parentId = parent.id) // schema-free, advances freely

            // Drive the child to TERMINAL; the parent's own notes are all filled so it cascades
            // all the way to TERMINAL too, alongside the child.
            transitionTool.execute(buildTransitionParams(transitionObj(child.id, "start")), context) // -> work
            val rComplete =
                transitionTool.execute(buildTransitionParams(transitionObj(child.id, "start")), context) // -> terminal
            assertTransitionSuccess(rComplete, "terminal")
            assertEquals(Role.TERMINAL, getItem(parent.id).role, "sanity check: parent must have reached TERMINAL")

            // Now delete every parent note so the parent is left with ZERO filled notes.
            context.noteRepository().deleteByItemId(parent.id)

            val reopenResult =
                transitionTool.execute(
                    buildTransitionParams(transitionObj(child.id, "reopen")),
                    context
                )
            val r = assertTransitionSuccess(reopenResult, "queue")
            assertEquals(Role.QUEUE, getItem(child.id).role)

            // The parent must have cascaded from TERMINAL back to WORK despite zero filled notes —
            // reopen cascades bypass the note gate by design (the start/reopen asymmetry).
            assertEquals(Role.WORK, getItem(parent.id).role)

            val cascade = singleCascade(r)
            assertEquals(parent.id.toString(), cascade["itemId"]!!.jsonPrimitive.content)
            assertEquals("work", cascade["targetRole"]!!.jsonPrimitive.content)
            assertTrue(cascade["applied"]!!.jsonPrimitive.boolean, "reopen cascade must be applied despite unfilled notes")
            assertNull(cascade["gateBlocked"], "reopen cascade must never report gateBlocked")
        }
}

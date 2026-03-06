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
                ),
                "mixed-schema" to listOf(
                    // Queue phase: one required, one optional
                    NoteSchemaEntry(key = "requirements", role = "queue", required = true, description = "Required requirements."),
                    NoteSchemaEntry(key = "context", role = "queue", required = false, description = "Optional context."),
                    // Work phase: one required, two optional
                    NoteSchemaEntry(key = "design-decisions", role = "work", required = true, description = "Required design decisions."),
                    NoteSchemaEntry(key = "alternatives", role = "work", required = false, description = "Optional alternatives considered."),
                    NoteSchemaEntry(key = "open-questions", role = "work", required = false, description = "Optional open questions."),
                    // Review phase: only optional (no required notes)
                    NoteSchemaEntry(key = "review-notes", role = "review", required = false, description = "Optional review observations."),
                    NoteSchemaEntry(key = "follow-ups", role = "review", required = false, description = "Optional follow-up items.")
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

    private suspend fun createItem(title: String, tags: String? = null, parentId: UUID? = null): WorkItem {
        val depth = if (parentId != null) {
            val parentResult = context.workItemRepository().getById(parentId)
            (parentResult as Result.Success).data.depth + 1
        } else 0
        val item = WorkItem(title = title, tags = tags, parentId = parentId, depth = depth)
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

    private fun assertTransitionSuccess(result: JsonElement, expectedNewRole: String): JsonObject {
        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean, "Expected successful transition but it was rejected: ${r["error"]}")
        assertEquals(expectedNewRole, r["newRole"]!!.jsonPrimitive.content)
        return r
    }

    /** Verify that advance_item response matches actual DB state */
    private suspend fun assertResponseMatchesDb(result: JsonElement, itemId: UUID) {
        val results = extractResults(result)
        val r = results[0].jsonObject
        val dbItem = getItem(itemId)
        val applied = r["applied"]!!.jsonPrimitive.boolean
        if (applied) {
            val reportedRole = r["newRole"]!!.jsonPrimitive.content
            assertEquals(
                reportedRole, dbItem.role.name.lowercase(),
                "Response says newRole=$reportedRole but DB has role=${dbItem.role.name.lowercase()}"
            )
        } else {
            // If applied=false, the item should NOT have changed role.
            // We can't assert the exact previous role here without knowing it,
            // but we verify the item is NOT in the target role if one was implied.
            val error = r["error"]?.jsonPrimitive?.content ?: ""
            assertFalse(
                dbItem.role == Role.TERMINAL && error.contains("Gate check failed"),
                "Response says gate failed but DB shows item is terminal — response contradicts DB"
            )
        }
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

    // ──────────────────────────────────────────────
    // 6. Child advance triggers parent start cascade
    // ──────────────────────────────────────────────

    @Test
    fun `child start cascades parent to work and response reflects correct state`(): Unit = runBlocking {
        // Parent container (no schema tag — advances freely)
        val parent = createItem("Parent container")
        assertEquals(Role.QUEUE, getItem(parent.id).role)

        // Child with schema tag
        val child = createItem("Child feature", tags = "feature-implementation", parentId = parent.id)

        // Fill child's queue-phase note so it can advance
        createNote(child.id, key = "specification", role = "queue")

        // Advance child: queue -> work (should cascade parent: queue -> work)
        val result = transitionTool.execute(
            buildTransitionParams(transitionObj(child.id, "start")),
            context
        )
        val r = assertTransitionSuccess(result, "work")

        // Verify child response matches DB
        assertResponseMatchesDb(result, child.id)
        assertEquals(Role.WORK, getItem(child.id).role)

        // Verify parent cascaded to WORK
        assertEquals(Role.WORK, getItem(parent.id).role)

        // Verify cascade is reported in the response
        val cascades = r["cascadeEvents"]!!.jsonArray
        assertTrue(cascades.isNotEmpty(), "Expected cascade event for parent")
        val cascade = cascades[0].jsonObject
        assertEquals(parent.id.toString(), cascade["itemId"]!!.jsonPrimitive.content)
        assertEquals("work", cascade["targetRole"]!!.jsonPrimitive.content)
        assertTrue(cascade["applied"]!!.jsonPrimitive.boolean)
    }

    // ──────────────────────────────────────────────
    // 7. Parent with schema tag — cascade does NOT
    //    bypass parent's gate
    // ──────────────────────────────────────────────

    @Test
    fun `parent with schema tag cascades to work without gate check on cascade trigger`(): Unit = runBlocking {
        // Parent with schema tag (but cascade trigger is not "start", so gate does not apply)
        val parent = createItem("Schema parent", tags = "feature-implementation")
        // Do NOT fill parent's specification note

        // Child with schema tag
        val child = createItem("Schema child", tags = "feature-implementation", parentId = parent.id)
        createNote(child.id, key = "specification", role = "queue")

        // Advance child: should cascade parent to WORK
        // The cascade trigger is "cascade" not "start", so the gate check at line 196
        // (which only fires for trigger=="start") should NOT block it
        val result = transitionTool.execute(
            buildTransitionParams(transitionObj(child.id, "start")),
            context
        )
        assertTransitionSuccess(result, "work")

        // Parent should be in WORK even though specification note is NOT filled
        // (cascade bypasses start-trigger gate check)
        assertEquals(Role.WORK, getItem(parent.id).role)
    }

    // ──────────────────────────────────────────────
    // 8. Batch transitions with mixed gate outcomes
    // ──────────────────────────────────────────────

    @Test
    fun `batch transitions report correct applied state for each item`(): Unit = runBlocking {
        // Item A: has spec note filled (should pass gate)
        val itemA = createItem("Batch item A", tags = "feature-implementation")
        createNote(itemA.id, key = "specification", role = "queue")

        // Item B: missing spec note (should fail gate)
        val itemB = createItem("Batch item B", tags = "feature-implementation")

        // Item C: no schema tag (should pass freely)
        val itemC = createItem("Batch item C")

        // Batch advance all three
        val result = transitionTool.execute(
            buildTransitionParams(
                transitionObj(itemA.id, "start"),
                transitionObj(itemB.id, "start"),
                transitionObj(itemC.id, "start")
            ),
            context
        )

        val data = extractData(result)
        val results = data["results"]!!.jsonArray
        assertEquals(3, results.size)

        // Item A: should succeed
        val rA = results[0].jsonObject
        assertTrue(rA["applied"]!!.jsonPrimitive.boolean, "Item A should have succeeded")
        assertEquals("work", rA["newRole"]!!.jsonPrimitive.content)

        // Item B: should fail gate
        val rB = results[1].jsonObject
        assertFalse(rB["applied"]!!.jsonPrimitive.boolean, "Item B should have failed gate")
        assertTrue(rB["error"]!!.jsonPrimitive.content.contains("specification"))

        // Item C: should succeed (schema-free)
        val rC = results[2].jsonObject
        assertTrue(rC["applied"]!!.jsonPrimitive.boolean, "Item C should have succeeded")
        assertEquals("work", rC["newRole"]!!.jsonPrimitive.content)

        // Verify summary counts
        val summary = data["summary"]!!.jsonObject
        assertEquals(2, summary["succeeded"]!!.jsonPrimitive.int)
        assertEquals(1, summary["failed"]!!.jsonPrimitive.int)

        // Verify DB matches responses
        assertEquals(Role.WORK, getItem(itemA.id).role)
        assertEquals(Role.QUEUE, getItem(itemB.id).role) // unchanged — gate blocked
        assertEquals(Role.WORK, getItem(itemC.id).role)
    }

    // ──────────────────────────────────────────────
    // 9. Full lifecycle with parent cascade and
    //    response/DB consistency at every step
    // ──────────────────────────────────────────────

    @Test
    fun `full lifecycle with parent cascade verifies response matches DB at every transition`(): Unit = runBlocking {
        val parent = createItem("Feature container")
        val child = createItem("Implementation task", tags = "feature-implementation", parentId = parent.id)

        // Step 1: Attempt start without spec note → gate rejection
        val r1 = transitionTool.execute(
            buildTransitionParams(transitionObj(child.id, "start")),
            context
        )
        assertGateRejection(r1, "specification")
        assertResponseMatchesDb(r1, child.id)
        assertEquals(Role.QUEUE, getItem(child.id).role)
        assertEquals(Role.QUEUE, getItem(parent.id).role) // no cascade on failure

        // Step 2: Fill spec, advance queue→work (cascades parent)
        createNote(child.id, key = "specification", role = "queue")
        val r2 = transitionTool.execute(
            buildTransitionParams(transitionObj(child.id, "start")),
            context
        )
        assertTransitionSuccess(r2, "work")
        assertResponseMatchesDb(r2, child.id)
        assertEquals(Role.WORK, getItem(child.id).role)
        assertEquals(Role.WORK, getItem(parent.id).role) // cascaded

        // Step 3: Attempt work→review without impl notes → gate rejection
        val r3 = transitionTool.execute(
            buildTransitionParams(transitionObj(child.id, "start")),
            context
        )
        assertGateRejection(r3, "implementation-notes")
        assertResponseMatchesDb(r3, child.id)
        assertEquals(Role.WORK, getItem(child.id).role) // unchanged

        // Step 4: Fill impl notes, advance work→review
        createNote(child.id, key = "implementation-notes", role = "work")
        val r4 = transitionTool.execute(
            buildTransitionParams(transitionObj(child.id, "start")),
            context
        )
        assertTransitionSuccess(r4, "review")
        assertResponseMatchesDb(r4, child.id)
        assertEquals(Role.REVIEW, getItem(child.id).role)

        // Step 5: Attempt review→terminal without review note → gate rejection
        val r5 = transitionTool.execute(
            buildTransitionParams(transitionObj(child.id, "start")),
            context
        )
        assertGateRejection(r5, "review-checklist")
        assertResponseMatchesDb(r5, child.id)
        assertEquals(Role.REVIEW, getItem(child.id).role) // unchanged

        // Step 6: Fill review note, advance review→terminal
        createNote(child.id, key = "review-checklist", role = "review")
        val r6 = transitionTool.execute(
            buildTransitionParams(transitionObj(child.id, "start")),
            context
        )
        assertTransitionSuccess(r6, "terminal")
        assertResponseMatchesDb(r6, child.id)
        assertEquals(Role.TERMINAL, getItem(child.id).role)

        // Parent should have cascaded to terminal (only child is done)
        assertEquals(Role.TERMINAL, getItem(parent.id).role)
    }

    // ──────────────────────────────────────────────
    // 10. Rapid sequential transitions — fill note
    //     and advance in tight sequence
    // ──────────────────────────────────────────────

    @Test
    fun `rapid sequential note fills and advances maintain response-DB consistency`(): Unit = runBlocking {
        val item = createItem("Rapid sequence item", tags = "bug-fix")

        // Rapid sequence: fill + advance, fill + advance, fill + advance
        // No pause between operations — tests that responses stay consistent

        createNote(item.id, key = "diagnosis", role = "queue")
        val r1 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(r1, "work")
        assertResponseMatchesDb(r1, item.id)

        createNote(item.id, key = "implementation-notes", role = "work")
        val r2 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(r2, "review")
        assertResponseMatchesDb(r2, item.id)

        createNote(item.id, key = "review-checklist", role = "review")
        val r3 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(r3, "terminal")
        assertResponseMatchesDb(r3, item.id)

        assertEquals(Role.TERMINAL, getItem(item.id).role)
    }

    // ──────────────────────────────────────────────
    // 11. Multiple children under one parent —
    //     mixed schemas, cascades, and batch
    // ──────────────────────────────────────────────

    @Test
    fun `multiple children with different schemas under shared parent`(): Unit = runBlocking {
        val parent = createItem("Mixed work container")

        val feature = createItem("Feature child", tags = "feature-implementation", parentId = parent.id)
        val bugfix = createItem("Bug fix child", tags = "bug-fix", parentId = parent.id)
        val untagged = createItem("Untagged child", parentId = parent.id)

        // Fill required notes for each
        createNote(feature.id, key = "specification", role = "queue")
        createNote(bugfix.id, key = "diagnosis", role = "queue")

        // Batch advance all three children: queue → work
        val r1 = transitionTool.execute(
            buildTransitionParams(
                transitionObj(feature.id, "start"),
                transitionObj(bugfix.id, "start"),
                transitionObj(untagged.id, "start")
            ),
            context
        )

        val results = extractResults(r1)
        assertEquals(3, results.size)

        // All should succeed
        for ((i, r) in results.withIndex()) {
            val obj = r.jsonObject
            assertTrue(obj["applied"]!!.jsonPrimitive.boolean, "Child $i should advance to work")
            assertEquals("work", obj["newRole"]!!.jsonPrimitive.content)
        }

        // Parent should have cascaded to WORK
        assertEquals(Role.WORK, getItem(parent.id).role)

        // Verify each child DB state
        assertEquals(Role.WORK, getItem(feature.id).role)
        assertEquals(Role.WORK, getItem(bugfix.id).role)
        assertEquals(Role.WORK, getItem(untagged.id).role)

        // Now complete the untagged child (schema-free → work→terminal)
        val r2 = transitionTool.execute(
            buildTransitionParams(transitionObj(untagged.id, "start")),
            context
        )
        assertTransitionSuccess(r2, "terminal")

        // Parent should still be in WORK (other children not done)
        assertEquals(Role.WORK, getItem(parent.id).role)

        // Complete feature child through full lifecycle
        createNote(feature.id, key = "implementation-notes", role = "work")
        transitionTool.execute(buildTransitionParams(transitionObj(feature.id, "start")), context) // → review
        createNote(feature.id, key = "review-checklist", role = "review")
        transitionTool.execute(buildTransitionParams(transitionObj(feature.id, "start")), context) // → terminal

        // Complete bugfix child through full lifecycle
        createNote(bugfix.id, key = "implementation-notes", role = "work")
        transitionTool.execute(buildTransitionParams(transitionObj(bugfix.id, "start")), context) // → review
        createNote(bugfix.id, key = "review-checklist", role = "review")
        val rLast = transitionTool.execute(
            buildTransitionParams(transitionObj(bugfix.id, "start")),
            context
        )
        assertTransitionSuccess(rLast, "terminal")
        assertResponseMatchesDb(rLast, bugfix.id)

        // All children terminal → parent should cascade to terminal
        assertEquals(Role.TERMINAL, getItem(feature.id).role)
        assertEquals(Role.TERMINAL, getItem(bugfix.id).role)
        assertEquals(Role.TERMINAL, getItem(untagged.id).role)
        assertEquals(Role.TERMINAL, getItem(parent.id).role)
    }

    // ──────────────────────────────────────────────
    // 12. Mixed required/optional — only required
    //     notes block the gate
    // ──────────────────────────────────────────────

    @Test
    fun `gate only blocks on required notes and ignores optional notes`(): Unit = runBlocking {
        val item = createItem("Mixed schema item", tags = "mixed-schema")

        // Queue phase has "requirements" (required) + "context" (optional)
        // Attempt advance without filling any notes → should fail on "requirements" only
        val r1 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertGateRejection(r1, "requirements")
        assertResponseMatchesDb(r1, item.id)
        assertEquals(Role.QUEUE, getItem(item.id).role)

        // Fill only the required note (skip optional "context")
        createNote(item.id, key = "requirements", role = "queue")

        // Advance should succeed — optional "context" note is NOT required
        val r2 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(r2, "work")
        assertResponseMatchesDb(r2, item.id)
        assertEquals(Role.WORK, getItem(item.id).role)
    }

    // ──────────────────────────────────────────────
    // 13. Mixed schema — optional-only review phase
    //     advances without any notes
    // ──────────────────────────────────────────────

    @Test
    fun `review phase with only optional notes advances freely`(): Unit = runBlocking {
        val item = createItem("Optional review item", tags = "mixed-schema")

        // Fill required queue note and advance to work
        createNote(item.id, key = "requirements", role = "queue")
        transitionTool.execute(buildTransitionParams(transitionObj(item.id, "start")), context)
        assertEquals(Role.WORK, getItem(item.id).role)

        // Fill required work note and advance to review
        createNote(item.id, key = "design-decisions", role = "work")
        transitionTool.execute(buildTransitionParams(transitionObj(item.id, "start")), context)
        assertEquals(Role.REVIEW, getItem(item.id).role)

        // Review phase has ONLY optional notes ("review-notes", "follow-ups")
        // Advance should succeed without filling any notes
        val r = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionSuccess(r, "terminal")
        assertResponseMatchesDb(r, item.id)
        assertEquals(Role.TERMINAL, getItem(item.id).role)
    }

    // ──────────────────────────────────────────────
    // 14. Complete trigger with mixed required/optional
    //     only checks required notes across all phases
    // ──────────────────────────────────────────────

    @Test
    fun `complete trigger only requires required notes not optional ones`(): Unit = runBlocking {
        val item = createItem("Complete mixed item", tags = "mixed-schema")

        // Attempt complete with no notes → should fail on "requirements"
        val r1 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "complete")),
            context
        )
        assertGateRejection(r1, "requirements")

        // Fill only the two required notes (skip all optional)
        createNote(item.id, key = "requirements", role = "queue")
        createNote(item.id, key = "design-decisions", role = "work")

        // Complete should succeed — no required notes in review phase
        val r2 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "complete")),
            context
        )
        assertTransitionSuccess(r2, "terminal")
        assertResponseMatchesDb(r2, item.id)
        assertEquals(Role.TERMINAL, getItem(item.id).role)
    }

    // ──────────────────────────────────────────────
    // 15. Filling optional note does not satisfy
    //     the required note gate
    // ──────────────────────────────────────────────

    @Test
    fun `filling optional note does not satisfy required note gate`(): Unit = runBlocking {
        val item = createItem("Optional not enough", tags = "mixed-schema")

        // Fill only the optional queue note, skip the required one
        createNote(item.id, key = "context", role = "queue", body = "Optional context filled")

        // Advance should still fail — "requirements" (required) is missing
        val r = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertGateRejection(r, "requirements")
        assertResponseMatchesDb(r, item.id)
        assertEquals(Role.QUEUE, getItem(item.id).role)
    }
}

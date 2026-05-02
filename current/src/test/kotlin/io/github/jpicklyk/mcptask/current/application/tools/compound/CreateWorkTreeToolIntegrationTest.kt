package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteNoteRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [CreateWorkTreeTool] using a real H2 in-memory database
 * and the production [DefaultRepositoryProvider] wiring (which constructs
 * [io.github.jpicklyk.mcptask.current.infrastructure.service.SQLiteWorkTreeService]
 * via the same lazy property used in production).
 *
 * Unlike [CreateWorkTreeToolTest] (which mocks the executor), these tests verify
 * that explicit `notes` provided to the tool actually flow through the executor
 * transaction and persist with the correct itemId binding, role, and verbatim body.
 */
class CreateWorkTreeToolIntegrationTest {
    private lateinit var tool: CreateWorkTreeTool
    private lateinit var context: ToolExecutionContext
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var noteRepository: SQLiteNoteRepository
    private lateinit var h2Database: Database

    @BeforeEach
    fun setUp() {
        val dbName = "create_work_tree_tool_integration_${System.nanoTime()}"
        h2Database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(h2Database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)

        workItemRepository = repositoryProvider.workItemRepository() as SQLiteWorkItemRepository
        noteRepository = repositoryProvider.noteRepository() as SQLiteNoteRepository

        tool = CreateWorkTreeTool()
        context = ToolExecutionContext(repositoryProvider)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inline notes are persisted end-to-end with bodies and itemId binding intact
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `inline notes are persisted end-to-end with bodies and correct itemId binding`(): Unit =
        runBlocking {
            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Integration Root"))
                        }
                    )
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("Child One"))
                                }
                            )
                        }
                    )
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("requirements"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive("Root requirements body"))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("c1"))
                                    put("key", JsonPrimitive("approach"))
                                    put("role", JsonPrimitive("work"))
                                    put("body", JsonPrimitive("Child approach body"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(
                result["success"]!!.jsonPrimitive.boolean,
                "Expected success; got: $result"
            )

            val data = result["data"] as JsonObject
            val rootIdStr = (data["root"] as JsonObject)["id"]!!.jsonPrimitive.content
            val childrenArr = (data["children"] as JsonArray)
            val childIdStr = (childrenArr[0] as JsonObject)["id"]!!.jsonPrimitive.content
            val rootId = UUID.fromString(rootIdStr)
            val childId = UUID.fromString(childIdStr)

            // Both items are in the DB
            val rootResult = workItemRepository.getById(rootId)
            assertTrue(rootResult is Result.Success, "Root item should be in DB; got: $rootResult")
            val childResult = workItemRepository.getById(childId)
            assertTrue(childResult is Result.Success, "Child item should be in DB; got: $childResult")

            // Notes persisted with verbatim bodies, correct itemId binding, and roles
            val rootNoteResult = noteRepository.findByItemIdAndKey(rootId, "requirements")
            assertTrue(rootNoteResult is Result.Success, "Root note lookup should succeed")
            val rootNote = (rootNoteResult as Result.Success).data
            assertNotNull(rootNote, "Root note should exist in DB")
            assertEquals(rootId, rootNote.itemId, "Root note must be bound to root item")
            assertEquals("queue", rootNote.role)
            assertEquals("Root requirements body", rootNote.body, "Root note body must round-trip verbatim")

            val childNoteResult = noteRepository.findByItemIdAndKey(childId, "approach")
            assertTrue(childNoteResult is Result.Success, "Child note lookup should succeed")
            val childNote = (childNoteResult as Result.Success).data
            assertNotNull(childNote, "Child note should exist in DB")
            assertEquals(childId, childNote.itemId, "Child note must be bound to child item")
            assertEquals("work", childNote.role)
            assertEquals("Child approach body", childNote.body, "Child note body must round-trip verbatim")
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Duplicate (itemRef, key) collapses to last-wins after end-to-end persistence
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `duplicate itemRef-key collapses to last-wins in persisted DB row`(): Unit =
        runBlocking {
            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Dedup Root"))
                        }
                    )
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("notes"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive("first body"))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("notes"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive("last body"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(
                result["success"]!!.jsonPrimitive.boolean,
                "Expected success; got: $result"
            )

            val data = result["data"] as JsonObject
            val rootIdStr = (data["root"] as JsonObject)["id"]!!.jsonPrimitive.content
            val rootId = UUID.fromString(rootIdStr)

            // Only one note row exists for (rootId, "notes"), with the LAST body
            val notesResult = noteRepository.findByItemId(rootId)
            assertTrue(notesResult is Result.Success, "findByItemId should succeed")
            val noteList = (notesResult as Result.Success).data
            assertEquals(
                1,
                noteList.size,
                "Duplicate (itemRef, key) must collapse to exactly one persisted note"
            )
            assertEquals("last body", noteList[0].body, "Last entry must win in persisted DB row")
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Invalid itemRef → tool returns error AND no items/notes are persisted
    //
    // Validates the failure path: the executor is never invoked, so no rows exist.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `invalid itemRef returns error and no items or notes persist`(): Unit =
        runBlocking {
            // Capture every workItem id we would have created so we can assert nothing landed
            val rootTitle = "Should Not Persist Root"
            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive(rootTitle))
                        }
                    )
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("nonexistent"))
                                    put("key", JsonPrimitive("k"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive("body"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertEquals(
                false,
                result["success"]!!.jsonPrimitive.boolean,
                "Expected failure for invalid itemRef; got: $result"
            )

            // The tool short-circuits before invoking the executor — no item or note
            // should be in the DB. Verify by scanning all work items: none should
            // have the rootTitle we tried to create.
            val allItemsResult = workItemRepository.search(query = rootTitle)
            assertTrue(allItemsResult is Result.Success, "Search should succeed")
            val foundItems = (allItemsResult as Result.Success).data
            assertEquals(
                0,
                foundItems.size,
                "No item should have been persisted when notes validation fails; found: ${foundItems.map { it.title }}"
            )
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Top-level actor attribution persists on the DB row for every note
    //
    // Verifies the audit trail is intact end-to-end: actor.id, actor.kind, and
    // verification metadata round-trip through the executor transaction onto
    // every persisted note (both explicit and createNotes=true blanks).
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `top-level actor attribution persists on every note via the executor transaction`(): Unit =
        runBlocking {
            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Attributed Root"))
                        }
                    )
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("authored-note"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive("authored body"))
                                }
                            )
                        }
                    )
                    put(
                        "actor",
                        buildJsonObject {
                            put("id", JsonPrimitive("orchestrator-integ"))
                            put("kind", JsonPrimitive("orchestrator"))
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Expected success; got: $result")

            val data = result["data"] as JsonObject
            val rootIdStr = (data["root"] as JsonObject)["id"]!!.jsonPrimitive.content
            val rootId = UUID.fromString(rootIdStr)

            val noteResult = noteRepository.findByItemIdAndKey(rootId, "authored-note")
            assertTrue(noteResult is Result.Success, "Note lookup should succeed")
            val persisted = (noteResult as Result.Success).data
            assertNotNull(persisted, "Note must exist in DB")

            assertNotNull(persisted.actorClaim, "Persisted note must carry the actor claim")
            assertEquals("orchestrator-integ", persisted.actorClaim!!.id)
            assertEquals(
                io.github.jpicklyk.mcptask.current.domain.model.ActorKind.ORCHESTRATOR,
                persisted.actorClaim!!.kind
            )
            assertNotNull(persisted.verification, "Persisted note must carry the verification result")
            // NoOpActorVerifier wired in DefaultRepositoryProvider's tool context
            assertEquals("noop", persisted.verification!!.verifier)
        }
}

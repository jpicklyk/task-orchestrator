package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var h2Database: Database

    @BeforeEach
    fun setUp() {
        val dbName = "create_work_tree_tool_integration_${System.nanoTime()}"
        h2Database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(h2Database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)

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
    // Attach mode: children created under existing item, root not re-inserted,
    // dep root→child resolves to existing id, note on root binds to existing id
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `attach mode - children created under existing item at correct depth`(): Unit =
        runBlocking {
            // 1. Create an existing item at depth 0 that will become the attach root
            val existingItemParams =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Existing Feature Root"))
                        }
                    )
                }
            val createResult = tool.execute(existingItemParams, context) as JsonObject
            assertTrue(createResult["success"]!!.jsonPrimitive.boolean, "Pre-create should succeed: $createResult")
            val existingRootId =
                UUID.fromString(
                    (createResult["data"] as JsonObject)["root"]!!.jsonObject["id"]!!.jsonPrimitive.content
                )
            val existingRoot = (workItemRepository.getById(existingRootId) as Result.Success).data
            assertEquals(0, existingRoot.depth, "Pre-created root should be at depth 0")

            // 2. Attach two children + a dep (root→c1) + a note on root
            val attachParams =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(existingRootId.toString())) })
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("Attach Child One"))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c2"))
                                    put("title", JsonPrimitive("Attach Child Two"))
                                }
                            )
                        }
                    )
                    put(
                        "deps",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("from", JsonPrimitive("root"))
                                    put("to", JsonPrimitive("c1"))
                                    put("type", JsonPrimitive("BLOCKS"))
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
                                    put("key", JsonPrimitive("attach-note"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive("note on existing root"))
                                }
                            )
                        }
                    )
                }
            val attachResult = tool.execute(attachParams, context) as JsonObject
            assertTrue(attachResult["success"]!!.jsonPrimitive.boolean, "Attach should succeed: $attachResult")

            val data = attachResult["data"] as JsonObject

            // Response root reflects the existing item
            val rootJson = data["root"] as JsonObject
            assertEquals(existingRootId.toString(), rootJson["id"]!!.jsonPrimitive.content)
            assertEquals("Existing Feature Root", rootJson["title"]!!.jsonPrimitive.content)
            assertEquals(0, rootJson["depth"]!!.jsonPrimitive.int)

            // Children are at depth 1 (existing.depth 0 + 1)
            val childrenArr = data["children"] as JsonArray
            assertEquals(2, childrenArr.size)
            val c1Id = UUID.fromString(childrenArr[0].jsonObject["id"]!!.jsonPrimitive.content)
            val c2Id = UUID.fromString(childrenArr[1].jsonObject["id"]!!.jsonPrimitive.content)

            val c1 = (workItemRepository.getById(c1Id) as Result.Success).data
            val c2 = (workItemRepository.getById(c2Id) as Result.Success).data
            assertEquals(1, c1.depth, "c1 should be at depth 1")
            assertEquals(1, c2.depth, "c2 should be at depth 1")
            assertEquals(existingRootId, c1.parentId, "c1 parent should be the existing root")
            assertEquals(existingRootId, c2.parentId, "c2 parent should be the existing root")

            // Existing root is NOT duplicated in DB (still exactly 1 item with that id)
            val rootRefetch = workItemRepository.getById(existingRootId)
            assertTrue(rootRefetch is Result.Success, "Existing root should still be fetchable after attach")

            // Dep root→c1 resolves to existingRootId
            val depsArr = data["dependencies"] as JsonArray
            assertEquals(1, depsArr.size)
            // The dep was created — verify via response
            val depJson = depsArr[0].jsonObject
            assertEquals("root", depJson["fromRef"]!!.jsonPrimitive.content)
            assertEquals("c1", depJson["toRef"]!!.jsonPrimitive.content)

            // Note on root binds to existingRootId
            val noteResult = noteRepository.findByItemIdAndKey(existingRootId, "attach-note")
            assertTrue(noteResult is Result.Success, "Note lookup should succeed")
            val note = (noteResult as Result.Success).data
            assertNotNull(note, "Note on root should be in DB")
            assertEquals(existingRootId, note.itemId, "Note must be bound to the existing root")
            assertEquals("note on existing root", note.body)
        }

    @Test
    fun `attach mode - root id not found returns RESOURCE_NOT_FOUND error`(): Unit =
        runBlocking {
            val missingId = UUID.randomUUID()
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(missingId.toString())) })
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("Should Not Create"))
                                }
                            )
                        }
                    )
                }
            val result = tool.execute(params, context) as JsonObject
            assertEquals(false, result["success"]!!.jsonPrimitive.boolean, "Expected failure for missing root.id: $result")
            val errorMsg = result["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            assertTrue(
                errorMsg.contains("not found") || errorMsg.contains(missingId.toString()),
                "Error should mention the missing id: $errorMsg"
            )
        }

    // ──────────────────────────────────────────────────────────────────────────
    // root_id stamping (create_work_tree is the third WorkItem-insert call site,
    // alongside CreateItemHandler and the REST POST /items route)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `create mode without parentId stamps rootId as the root's own id, inherited by children and grandchildren`(): Unit =
        runBlocking {
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("New Root")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("child"))
                                    put("title", JsonPrimitive("Child"))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("grandchild"))
                                    put("parentRef", JsonPrimitive("child"))
                                    put("title", JsonPrimitive("Grandchild"))
                                }
                            )
                        }
                    )
                }
            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Create should succeed: $result")

            val data = result["data"] as JsonObject
            val rootId = UUID.fromString((data["root"] as JsonObject)["id"]!!.jsonPrimitive.content)
            val childrenArr = data["children"] as JsonArray
            val childId = UUID.fromString(childrenArr[0].jsonObject["id"]!!.jsonPrimitive.content)
            val grandchildId = UUID.fromString(childrenArr[1].jsonObject["id"]!!.jsonPrimitive.content)

            val root = (workItemRepository.getById(rootId) as Result.Success).data
            val child = (workItemRepository.getById(childId) as Result.Success).data
            val grandchild = (workItemRepository.getById(grandchildId) as Result.Success).data

            assertEquals(rootId, root.rootId, "A newly created root with no parentId must be its own root")
            assertEquals(rootId, child.rootId, "Child must inherit the new root's id")
            assertEquals(rootId, grandchild.rootId, "Grandchild must inherit the same root id transitively")
        }

    @Test
    fun `create mode with parentId stamps rootId inherited from the parent chain`(): Unit =
        runBlocking {
            // Pre-create an existing root to hang the new tree's root under.
            val existingRootParams =
                buildJsonObject { put("root", buildJsonObject { put("title", JsonPrimitive("Existing Root")) }) }
            val existingRootResult = tool.execute(existingRootParams, context) as JsonObject
            val existingRootId =
                UUID.fromString((existingRootResult["data"] as JsonObject)["root"]!!.jsonObject["id"]!!.jsonPrimitive.content)

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("New Root Under Parent")) })
                    put("parentId", JsonPrimitive(existingRootId.toString()))
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("child"))
                                    put("title", JsonPrimitive("Child"))
                                }
                            )
                        }
                    )
                }
            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Create under parentId should succeed: $result")

            val data = result["data"] as JsonObject
            val newRootId = UUID.fromString((data["root"] as JsonObject)["id"]!!.jsonPrimitive.content)
            val childId = UUID.fromString((data["children"] as JsonArray)[0].jsonObject["id"]!!.jsonPrimitive.content)

            val newRoot = (workItemRepository.getById(newRootId) as Result.Success).data
            val child = (workItemRepository.getById(childId) as Result.Success).data

            assertEquals(
                existingRootId,
                newRoot.rootId,
                "A new root created under parentId must inherit the parent's root (or the parent's own id)"
            )
            assertEquals(existingRootId, child.rootId, "Child must inherit the same inherited root id")
        }

    @Test
    fun `attach mode - children inherit the existing root's effective rootId`(): Unit =
        runBlocking {
            // 1. Create an existing item at depth 0 that will become the attach root.
            //    Its rootId is whatever CreateWorkTreeTool's own create-mode path stamps it
            //    with — own id, since it has no parentId.
            val existingItemParams =
                buildJsonObject { put("root", buildJsonObject { put("title", JsonPrimitive("Attach Root")) }) }
            val createResult = tool.execute(existingItemParams, context) as JsonObject
            val existingRootId =
                UUID.fromString((createResult["data"] as JsonObject)["root"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            val existingRoot = (workItemRepository.getById(existingRootId) as Result.Success).data
            assertEquals(existingRootId, existingRoot.rootId, "Pre-created root should be its own root")

            // 2. Attach two children (one direct, one grandchild) to the existing root.
            val attachParams =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(existingRootId.toString())) })
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("Attach Child"))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("gc1"))
                                    put("parentRef", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("Attach Grandchild"))
                                }
                            )
                        }
                    )
                }
            val attachResult = tool.execute(attachParams, context) as JsonObject
            assertTrue(attachResult["success"]!!.jsonPrimitive.boolean, "Attach should succeed: $attachResult")

            val childrenArr = (attachResult["data"] as JsonObject)["children"] as JsonArray
            val c1Id = UUID.fromString(childrenArr[0].jsonObject["id"]!!.jsonPrimitive.content)
            val gc1Id = UUID.fromString(childrenArr[1].jsonObject["id"]!!.jsonPrimitive.content)

            val c1 = (workItemRepository.getById(c1Id) as Result.Success).data
            val gc1 = (workItemRepository.getById(gc1Id) as Result.Success).data

            assertEquals(existingRootId, c1.rootId, "Direct child must inherit the existing root's effective rootId")
            assertEquals(existingRootId, gc1.rootId, "Grandchild must inherit the same root id transitively")
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
            assertEquals("orchestrator-integ", persisted.actorClaim.id)
            assertEquals(
                io.github.jpicklyk.mcptask.current.domain.model.ActorKind.ORCHESTRATOR,
                persisted.actorClaim.kind
            )
            assertNotNull(persisted.verification, "Persisted note must carry the verification result")
            // NoOpActorVerifier wired in DefaultRepositoryProvider's tool context
            assertEquals("noop", persisted.verification.verifier)
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Materialize-from-document: docRef + noteAnchors
    // ──────────────────────────────────────────────────────────────────────────

    private val planBody =
        """
        # Overview
        Feature overview text.
        # Task 1
        Task 1 detail text.
        """.trimIndent()

    private suspend fun createProjectRoot(title: String = "Project"): UUID {
        val result = workItemRepository.create(WorkItem(title = title, type = "project"))
        return (result as Result.Success).data.id
    }

    @Test
    fun `docRef materializes note bodies from stashed plan document sections and adopts the document exactly once`(): Unit =
        runBlocking {
            val projectRootId = createProjectRoot()
            repositoryProvider.planDocumentRepository().stash(projectRootId, "my-plan", planBody)

            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Feature X"))
                            put(
                                "noteAnchors",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("noteKey", JsonPrimitive("requirements"))
                                            put("role", JsonPrimitive("queue"))
                                            put("anchor", JsonPrimitive("overview"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("parentId", JsonPrimitive(projectRootId.toString()))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("t1"))
                                    put("title", JsonPrimitive("Task 1"))
                                    put(
                                        "noteAnchors",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("noteKey", JsonPrimitive("task-scope"))
                                                    put("role", JsonPrimitive("queue"))
                                                    put("anchor", JsonPrimitive("task-1"))
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Expected success; got: $result")

            val data = result["data"] as JsonObject
            val rootId = UUID.fromString((data["root"] as JsonObject)["id"]!!.jsonPrimitive.content)
            val childId =
                UUID.fromString(((data["children"] as JsonArray)[0] as JsonObject)["id"]!!.jsonPrimitive.content)

            val rootNote = (noteRepository.findByItemIdAndKey(rootId, "requirements") as Result.Success).data
            assertNotNull(rootNote)
            assertEquals("# Overview\nFeature overview text.", rootNote.body)

            val childNote = (noteRepository.findByItemIdAndKey(childId, "task-scope") as Result.Success).data
            assertNotNull(childNote)
            assertEquals("# Task 1\nTask 1 detail text.", childNote.body)

            val doc = (repositoryProvider.planDocumentRepository().get(projectRootId, "my-plan") as Result.Success).data
            assertNotNull(doc)
            assertEquals(PlanDocumentStatus.ADOPTED, doc.status)
            // Adopted by the newly-created FEATURE root, not the project root the doc was stashed against.
            assertEquals(rootId, doc.adoptedByItemId)
        }

    @Test
    fun `docRef anchor miss fails atomically — zero items created and document remains pending`(): Unit =
        runBlocking {
            val projectRootId = createProjectRoot()
            repositoryProvider.planDocumentRepository().stash(projectRootId, "my-plan", planBody)

            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Feature X"))
                            put(
                                "noteAnchors",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("noteKey", JsonPrimitive("requirements"))
                                            put("role", JsonPrimitive("queue"))
                                            put("anchor", JsonPrimitive("does-not-exist"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("parentId", JsonPrimitive(projectRootId.toString()))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(!result["success"]!!.jsonPrimitive.boolean, "Expected failure; got: $result")

            val itemsResult = workItemRepository.findByFilters(parentId = projectRootId, limit = 100)
            val titles = (itemsResult as Result.Success).data.items.map { it.title }
            assertTrue("Feature X" !in titles, "Root item must NOT be created on anchor miss; got: $titles")

            val doc = (repositoryProvider.planDocumentRepository().get(projectRootId, "my-plan") as Result.Success).data
            assertNotNull(doc)
            assertEquals(PlanDocumentStatus.PENDING, doc.status, "Document must remain PENDING after a failed materialization")
        }

    @Test
    fun `docRef against an already-adopted document fails atomically with zero items created`(): Unit =
        runBlocking {
            val projectRootId = createProjectRoot()
            val earlierAdopterId = createProjectRoot("Earlier Adopter") // FK-valid target for adoptedByItemId
            repositoryProvider.planDocumentRepository().stash(projectRootId, "my-plan", planBody)
            val adoptResult = repositoryProvider.planDocumentRepository().markAdopted(projectRootId, "my-plan", earlierAdopterId)
            assertTrue(adoptResult is Result.Success, "Pre-test setup: markAdopted must succeed; got: $adoptResult")

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Feature X")) })
                    put("parentId", JsonPrimitive(projectRootId.toString()))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(!result["success"]!!.jsonPrimitive.boolean, "Expected failure; got: $result")

            val itemsResult = workItemRepository.findByFilters(parentId = projectRootId, limit = 100)
            val titles = (itemsResult as Result.Success).data.items.map { it.title }
            assertTrue("Feature X" !in titles, "Root item must NOT be created against an already-adopted document; got: $titles")
        }

    @Test
    fun `explicit notes win over noteAnchors on (itemRef, key) collision`(): Unit =
        runBlocking {
            val projectRootId = createProjectRoot()
            repositoryProvider.planDocumentRepository().stash(projectRootId, "my-plan", planBody)

            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Feature X"))
                            put(
                                "noteAnchors",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("noteKey", JsonPrimitive("requirements"))
                                            put("role", JsonPrimitive("queue"))
                                            put("anchor", JsonPrimitive("overview"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("parentId", JsonPrimitive(projectRootId.toString()))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("requirements"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive("Explicit body wins"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Expected success; got: $result")

            val data = result["data"] as JsonObject
            val rootId = UUID.fromString((data["root"] as JsonObject)["id"]!!.jsonPrimitive.content)
            val rootNote = (noteRepository.findByItemIdAndKey(rootId, "requirements") as Result.Success).data
            assertNotNull(rootNote)
            assertEquals("Explicit body wins", rootNote.body, "Explicit notes must win over noteAnchors")
        }

    @Test
    fun `docRef rootId mismatch fails validation before any items are created`(): Unit =
        runBlocking {
            val projectRootId = createProjectRoot()
            repositoryProvider.planDocumentRepository().stash(projectRootId, "my-plan", planBody)
            val wrongRootId = createProjectRoot("Other Project")

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Feature X")) })
                    put("parentId", JsonPrimitive(projectRootId.toString()))
                    put(
                        "docRef",
                        buildJsonObject {
                            put("slug", JsonPrimitive("my-plan"))
                            put("rootId", JsonPrimitive(wrongRootId.toString()))
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(!result["success"]!!.jsonPrimitive.boolean, "Expected failure; got: $result")

            val itemsResult = workItemRepository.findByFilters(parentId = projectRootId, limit = 100)
            val titles = (itemsResult as Result.Success).data.items.map { it.title }
            assertTrue("Feature X" !in titles, "Root item must NOT be created on a docRef.rootId mismatch; got: $titles")
        }

    @Test
    fun `omitting docRef leaves create_work_tree behavior unchanged (regression pin)`(): Unit =
        runBlocking {
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("No Doc Feature")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Expected success; got: $result")

            val data = result["data"] as JsonObject
            assertEquals(0, (data["notes"] as JsonArray).size, "No notes expected without docRef/noteAnchors")
        }
}

package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.QueryNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for item d426fbfa: an actor proof (a bearer JWT) supplied on `manage_notes`,
 * `query_notes`, `advance_item`, or `create_work_tree` must never be echoed back over MCP.
 *
 * [ActorClaim.toJson] is the single serializer all four tools funnel through, so these tests
 * exercise it end to end via each tool's real `execute()` against a real (H2 in-memory) database
 * — the same harness pattern used by `ManageNotesToolTest` / `QueryNotesToolTest` /
 * `CreateWorkTreeToolIntegrationTest`.
 *
 * "SECRET" below is a unique, JWT-shaped string used as the actor proof in every scenario; a
 * scenario passes only when SECRET is not a substring of the tool's serialized JSON response.
 */
class ActorProofMcpExposureTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var manageNotesTool: ManageNotesTool
    private lateinit var queryNotesTool: QueryNotesTool
    private lateinit var advanceItemTool: AdvanceItemTool
    private lateinit var createWorkTreeTool: CreateWorkTreeTool

    /** A unique, JWT-shaped bearer credential used as the actor proof in a scenario. */
    private fun freshSecret(): String = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.${UUID.randomUUID()}"

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        manageNotesTool = ManageNotesTool()
        queryNotesTool = QueryNotesTool()
        advanceItemTool = AdvanceItemTool()
        createWorkTreeTool = CreateWorkTreeTool()
    }

    /** Creates a WorkItem directly via the repository and returns its UUID string. */
    private suspend fun createTestItem(title: String = "Test Item"): String {
        val item = WorkItem(title = title)
        val result = context.workItemRepository().create(item)
        return ((result as Result.Success).data.id).toString()
    }

    private fun actorJson(
        id: String,
        kind: String = "subagent",
        parent: String? = null,
        proof: String? = null
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", kind)
            parent?.let { put("parent", it) }
            proof?.let { put("proof", it) }
        }

    /**
     * A `manage_notes` note element carrying its own per-note `actor` — the documented shape for
     * attributing a note's authorship (manage_notes's `notes[].actor` field), as distinct from
     * `advance_item`'s per-transition `actor` (S3) or `create_work_tree`'s top-level `actor` (S4).
     */
    private fun noteWithActorJson(
        itemId: String,
        key: String,
        role: String,
        body: String,
        actor: JsonObject
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId)
            put("key", key)
            put("role", role)
            put("body", body)
            put("actor", actor)
        }

    // -----------------------------------------------------------------------
    // S1 — manage_notes upsert never echoes proof
    // -----------------------------------------------------------------------

    @Test
    fun `S1 manage_notes upsert omits proof from echoed actor and response is SECRET-free`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val secret = freshSecret()

            val params =
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                noteWithActorJson(
                                    itemId = itemId,
                                    key = "approach",
                                    role = "work",
                                    body = "Some approach text",
                                    actor = actorJson(id = "agent-1", parent = "session-1", proof = secret)
                                )
                            )
                        }
                    )
                }

            val result = manageNotesTool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected success; got $result")
            assertFalse(result.toString().contains(secret), "manage_notes response must be SECRET-free")

            val note = (result["data"] as JsonObject)["notes"]!!.jsonArray[0].jsonObject
            val actor = note["actor"]!!.jsonObject
            assertEquals("agent-1", actor["id"]!!.jsonPrimitive.content)
            assertEquals("subagent", actor["kind"]!!.jsonPrimitive.content)
            assertEquals("session-1", actor["parent"]!!.jsonPrimitive.content)
            assertFalse(actor.containsKey("proof"), "echoed actor must not carry a proof key")
        }

    // -----------------------------------------------------------------------
    // S2 — query_notes get/list (both includeBody variants) never echo proof
    // -----------------------------------------------------------------------

    @Test
    fun `S2 query_notes get and list omit proof from actor at every includeBody variant`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val secret = freshSecret()

            val upsertResult =
                manageNotesTool.execute(
                    buildJsonObject {
                        put("operation", "upsert")
                        put(
                            "notes",
                            buildJsonArray {
                                add(
                                    noteWithActorJson(
                                        itemId = itemId,
                                        key = "spec",
                                        role = "queue",
                                        body = "Spec body",
                                        actor = actorJson(id = "agent-2", proof = secret)
                                    )
                                )
                            }
                        )
                    },
                    context
                ) as JsonObject
            val noteId =
                (upsertResult["data"] as JsonObject)["notes"]!!
                    .jsonArray[0]
                    .jsonObject["id"]!!
                    .jsonPrimitive.content

            // get
            val getResult =
                queryNotesTool.execute(
                    buildJsonObject {
                        put("operation", "get")
                        put("noteId", noteId)
                    },
                    context
                ) as JsonObject
            assertFalse(getResult.toString().contains(secret), "query_notes get response must be SECRET-free")
            val getActor = (getResult["data"] as JsonObject)["actor"]!!.jsonObject
            assertFalse(getActor.containsKey("proof"), "get: echoed actor must not carry a proof key")

            // list includeBody=false
            val listFalse =
                queryNotesTool.execute(
                    buildJsonObject {
                        put("operation", "list")
                        put("itemId", itemId)
                        put("includeBody", false)
                    },
                    context
                ) as JsonObject
            assertFalse(listFalse.toString().contains(secret), "list includeBody=false response must be SECRET-free")
            val listFalseActor = (listFalse["data"] as JsonObject)["notes"]!!.jsonArray[0].jsonObject["actor"]!!.jsonObject
            assertFalse(listFalseActor.containsKey("proof"), "list includeBody=false: actor must not carry a proof key")

            // list includeBody=true
            val listTrue =
                queryNotesTool.execute(
                    buildJsonObject {
                        put("operation", "list")
                        put("itemId", itemId)
                        put("includeBody", true)
                    },
                    context
                ) as JsonObject
            assertFalse(listTrue.toString().contains(secret), "list includeBody=true response must be SECRET-free")
            val listTrueActor = (listTrue["data"] as JsonObject)["notes"]!!.jsonArray[0].jsonObject["actor"]!!.jsonObject
            assertFalse(listTrueActor.containsKey("proof"), "list includeBody=true: actor must not carry a proof key")
        }

    // -----------------------------------------------------------------------
    // S3 — advance_item never echoes proof
    // -----------------------------------------------------------------------

    @Test
    fun `S3 advance_item start omits proof from echoed actor and response is SECRET-free`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val secret = freshSecret()

            val params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", itemId)
                                    put("trigger", "start")
                                    put("actor", actorJson(id = "agent-3", proof = secret))
                                }
                            )
                        }
                    )
                }

            val result = advanceItemTool.execute(params, context) as JsonObject
            assertFalse(result.toString().contains(secret), "advance_item response must be SECRET-free")

            val results = (result["data"] as JsonObject)["results"]!!.jsonArray
            val first = results[0].jsonObject
            assertTrue(first["applied"]!!.jsonPrimitive.boolean, "expected transition to be applied; got $first")
            val actor = first["actor"]!!.jsonObject
            assertEquals("agent-3", actor["id"]!!.jsonPrimitive.content)
            assertFalse(actor.containsKey("proof"), "echoed actor must not carry a proof key")
        }

    // -----------------------------------------------------------------------
    // S4 — create_work_tree top-level actor proof never leaks, including via follow-up query_notes
    // -----------------------------------------------------------------------

    @Test
    fun `S4 create_work_tree response and follow-up query_notes list are SECRET-free`(): Unit =
        runBlocking {
            val secret = freshSecret()

            val params =
                buildJsonObject {
                    put("actor", actorJson(id = "agent-4", proof = secret))
                    put(
                        "root",
                        buildJsonObject {
                            put("title", "S4 Root")
                        }
                    )
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", "root")
                                    put("key", "requirements")
                                    put("role", "queue")
                                    put("body", "S4 requirements body")
                                }
                            )
                        }
                    )
                }

            val result = createWorkTreeTool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected success; got $result")
            assertFalse(result.toString().contains(secret), "create_work_tree response must be SECRET-free")

            val rootId = ((result["data"] as JsonObject)["root"] as JsonObject)["id"]!!.jsonPrimitive.content

            val listResult =
                queryNotesTool.execute(
                    buildJsonObject {
                        put("operation", "list")
                        put("itemId", rootId)
                        put("includeBody", true)
                    },
                    context
                ) as JsonObject
            assertFalse(listResult.toString().contains(secret), "follow-up query_notes list must be SECRET-free")
            val notes = (listResult["data"] as JsonObject)["notes"]!!.jsonArray
            assertEquals(1, notes.size)
            assertFalse(notes[0].jsonObject["actor"]!!.jsonObject.containsKey("proof"), "note actor must not carry a proof key")
        }

    // -----------------------------------------------------------------------
    // S5 — a non-noop (jwks) verifier still keeps verification metadata, still omits proof
    // -----------------------------------------------------------------------

    @Test
    fun `S5 manage_notes with a VERIFIED jwks actor keeps verification status but omits proof`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val secret = freshSecret()

            val verifiedContext =
                ToolExecutionContext(
                    repositoryProvider = repositoryProvider,
                    actorVerifier =
                        object : ActorVerifier {
                            override suspend fun verify(actor: ActorClaim): VerificationResult =
                                VerificationResult(status = VerificationStatus.VERIFIED, verifier = "jwks")
                        }
                )

            val params =
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                noteWithActorJson(
                                    itemId = itemId,
                                    key = "verified-approach",
                                    role = "work",
                                    body = "Verified body",
                                    actor = actorJson(id = "agent-5", proof = secret)
                                )
                            )
                        }
                    )
                }

            val result = manageNotesTool.execute(params, verifiedContext) as JsonObject
            assertFalse(result.toString().contains(secret), "response must be SECRET-free")

            val note = (result["data"] as JsonObject)["notes"]!!.jsonArray[0].jsonObject
            assertFalse(note["actor"]!!.jsonObject.containsKey("proof"), "echoed actor must not carry a proof key")

            val verification = note["verification"]!!.jsonObject
            assertEquals("verified", verification["status"]!!.jsonPrimitive.content)
            assertEquals("jwks", verification["verifier"]!!.jsonPrimitive.content)
        }

    // -----------------------------------------------------------------------
    // S14a — blank / absent proof: still no proof key (guard, not just SECRET-based)
    // -----------------------------------------------------------------------

    @Test
    fun `S14a manage_notes with a blank proof omits the proof key`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            val params =
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                noteWithActorJson(
                                    itemId = itemId,
                                    key = "blank-proof",
                                    role = "work",
                                    body = "Blank proof body",
                                    actor = actorJson(id = "agent-6", proof = "")
                                )
                            )
                        }
                    )
                }

            val result = manageNotesTool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected success; got $result")
            val actor = (result["data"] as JsonObject)["notes"]!!.jsonArray[0].jsonObject["actor"]!!.jsonObject
            assertFalse(actor.containsKey("proof"), "actor with a blank proof must not carry a proof key")
        }

    @Test
    fun `S14a manage_notes with an absent proof field omits the proof key`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            val params =
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                // actorJson with proof = null omits the "proof" field entirely.
                                noteWithActorJson(
                                    itemId = itemId,
                                    key = "absent-proof",
                                    role = "work",
                                    body = "Absent proof body",
                                    actor = actorJson(id = "agent-7", proof = null)
                                )
                            )
                        }
                    )
                }

            val result = manageNotesTool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected success; got $result")
            val actor = (result["data"] as JsonObject)["notes"]!!.jsonArray[0].jsonObject["actor"]!!.jsonObject
            assertFalse(actor.containsKey("proof"), "actor with no proof field must not carry a proof key")
        }

    // -----------------------------------------------------------------------
    // S15 — raw proof remains persisted in the repository (D2: MCP-only omission)
    // -----------------------------------------------------------------------

    @Test
    fun `S15 note repository still persists the raw proof though MCP responses omit it`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val secret = freshSecret()

            val upsertResult =
                manageNotesTool.execute(
                    buildJsonObject {
                        put("operation", "upsert")
                        put(
                            "notes",
                            buildJsonArray {
                                add(
                                    noteWithActorJson(
                                        itemId = itemId,
                                        key = "persisted-proof",
                                        role = "work",
                                        body = "Persisted proof body",
                                        actor = actorJson(id = "agent-8", proof = secret)
                                    )
                                )
                            }
                        )
                    },
                    context
                ) as JsonObject
            assertTrue(upsertResult["success"]!!.jsonPrimitive.boolean, "expected success; got $upsertResult")
            // The MCP echo itself must still be SECRET-free even though the value is persisted underneath.
            assertFalse(upsertResult.toString().contains(secret), "MCP echo must remain SECRET-free")

            val persisted = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "persisted-proof")
            assertTrue(persisted is Result.Success, "expected the note to be found; got $persisted")
            val note = persisted.data
            assertNotNull(note, "note should be persisted")
            assertEquals(secret, note.actorClaim?.proof, "raw proof must remain persisted per D2")
        }

    // -----------------------------------------------------------------------
    // Probe — idempotent replay (same requestId twice) must remain SECRET-free
    // -----------------------------------------------------------------------

    @Test
    fun `Probe idempotent replay of manage_notes upsert with the same requestId stays SECRET-free`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val secret = freshSecret()
            val requestId = UUID.randomUUID().toString()

            val params =
                buildJsonObject {
                    put("operation", "upsert")
                    put("requestId", requestId)
                    put("actor", actorJson(id = "agent-9"))
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                noteWithActorJson(
                                    itemId = itemId,
                                    key = "idempotent-note",
                                    role = "work",
                                    body = "Idempotent body",
                                    actor = actorJson(id = "agent-9", proof = secret)
                                )
                            )
                        }
                    )
                }

            val first = manageNotesTool.execute(params, context) as JsonObject
            assertFalse(first.toString().contains(secret), "first call must be SECRET-free")

            val second = manageNotesTool.execute(params, context) as JsonObject
            assertFalse(second.toString().contains(secret), "replayed call with the same requestId must remain SECRET-free")
        }
}

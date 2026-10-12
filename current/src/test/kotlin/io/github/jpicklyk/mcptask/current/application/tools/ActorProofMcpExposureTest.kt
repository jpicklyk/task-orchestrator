package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.envelopeOk
import io.github.jpicklyk.mcptask.current.test.getNoteVia
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.listNotesVia
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsertNoteVia
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for item d426fbfa: an actor proof (a bearer JWT) supplied on `notes_write`,
 * `notes_read`, `advance_item`, or `items_write` `create_tree` must never be echoed back over MCP.
 *
 * The 3.x tools funnel through [ActorClaim.toJson]; the 4.0 note tools have no proof mapping at all (a note view
 * maps `actor` to `{id, kind, parent?}` and `verification` to `{status, verifier?, reason?}`). These tests exercise
 * both end to end against a real (SQLite) database: the 3.x tools via `execute()`, the note tools through the real
 * dispatcher.
 *
 * "SECRET" below is a unique, JWT-shaped string used as the actor proof in every scenario; a
 * scenario passes only when SECRET is not a substring of the tool's serialized JSON response.
 */
class ActorProofMcpExposureTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var advanceItemTool: AdvanceItemTool

    /** A unique, JWT-shaped bearer credential used as the actor proof in a scenario. */
    private fun freshSecret(): String = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.${UUID.randomUUID()}"

    @BeforeEach
    fun setUp() {
        repositoryProvider = db.repositoryProvider()
        context = ToolExecutionContext(repositoryProvider, unitOfWork = db.unitOfWork())
        advanceItemTool = AdvanceItemTool()
    }

    /** Creates a WorkItem directly via the repository and returns its UUID string. */
    private suspend fun createTestItem(title: String = "Test Item"): String {
        val item = WorkItem(title = title)
        val result = context.workItemRepository().create(item)
        return (result.id).toString()
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

    // -----------------------------------------------------------------------
    // S1 - notes_write upsert never echoes proof
    // -----------------------------------------------------------------------

    @Test
    fun `S1 notes_write upsert omits proof and the read-back actor is proof-free`(): Unit =
        runBlocking {
            val itemId = UUID.fromString(createTestItem())
            val secret = freshSecret()

            val result =
                context.upsertNoteVia(
                    itemId,
                    "approach",
                    "work",
                    "Some approach text",
                    actor = actorJson(id = "agent-1", parent = "session-1", proof = secret)
                )
            assertTrue(envelopeOk(result), "expected success; got $result")
            assertFalse(result.toString().contains(secret), "notes_write response must be SECRET-free")

            val read = context.getNoteVia(itemId, "approach")
            assertFalse(read.toString().contains(secret), "notes_read get response must be SECRET-free")
            val actor = read["data"]!!.jsonObject["actor"]!!.jsonObject
            assertEquals("agent-1", actor["id"]!!.jsonPrimitive.content)
            assertEquals("subagent", actor["kind"]!!.jsonPrimitive.content)
            assertEquals("session-1", actor["parent"]!!.jsonPrimitive.content)
            assertFalse(actor.containsKey("proof"), "echoed actor must not carry a proof key")
        }

    // -----------------------------------------------------------------------
    // S2 - notes_read get/list never echo proof
    // -----------------------------------------------------------------------

    @Test
    fun `S2 notes_read get and list omit proof from actor`(): Unit =
        runBlocking {
            val itemId = UUID.fromString(createTestItem())
            val secret = freshSecret()

            context.upsertNoteVia(itemId, "spec", "queue", "Spec body", actor = actorJson(id = "agent-2", proof = secret))

            val getResult = context.getNoteVia(itemId, "spec")
            assertFalse(getResult.toString().contains(secret), "notes_read get response must be SECRET-free")
            assertFalse(getResult["data"]!!.jsonObject["actor"]!!.jsonObject.containsKey("proof"), "get: actor must not carry a proof key")

            val listResult = context.listNotesVia(itemId)
            assertFalse(listResult.toString().contains(secret), "notes_read list response must be SECRET-free")
            val listActor =
                listResult["data"]!!
                    .jsonObject["items"]!!
                    .jsonArray[0]
                    .jsonObject["actor"]!!
                    .jsonObject
            assertFalse(listActor.containsKey("proof"), "list: actor must not carry a proof key")
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
    // S4 — items_write create_tree top-level actor proof never leaks, including via follow-up notes_read
    // -----------------------------------------------------------------------

    @Test
    fun `S4 create_tree response and follow-up notes_read list are SECRET-free`(): Unit =
        runBlocking {
            val secret = freshSecret()

            val op =
                buildJsonObject {
                    put("op", "create_tree")
                    put(
                        "root",
                        buildJsonObject {
                            put("type", "test-item")
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

            val result = context.itemsWrite(op, actor = actorJson(id = "agent-4", proof = secret))
            val element = result.okResult(0)
            assertFalse(result.toString().contains(secret), "create_tree response must be SECRET-free")

            val rootId = element["id"]!!.jsonPrimitive.content

            val listResult = context.listNotesVia(UUID.fromString(rootId))
            assertFalse(listResult.toString().contains(secret), "follow-up notes_read list must be SECRET-free")
            val notes = listResult["data"]!!.jsonObject["items"]!!.jsonArray
            assertEquals(1, notes.size)
            assertFalse(notes[0].jsonObject["actor"]!!.jsonObject.containsKey("proof"), "note actor must not carry a proof key")
        }

    // -----------------------------------------------------------------------
    // S5 - a non-noop (jwks) verifier still keeps verification metadata, still omits proof
    // -----------------------------------------------------------------------

    @Test
    fun `S5 notes_write with a VERIFIED jwks actor keeps verification status but omits proof`(): Unit =
        runBlocking {
            val itemId = UUID.fromString(createTestItem())
            val secret = freshSecret()

            val verifiedContext =
                ToolExecutionContext(
                    repositoryProvider = repositoryProvider,
                    actorVerifier =
                        object : ActorVerifier {
                            override suspend fun verify(actor: ActorClaim): VerificationResult =
                                VerificationResult(status = VerificationStatus.VERIFIED, verifier = "jwks")
                        },
                    unitOfWork = db.unitOfWork()
                )

            val result =
                verifiedContext.upsertNoteVia(
                    itemId,
                    "verified-approach",
                    "work",
                    "Verified body",
                    actor = actorJson(id = "agent-5", proof = secret)
                )
            assertFalse(result.toString().contains(secret), "response must be SECRET-free")

            val read = verifiedContext.getNoteVia(itemId, "verified-approach")
            assertFalse(read.toString().contains(secret), "read-back must be SECRET-free")
            val note = read["data"]!!.jsonObject
            assertFalse(note["actor"]!!.jsonObject.containsKey("proof"), "echoed actor must not carry a proof key")

            val verification = note["verification"]!!.jsonObject
            assertEquals("verified", verification["status"]!!.jsonPrimitive.content)
            assertEquals("jwks", verification["verifier"]!!.jsonPrimitive.content)
        }

    // -----------------------------------------------------------------------
    // S14a - blank / absent proof: still no proof key (guard, not just SECRET-based)
    // -----------------------------------------------------------------------

    @Test
    fun `S14a notes_write with a blank proof omits the proof key`(): Unit =
        runBlocking {
            val itemId = UUID.fromString(createTestItem())

            val result =
                context.upsertNoteVia(itemId, "blank-proof", "work", "Blank proof body", actor = actorJson(id = "agent-6", proof = ""))
            assertTrue(envelopeOk(result), "expected success; got $result")
            val actor = context.getNoteVia(itemId, "blank-proof")["data"]!!.jsonObject["actor"]!!.jsonObject
            assertFalse(actor.containsKey("proof"), "actor with a blank proof must not carry a proof key")
        }

    @Test
    fun `S14a notes_write with an absent proof field omits the proof key`(): Unit =
        runBlocking {
            val itemId = UUID.fromString(createTestItem())

            // actorJson with proof = null omits the "proof" field entirely.
            val result =
                context.upsertNoteVia(itemId, "absent-proof", "work", "Absent proof body", actor = actorJson(id = "agent-7", proof = null))
            assertTrue(envelopeOk(result), "expected success; got $result")
            val actor = context.getNoteVia(itemId, "absent-proof")["data"]!!.jsonObject["actor"]!!.jsonObject
            assertFalse(actor.containsKey("proof"), "actor with no proof field must not carry a proof key")
        }

    // -----------------------------------------------------------------------
    // S15 - raw proof is scrubbed to null in the repository (item 983615e7 D5: proof evidence,
    // not the credential itself, is what gets persisted)
    // -----------------------------------------------------------------------

    @Test
    fun `S15 note repository stores no raw proof though MCP responses already omitted it`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val secret = freshSecret()

            val upsertResult =
                context.upsertNoteVia(
                    UUID.fromString(itemId),
                    "persisted-proof",
                    "work",
                    "Persisted proof body",
                    actor = actorJson(id = "agent-8", proof = secret)
                )
            assertTrue(envelopeOk(upsertResult), "expected success; got $upsertResult")
            // The MCP echo itself must still be SECRET-free even though it never reaches storage underneath.
            assertFalse(upsertResult.toString().contains(secret), "MCP echo must remain SECRET-free")

            val persisted = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "persisted-proof")
            assertNotNull(persisted, "expected the note to be found; got $persisted")
            assertNull(persisted.actorClaim?.proof, "raw proof must be scrubbed to null on write per item 983615e7 D5")
        }

    // -----------------------------------------------------------------------
    // Probe - idempotent replay (same idempotencyKey twice) must remain SECRET-free
    // -----------------------------------------------------------------------

    @Test
    fun `Probe idempotent replay of notes_write upsert with the same idempotencyKey stays SECRET-free`(): Unit =
        runBlocking {
            val itemId = UUID.fromString(createTestItem())
            val secret = freshSecret()
            val key = UUID.randomUUID().toString()
            val actor = actorJson(id = "agent-9", proof = secret)

            val first = context.upsertNoteVia(itemId, "idempotent-note", "work", "Idempotent body", actor = actor, idempotencyKey = key)
            assertFalse(first.toString().contains(secret), "first call must be SECRET-free")

            val second = context.upsertNoteVia(itemId, "idempotent-note", "work", "Idempotent body", actor = actor, idempotencyKey = key)
            assertTrue(second.toString().contains("\"replayed\":true"), "the second call is a replay: $second")
            assertFalse(second.toString().contains(secret), "replayed call with the same idempotencyKey must remain SECRET-free")
        }
}

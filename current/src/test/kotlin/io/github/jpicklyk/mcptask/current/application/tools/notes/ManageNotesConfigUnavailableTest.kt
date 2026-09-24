package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `diagnosis` note's "D10 (post-simplify amendment,
 * orchestrator; frozen before code)" entry on item `aa664be1` — scenario S18 is this file's
 * entire scope.
 *
 * Oracle: D10 — "manage_notes upsert: a `PerRootConfigUnavailableException` raised while
 * resolving one note's schema or limits mode fails ONLY that note, and its failure entry carries
 * `errorKind:"transient"` + `errorCode:"config_unavailable"` (nothing stored for it); the other
 * notes proceed. Mirrors advance_item per-transition (D5)." — plus D5 for the per-item transient
 * shape (`errorKind`/`errorCode`, no `retryAfterMs`).
 *
 * Per-note failure entry shape (as declared for this dispatch, not read from src/main): an object
 * with `index`, `error`, `errorKind = "transient"`, `errorCode = "config_unavailable"`, sitting in
 * the upsert response's `data.failures` array — the same array/field names
 * [ManageNotesToolTest] already exercises for ordinary validation failures (e.g. "upsert
 * validates itemId exists", "invalid actor kind in upsert returns failure for that note") and
 * that [io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesToolTest]
 * confirms carries a zero-based `index` matching the request array's position.
 *
 * Harness: a REAL [PerRootConfigService] backed by a REAL [ProjectConfigRepository] (H2-backed
 * [DefaultRepositoryProvider], schema via [DirectDatabaseSchemaManager]) wrapped in a private
 * [FailableProjectConfigRepository] whose reads can be switched to `Result.Error` — the same "own
 * copy per file" harness pattern used by the sibling `AdvanceItemToolConfigUnavailableTest` /
 * `CreateItemConfigUnavailableTest` / `CompleteTreeToolConfigUnavailableTest` files (this item's
 * file-ownership rule forbids a shared harness file). [ManageNotesTool] itself runs against the
 * real DB-backed repositories via [DefaultRepositoryProvider], mirroring
 * [ManageNotesToolTest]'s own setup.
 *
 * Cold/no-LKG per D1/D3: `failFingerprint` is armed on the wrapper BEFORE the
 * [PerRootConfigService] instance under test ever attempts a read for root `rootId` — so the
 * service's in-memory cache holds nothing for that root when the first read fails, matching
 * S18's "cold: getFingerprint returns Result.Error before any successful read". No config row is
 * ever pushed for `rootId` either; the failure is a read error, not genuine absence (D2), which
 * is exactly the distinction D1 depends on.
 */
class ManageNotesConfigUnavailableTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var failable: FailableProjectConfigRepository
    private lateinit var tool: ManageNotesTool
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
            repositoryProvider = DefaultRepositoryProvider(databaseManager)

            failable = FailableProjectConfigRepository(repositoryProvider.projectConfigRepository())
            // Armed before any read is ever attempted through this PerRootConfigService instance,
            // so root `rootId` starts cold with no last-known-good entry (D1/D3).
            failable.failFingerprint = true

            val root = WorkItem(title = "Root R (S18)")
            repositoryProvider.workItemRepository().create(root)
            rootId = root.id

            tool = ManageNotesTool()
        }

    private fun coldContext() = ToolExecutionContext(repositoryProvider, perRootConfigService = PerRootConfigService(failable))

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun createItem(
        title: String,
        rootId: UUID? = null
    ): UUID {
        val item = WorkItem(title = title, rootId = rootId)
        val result = repositoryProvider.workItemRepository().create(item)
        return (result as Result.Success).data.id
    }

    private fun noteObj(
        itemId: UUID,
        key: String,
        body: String
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("key", JsonPrimitive(key))
            put("role", JsonPrimitive("work"))
            put("body", JsonPrimitive(body))
        }

    @Test
    fun `S18 - a note on a cold failing root fails alone transient config_unavailable, rootId-less sibling still stores`(): Unit =
        runBlocking {
            val context = coldContext()
            val itemA = createItem("Item A (under cold root R)", rootId = rootId)
            val itemB = createItem("Item B (no rootId)", rootId = null)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    noteObj(itemA, "note-a", "Body A"),
                                    noteObj(itemB, "note-b", "Body B")
                                )
                            )
                    ),
                    context
                ) as JsonObject

            assertEquals(
                true,
                result["success"]?.jsonPrimitive?.boolean,
                "D10: a per-note config_unavailable classification must not fail the whole call"
            )
            val data = result["data"] as JsonObject
            assertEquals(1, data["upserted"]?.jsonPrimitive?.int, "only note B (rootId-less) should succeed")
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)

            val failures = data["failures"]!!.jsonArray
            assertEquals(1, failures.size)
            val failureA = failures[0].jsonObject
            assertEquals(0, failureA["index"]?.jsonPrimitive?.int, "note A is at batch index 0")
            assertEquals(
                "transient",
                failureA["errorKind"]?.jsonPrimitive?.content,
                "D10/D5: classified transient, not a generic validation failure"
            )
            assertEquals("config_unavailable", failureA["errorCode"]?.jsonPrimitive?.content)
            assertTrue(
                failureA["error"]?.jsonPrimitive?.content?.isNotBlank() == true,
                "a human-readable error message must still be present alongside the classification"
            )

            val storedNotes = data["notes"]!!.jsonArray
            assertEquals(1, storedNotes.size, "only note B's entry should appear among stored notes")
            val storedB = storedNotes[0].jsonObject
            assertEquals(itemB.toString(), storedB["itemId"]?.jsonPrimitive?.content)
            assertEquals("note-b", storedB["key"]?.jsonPrimitive?.content)

            // (2) nothing persisted for the failed note.
            val notesForA = (repositoryProvider.noteRepository().findByItemId(itemA) as Result.Success).data
            assertTrue(notesForA.isEmpty(), "D10: nothing is stored for the note that failed schema/limits resolution")

            // (3) the rootId-less note really landed in the repository, not just in the response.
            val notesForB = (repositoryProvider.noteRepository().findByItemId(itemB) as Result.Success).data
            assertEquals(1, notesForB.size)
            assertEquals("note-b", notesForB[0].key)
            assertEquals("Body B", notesForB[0].body)
        }

    @Test
    fun `probe - a lone note on the same cold failing root is classified the same way, not surfaced as a whole-call error`(): Unit =
        runBlocking {
            val context = coldContext()
            val itemA = createItem("Item A alone (under cold root R)", rootId = rootId)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to JsonArray(listOf(noteObj(itemA, "note-a-alone", "Body A alone")))
                    ),
                    context
                ) as JsonObject

            assertEquals(
                true,
                result["success"]?.jsonPrimitive?.boolean,
                "a single-note config_unavailable failure must still be a normal (non-error-envelope) tool response"
            )
            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]?.jsonPrimitive?.int)
            assertEquals(1, data["failed"]?.jsonPrimitive?.int)

            val failures = data["failures"]!!.jsonArray
            assertEquals(1, failures.size)
            val failure = failures[0].jsonObject
            assertEquals(0, failure["index"]?.jsonPrimitive?.int)
            assertEquals("transient", failure["errorKind"]?.jsonPrimitive?.content)
            assertEquals("config_unavailable", failure["errorCode"]?.jsonPrimitive?.content)

            val notesForA = (repositoryProvider.noteRepository().findByItemId(itemA) as Result.Success).data
            assertTrue(notesForA.isEmpty(), "the single failed note must not be persisted")
        }
}

/**
 * Wraps a real [ProjectConfigRepository] and lets tests force [get]/[getFingerprint] to return
 * `Result.Error(RepositoryError.DatabaseError("x"))` on demand. Own copy for this file — see the
 * identical class in the sibling config-unavailable test files for the full rationale (no shared
 * harness file per this item's file-ownership rule).
 */
private class FailableProjectConfigRepository(
    private val delegate: ProjectConfigRepository
) : ProjectConfigRepository by delegate {
    @Volatile var failFingerprint: Boolean = false

    @Volatile var failGet: Boolean = false

    override suspend fun getFingerprint(rootItemId: UUID) =
        if (failFingerprint) Result.Error(RepositoryError.DatabaseError("x")) else delegate.getFingerprint(rootItemId)

    override suspend fun get(rootItemId: UUID) = if (failGet) Result.Error(RepositoryError.DatabaseError("x")) else delegate.get(rootItemId)
}

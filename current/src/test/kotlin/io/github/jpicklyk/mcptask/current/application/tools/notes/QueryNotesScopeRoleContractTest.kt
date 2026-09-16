package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.SearchResult
import io.github.jpicklyk.mcptask.current.domain.repository.SearchScope
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent contract tests for item 9ad250e3 — `query_notes` `search` mode used to validate
 * `scope.role` against `setOf("queue","work","review")` (case-sensitively) and reject a miss with
 * `VALIDATION_ERROR`, even though `scope.role` is not, and never was, a declared property of the
 * `search` `parameterSchema` (only `itemId`/`ancestorId` are declared there) and the value was
 * never threaded into the `SearchScope` passed to the repository (hard-coded `role = null`).
 *
 * Per the frozen `test-plan` note (disposition: REMOVE the dead validation, not honour it — three
 * declared surfaces already say role filtering is `list`-only): after the fix, an undeclared
 * `scope.role` of any value — valid, invalid, or mixed-case — must be silently ignored, exactly
 * like any other undeclared JSON property, and must never reach the repository query.
 *
 * Oracle sources: the tool's own `description` ("`search`'s `scope` has no role field"), the
 * declared `scope` schema (`itemId`/`ancestorId` only, no `role`), and the `scope` schema's own
 * description ("All fields are optional and combined with AND.").
 *
 * S1-S4 and S6 use [MockRepositoryProvider] against the `NoteRepository` interface directly (no
 * FTS5/H2 dependency — mirrors [QueryNotesToolFtsDecoratorDispatchTest]'s harness). S5 exercises
 * the untouched top-level `list` `role` filter against a real H2-backed repository, matching
 * [QueryNotesToolTest]'s convention, to guard against an over-broad deletion that also removes the
 * unrelated top-level `role` filter.
 */
class QueryNotesScopeRoleContractTest {
    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun emptySentinel() = SearchResult(hits = emptyList(), totalHits = 0, nextOffset = null)

    // ──────────────────────────────────────────────
    // S1 — failure->happy, red pre-fix: an invalid scope.role value is ignored, not rejected.
    // ──────────────────────────────────────────────

    @Test
    fun `S1 - scope role with an invalid value is ignored, not rejected`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            coEvery {
                mocks.noteRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = any(),
                    limit = any(),
                    offset = any()
                )
            } returns emptySentinel()

            val result =
                QueryNotesTool().execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                        "scope" to buildJsonObject { put("role", JsonPrimitive("bogus")) }
                    ),
                    mocks.context()
                ) as JsonObject

            // Pre-fix this was success=false / VALIDATION_ERROR with the repository never called —
            // the item's regression: an undeclared property must be ignored, not rejected.
            assertTrue(
                result["success"]!!.jsonPrimitive.boolean,
                "an undeclared scope.role of any value must not cause a VALIDATION_ERROR"
            )
            coVerify(exactly = 1) {
                mocks.noteRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = any(),
                    limit = any(),
                    offset = any()
                )
            }
        }

    // ──────────────────────────────────────────────
    // S2 — edge (mixed case), red pre-fix: the removed check was case-sensitive.
    // ──────────────────────────────────────────────

    @Test
    fun `S2 - scope role with mixed case is ignored, not rejected`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            coEvery {
                mocks.noteRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = any(),
                    limit = any(),
                    offset = any()
                )
            } returns emptySentinel()

            val result =
                QueryNotesTool().execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                        // "WORK" would have failed the removed check even though "work" would not —
                        // it was case-sensitive, unlike the top-level `role` check (S5).
                        "scope" to buildJsonObject { put("role", JsonPrimitive("WORK")) }
                    ),
                    mocks.context()
                ) as JsonObject

            assertTrue(
                result["success"]!!.jsonPrimitive.boolean,
                "a mixed-case scope.role must not cause a VALIDATION_ERROR"
            )
            coVerify(exactly = 1) {
                mocks.noteRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = any(),
                    limit = any(),
                    offset = any()
                )
            }
        }

    // ──────────────────────────────────────────────
    // S3 — happy: a valid scope.role succeeds and the captured SearchScope.role is null.
    // ──────────────────────────────────────────────

    @Test
    fun `S3 - scope role with a valid value is dropped, captured SearchScope role is null`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val scopeSlot = slot<SearchScope>()
            coEvery {
                mocks.noteRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = capture(scopeSlot),
                    limit = any(),
                    offset = any()
                )
            } returns emptySentinel()

            val result =
                QueryNotesTool().execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                        "scope" to buildJsonObject { put("role", JsonPrimitive("work")) }
                    ),
                    mocks.context()
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertNull(
                scopeSlot.captured.role,
                "scope.role must be dropped, never threaded through into the repository's SearchScope"
            )
        }

    // ──────────────────────────────────────────────
    // S4 — edge: scope.role alongside a sibling itemId — siblings undisturbed, role still null.
    // ──────────────────────────────────────────────

    @Test
    fun `S4 - scope role alongside itemId - itemId threaded through, role stays null`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val scopeSlot = slot<SearchScope>()
            coEvery {
                mocks.noteRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = capture(scopeSlot),
                    limit = any(),
                    offset = any()
                )
            } returns emptySentinel()

            val itemId = UUID.randomUUID().toString()

            val result =
                QueryNotesTool().execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                        "scope" to
                            buildJsonObject {
                                put("role", JsonPrimitive("work"))
                                put("itemId", JsonPrimitive(itemId))
                            }
                    ),
                    mocks.context()
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val captured = scopeSlot.captured
            assertEquals(
                itemId,
                captured.itemId.toString(),
                "the declared sibling field itemId must still be threaded through unaffected"
            )
            assertNull(captured.role, "role stays null even alongside a populated declared sibling field")
        }

    // ──────────────────────────────────────────────
    // S5 — regression guard: the TOP-LEVEL list `role` filter is untouched by the scope.role
    // removal. Real H2-backed repository, matching QueryNotesToolTest's convention, so this
    // exercises the actual findByItemId(itemId, role) path rather than a mocked signature.
    // ──────────────────────────────────────────────

    @Test
    fun `S5 - top-level list role filter is untouched by the scope role removal`(): Unit =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            DirectDatabaseSchemaManager().updateSchema()
            val context = ToolExecutionContext(DefaultRepositoryProvider(DatabaseManager(database)))
            val queryTool = QueryNotesTool()
            val manageTool = ManageNotesTool()

            val itemId =
                ((context.workItemRepository().create(WorkItem(title = "S5 item"))) as Result.Success).data.id.toString()

            suspend fun upsert(
                key: String,
                role: String
            ) = manageTool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive(key))
                                    put("role", JsonPrimitive(role))
                                }
                            )
                        )
                ),
                context
            )

            upsert("plan", "queue")
            upsert("approach", "work")

            val filtered =
                queryTool.execute(
                    params(
                        "operation" to JsonPrimitive("list"),
                        "itemId" to JsonPrimitive(itemId),
                        "role" to JsonPrimitive("work")
                    ),
                    context
                ) as JsonObject

            assertTrue(filtered["success"]!!.jsonPrimitive.boolean)
            val filteredData = filtered["data"] as JsonObject
            assertEquals(1, filteredData["total"]!!.jsonPrimitive.int)
            assertEquals(
                "work",
                filteredData["notes"]!!
                    .jsonArray[0]
                    .jsonObject["role"]!!
                    .jsonPrimitive.content
            )

            // An invalid TOP-LEVEL role must still be rejected — this is the declared `list`
            // `role` filter, an entirely separate parameter from the removed `scope.role`.
            // validateParams() throws directly (the MCP adapter calls it before execute(); the
            // tool's own execute() does not re-validate) — asserted the same way the existing
            // `QueryNotesToolTest.list with invalid role throws` case does.
            val ex =
                assertFailsWith<ToolValidationException> {
                    queryTool.validateParams(
                        params(
                            "operation" to JsonPrimitive("list"),
                            "itemId" to JsonPrimitive(itemId),
                            "role" to JsonPrimitive("bogus")
                        )
                    )
                }
            assertEquals(
                "Invalid role: 'bogus'. Must be one of: queue, work, review",
                ex.message
            )
        }

    // ──────────────────────────────────────────────
    // S6 — edge: empty / absent / null scope.role all collapse to the same "no scope.role" state.
    // ──────────────────────────────────────────────

    @Test
    fun `S6a - scope absent - repository receives a null SearchScope`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val scopeSlot = slot<SearchScope?>()
            coEvery {
                mocks.noteRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = captureNullable(scopeSlot),
                    limit = any(),
                    offset = any()
                )
            } returns emptySentinel()

            val result =
                QueryNotesTool().execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle")
                    ),
                    mocks.context()
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertNull(scopeSlot.captured, "omitting scope entirely must pass a null SearchScope through")
        }

    @Test
    fun `S6b - scope empty object - non-null SearchScope with all fields null`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val scopeSlot = slot<SearchScope>()
            coEvery {
                mocks.noteRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = capture(scopeSlot),
                    limit = any(),
                    offset = any()
                )
            } returns emptySentinel()

            val result =
                QueryNotesTool().execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                        "scope" to buildJsonObject { }
                    ),
                    mocks.context()
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val captured = scopeSlot.captured
            assertNull(captured.itemId, "an empty scope object still yields a non-null SearchScope")
            assertNull(captured.role, "role remains null for an empty scope object")
        }

    @Test
    fun `S6c - scope role explicit null - same as an empty scope object`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val scopeSlot = slot<SearchScope>()
            coEvery {
                mocks.noteRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = capture(scopeSlot),
                    limit = any(),
                    offset = any()
                )
            } returns emptySentinel()

            val result =
                QueryNotesTool().execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                        "scope" to buildJsonObject { put("role", JsonNull) }
                    ),
                    mocks.context()
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val captured = scopeSlot.captured
            assertNull(captured.itemId, "an explicit null scope.role must behave like an absent one")
            assertNull(captured.role, "role remains null for an explicit scope.role: null")
        }
}

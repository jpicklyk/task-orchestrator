package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for `query_notes` `search` mode `scope` handling.
 *
 * History: item 9ad250e3 removed a dead, case-sensitive `scope.role` check and made an undeclared `scope.role` a silent
 * no-op. Item 4a15997e (unified search core) reverses the disposition (task-scope item 8, plan 8.1): `scope.role` and
 * `scope.tags` are not declared properties of the `search` schema and silently dropping them hid caller mistakes, so a
 * PRESENT `scope.role` or `scope.tags` is now rejected with `VALIDATION_ERROR`, and `scope.role: null` is treated as
 * absent (AC5). `limit` above 100 is rejected as well.
 *
 * Oracles: task-scope item 8 and AC5; the tool `description` ("`search`'s `scope` has no role field"); the declared
 * `scope` schema (`itemId`/`ancestorId` only); `ErrorCodes.VALIDATION_ERROR`. Rejections are driven through the real
 * [McpToolAdapter] (the runtime order is validateParams, then execute, inside one envelope), and the `SearchIndex` mock
 * is strict, so a rejected call that still reached the index would surface as a different error.
 *
 * S1-S4 and S6 use [MockRepositoryProvider] with its `searchIndex` port mock. S5 exercises the untouched top-level
 * `list` `role` filter against a real SQLite-backed repository.
 */
class QueryNotesScopeRoleContractTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun stubIndex(
        mocks: MockRepositoryProvider,
        filterSlot: io.mockk.CapturingSlot<ScopeFilter>? = null,
        candidates: List<Candidate> = emptyList(),
    ) {
        if (filterSlot != null) {
            coEvery { mocks.searchIndex.candidates(any(), any(), any(), capture(filterSlot), any()) } returns candidates
        } else {
            coEvery { mocks.searchIndex.candidates(any(), any(), any(), any(), any()) } returns candidates
        }
        coEvery { mocks.searchIndex.titles(any()) } returns emptyMap()
    }

    /** Drives [tool] through the real adapter, so the runtime validate-then-execute order decides the envelope. */
    private suspend fun callThroughAdapter(
        mocks: MockRepositoryProvider,
        tool: ToolDefinition,
        args: Map<String, JsonElement>,
    ): CallToolResult {
        val server =
            Server(serverInfo = Implementation(name = "scope-contract-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        McpToolAdapter().registerToolWithServer(server, tool, mocks.context())
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "scope-contract-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities()),
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        try {
            return client.callTool(
                buildCallToolRequest {
                    name = tool.name
                    arguments(JsonObject(args))
                }
            )
        } finally {
            closeInMemoryPair(client, server)
        }
    }

    private fun assertValidationError(
        result: CallToolResult,
        label: String,
    ) {
        assertEquals(true, result.isError, "$label must be rejected: ${result.content}")
        val error = assertNotNull(result.structuredContent?.get("error")?.jsonObject, "$label: ${result.content}")
        assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]?.jsonPrimitive?.content, label)
    }

    private fun searchArgs(scope: JsonObject? = null): Map<String, JsonElement> =
        buildMap {
            put("operation", JsonPrimitive("search"))
            put("query", JsonPrimitive("needle"))
            if (scope != null) put("scope", scope)
        }

    // ──────────────────────────────────────────────
    // S1-S4 — a present scope.role is rejected, whatever its value or its siblings (AC5).
    // ──────────────────────────────────────────────

    @Test
    fun `S1 - scope role with an invalid value is rejected`() =
        runBlocking {
            val mocks = MockRepositoryProvider().also { stubIndex(it) }

            val result =
                callThroughAdapter(mocks, QueryNotesTool(), searchArgs(buildJsonObject { put("role", JsonPrimitive("bogus")) }))

            assertValidationError(result, "scope.role=bogus")
            coVerify(exactly = 0) { mocks.searchIndex.candidates(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `S2 - scope role with mixed case is rejected`() =
        runBlocking {
            val mocks = MockRepositoryProvider().also { stubIndex(it) }

            val result =
                callThroughAdapter(mocks, QueryNotesTool(), searchArgs(buildJsonObject { put("role", JsonPrimitive("WORK")) }))

            assertValidationError(result, "scope.role=WORK")
            coVerify(exactly = 0) { mocks.searchIndex.candidates(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `S3 - scope role with a valid value is rejected, not silently dropped`() =
        runBlocking {
            val mocks = MockRepositoryProvider().also { stubIndex(it) }

            val result =
                callThroughAdapter(mocks, QueryNotesTool(), searchArgs(buildJsonObject { put("role", JsonPrimitive("work")) }))

            assertValidationError(result, "scope.role=work")
            coVerify(exactly = 0) { mocks.searchIndex.candidates(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `S4 - scope role alongside itemId is still rejected`() =
        runBlocking {
            val mocks = MockRepositoryProvider().also { stubIndex(it) }

            val result =
                callThroughAdapter(
                    mocks,
                    QueryNotesTool(),
                    searchArgs(
                        buildJsonObject {
                            put("role", JsonPrimitive("work"))
                            put("itemId", JsonPrimitive(UUID.randomUUID().toString()))
                        }
                    ),
                )

            assertValidationError(result, "scope.role alongside scope.itemId")
            coVerify(exactly = 0) { mocks.searchIndex.candidates(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `S4b - scope tags is rejected in array and string form`() =
        runBlocking {
            listOf<JsonElement>(JsonArray(listOf(JsonPrimitive("alpha"))), JsonPrimitive("alpha")).forEach { tags ->
                val mocks = MockRepositoryProvider().also { stubIndex(it) }

                val result = callThroughAdapter(mocks, QueryNotesTool(), searchArgs(buildJsonObject { put("tags", tags) }))

                assertValidationError(result, "scope.tags=$tags")
                coVerify(exactly = 0) { mocks.searchIndex.candidates(any(), any(), any(), any(), any()) }
            }
        }

    @Test
    fun `S7 - limit above 100 is rejected and 100 is accepted`() =
        runBlocking {
            listOf(101, 500).forEach { tooBig ->
                val mocks = MockRepositoryProvider().also { stubIndex(it) }

                val result =
                    callThroughAdapter(mocks, QueryNotesTool(), searchArgs() + ("limit" to JsonPrimitive(tooBig)))

                assertValidationError(result, "limit=$tooBig")
                coVerify(exactly = 0) { mocks.searchIndex.candidates(any(), any(), any(), any(), any()) }
            }

            val mocks = MockRepositoryProvider()
            val many =
                (0 until 150).map { i ->
                    Candidate(
                        id = UUID(0L, 9000L - i),
                        ownerItemId = UUID(0L, 100L + i),
                        noteKey = "k$i",
                        rank = -1000.0 + i,
                        field = "body",
                        snippet = "s <mark>needle</mark>",
                    )
                }
            stubIndex(mocks, candidates = many)
            val ok =
                QueryNotesTool().execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                        "limit" to JsonPrimitive(100),
                    ),
                    mocks.context()
                ) as JsonObject
            assertTrue(ok["success"]!!.jsonPrimitive.boolean)
            assertEquals(100, (ok["data"] as JsonObject)["hits"]!!.jsonArray.size)
        }

    // ──────────────────────────────────────────────
    // S5 — regression guard: the TOP-LEVEL list `role` filter is untouched by the scope.role
    // rejection. Real SQLite-backed repository, matching QueryNotesToolTest's convention, so this
    // exercises the actual findByItemId(itemId, role) path rather than a mocked signature.
    // ──────────────────────────────────────────────

    @Test
    fun `S5 - top-level list role filter is untouched by the scope role rejection`(): Unit =
        runBlocking {
            val context = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
            val queryTool = QueryNotesTool()
            val manageTool = ManageNotesTool()

            val itemId =
                ((context.workItemRepository().create(WorkItem(title = "S5 item")))!!).id.toString()

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

            // An invalid TOP-LEVEL role must still be rejected, as before: this is the declared `list` `role` filter,
            // an entirely separate parameter from `scope.role`. validateParams() throws directly (the MCP adapter
            // calls it before execute(); the tool's own execute() does not re-validate).
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
    // S6 — absent / empty / null scope all collapse to the same "no scope constraint" state, and the declared
    // scope fields still reach the index filter.
    // ──────────────────────────────────────────────

    private fun assertNoRequestScope(filter: ScopeFilter) {
        assertNull(filter.itemId)
        assertNull(filter.ancestorId)
        assertNull(filter.roles)
        assertNull(filter.tagsAny)
    }

    @Test
    fun `S6a - scope absent - the index receives an unconstrained filter`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val filterSlot = slot<ScopeFilter>()
            stubIndex(mocks, filterSlot)

            val result = QueryNotesTool().execute(params(*searchArgs().toList().toTypedArray()), mocks.context()) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertNoRequestScope(filterSlot.captured)
        }

    @Test
    fun `S6b - scope empty object - succeeds with an unconstrained filter`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val filterSlot = slot<ScopeFilter>()
            stubIndex(mocks, filterSlot)

            val result =
                QueryNotesTool().execute(params(*searchArgs(buildJsonObject { }).toList().toTypedArray()), mocks.context()) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertNoRequestScope(filterSlot.captured)
        }

    @Test
    fun `S6c - scope role explicit null is treated as absent and accepted`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val filterSlot = slot<ScopeFilter>()
            stubIndex(mocks, filterSlot)

            val result =
                callThroughAdapter(mocks, QueryNotesTool(), searchArgs(buildJsonObject { put("role", JsonNull) }))

            assertEquals(false, result.isError ?: false, "scope.role: null must not be rejected: ${result.content}")
            assertNoRequestScope(filterSlot.captured)
        }

    @Test
    fun `S6d - scope itemId and ancestorId reach the index filter`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val filterSlot = slot<ScopeFilter>()
            stubIndex(mocks, filterSlot)
            val itemId = UUID.randomUUID()
            val ancestorId = UUID.randomUUID()

            val result =
                QueryNotesTool().execute(
                    params(
                        *searchArgs(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(itemId.toString()))
                                put("ancestorId", JsonPrimitive(ancestorId.toString()))
                            }
                        ).toList().toTypedArray()
                    ),
                    mocks.context()
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(itemId, filterSlot.captured.itemId)
            assertEquals(ancestorId, filterSlot.captured.ancestorId)
            assertNull(filterSlot.captured.roles)
        }
}

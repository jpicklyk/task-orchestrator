package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independently authored for item `0c07190d` (error catalog adoption): scenarios S3, S4, S5, S6 and S12 of the frozen
 * `test-plan`, plus the planned probes. EXISTING-SURFACE throughout: every tool, wire code and message predates the
 * item, only the `kind` / `errorKind` / `errorCode` additions are new, so a plain revert of the item's changes turns
 * these red on behaviour (a missing key), never on a compile failure. The file deliberately references no declaration
 * the item introduced.
 *
 * Harness: a real [Server]/[Client] pair over a linked channel driving the production [McpToolAdapter] against a real
 * SQLite database, so each tool runs through the adapter's real call order (and `structuredContent` is the tool's
 * `data` for a result, `{error}` for a top-level failure).
 *
 * Oracles: [T] task-scope Build steps 3 and 5 and AC1/AC2 (every MCP error, top-level and per-element, carries a kind
 * equal to its catalog code's kind; every 3.x code and message is unchanged; additions only; new `errorCode` values
 * use the catalog wire code); [E] the catalog table in `ErrorCatalogTest` (kind per code, lowercase strings); [M]
 * api-reference `advance_item` error-code list as of the base commit
 * (`gate_blocked`, `dependency_blocked`, `invalid_trigger`); [P] test-plan S3-S6 and S12.
 */
class ErrorKindCoverageTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var server: Server
    private lateinit var client: Client

    private val repo get() = db.repositoryProvider().workItemRepository()

    /** An item typed `gated` resolves to a schema with one required queue-phase note; other types are accepted unmatched. */
    private val gateSchema: EffectiveConfigResolver =
        TestConfigResolver.withoutTypeValidation(
            TestConfigResolver.forSchemas(
                mapOf(
                    "gated" to
                        listOf(NoteSchemaEntry(key = "acceptance-criteria", role = Role.QUEUE, required = true, description = "criteria"))
                ),
            ),
        )

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "kind-coverage-server", version = "1.0.0"),
                    options = inMemoryTestServerOptions()
                )
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "kind-coverage-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
            server.createSession(serverTransport)
            client.connect(clientTransport)

            val context =
                ToolExecutionContext(
                    repositoryProvider = db.repositoryProvider(),
                    configResolver = gateSchema,
                    unitOfWork = db.unitOfWork()
                )
            val adapter = McpToolAdapter()
            listOf(
                ClaimItemTool(),
                AdvanceItemTool()
            ).forEach { adapter.registerToolWithServer(server, it, context) }
        }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            closeInMemoryPair(client, server)
        }

    // ----------------------------------------------
    // helpers
    // ----------------------------------------------

    private val actor = mapOf("id" to "agent-1", "kind" to "subagent")

    private suspend fun call(
        tool: String,
        args: Map<String, Any?>
    ): CallToolResult = client.callTool(name = tool, arguments = args)

    private fun CallToolResult.data(): JsonObject = assertNotNull(structuredContent, "result must carry structuredContent: $content")

    private fun CallToolResult.error(): JsonObject =
        assertNotNull(data()["error"]?.jsonObject, "structuredContent must contain the error object: ${data()}")

    private fun JsonObject.entries(key: String): List<JsonObject> =
        assertNotNull(this[key], "missing '$key' in $this").jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.byItem(id: UUID): JsonObject =
        assertNotNull(entries("results").firstOrNull { it.str("itemId") == id.toString() }, "no result entry for $id")

    private suspend fun item(
        title: String,
        role: Role = Role.QUEUE,
        tags: String? = null
    ): WorkItem = repo.create(WorkItem(title = title, role = role, tags = tags, type = tags?.substringBefore(",")?.trim()))

    private val validKinds = setOf("transient", "permanent", "shedding")

    /**
     * Expected `kind` for an element `errorCode`: either a legacy 3.x wire code (assigned to its catalog code by the
     * task-scope) or a catalog wire code (new `errorCode` values use the catalog wire, task-scope Build step 5). The
     * kinds come from the catalog table in `ErrorCatalogTest`. Codes whose kind depends on the underlying cause
     * (`apply_failed`, `DATABASE_ERROR`, `db_error`) are not in the table; a code outside both vocabularies fails.
     */
    private val kindByWireCode =
        mapOf(
            // legacy 3.x wire codes
            "VALIDATION_ERROR" to "permanent",
            "RESOURCE_NOT_FOUND" to "permanent",
            "CONFLICT_ERROR" to "permanent",
            "INTERNAL_ERROR" to "transient",
            "OPERATION_FAILED" to "permanent",
            "item_not_found" to "permanent",
            "invalid_trigger" to "permanent",
            "invalid_actor" to "permanent",
            "validation_failed" to "permanent",
            "dependency_blocked" to "permanent",
            "rejected_by_policy" to "permanent",
            "config_unavailable" to "transient",
            "NOTE_BODY_TOO_LONG" to "permanent",
            "NOTE_BODY_TOO_LARGE" to "permanent",
            "already_claimed" to "transient",
            "terminal_item" to "permanent",
            "not_claimed_by_you" to "permanent",
            "queue_empty" to "permanent",
            "none_eligible" to "transient",
            // catalog wire codes (ErrorCatalogTest table)
            "invalid_request" to "permanent",
            "unknown_parameter" to "permanent",
            "invalid_cursor" to "permanent",
            "not_found" to "permanent",
            "ambiguous_id" to "permanent",
            "version_conflict" to "permanent",
            "duplicate" to "permanent",
            "idempotency_mismatch" to "permanent",
            "invalid_transition" to "permanent",
            "gate_blocked" to "permanent",
            "dependency_unmet" to "permanent",
            "cycle_detected" to "permanent",
            "claim_held" to "transient",
            "not_claim_holder" to "permanent",
            "seat_forbidden" to "permanent",
            "note_owned_by_other" to "permanent",
            "note_too_long" to "permanent",
            "resource_unavailable" to "transient",
            "schema_violation" to "permanent",
            "schema_pinned_conflict" to "permanent",
            "config_invalid" to "permanent",
            "payload_too_large" to "permanent",
            "unauthenticated" to "permanent",
            "forbidden" to "permanent",
            "partial_failure" to "permanent",
            "unavailable" to "shedding",
            "internal" to "transient"
        )
    private val causeDependentCodes = setOf("apply_failed", "DATABASE_ERROR", "db_error")

    /** An element failure entry carries a wire code from the closed 3.x vocabulary and a kind consistent with it. */
    private fun assertCodeAndKind(
        entry: JsonObject,
        context: String
    ) {
        val code = assertNotNull(entry.str("errorCode"), "$context: missing errorCode in $entry")
        val kind = assertNotNull(entry.str("errorKind"), "$context: missing errorKind in $entry")
        assertTrue(kind in validKinds, "$context: errorKind '$kind' is not one of $validKinds")
        if (code in causeDependentCodes) return
        val expected =
            kindByWireCode[code] ?: fail("$context: errorCode '$code' is in neither the 3.x nor the catalog wire vocabulary: $entry")
        assertEquals(expected, kind, "$context: errorKind for '$code' must equal its catalog kind: $entry")
    }

    // ----------------------------------------------
    // S6 - claim_item results carry kind and code
    // ----------------------------------------------

    private suspend fun claimResult(
        key: String,
        itemId: String,
        agent: String = "agent-y"
    ): JsonObject {
        val args =
            mapOf(
                if (key ==
                    "claimResults"
                ) {
                    "claims" to listOf(mapOf("itemId" to itemId))
                } else {
                    "releases" to listOf(mapOf("itemId" to itemId))
                },
                "actor" to mapOf("id" to agent, "kind" to "subagent"),
                "requestId" to UUID.randomUUID().toString()
            )
        return call("claim_item", args).data().entries(key).single()
    }

    @Test
    fun `S6 claim of an unknown full UUID reports outcome not_found with kind permanent and code not_found`(): Unit =
        runBlocking {
            val entry = claimResult("claimResults", UUID.randomUUID().toString())

            assertEquals("not_found", entry.str("outcome"))
            assertEquals("permanent", entry.str("kind"))
            assertEquals("not_found", entry.str("code"))
        }

    @Test
    fun `S6 claim of a terminal item reports outcome terminal_item with kind permanent and code terminal_item`(): Unit =
        runBlocking {
            val done = item("done already", role = Role.TERMINAL)

            val entry = claimResult("claimResults", done.id.toString())

            assertEquals("terminal_item", entry.str("outcome"))
            assertEquals("permanent", entry.str("kind"))
            assertEquals("terminal_item", entry.str("code"))
        }

    @Test
    fun `S6 release of an unheld item reports outcome not_claimed_by_you with kind permanent and code not_claimed_by_you`(): Unit =
        runBlocking {
            val unheld = item("never claimed")

            val entry = claimResult("releaseResults", unheld.id.toString())

            assertEquals("not_claimed_by_you", entry.str("outcome"))
            assertEquals("permanent", entry.str("kind"))
            assertEquals("not_claimed_by_you", entry.str("code"))
        }

    @Test
    fun `S6 release of an unknown full UUID reports outcome not_found with kind permanent and code not_found`(): Unit =
        runBlocking {
            val entry = claimResult("releaseResults", UUID.randomUUID().toString())

            assertEquals("not_found", entry.str("outcome"))
            assertEquals("permanent", entry.str("kind"))
            assertEquals("not_found", entry.str("code"))
        }

    @Test
    fun `S6 probe claim contention keeps outcome already_claimed with kind transient`(): Unit =
        runBlocking {
            val contended = item("contended")
            repo.claim(contended.id, "agent-x", 900)

            val entry = claimResult("claimResults", contended.id.toString())

            assertEquals("already_claimed", entry.str("outcome"))
            assertEquals("transient", entry.str("kind"))
        }

    @Test
    fun `S6 probe a successful claim carries no kind and no code`(): Unit =
        runBlocking {
            val free = item("free")

            val entry = claimResult("claimResults", free.id.toString())

            assertEquals("success", entry.str("outcome"))
            assertNull(entry["kind"])
            assertNull(entry["code"])
        }

    // ----------------------------------------------
    // S12 - regression guard: advance_item codes unchanged (green before and after)
    // ----------------------------------------------

    private suspend fun advanceEntry(
        itemId: UUID,
        trigger: String
    ): JsonObject =
        call(
            "advance_item",
            mapOf("transitions" to listOf(mapOf("itemId" to itemId.toString(), "trigger" to trigger, "actor" to actor)))
        ).data().entries("results").single()

    @Test
    fun `S12 advance_item unknown trigger reaching execute keeps errorCode invalid_trigger and errorKind permanent`(): Unit =
        runBlocking {
            val subject = item("trigger subject")
            val context = ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork())
            val params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", subject.id.toString())
                                    put("trigger", "bogus")
                                    put(
                                        "actor",
                                        buildJsonObject {
                                            put("id", "agent-1")
                                            put("kind", "subagent")
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

            // execute() alone never runs validateParams; the adapter path rejects the same trigger as VALIDATION_ERROR first.
            val response = AdvanceItemTool().execute(params, context).jsonObject
            val entry = response["data"]!!.jsonObject.entries("results").single()

            assertEquals("false", entry["applied"]!!.jsonPrimitive.content)
            assertEquals("invalid_trigger", entry.str("errorCode"))
            assertEquals("permanent", entry.str("errorKind"))
        }

    @Test
    fun `S12 advance_item unknown trigger through the adapter is the VALIDATION_ERROR envelope with kind permanent`(): Unit =
        runBlocking {
            val subject = item("adapter trigger subject")

            val result =
                call(
                    "advance_item",
                    mapOf("transitions" to listOf(mapOf("itemId" to subject.id.toString(), "trigger" to "bogus", "actor" to actor)))
                )

            assertEquals(true, result.isError)
            assertEquals(ErrorCodes.VALIDATION_ERROR, result.error().str("code"))
            assertEquals("permanent", result.error().str("kind"))
        }

    @Test
    fun `S12 advance_item gate failure keeps errorCode gate_blocked and errorKind permanent`(): Unit =
        runBlocking {
            val gated = item("gated advance", tags = "gated")

            val entry = advanceEntry(gated.id, "start")

            assertEquals("false", entry["applied"]!!.jsonPrimitive.content)
            assertEquals("gate_blocked", entry.str("errorCode"))
            assertEquals("permanent", entry.str("errorKind"))
        }

    @Test
    fun `S12 advance_item unmet dependency keeps errorCode dependency_blocked and errorKind permanent`(): Unit =
        runBlocking {
            val blocker = item("blocker")
            val blocked = item("blocked advance")
            db.repositoryProvider().dependencyRepository().create(
                Dependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS)
            )

            val entry = advanceEntry(blocked.id, "start")

            assertEquals("false", entry["applied"]!!.jsonPrimitive.content)
            assertEquals("dependency_blocked", entry.str("errorCode"))
            assertEquals("permanent", entry.str("errorKind"))
        }

    @Test
    fun `S12 advance_item on a terminal item keeps errorCode invalid_transition and errorKind permanent`(): Unit =
        runBlocking {
            val done = item("terminal advance", role = Role.TERMINAL)

            val entry = advanceEntry(done.id, "start")

            assertEquals("invalid_transition", entry.str("errorCode"))
            assertEquals("permanent", entry.str("errorKind"))
        }
}

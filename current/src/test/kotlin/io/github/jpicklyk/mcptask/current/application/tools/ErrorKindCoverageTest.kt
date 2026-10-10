package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureTestApp
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemRoutes
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
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
import kotlin.test.assertFalse
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
 * api-reference `complete_tree` entry shapes and the `advance_item` error-code list as of the base commit
 * (`gate_blocked`, `dependency_blocked`, `invalid_trigger`); [P] test-plan S3-S6 and S12.
 */
class ErrorKindCoverageTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var server: Server
    private lateinit var client: Client

    private val repo get() = db.repositoryProvider().workItemRepository()

    /** Any item with a non-empty tag set resolves to a schema with one required queue-phase note. */
    private val gateSchema: NoteSchemaService =
        object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.isNotEmpty()) {
                    listOf(NoteSchemaEntry(key = "acceptance-criteria", role = Role.QUEUE, required = true, description = "criteria"))
                } else {
                    null
                }
        }

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
                    noteSchemaService = gateSchema,
                    unitOfWork = db.unitOfWork()
                )
            val adapter = McpToolAdapter()
            listOf(
                QueryItemsTool(),
                ManageItemsTool(),
                ManageNotesTool(),
                ManageDependenciesTool(),
                CompleteTreeTool(),
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
    ): WorkItem = repo.create(WorkItem(title = title, role = role, tags = tags))

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
    // S3 - top-level errors carry kind
    // ----------------------------------------------

    @Test
    fun `S3 query_items get with a too-short id is VALIDATION_ERROR with kind permanent and its message unchanged`(): Unit =
        runBlocking {
            val result = call("query_items", mapOf("operation" to "get", "itemId" to "zz"))

            assertEquals(true, result.isError)
            val error = result.error()
            assertEquals(ErrorCodes.VALIDATION_ERROR, error.str("code"))
            assertEquals("permanent", error.str("kind"))
            assertTrue(
                error.str("message")!!.contains("itemId must be a UUID or hex prefix") && error.str("message")!!.endsWith("got: zz"),
                "message unchanged: $error"
            )
        }

    @Test
    fun `S3 query_items get of an unknown full UUID is RESOURCE_NOT_FOUND with kind permanent`(): Unit =
        runBlocking {
            val result = call("query_items", mapOf("operation" to "get", "itemId" to UUID.randomUUID().toString()))

            assertEquals(true, result.isError)
            val error = result.error()
            assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error.str("code"))
            assertEquals("permanent", error.str("kind"))
        }

    @Test
    fun `S3 probe a successful query_items get carries no error object`(): Unit =
        runBlocking {
            val existing = item("present")

            val result = call("query_items", mapOf("operation" to "get", "itemId" to existing.id.toString()))

            assertTrue(result.isError != true, "control: a present item reads fine: ${result.content}")
            assertNull(result.structuredContent?.get("error"))
            assertNull(result.structuredContent?.get("kind"))
        }

    // ----------------------------------------------
    // S4 - complete_tree per-item failures
    // ----------------------------------------------

    @Test
    fun `S4 complete_tree gate-blocked item keeps its gate fields and gains errorCode gate_blocked with errorKind permanent`(): Unit =
        runBlocking {
            val gated = item("gated", tags = "gated")

            val result = call("complete_tree", mapOf("itemIds" to listOf(gated.id.toString())))

            val entry = result.data().byItem(gated.id)
            assertEquals("gated", entry.str("title"))
            assertEquals("false", entry["applied"]!!.jsonPrimitive.content)
            val gateErrors = entry["gateErrors"]!!.jsonArray
            assertTrue(gateErrors.any { it.jsonPrimitive.content.contains("acceptance-criteria") }, "gateErrors kept: $entry")
            assertTrue(entry["missingNotes"].toString().contains("acceptance-criteria"), "missingNotes kept: $entry")
            assertEquals("gate_blocked", entry.str("errorCode"))
            assertEquals("permanent", entry.str("errorKind"))
        }

    @Test
    fun `S4 complete_tree item with an unmet external blocker keeps error and blockers and gains errorCode dependency_blocked`(): Unit =
        runBlocking {
            val outsider = item("outside the set")
            val blocked = item("blocked target")
            db.repositoryProvider().dependencyRepository().create(
                Dependency(fromItemId = outsider.id, toItemId = blocked.id, type = DependencyType.BLOCKS)
            )

            val result = call("complete_tree", mapOf("itemIds" to listOf(blocked.id.toString())))

            val entry = result.data().byItem(blocked.id)
            assertEquals("false", entry["applied"]!!.jsonPrimitive.content)
            assertNotNull(entry["error"], "the human-readable error is kept: $entry")
            assertNotNull(entry["blockers"], "the blockers array is kept: $entry")
            assertEquals("dependency_blocked", entry.str("errorCode"))
            assertEquals("permanent", entry.str("errorKind"))
        }

    @Test
    fun `S4 complete_tree every non-applied variant carries errorCode and errorKind consistent with the catalog`(): Unit =
        runBlocking {
            val upstream = item("upstream")
            val gated = item("gated middle", tags = "gated")
            val downstream = item("downstream")
            val alreadyTerminal = item("already terminal", role = Role.TERMINAL)
            val deps = db.repositoryProvider().dependencyRepository()
            deps.create(Dependency(fromItemId = upstream.id, toItemId = gated.id, type = DependencyType.BLOCKS))
            deps.create(Dependency(fromItemId = gated.id, toItemId = downstream.id, type = DependencyType.BLOCKS))

            val result =
                call(
                    "complete_tree",
                    mapOf("itemIds" to listOf(upstream, gated, downstream, alreadyTerminal).map { it.id.toString() })
                )

            val data = result.data()
            assertEquals("true", data.byItem(upstream.id)["applied"]!!.jsonPrimitive.content, "control: the unblocked item completes")
            assertCodeAndKind(data.byItem(gated.id), "gate failure")
            val skippedDownstream = data.byItem(downstream.id)
            assertEquals("true", skippedDownstream["skipped"]!!.jsonPrimitive.content, "control: the dependent is skipped")
            assertCodeAndKind(skippedDownstream, "dependency skip")
            val skippedTerminal = data.byItem(alreadyTerminal.id)
            assertEquals("true", skippedTerminal["skipped"]!!.jsonPrimitive.content, "control: the terminal item is skipped")
            assertCodeAndKind(skippedTerminal, "already terminal skip")
        }

    @Test
    fun `S4 probe an applied complete_tree entry carries no error fields`(): Unit =
        runBlocking {
            val plain = item("plain")

            val result = call("complete_tree", mapOf("itemIds" to listOf(plain.id.toString())))

            val entry = result.data().byItem(plain.id)
            assertEquals("true", entry["applied"]!!.jsonPrimitive.content, "control: it completes: $entry")
            assertNull(entry["errorCode"])
            assertNull(entry["errorKind"])
        }

    // ----------------------------------------------
    // S5 - manage_items / manage_notes / manage_dependencies per-element failures
    // ----------------------------------------------

    @Test
    fun `S5 manage_items update of an unknown id keeps its error and gains errorCode not_found with errorKind permanent`(): Unit =
        runBlocking {
            val result =
                call(
                    "manage_items",
                    mapOf("operation" to "update", "items" to listOf(mapOf("itemId" to UUID.randomUUID().toString(), "title" to "x")))
                )

            val failure = result.data().entries("failures").single()
            assertTrue(failure.str("error")!!.contains("not found", ignoreCase = true), "error text kept: $failure")
            assertEquals("not_found", failure.str("errorCode"))
            assertEquals("permanent", failure.str("errorKind"))
        }

    @Test
    fun `S5 manage_items update that changes the role is an invalid_request failure with errorKind permanent`(): Unit =
        runBlocking {
            val existing = item("role change")

            val result =
                call(
                    "manage_items",
                    mapOf("operation" to "update", "items" to listOf(mapOf("itemId" to existing.id.toString(), "role" to "work")))
                )

            val failure = result.data().entries("failures").single()
            assertTrue(failure.str("error")!!.contains("role changes are not allowed"), "error text kept: $failure")
            assertEquals("invalid_request", failure.str("errorCode"))
            assertEquals("permanent", failure.str("errorKind"))
        }

    @Test
    fun `S5 manage_items create under a missing parent keeps its error and gains errorCode not_found with errorKind permanent`(): Unit =
        runBlocking {
            val result =
                call(
                    "manage_items",
                    mapOf(
                        "operation" to "create",
                        "items" to listOf(mapOf("title" to "orphan", "parentId" to UUID.randomUUID().toString()))
                    )
                )

            val failure = result.data().entries("failures").single()
            assertTrue(failure.str("error")!!.contains("not found"), "error text kept: $failure")
            assertEquals("not_found", failure.str("errorCode"))
            assertEquals("permanent", failure.str("errorKind"))
        }

    @Test
    fun `S5 manage_items delete of an unknown id gains errorCode not_found with errorKind permanent`(): Unit =
        runBlocking {
            val result = call("manage_items", mapOf("operation" to "delete", "itemIds" to listOf(UUID.randomUUID().toString())))

            val failure = result.data().entries("failures").single()
            assertEquals("not_found", failure.str("errorCode"))
            assertEquals("permanent", failure.str("errorKind"))
        }

    @Test
    fun `S5 manage_notes upsert on an unknown item keeps its error and gains errorKind permanent`(): Unit =
        runBlocking {
            val result =
                call(
                    "manage_notes",
                    mapOf(
                        "operation" to "upsert",
                        "notes" to
                            listOf(
                                mapOf("itemId" to UUID.randomUUID().toString(), "key" to "orphan-note", "role" to "queue", "body" to "b")
                            )
                    )
                )

            val failure = result.data().entries("failures").single()
            assertTrue(failure.str("error")!!.contains("not found"), "error text kept: $failure")
            assertEquals("not_found", failure.str("errorCode"))
            assertEquals("permanent", failure.str("errorKind"))
        }

    @Test
    fun `S5 manage_notes upsert without a key is an invalid_request failure with errorKind permanent`(): Unit =
        runBlocking {
            val existing = item("note target")

            val result =
                call(
                    "manage_notes",
                    mapOf("operation" to "upsert", "notes" to listOf(mapOf("itemId" to existing.id.toString(), "role" to "queue")))
                )

            val failure = result.data().entries("failures").single()
            assertTrue(failure.str("error")!!.contains("key"), "error text kept: $failure")
            assertEquals("invalid_request", failure.str("errorCode"))
            assertEquals("permanent", failure.str("errorKind"))
        }

    @Test
    fun `S5 manage_dependencies duplicate edge keeps its error and gains errorCode duplicate with errorKind permanent`(): Unit =
        runBlocking {
            val a = item("dep a")
            val b = item("dep b")
            val edge = mapOf("fromItemId" to a.id.toString(), "toItemId" to b.id.toString())
            val first = call("manage_dependencies", mapOf("operation" to "create", "dependencies" to listOf(edge)))
            assertTrue(first.isError != true, "control: the first edge is created: ${first.content}")

            val result = call("manage_dependencies", mapOf("operation" to "create", "dependencies" to listOf(edge)))

            val failure = result.data().entries("failures").single()
            assertTrue(failure.str("error")!!.contains("already exists", ignoreCase = true), "error text kept: $failure")
            assertEquals("duplicate", failure.str("errorCode"))
            assertEquals("permanent", failure.str("errorKind"))
        }

    @Test
    fun `S5 manage_dependencies cycle keeps its error and gains errorCode cycle_detected with errorKind permanent`(): Unit =
        runBlocking {
            val a = item("cycle a")
            val b = item("cycle b")
            val forward = mapOf("fromItemId" to a.id.toString(), "toItemId" to b.id.toString())
            val backward = mapOf("fromItemId" to b.id.toString(), "toItemId" to a.id.toString())
            val first = call("manage_dependencies", mapOf("operation" to "create", "dependencies" to listOf(forward)))
            assertTrue(first.isError != true, "control: the forward edge is created: ${first.content}")

            val result = call("manage_dependencies", mapOf("operation" to "create", "dependencies" to listOf(backward)))

            val failure = result.data().entries("failures").single()
            assertTrue(failure.str("error")!!.contains("circular", ignoreCase = true), "error text kept: $failure")
            assertEquals("cycle_detected", failure.str("errorCode"))
            assertEquals("permanent", failure.str("errorKind"))
        }

    @Test
    fun `S5 probe a keyed replay of a failing manage_items update returns the identical failure entry`(): Unit =
        runBlocking {
            val args =
                mapOf(
                    "operation" to "update",
                    "items" to listOf(mapOf("itemId" to UUID.randomUUID().toString(), "title" to "x")),
                    "requestId" to UUID.randomUUID().toString(),
                    "actor" to actor
                )

            val first = call("manage_items", args).data().entries("failures").single()
            val second = call("manage_items", args).data().entries("failures").single()

            assertEquals(first, second)
            assertEquals("permanent", second.str("errorKind"))
        }

    @Test
    fun `S5 probe successful create and update responses carry no failure entries and no error fields`(): Unit =
        runBlocking {
            val created = call("manage_items", mapOf("operation" to "create", "items" to listOf(mapOf("title" to "fine"))))

            assertTrue(created.isError != true, "control: create succeeds: ${created.content}")
            assertNull(created.data()["failures"], "no failures key on a clean batch: ${created.data()}")
            assertFalse(created.data().toString().contains("errorKind"), "no errorKind anywhere on a clean batch: ${created.data()}")
            assertFalse(created.data().toString().contains("errorCode"), "no errorCode anywhere on a clean batch: ${created.data()}")
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

    // ----------------------------------------------
    // S12 - REST regression guard: status, code and message of the read-route errors are unchanged
    // ----------------------------------------------

    private fun restError(
        status: HttpStatusCode?,
        body: String
    ): Pair<HttpStatusCode?, JsonObject> =
        status to
            kotlinx.serialization.json.Json
                .parseToJsonElement(body)
                .jsonObject

    @Test
    fun `S12 REST GET items with a malformed id keeps 400 bad_request and its message`() =
        testApplication {
            val provider = db.repositoryProvider()
            application { configureTestApp { itemRoutes(provider) } }

            val response = client.get("/api/v1/items/not-a-uuid") { header("Authorization", "Bearer $TEST_TOKEN") }
            val (status, body) = restError(response.status, response.bodyAsText())

            assertEquals(HttpStatusCode.BadRequest, status)
            assertEquals("bad_request", body["error"]?.jsonPrimitive?.content)
            assertEquals("Invalid UUID: not-a-uuid", body["message"]?.jsonPrimitive?.content)
            assertEquals(setOf("error", "message"), body.keys, "no kind and no details on the REST wire in phase 1: $body")
        }

    @Test
    fun `S12 REST GET items with an unknown id keeps 404 not_found and its message`() =
        testApplication {
            val provider = db.repositoryProvider()
            application { configureTestApp { itemRoutes(provider) } }

            val unknownId = UUID.randomUUID()
            val response = client.get("/api/v1/items/$unknownId") { header("Authorization", "Bearer $TEST_TOKEN") }
            val (status, body) = restError(response.status, response.bodyAsText())

            assertEquals(HttpStatusCode.NotFound, status)
            assertEquals("not_found", body["error"]?.jsonPrimitive?.content)
            assertEquals("Item $unknownId not found", body["message"]?.jsonPrimitive?.content)
            assertEquals(setOf("error", "message"), body.keys, "no kind and no details on the REST wire in phase 1: $body")
        }
}

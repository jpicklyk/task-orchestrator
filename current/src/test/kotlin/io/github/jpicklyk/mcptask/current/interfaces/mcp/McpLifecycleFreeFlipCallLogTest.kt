package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.report.ReportFixture
import io.github.jpicklyk.mcptask.current.application.report.ReportWasteRequest
import io.github.jpicklyk.mcptask.current.application.report.WasteReport
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

/** `test-item` is an ordinary auto type, `page` is lifecycle-free. */
private const val FLIP_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  page:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n"

/**
 * Independent end-to-end tests (test-author seat, item 03f3520d, R12) for scenario S17 of the frozen test-plan and the carry-in
 * `carry-in-df10fafb-n8`: an MCP `items_write` flip that the lifecycle rules refuse for a lifecycle-free item writes a `call_log`
 * row with `lifecycle_free_rejections` = 1, and `report waste` counts it. The harness is the one of `McpCallLogFollowThroughTest`
 * (the real [McpOperationAdapter] over an in-memory client / server pair, the production registry, the real composition and a
 * real [CallLogWriter]); the waste answer comes from the real [io.github.jpicklyk.mcptask.current.application.report.ReportService]
 * over the same database.
 *
 * Oracles (frozen, none read from a run). The flip rule (`LifecycleFlipRuleTest`, task-scope of Q31b / U13b): a none item may
 * become an auto item only if its parent is not terminal; a refused crossing is `invalid_transition` with detail
 * `{itemId, fromRole (role wire or "none"), trigger, allowed: []}` and nothing written; into none is allowed only from QUEUE and
 * TERMINAL, so a WORK item is refused with its own role as `fromRole`. The capture rule (`CallLogFieldsLifecycleFreeTest`): a
 * rejection counts as lifecycle-free exactly when it is an `invalid_transition` whose `fromRole` is `none` (or an `invalid_request`
 * refusing a BLOCKS endpoint); the count is NULL, never 0, when nothing counts. The waste rule (TS-5): the denominator of
 * `lifecycleFreeRejections` is the non-replayed WRITE calls (`items_write` is one) in the window and the numerator is the sum of
 * their counts, NULL as 0.
 *
 * Oracle status (arbitration record). The two `flip` tests assert the frozen S17 / carry-in value 1 for a refused `items_write` flip
 * of a lifecycle-free item. The capture rule above counts only a refusal whose `fromRole` is `none`, and the flip refusals name a
 * role (the closed parent's `terminal`, or the item's own `queue`), so these two are expected to disagree with the capture; they
 * are reported as findings, not adjusted. The `chain` test uses a refusal that does carry `fromRole: none` and checks the whole
 * path capture -> call_log -> waste.
 *
 * Absence assertions and the condition that fails them: the NULL on the control row would be a number if successes were counted;
 * the NULL on the role-bearing refusal would be 1 if every refused flip were counted; the denominator 2 of the waste answer would
 * be 1 if the control write were not a write call.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class McpLifecycleFreeFlipCallLogTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private lateinit var rig: EventLogRig
    private lateinit var writer: CallLogWriter
    private lateinit var client: Client
    private val pairs = mutableListOf<Pair<Client, Server>>()

    private suspend fun start() {
        rig = EventLogRig.build(db.db, dir, FLIP_YAML)
        writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)
        val server = Server(serverInfo = Implementation(name = "r12-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        McpOperationAdapter(buildToolDispatcher(rig.ctx), callLog = writer)
            .register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        client =
            Client(
                clientInfo = Implementation(name = "r12-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        pairs += client to server
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking { pairs.forEach { closeInMemoryPair(it.first, it.second) } }

    // ------------------------------------------------------------------ helpers

    private suspend fun call(
        tool: String,
        vararg args: Pair<String, JsonElement>
    ): CallToolResult =
        client.callTool(
            buildCallToolRequest {
                name = tool
                arguments(JsonObject(mapOf(*args)))
            }
        )

    private suspend fun write(vararg ops: JsonObject): CallToolResult = call("items_write", "ops" to JsonArray(ops.toList()))

    private fun changeType(
        id: Any,
        type: String
    ): JsonObject =
        buildJsonObject {
            put("op", "update")
            put("id", id.toString())
            put("type", type)
        }

    private suspend fun rowOf(result: CallToolResult): Map<String, Any?> {
        writer.flushNow()
        val reqId =
            assertNotNull(
                result.meta
                    ?.get("reqId")
                    ?.jsonPrimitive
                    ?.content,
                "_meta.reqId"
            )
        return CallLogRows.read(db.jdbcUrl).single { it["req_id"] == reqId }
    }

    private fun lifecycleFree(row: Map<String, Any?>): Long? = row["lifecycle_free_rejections"] as Long?

    private suspend fun page(
        title: String,
        parent: WorkItem
    ): WorkItem =
        rig.ctx.itemCommandService
            .create(ItemCreateCommand(parentId = parent.id, title = title, type = "page"))
            .orFail()

    private suspend fun close(item: WorkItem) {
        val stored = checkNotNull(rig.raw.workItemRepository().getById(item.id)) { "item ${item.id}" }
        rig.raw.workItemRepository().update(stored.copy(role = Role.TERMINAL, previousRole = null, statusLabel = null))
    }

    /** `report waste` over the rows the writer flushed, in a window of one hour around [around] (the `at` of a recorded row). */
    private suspend fun wasteAround(around: Map<String, Any?>): WasteReport {
        val at = Instant.parse((around["at"] as String).replace(' ', 'T') + "Z")
        val fx = ReportFixture(db.db, SettableClock(at.plusSeconds(7200)))
        val request = ReportWasteRequest(since = at.minusSeconds(3600).toString(), until = at.plusSeconds(3600).toString())
        return when (val outcome = fx.service().waste(fx.ctx(), request)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok waste report but got ${outcome.error}")
        }
    }

    // ------------------------------------------------------------------ S17

    @Test
    fun `S17 a lifecycle-free item flipped out under a closed parent records one rejection and waste counts it`(): Unit =
        runBlocking {
            start()
            val closed = rig.make("Closed parent")
            val open = rig.make("Open parent")
            val refusedPage = page("Page under closed", closed)
            val allowedPage = page("Page under open", open)
            close(closed)

            val refused = write(changeType(refusedPage.id, "test-item"))
            val control = write(changeType(allowedPage.id, "test-item"))

            assertEquals(true, refused.isError, "fixture: a none item under a terminal parent cannot become an auto item")
            assertNotEquals(true, control.isError, "control: the same flip under an open parent succeeds: ${control.content}")
            assertNull(
                checkNotNull(rig.raw.workItemRepository().getById(refusedPage.id)).role,
                "fixture: the refused item is still role-free"
            )
            assertEquals(Role.QUEUE, checkNotNull(rig.raw.workItemRepository().getById(allowedPage.id)).role, "control: it became queue")
            val refusedRow = rowOf(refused)
            val controlRow = rowOf(control)
            assertEquals("items_write", refusedRow["tool"])
            assertEquals("error", refusedRow["outcome"])
            assertEquals(
                1L,
                lifecycleFree(refusedRow),
                "one lifecycle-free rejection in the refused flip: error=${refusedRow["error_code"]} body=${refused.structuredContent}"
            )
            assertNull(lifecycleFree(controlRow), "an ok call is NULL, never 0")

            val waste = wasteAround(refusedRow)

            // non-replayed write calls in the window: the refused and the control items_write; one rejection in total
            val signal = waste.lifecycleFreeRejections
            assertEquals(1L, signal.numerator)
            assertEquals(2L, signal.denominator)
            assertEquals(0, BigDecimal("0.5").compareTo(assertNotNull(signal.value)))
            assertNull(signal.basis, "lifecycleFreeRejections is not a session signal")
        }

    @Test
    fun `S17 probe a flip refused for a role-bearing item records NULL and waste counts the call without a rejection`(): Unit =
        runBlocking {
            start()
            val worker = rig.make("Worker", role = Role.WORK)

            val refused = write(changeType(worker.id, "page"))

            assertEquals(true, refused.isError, "fixture: a WORK item cannot flip into none")
            val row = rowOf(refused)
            assertNull(lifecycleFree(row), "the refusal names the item's own role, not none: not a lifecycle-free rejection")

            val signal = wasteAround(row).lifecycleFreeRejections
            assertEquals(0L, signal.numerator)
            assertEquals(1L, signal.denominator)
            assertEquals(0, BigDecimal.ZERO.compareTo(assertNotNull(signal.value)))
        }

    @Test
    fun `S17 a flip into a lifecycle-free type refused for a BLOCKS edge records one rejection and waste counts it`(): Unit =
        runBlocking {
            start()
            val blocker = rig.make("Blocker")
            val blocked = rig.make("Blocked")
            val free = rig.make("Free")
            rig.raw.dependencyRepository().create(Dependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS))

            val refused = write(changeType(blocker.id, "page"))
            val control = write(changeType(free.id, "page"))

            assertEquals(true, refused.isError, "fixture: an item with a BLOCKS edge cannot become lifecycle-free")
            assertNotEquals(true, control.isError, "control: an item without an edge can: ${control.content}")
            assertNull(checkNotNull(rig.raw.workItemRepository().getById(free.id)).role, "control: it became role-free")
            val refusedRow = rowOf(refused)
            val controlRow = rowOf(control)
            assertEquals(
                1L,
                lifecycleFree(refusedRow),
                "one lifecycle-free rejection in the refused flip: error=${refusedRow["error_code"]} body=${refused.structuredContent}"
            )
            assertNull(lifecycleFree(controlRow), "an ok call is NULL, never 0")

            val signal = wasteAround(refusedRow).lifecycleFreeRejections

            assertEquals(1L, signal.numerator)
            assertEquals(2L, signal.denominator)
            assertEquals(0, BigDecimal("0.5").compareTo(assertNotNull(signal.value)))
        }

    @Test
    fun `S17 chain - a rejected start on a lifecycle-free item is recorded and waste counts it`(): Unit =
        runBlocking {
            start()
            val parent = rig.make("Parent")
            val freePage = page("Page", parent)
            val plain = rig.make("Plain")

            val refused = advanceStart(freePage.id)
            val control = advanceStart(plain.id)

            assertEquals(true, refused.isError, "fixture: a lifecycle-free item cannot start")
            assertNotEquals(true, control.isError, "control: an ordinary item starts: ${control.content}")
            val refusedRow = rowOf(refused)
            assertEquals("advance", refusedRow["tool"])
            assertEquals(1L, lifecycleFree(refusedRow))
            assertNull(lifecycleFree(rowOf(control)), "an ok call is NULL, never 0")

            val signal = wasteAround(refusedRow).lifecycleFreeRejections

            // non-replayed write calls: the refused start and the control start; one rejection
            assertEquals(1L, signal.numerator)
            assertEquals(2L, signal.denominator)
            assertEquals(0, BigDecimal("0.5").compareTo(assertNotNull(signal.value)))
        }

    private val actor: JsonObject =
        buildJsonObject {
            put("id", "agent-r12")
            put("kind", "subagent")
        }

    private suspend fun advanceStart(id: Any): CallToolResult =
        call(
            "advance",
            "ops" to
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("op", "transition")
                            put("itemId", id.toString())
                            put("trigger", "start")
                        }
                    )
                ),
            "actor" to actor
        )
}

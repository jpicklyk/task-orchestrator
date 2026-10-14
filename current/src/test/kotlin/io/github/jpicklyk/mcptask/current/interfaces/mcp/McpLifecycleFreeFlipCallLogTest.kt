package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.report.ReportFixture
import io.github.jpicklyk.mcptask.current.application.report.ReportWasteRequest
import io.github.jpicklyk.mcptask.current.application.report.WasteReport
import io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
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
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** `test-item` and `shifty` are ordinary auto types, `page` is lifecycle-free; `t-one` is a trait without notes. */
private const val FLIP_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  shifty:\n" +
        "    notes: []\n" +
        "  page:\n" +
        "    lifecycle: none\n" +
        "    notes: []\n" +
        "traits:\n" +
        "  t-one:\n" +
        "    notes: []\n"

private const val ROOT_SHIFTY_NONE = "work_item_schemas:\n  shifty:\n    lifecycle: none\n    notes: []\n"

private const val ROOT_SHIFTY_AUTO = "work_item_schemas:\n  shifty:\n    lifecycle: auto\n    notes: []\n"

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
 * Oracle status (orchestrator ruling). The S17 oracle stands: a refused `items_write` pin crossing of a lifecycle-free item answers
 * `invalid_transition` with `fromRole` `none` and the call_log row carries 1. The reachable inputs are a trait change (trigger
 * `trait_change`) or a reparent (trigger `reparent`) on an item that is still role-free but whose resolved schema, after a per-root
 * push or a move into another root, is role-bearing; only a type change and `repin` may cross. The `chain` test uses an `advance`
 * refusal that also carries `fromRole: none`.
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

    private suspend fun push(
        root: WorkItem,
        yaml: String
    ) {
        assertIs<ConfigPushResult.Success>(checkNotNull(rig.ctx.configService).push(root.id, yaml), "push of '${yaml.take(40)}'")
    }

    private fun setTraits(
        id: Any,
        trait: String
    ): JsonObject =
        buildJsonObject {
            put("op", "update")
            put("id", id.toString())
            put("traits", JsonArray(listOf(JsonPrimitive(trait))))
        }

    private fun move(
        id: Any,
        parentId: Any
    ): JsonObject =
        buildJsonObject {
            put("op", "update")
            put("id", id.toString())
            put("parentId", parentId.toString())
        }

    /** The refusal envelope: code invalid_transition, detail fromRole none, the given trigger, nothing allowed. */
    private fun assertRefusal(
        result: CallToolResult,
        id: Any,
        trigger: String
    ) {
        val text = result.content.joinToString("") { (it as? TextContent)?.text.orEmpty() } + result.structuredContent.toString()
        val compact = text.replace(" ", "")
        assertTrue("invalid_transition" in text, "code invalid_transition: $text")
        assertTrue("\"fromRole\":\"none\"" in compact, "fromRole none: $text")
        assertTrue("\"trigger\":\"$trigger\"" in compact, "trigger $trigger: $text")
        assertTrue(id.toString() in text, "itemId: $text")
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
    fun `S17 trait change into a role-bearing schema is refused with fromRole none and waste counts it`(): Unit =
        runBlocking {
            start()
            val root = rig.make("Root")
            push(root, ROOT_SHIFTY_NONE)
            val frozen =
                rig.ctx.itemCommandService
                    .create(
                        ItemCreateCommand(parentId = root.id, title = "Frozen", type = "shifty")
                    ).orFail()
            val control = rig.make("Control", parent = root)
            push(root, ROOT_SHIFTY_AUTO)
            assertNull(checkNotNull(rig.raw.workItemRepository().getById(frozen.id)).role, "fixture: the item is still lifecycle-free")

            val refused = write(setTraits(frozen.id, "t-one"))
            val ok = write(setTraits(control.id, "t-one"))

            assertEquals(true, refused.isError, "fixture: the trait change would cross into a role-bearing schema")
            assertNotEquals(true, ok.isError, "control: a trait change on an ordinary item works: ${ok.content}")
            assertRefusal(refused, frozen.id, "trait_change")
            val refusedRow = rowOf(refused)
            val okRow = rowOf(ok)
            assertEquals("items_write", refusedRow["tool"])
            assertEquals("error", refusedRow["outcome"])
            assertEquals(1L, lifecycleFree(refusedRow), "one lifecycle-free rejection: error=${refusedRow["error_code"]}")
            assertNull(lifecycleFree(okRow), "an ok call is NULL, never 0")

            val signal = wasteAround(refusedRow).lifecycleFreeRejections

            // non-replayed write calls in the window: the refused and the control items_write; one rejection in total
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
    fun `S17 reparent into a role-bearing schema is refused with fromRole none and waste counts it`(): Unit =
        runBlocking {
            start()
            val r1 = rig.make("R1")
            val r2 = rig.make("R2")
            push(r1, ROOT_SHIFTY_NONE)
            val frozen =
                rig.ctx.itemCommandService
                    .create(ItemCreateCommand(parentId = r1.id, title = "Frozen", type = "shifty"))
                    .orFail()
            val control = rig.make("Control", parent = r1)
            assertNull(checkNotNull(rig.raw.workItemRepository().getById(frozen.id)).role, "fixture: the item is lifecycle-free")

            val refused = write(move(frozen.id, r2.id))
            val ok = write(move(control.id, r2.id))

            assertEquals(true, refused.isError, "fixture: the move would cross into a role-bearing schema")
            assertNotEquals(true, ok.isError, "control: an unaffected item moves: ${ok.content}")
            assertRefusal(refused, frozen.id, "reparent")
            val refusedRow = rowOf(refused)
            assertNull(lifecycleFree(rowOf(ok)), "an ok call is NULL, never 0")
            assertEquals(1L, lifecycleFree(refusedRow), "one lifecycle-free rejection: error=${refusedRow["error_code"]}")

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
                )
        )
}

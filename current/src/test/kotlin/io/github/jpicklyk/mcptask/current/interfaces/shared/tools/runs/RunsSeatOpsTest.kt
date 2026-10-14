package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.BaselineDataset
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.PlannedSurfaceChecks
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.visibleNames
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** The two seated schemas and one seat-less schema every seat test runs under. */
internal const val SEAT_WORLD_CONFIG = """
work_item_schemas:
  seated-task:
    seats:
      - { name: builder, phase: work, enters: true }
      - { name: checker, phase: work }
    notes:
      - key: build-notes
        role: work
        required: false
        seat: builder
      - key: check-notes
        role: work
        required: false
        seat: checker
      - key: shared-notes
        role: work
        required: false
        shared: true
  plain-task:
    notes: []
"""

/** One answer of a tool call, as the envelope body, with the lookups the seat tests need. */
internal class Reply(
    val isError: Boolean,
    val body: JsonObject,
    val reqId: String? = null,
    val status: Int? = null
) {
    val data: JsonObject? get() = body["data"] as? JsonObject

    /** The code of a whole-call error, null for a body without one. */
    fun wholeCode(): String? =
        body["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content

    fun results(): List<JsonObject> =
        data
            ?.get("results")
            ?.jsonArray
            ?.map { it.jsonObject }
            .orEmpty()

    fun elementOk(i: Int = 0): Boolean =
        results()
            .getOrNull(i)
            ?.get("ok")
            ?.jsonPrimitive
            ?.booleanOrNull == true

    fun elementError(i: Int = 0): JsonObject? = results().getOrNull(i)?.get("error") as? JsonObject

    /** The error object that explains element [i]: the whole-call error unless it is only the batch's `partial_failure`. */
    fun error(i: Int = 0): JsonObject? =
        if (wholeCode() == null ||
            wholeCode() == "partial_failure"
        ) {
            elementError(i)
        } else {
            body["error"]?.jsonObject
        }

    /** The code of that error, or null when the call (and element [i]) succeeded. */
    fun code(i: Int = 0): String? =
        error(i)
            ?.get("code")
            ?.jsonPrimitive
            ?.content

    /** True when the call and its first element succeeded. */
    fun ok(): Boolean = wholeCode() == null && (results().isEmpty() || elementOk(0))

    /** The first value of [key] anywhere in the result of element 0 (or the data of a select), as text. */
    fun find(
        key: String,
        within: JsonElement? = results().firstOrNull() ?: data
    ): String? = findElement(key, within)?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }

    fun findElement(
        key: String,
        within: JsonElement? = results().firstOrNull() ?: data
    ): JsonElement? =
        when (within) {
            is JsonObject -> within[key] ?: within.values.firstNotNullOfOrNull { findElement(key, it) }
            is JsonArray -> within.firstNotNullOfOrNull { findElement(key, it) }
            else -> null
        }

    /** Every `field` pointer of the invalid_request error of element [i] (empty for other errors). */
    fun pointers(i: Int = 0): List<String> = collect(error(i), "field")

    /** Every `parameters` pointer of the unknown_parameter error of element [i]. */
    fun parameters(i: Int = 0): List<String> =
        (findElement("parameters", error(i)) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    /** Every JSON object of the data that carries both `seat` and `attempt`: the seat rows of a `seats` select. */
    fun seatRows(): List<JsonObject> = objectsWith(data, "seat", "attempt")

    private fun collect(
        within: JsonElement?,
        key: String
    ): List<String> =
        when (within) {
            is JsonObject ->
                listOfNotNull(within[key]?.let { (it as? JsonPrimitive)?.contentOrNull }) +
                    within.values.flatMap { collect(it, key) }
            is JsonArray -> within.flatMap { collect(it, key) }
            else -> emptyList()
        }

    private fun objectsWith(
        within: JsonElement?,
        vararg keys: String
    ): List<JsonObject> =
        when (within) {
            is JsonObject ->
                (if (keys.all { it in within }) listOf(within) else emptyList()) + within.values.flatMap { objectsWith(it, *keys) }
            is JsonArray -> within.flatMap { objectsWith(it, *keys) }
            else -> emptyList()
        }

    /** Every key name anywhere in the body. */
    fun allKeys(within: JsonElement? = body): Set<String> =
        when (within) {
            is JsonObject -> within.keys + within.values.flatMap { allKeys(it) }
            is JsonArray -> within.flatMap { allKeys(it) }.toSet()
            else -> emptySet()
        }

    override fun toString(): String = body.toString()
}

internal class Opened(
    val runId: String,
    val coordinatorToken: String,
    val reply: Reply
)

internal class Assigned(
    val reply: Reply,
    val assignmentId: String?,
    val token: String?,
    val attempt: Int?
)

/**
 * The seat test world: the production composition over a real SQLite database, one in-memory MCP client/server pair on the
 * orchestration profile (the access seam reads [who], so one test drives several principals), the call-log writer, and a seeded
 * tree: root R with M1 (seated), M2 (seat-less schema), M3 (schema-free) and a same-root item D that is in no run, and a second root R2
 * with E. Every identity is a [ParityToken], so the MCP leg and the REST leg run as the same principal (`api:<id>`).
 */
internal class SeatWorld private constructor(
    val database: SqliteTestDatabase,
    val clock: SettableClock,
    val rig: EventLogRig
) : AutoCloseable {
    val registry = ToolRegistry(buildToolSpecs())
    private val writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(database.databaseManager), flushInterval = 1.hours)
    private val server = Server(serverInfo = Implementation(name = "seat-server", version = "1.0.0"), options = inMemoryTestServerOptions())
    private val client =
        Client(
            clientInfo = Implementation(name = "seat-client", version = "1.0.0"),
            options = ClientOptions(capabilities = ClientCapabilities())
        )

    @Volatile
    private var who: ParityToken? = null

    private val caps =
        setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.ADVANCE, ApiCapability.WRITE_NOTES, ApiCapability.WRITE_ITEMS)
    val orch = ParityToken("seat-orch", caps)
    val other = ParityToken("seat-other", caps)
    val admin = ParityToken("seat-admin", caps + ApiCapability.ADMIN)
    val human = ParityToken("seat-human", caps, kind = PrincipalKind.HUMAN)
    val readOnly = ParityToken("seat-read", setOf(ApiCapability.READ))

    lateinit var root: WorkItem
    lateinit var root2: WorkItem
    lateinit var m1: WorkItem
    lateinit var m2: WorkItem
    lateinit var m3: WorkItem
    lateinit var d: WorkItem
    lateinit var e: WorkItem

    /** A token scoped to root R only (the run of R2 is outside its scope). */
    val rootScoped: ParityToken get() = ParityToken("seat-root1", caps, rootIds = setOf(root.id))

    val everyToken: List<ParityToken> get() = listOf(orch, other, admin, human, readOnly, rootScoped)

    /** The opener identity of `orch` as the server records it. */
    val orchId = "api:seat-orch"

    val items: List<WorkItem> get() = listOf(m1, m2, m3)

    init {
        val adapter =
            McpOperationAdapter(
                buildToolDispatcher(rig.ctx),
                access = { _ -> who?.let { parityAccess(it) } ?: CallAccess.unauthenticated() },
                callLog = writer
            )
        adapter.register(server, registry, ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        runBlocking {
            server.createSession(serverTransport)
            client.connect(clientTransport)
        }
    }

    // ------------------------------------------------------------------ calls

    private suspend fun send(
        tool: String,
        args: JsonObject
    ): Reply {
        val result =
            client.callTool(
                buildCallToolRequest {
                    name = tool
                    arguments(args)
                }
            )
        return Reply(
            isError = result.isError == true,
            body = assertNotNull(result.structuredContent, "MCP result carries structuredContent"),
            reqId =
                result.meta
                    ?.get("reqId")
                    ?.jsonPrimitive
                    ?.contentOrNull
        )
    }

    /** A call with [args] (a JSON object text) as [by]; null is a call with no credential. */
    suspend fun call(
        tool: String,
        args: String,
        by: ParityToken? = orch
    ): Reply {
        who = by
        return send(tool, Json.parseToJsonElement(args).jsonObject)
    }

    /** An ops call: the elements run in order, the top-level `seatToken` is [token] when given, [top] is spliced into the object. */
    suspend fun ops(
        tool: String,
        vararg elements: String,
        token: String? = null,
        by: ParityToken? = orch,
        top: String = ""
    ): Reply =
        call(
            tool,
            "{" + (if (token != null) """"seatToken":"$token",""" else "") + """"ops":[${elements.joinToString(",")}]$top}""",
            by
        )

    /** A `runs_read` select call. */
    suspend fun select(
        select: String,
        extra: String = "",
        token: String? = null,
        by: ParityToken? = orch,
        tool: String = "runs_read"
    ): Reply = call(tool, "{" + (if (token != null) """"seatToken":"$token",""" else "") + """"select":"$select"$extra}""", by)

    /** A call on the director profile, through the SDK pair [callMcp] builds (the director profile is not served by [call]). */
    suspend fun director(
        tool: String,
        args: String,
        by: ParityToken
    ): Reply {
        val r =
            callMcp(
                rig.composition.toolContext,
                registry,
                { _ -> parityAccess(by) },
                tool,
                Json.parseToJsonElement(args).jsonObject,
                ToolProfile.DIRECTOR
            )
        return Reply(r.isError, r.structured, r.reqIdMeta)
    }

    // ------------------------------------------------------------------ run flows

    fun ids(vararg items: WorkItem): String = items.joinToString(",") { "\"${it.id}\"" }

    suspend fun open(
        members: List<WorkItem> = items,
        by: ParityToken = orch,
        rootItem: WorkItem = root,
        extra: String = ""
    ): Opened {
        val reply =
            ops("runs_write", """{"op":"open","rootId":"${rootItem.id}","items":[${ids(*members.toTypedArray())}]$extra}""", by = by)
        assertTrue(reply.ok(), "open must succeed: $reply")
        return Opened(
            runId = assertNotNull(reply.find("runId"), "open returns the run: $reply"),
            coordinatorToken = assertNotNull(reply.find("coordinatorToken"), "open returns the coordinator token: $reply"),
            reply = reply
        )
    }

    suspend fun assign(
        run: Opened,
        item: WorkItem,
        seat: String,
        token: String? = run.coordinatorToken,
        by: ParityToken = orch,
        extra: String = "",
        key: String? = null
    ): Assigned {
        val keyField = if (key != null) ""","idempotencyKey":"$key"""" else ""
        val reply =
            ops(
                "runs_write",
                """{"op":"assign","runId":"${run.runId}","itemId":"${item.id}","seat":"$seat"$extra$keyField}""",
                token = token,
                by = by
            )
        return Assigned(reply, reply.find("assignmentId"), reply.find("seatToken"), reply.find("attempt")?.toIntOrNull())
    }

    suspend fun assignOk(
        run: Opened,
        item: WorkItem,
        seat: String,
        by: ParityToken = orch
    ): Assigned {
        val assigned = assign(run, item, seat, by = by)
        assertTrue(assigned.reply.ok(), "assign must succeed: ${assigned.reply}")
        assertNotNull(assigned.token, "assign returns a seat token: ${assigned.reply}")
        return assigned
    }

    suspend fun claim(
        item: WorkItem,
        token: String?,
        by: ParityToken = orch
    ): Reply = ops("advance", """{"op":"claim","itemId":"${item.id}"}""", token = token, by = by)

    suspend fun heartbeat(
        item: WorkItem,
        token: String?,
        usage: String? = null,
        by: ParityToken = orch
    ): Reply =
        ops(
            "advance",
            """{"op":"heartbeat","itemId":"${item.id}"${if (usage != null) ""","usage":$usage""" else ""}}""",
            token = token,
            by = by
        )

    suspend fun release(
        item: WorkItem,
        token: String?,
        by: ParityToken = orch
    ): Reply = ops("advance", """{"op":"release","itemId":"${item.id}"}""", token = token, by = by)

    suspend fun endSeat(
        token: String?,
        outcome: String = "done",
        extra: String = "",
        by: ParityToken = orch
    ): Reply = ops("runs_write", """{"op":"end_seat","outcome":"$outcome"$extra}""", token = token, by = by)

    suspend fun close(
        run: Opened,
        reason: String,
        token: String?,
        by: ParityToken = orch
    ): Reply = ops("runs_write", """{"op":"close","runId":"${run.runId}","reason":"$reason"}""", token = token, by = by)

    suspend fun seats(
        run: Opened,
        extra: String = "",
        by: ParityToken = orch
    ): Reply = select("seats", ""","runId":"${run.runId}"$extra""", by = by)

    suspend fun noteUpsert(
        item: WorkItem,
        key: String,
        token: String?,
        body: String = "text",
        extra: String = "",
        by: ParityToken = orch
    ): Reply =
        ops(
            "notes_write",
            """{"op":"upsert","itemId":"${item.id}","key":"$key","role":"work","body":"$body"$extra}""",
            token = token,
            by = by
        )

    // ------------------------------------------------------------------ database reads

    fun rows(
        sql: String,
        vararg args: Any?
    ): List<Map<String, Any?>> =
        DriverManager.getConnection(database.jdbcUrl).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a ->
                    when (a) {
                        null -> ps.setNull(i + 1, java.sql.Types.NULL)
                        is UUID -> ps.setBytes(i + 1, BaselineDataset.bytes(a))
                        else -> ps.setString(i + 1, a.toString())
                    }
                }
                ps.executeQuery().use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) {
                            add(
                                (1..meta.columnCount).associate { column ->
                                    meta.getColumnLabel(column) to rs.getObject(column).let { v -> if (v is Int) v.toLong() else v }
                                }
                            )
                        }
                    }
                }
            }
        }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** The run_seats row a token hashes to, null when there is none. */
    fun seatByToken(token: String): Map<String, Any?>? =
        rows("SELECT * FROM run_seats WHERE token_hash = ?", sha256Hex(token)).singleOrNull()

    fun seatById(assignmentId: String): Map<String, Any?> =
        rows("SELECT * FROM run_seats WHERE id = ?", UUID.fromString(assignmentId)).single()

    fun seatsOfRun(runId: String): List<Map<String, Any?>> =
        rows("SELECT * FROM run_seats WHERE run_id = ? ORDER BY assigned_at, attempt", UUID.fromString(runId))

    fun itemRow(item: WorkItem): Map<String, Any?> = rows("SELECT * FROM work_items WHERE id = ?", item.id).single()

    fun noteRow(
        item: WorkItem,
        key: String
    ): Map<String, Any?>? = rows("SELECT * FROM notes WHERE work_item_id = ? AND key = ?", item.id, key).singleOrNull()

    /** Runs a write statement against the world's database (fixture setup only); UUID arguments bind as 16-byte blobs. */
    fun exec(
        sql: String,
        vararg args: Any?
    ) {
        DriverManager.getConnection(database.jdbcUrl).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a ->
                    when (a) {
                        null -> ps.setNull(i + 1, java.sql.Types.NULL)
                        is UUID -> ps.setBytes(i + 1, BaselineDataset.bytes(a))
                        else -> ps.setString(i + 1, a.toString())
                    }
                }
                ps.executeUpdate()
            }
        }
    }

    /** Inserts a pre-4.0 note: no owner, no owner seat, no attribution seat. */
    fun legacyNote(
        item: WorkItem,
        key: String
    ) {
        DriverManager.getConnection(database.jdbcUrl).use { conn ->
            BaselineDataset.insert(
                conn,
                "notes",
                "id" to BaselineDataset.id("legacy-note:${item.id}:$key"),
                "work_item_id" to BaselineDataset.bytes(item.id),
                "key" to key,
                "role" to "work",
                "body" to "3.x body",
                "created_at" to BaselineDataset.TS,
                "modified_at" to BaselineDataset.TS_PLUS_1
            )
        }
    }

    fun uuidOf(bytes: Any?): UUID = ByteBuffer.wrap(bytes as ByteArray).let { UUID(it.long, it.long) }

    fun stamp(text: Any?): Instant = LocalDateTime.parse((text as String).replace(' ', 'T')).toInstant(ZoneOffset.UTC)

    suspend fun callLogRowOf(reply: Reply): Map<String, Any?> = assertNotNull(callLogRowOrNull(reply), "the call was logged: $reply")

    /** The call_log row of [reply], or null when the call left none (a refusal before telemetry may leave none). */
    suspend fun callLogRowOrNull(reply: Reply): Map<String, Any?>? {
        writer.flushNow()
        return CallLogRows.read(database.jdbcUrl).firstOrNull { it["req_id"] == reply.reqId }
    }

    suspend fun shutdown() {
        closeInMemoryPair(client, server)
    }

    override fun close() {
        runBlocking { shutdown() }
    }

    companion object {
        suspend fun create(
            database: SqliteTestDatabase,
            dir: Path,
            clock: SettableClock,
            config: String = SEAT_WORLD_CONFIG
        ): SeatWorld {
            val world = SeatWorld(database, clock, EventLogRig.build(database, dir, config, clock))
            val rig = world.rig
            world.root = rig.seed("Seat root")
            world.m1 = rig.seed("Seated member", parent = world.root, tags = "seated-task")
            world.m2 = rig.seed("Seat-less member", parent = world.root, tags = "plain-task")
            world.m3 = rig.seed("Schema-free member", parent = world.root, tags = "free-task")
            world.d = rig.seed("Not in any run", parent = world.root, tags = "seated-task")
            world.root2 = rig.seed("Other root")
            world.e = rig.seed("Other root member", parent = world.root2, tags = "seated-task")
            return world
        }
    }
}

/**
 * Independent tests (test-author seat, item 95a22e6e, R22) for the seat ops of `runs_write` (`open`'s coordinator token, `assign`,
 * `end_seat`), the `seats` select of `runs_read`, the coordinator-token permissions of `update` and `close`, the heartbeat `usage`
 * and the token-carrying attribution, holder, note-owner and idempotency rules. Every call goes through the real MCP adapter and the
 * production dispatcher over the real composition and SQLite; the lift itself is covered by `SeatTokenLiftTest`.
 *
 * Scenarios S1 to S11 and S15 to S21 of the frozen test-plan (S12 to S14 live in `SeatTokenLiftTest`, S22 in `SeedV35`), with the
 * probes of its section 6.
 *
 * Oracles (never read from a run): the frozen task-scope decisions D1 to D12 and the test-plan ("Seats and the seat token", plan 4.1
 * R22): `open` returns the coordinator token once; `assign` returns `{assignmentId, seatToken, attempt}` to the coordinator token
 * only, validated in the order of D5 (run in scope, coordinator token, run status, `coordinator` is not assignable, membership, seat
 * vocabulary); `end_seat` needs the seat's own token, releases that holder's claims and reports them in `released`; `seats` lists
 * oldest first, active by default, and never echoes a token; heartbeat `usage` needs a token and keeps the maximum per field; the
 * coordinator is the token, never the opener; under a token ADMIN is dropped; claims are held by (principal, assignment); notes are
 * owned by (principal, seat); idempotency is namespaced per assignment. The registered descriptions are compared with the planned
 * text of the base commit's planned deltas, word for word. Literal strings and ids are written from those clauses.
 *
 * Self-resolved from public evidence (task-scope text and `current/docs/api-reference.md`, not from the implementation): the result
 * keys `assignmentId`, `seatToken`, `attempt` (assign), `released` (end_seat) and `coordinatorToken` (open) are looked up by name
 * anywhere in the element result, so a nesting choice does not decide a test; the `seats` rows are the objects that carry both `seat`
 * and `attempt`.
 *
 * Arbitrations (test-plan section 8): (1) a closed run's coordinator token is dead, so `assign` on it is `unauthenticated` at the
 * dispatcher before `run_closed` can be reached (the `run_closed` word is asserted at the service seam in `SeatServiceTest`);
 * (2) a keyed retry of `end_seat` after it committed meets the dead token first, so S13 (`unauthenticated`) decides, not the D8
 * "replay returns the stored result" clause.
 *
 * Negative-assertion fixture conditions: every refusal is paired with the same call that succeeds when the single input it turns on
 * is changed (the coordinator token, the seat's own token, the exact principal), and asserts the table it would have written is
 * unchanged.
 *
 * Domain invariants satisfied by construction: items are children of their root and seeded through the rig; runs are opened through
 * the tool itself, so the stored state is whatever the service produced; seats are assigned through the coordinator token.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunsSeatOpsTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private val token43 = Regex("^[A-Za-z0-9_-]{43}$")
    private val tokenKeys = setOf("token", "seatToken", "coordinatorToken", "tokenHash", "token_hash")
    private val uuidText = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private fun assertForbiddenKind(
        reply: Reply,
        required: String,
        label: String
    ) {
        assertEquals("forbidden", reply.code(), "$label: $reply")
        val error = reply.error()
        assertEquals("principal_kind", reply.find("scope", error), "$label: $reply")
        assertEquals(required, reply.find("required", error), "$label: $reply")
    }

    private fun seatCount(
        w: SeatWorld,
        run: Opened
    ): Int = w.seatsOfRun(run.runId).size

    // ------------------------------------------------------------------ the registered surface

    private fun rendered(
        tool: String,
        profile: ToolProfile
    ): JsonObject =
        McpJson
            .encodeToJsonElement(
                Tool.serializer(),
                assertNotNull(McpOperationAdapter.toolFor(registry[tool]!!, profile), "$tool on ${profile.wire}")
            ).jsonObject

    private fun JsonObject.properties(vararg path: String): JsonObject {
        var node: JsonObject = getValue("inputSchema").jsonObject.getValue("properties").jsonObject
        for (step in path) node = node.getValue(step).jsonObject
        return node
    }

    private fun JsonObject.opFields(): JsonObject =
        properties("ops")
            .getValue("items")
            .jsonObject
            .getValue("properties")
            .jsonObject

    private fun JsonObject.described(name: String): String =
        getValue(name)
            .jsonObject
            .getValue("description")
            .jsonPrimitive.content

    @Test
    fun `the new members exist on the orchestration profile only, with the capability of their tool`() {
        val write = registry["runs_write"] as OpsToolSpec<*>
        val read = registry["runs_read"] as SelectToolSpec
        for (name in listOf("assign", "end_seat")) {
            val op = write.ops.single { it.name == name }
            assertEquals(setOf(ToolProfile.ORCHESTRATION), op.profiles, name)
            assertEquals(ApiCapability.RUNS, op.capability, name)
            assertFalse(op.destructive, "$name is not destructive; close stays the only destructive op")
        }
        val seats = read.selects.single { it.name == "seats" }
        assertEquals(setOf(ToolProfile.ORCHESTRATION), seats.profiles)
        assertEquals(ApiCapability.READ, seats.capability)

        val assign = write.ops.single { it.name == "assign" }
        for (code in listOf(
            ErrorCode.NOT_FOUND,
            ErrorCode.FORBIDDEN,
            ErrorCode.RUN_CLOSED,
            ErrorCode.RUN_PAUSED,
            ErrorCode.UNAUTHENTICATED
        )) {
            assertTrue(code in assign.errors, "assign declares $code: ${assign.errors}")
        }
        val end = write.ops.single { it.name == "end_seat" }
        assertTrue(ErrorCode.UNAUTHENTICATED in end.errors && ErrorCode.FORBIDDEN in end.errors, "end_seat declares ${end.errors}")

        assertEquals(setOf("open", "update", "close", "assign", "end_seat"), visibleNames(write, ToolProfile.ORCHESTRATION).toSet())
        assertEquals(
            setOf("open", "close", "pause", "resume"),
            visibleNames(write, ToolProfile.DIRECTOR).toSet(),
            "the director sees neither new op"
        )
        assertEquals(setOf("get", "list", "seats"), visibleNames(read, ToolProfile.ORCHESTRATION).toSet())
        assertEquals(setOf("get", "list"), visibleNames(read, ToolProfile.DIRECTOR).toSet(), "the director does not see seats")
        assertEquals(emptySet(), visibleNames(write, ToolProfile.KNOWLEDGE).toSet())
    }

    @Test
    fun `the registered descriptions of the new fields are the planned text of the base commit, word for word`() {
        val write = rendered("runs_write", ToolProfile.ORCHESTRATION).opFields()
        assertEquals(
            "A seat of the item's schema (implementer or planner when it has none). Returns a seat token; re-assigning supersedes. [ops: assign]",
            write.described("seat")
        )
        assertEquals("How the calling seat ended. [ops: end_seat]", write.described("outcome"))
        assertEquals(
            listOf("done", "failed", "abandoned"),
            write
                .getValue("outcome")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content },
            "the wire outcomes; superseded and run_closed are server-only"
        )
        assertEquals("Closing summary, at most 500 characters. [ops: end_seat]", write.described("summary"))
        assertTrue(
            write.described("links").startsWith("Up to 10 {kind, ref} pointers; kind is item, note, doc, commit or url. [ops: end_seat"),
            write.described("links")
        )
        assertTrue(
            write.described("itemId").startsWith("Item id: a UUID or a hex prefix of 8 to 32 characters. [ops: assign"),
            write.described("itemId")
        )
        assertTrue(
            "assign" in write.described("runId") && "update" in write.described("runId"),
            "runId is shared by update, close and assign"
        )
        val opEnum =
            write
                .getValue("op")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        assertEquals(setOf("open", "update", "close", "assign", "end_seat"), opEnum)

        val read = rendered("runs_read", ToolProfile.ORCHESTRATION).properties()
        assertEquals(
            setOf("get", "list", "seats"),
            read
                .getValue("select")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        )
        assertEquals("Only the seats on this item (a UUID or a hex prefix). [select: seats]", read.described("itemId"))
        assertEquals("true: include ended seats; default only active ones. [select: seats]", read.described("ended"))

        val advance = rendered("advance", ToolProfile.ORCHESTRATION).opFields()
        assertEquals(
            "Absolute spend so far for the calling seat, {tokens, costUsd}; the highest reported value is kept. [ops: heartbeat]",
            advance.described("usage")
        )
    }

    private fun resource(name: String) = RunsSeatOpsTest::class.java.getResource("/golden/tools/$name")

    @Test
    fun `the planned deltas no longer hold what the registered tools now carry, and keep what is still planned`() {
        fun keys(file: String): Set<String> = Json.parseToJsonElement(assertNotNull(resource(file), file).readText()).jsonObject.keys

        val write = keys("planned/runs_write.orchestration.delta.json")
        for (consumed in listOf("seat", "outcome", "summary", "links")) {
            assertFalse(consumed in write, "$consumed is registered now, so the planned delta no longer holds it: $write")
        }
        for (planned in listOf("class", "question", "options", "recommended", "context", "dedupeKey", "severity", "block")) {
            assertTrue(planned in write, "$planned is still planned (raise): $write")
        }
        assertNull(resource("planned/runs_read.orchestration.delta.json"), "itemId and ended are registered now, nothing is left to plan")
        val advance = keys("planned/advance.orchestration.delta.json")
        assertFalse("usage" in advance, "usage is registered now: $advance")
        assertTrue("reason" in advance && "decisionId" in advance, "the still-planned transition fields stay: $advance")
        assertEquals(emptyList(), PlannedSurfaceChecks.violations(registry))
    }

    // ------------------------------------------------------------------ S1 open and the coordinator token

    @Test
    fun `S1 open returns the coordinator token once, stores only its hash on an active coordinator row and shows it nowhere else`() =
        world { w ->
            val opened = w.open()
            val token = opened.coordinatorToken

            assertTrue(token43.matches(token), "43 base64url characters: [$token]")
            val row = w.seatsOfRun(opened.runId).single()
            assertEquals("coordinator", row["seat"])
            assertNull(row["item_id"], "the pseudo-seat has no item")
            assertEquals(1L, row["attempt"])
            assertEquals("active", row["status"])
            assertNull(row["outcome"])
            assertEquals(w.orchId, row["principal_id"], "bound to the opener")
            assertEquals(w.sha256Hex(token), row["token_hash"])

            val get = w.select("get", ""","runId":"${opened.runId}"""")
            assertTrue(get.ok(), get.toString())
            assertFalse(token in get.toString(), "a later read never shows the token")
            assertTrue(get.allKeys().intersect(tokenKeys).isEmpty(), "no token key in the run view: ${get.allKeys()}")
            assertFalse(token in w.seats(opened).toString(), "the seats select never shows it")
            assertTrue(w.rows("SELECT data FROM events").none { token in ((it["data"] as? String) ?: "") }, "no event payload carries it")

            // control: another run gets another token and its own row
            val second = w.open(members = listOf(w.d))
            assertNotEquals(token, second.coordinatorToken)
            assertEquals(w.sha256Hex(second.coordinatorToken), w.seatsOfRun(second.runId).single()["token_hash"])
        }

    @Test
    fun `S1 a refused open creates no coordinator row, and the successful open of the same call shape creates one`() =
        world { w ->
            val member = """{"op":"open","rootId":"${w.root2.id}","items":[${w.ids(w.e)}]}"""
            val noRuns = w.ops("runs_write", member, by = w.readOnly)
            assertEquals("forbidden", noRuns.code(), noRuns.toString())
            val outOfScope = w.ops("runs_write", member, by = w.rootScoped)
            assertEquals("forbidden", outOfScope.code(), outOfScope.toString())
            assertEquals(0, w.rows("SELECT * FROM run_seats").size, "a refused open wrote no seat row")
            assertEquals(0, w.rows("SELECT * FROM runs").size, "and no run")

            val opened = w.open(members = listOf(w.e), rootItem = w.root2)
            assertEquals(1, seatCount(w, opened), "control: the accepted open wrote exactly the coordinator row")
        }

    // ------------------------------------------------------------------ S2 assign

    @Test
    fun `S2 assign by the coordinator token returns the assignment id, a fresh seat token and attempt 1, and stores row and event`() =
        world { w ->
            val run = w.open()
            val assigned = w.assign(run, w.m1, "builder", extra = ""","hints":{"model":"opus"}""")

            assertTrue(assigned.reply.ok(), assigned.reply.toString())
            val id = assertNotNull(assigned.assignmentId)
            val token = assertNotNull(assigned.token)
            assertTrue(uuidText.matches(id), "assignmentId is a UUID: $id")
            assertTrue(token43.matches(token), "seatToken: [$token]")
            assertNotEquals(run.coordinatorToken, token)
            assertEquals(1, assigned.attempt)

            val row = w.seatById(id)
            assertEquals(run.runId, w.uuidOf(row["run_id"]).toString())
            assertEquals(w.m1.id, w.uuidOf(row["item_id"]))
            assertEquals("builder", row["seat"])
            assertEquals("active", row["status"])
            assertEquals(w.orchId, row["principal_id"], "the assigning principal holds the seat")
            assertEquals(w.sha256Hex(token), row["token_hash"])
            assertEquals(
                "opus",
                Json
                    .parseToJsonElement(row["hints"] as String)
                    .jsonObject["model"]
                    ?.jsonPrimitive
                    ?.content
            )

            val event = w.rig.rows().single { it.type == "seat.assigned" }
            assertEquals(run.runId, event.payload()["runId"]?.jsonPrimitive?.content)
            assertEquals(w.m1.id.toString(), event.payload()["itemId"]?.jsonPrimitive?.content)
            assertEquals("builder", event.payload()["seat"]?.jsonPrimitive?.content)
            assertEquals(
                1,
                event
                    .payload()["attempt"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt()
            )

            // the token does not appear in the event log or the read views
            assertTrue(w.rows("SELECT data FROM events").none { token in ((it["data"] as? String) ?: "") })
        }

    @Test
    fun `S2 hints must be a string to string map`() =
        world { w ->
            val run = w.open()
            val bad = w.assign(run, w.m1, "builder", extra = ""","hints":{"model":1}""")
            assertEquals("invalid_request", bad.reply.code(), bad.reply.toString())
            assertTrue(bad.reply.pointers().any { it.endsWith("/hints") || it.contains("/hints/") }, "pointer: ${bad.reply.pointers()}")
            assertEquals(1, seatCount(w, run), "nothing was assigned")
            assertTrue(w.assign(run, w.m1, "builder", extra = ""","hints":{"model":"1"}""").reply.ok(), "control: strings are accepted")
        }

    @Test
    fun `S3 re-assigning a seat supersedes the old assignment, counts attempt 2, kills the old token and frees its claim at once`() =
        world { w ->
            val run = w.open()
            val first = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, first.token).ok(), "fixture: the first attempt holds the item")
            clock.advanceSeconds(3)

            val second = w.assignOk(run, w.m1, "builder")

            assertEquals(2, second.attempt)
            assertNotEquals(first.token, second.token)
            val old = w.seatById(assertNotNull(first.assignmentId))
            assertEquals("ended", old["status"])
            assertEquals("superseded", old["outcome"])
            val item = w.itemRow(w.m1)
            assertNull(item["claimed_by"], "the old attempt's claim is released")
            assertNull(item["claim_assignment_id"])
            assertEquals("unauthenticated", w.claim(w.m2, first.token).wholeCode(), "the old token is dead")
            assertTrue(w.claim(w.m1, second.token).ok(), "control: the new attempt claims without waiting for the old TTL")
            assertEquals(second.assignmentId, w.uuidOf(w.itemRow(w.m1)["claim_assignment_id"]).toString())
        }

    @Test
    fun `S2 a replayed assign with the same idempotency key returns the stored result and writes no second seat`() =
        world { w ->
            val run = w.open()
            val first = w.assign(run, w.m1, "builder", key = "assign-key-1")
            val replay = w.assign(run, w.m1, "builder", key = "assign-key-1")

            assertTrue(first.reply.ok() && replay.reply.ok(), "$first / $replay")
            assertEquals("true", replay.reply.find("replayed"), replay.reply.toString())
            assertEquals(first.assignmentId, replay.assignmentId, "the stored result is returned")
            assertEquals(2, seatCount(w, run), "the coordinator row and exactly one seat")
            assertEquals(1, w.seatsOfRun(run.runId).count { it["seat"] == "builder" })

            // the same key with a different element is idempotency_mismatch and writes nothing
            val clash = w.assign(run, w.m2, "implementer", key = "assign-key-1")
            assertEquals("idempotency_mismatch", clash.reply.code(), clash.reply.toString())
            assertEquals(2, seatCount(w, run))
        }

    // ------------------------------------------------------------------ S15 who may assign

    @Test
    fun `S15 only the coordinator token assigns - not the opener, another runs principal, a human, nor a member seat token`() =
        world { w ->
            val run = w.open()
            val member = w.assignOk(run, w.m1, "builder")

            val attempts =
                listOf(
                    "the opener without a token" to w.assign(run, w.m2, "implementer", token = null),
                    "another principal with the runs capability" to w.assign(run, w.m2, "implementer", token = null, by = w.other),
                    "a human credential" to w.assign(run, w.m2, "implementer", token = null, by = w.human),
                    "a member seat token" to w.assign(run, w.m2, "implementer", token = member.token)
                )
            for ((label, attempt) in attempts) assertForbiddenKind(attempt.reply, "coordinator", label)
            assertEquals(2, seatCount(w, run), "no refused attempt wrote a seat")

            assertTrue(w.assign(run, w.m2, "implementer").reply.ok(), "control: the coordinator token assigns the same call")
            assertEquals(3, seatCount(w, run))
        }

    @Test
    fun `S15 the scope check comes before the token check, and a coordinator token of another run authorizes nothing`() =
        world { w ->
            val run1 = w.open()
            val run2 = w.open(members = listOf(w.e), rootItem = w.root2)

            // a caller scoped to root R cannot see the run of R2 at all: forbidden on the root, not on the principal kind
            val scoped = w.assign(run2, w.e, "builder", token = null, by = w.rootScoped)
            assertEquals("forbidden", scoped.reply.code(), scoped.reply.toString())
            assertEquals("root", scoped.reply.find("scope", scoped.reply.error()), scoped.reply.toString())
            // control: the same call by an unrestricted caller without a token is refused on the kind instead
            assertForbiddenKind(w.assign(run2, w.e, "builder", token = null, by = w.other).reply, "coordinator", "unrestricted, no token")

            // run1's coordinator token on run2
            assertForbiddenKind(w.assign(run2, w.e, "builder", token = run1.coordinatorToken).reply, "coordinator", "another run's token")
            assertEquals(1, seatCount(w, run2), "run2 still holds only its coordinator row")
            assertTrue(w.assign(run2, w.e, "builder", token = run2.coordinatorToken).reply.ok(), "control: run2's own token assigns")
        }

    // ------------------------------------------------------------------ S16 assign validation

    @Test
    fun `S16 an unknown seat is not_found of kind seat, and the default pair applies only to a seat-less or schema-free item`() =
        world { w ->
            val run = w.open()
            val checks =
                listOf(
                    Triple(w.m1, "reviewer", "not_found"),
                    Triple(w.m1, "Builder", "not_found"),
                    Triple(w.m1, "implementer", "not_found"),
                    Triple(w.m1, "planner", "not_found"),
                    Triple(w.m2, "builder", "not_found"),
                    Triple(w.m2, "Implementer", "not_found"),
                    Triple(w.m3, "reviewer", "not_found")
                )
            for ((item, seat, code) in checks) {
                val reply = w.assign(run, item, seat).reply
                assertEquals(code, reply.code(), "seat [$seat] on ${item.title}: $reply")
                assertEquals("seat", reply.find("kind", reply.error()?.get("detail")), "$seat: $reply")
            }
            assertEquals(1, seatCount(w, run), "no refused seat was written")

            assertTrue(w.assign(run, w.m1, "builder").reply.ok(), "control: a seat of the item's schema")
            assertTrue(w.assign(run, w.m2, "implementer").reply.ok(), "control: the default pair on a seat-less schema")
            assertTrue(w.assign(run, w.m3, "planner").reply.ok(), "control: the default pair on a schema-free item")
        }

    @Test
    fun `S16 the seat name coordinator is invalid_request on the seat pointer, a non-member item on the item pointer`() =
        world { w ->
            val run = w.open()
            for (item in listOf(w.m1, w.m2, w.m3)) {
                val reply = w.assign(run, item, "coordinator").reply
                assertEquals("invalid_request", reply.code(), "${item.title}: $reply")
                assertTrue(reply.pointers().any { it.endsWith("/seat") }, "pointer: ${reply.pointers()}")
            }
            for (outsider in listOf(w.d, w.e)) {
                val reply = w.assign(run, outsider, "builder").reply
                assertEquals("invalid_request", reply.code(), "${outsider.title}: $reply")
                assertTrue(reply.pointers().any { it.endsWith("/itemId") }, "pointer: ${reply.pointers()}")
                assertTrue("is not a member of the run" in reply.toString(), reply.toString())
            }
            assertEquals(1, seatCount(w, run))
            assertTrue(w.assign(run, w.m1, "builder").reply.ok(), "control")
        }

    @Test
    fun `S16 an unknown run is not_found of kind run`() =
        world { w ->
            val run = w.open()
            val ghost = Opened(UUID.randomUUID().toString(), run.coordinatorToken, run.reply)
            val reply = w.assign(ghost, w.m1, "builder").reply
            assertEquals("not_found", reply.code(), reply.toString())
            assertEquals("run", reply.find("kind", reply.error()?.get("detail")), reply.toString())
            assertEquals(1, seatCount(w, run))
        }

    @Test
    fun `S16 a paused run refuses assign as run_paused until resumed, and a closed run's dead coordinator token is unauthenticated`() =
        world { w ->
            val run = w.open()
            val pause = w.director("runs_write", """{"ops":[{"op":"pause","runId":"${run.runId}"}]}""", w.human)
            assertTrue(pause.ok(), pause.toString())

            val refused = w.assign(run, w.m1, "builder").reply
            assertEquals("run_paused", refused.code(), refused.toString())
            assertEquals(1, seatCount(w, run), "nothing was assigned while paused")

            val resume = w.director("runs_write", """{"ops":[{"op":"resume","runId":"${run.runId}"}]}""", w.human)
            assertTrue(resume.ok(), resume.toString())
            assertTrue(w.assign(run, w.m1, "builder").reply.ok(), "control: the same call after the resume")

            val closed = w.close(run, "cancelled", run.coordinatorToken)
            assertTrue(closed.ok(), closed.toString())
            val afterClose = w.assign(run, w.m2, "implementer").reply
            assertEquals(
                "unauthenticated",
                afterClose.wholeCode(),
                "the token died with the run, before run_closed can be reached: $afterClose"
            )
        }
    // ------------------------------------------------------------------ S8 end_seat

    @Test
    fun `S8 end_seat by the seat's own token ends it, reports the released claim, kills the token and records seat ended`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, a.token).ok(), "fixture: the seat holds the item")
            clock.advanceSeconds(9)

            val end =
                w.endSeat(
                    a.token,
                    "done",
                    ""","summary":"all green","links":[{"kind":"commit","ref":"abc123"},{"kind":"url","ref":"https://example.test/p/1"}]"""
                )

            assertTrue(end.ok(), end.toString())
            assertEquals(a.assignmentId, end.find("assignmentId"))
            assertEquals(w.m1.id.toString(), end.find("itemId"))
            assertEquals("builder", end.find("seat"))
            assertEquals("1", end.find("attempt"))
            assertEquals("done", end.find("outcome"))
            assertEquals(clock.now(), Instant.parse(assertNotNull(end.find("endedAt"), end.toString())), "endedAt is the unit clock")
            assertTrue(w.m1.id.toString() in end.findElement("released").toString(), "released lists the item: $end")

            val row = w.seatById(assertNotNull(a.assignmentId))
            assertEquals("ended", row["status"])
            assertEquals("done", row["outcome"])
            assertEquals("all green", row["summary"])
            assertEquals(2, Json.parseToJsonElement(row["links"] as String).jsonArray.size, "both links are stored")
            assertNull(w.itemRow(w.m1)["claimed_by"], "the seat's claim is released")
            assertNull(w.itemRow(w.m1)["claim_assignment_id"])

            val event = w.rig.rows().single { it.type == "seat.ended" }
            assertEquals("done", event.payload()["outcome"]?.jsonPrimitive?.content)
            assertEquals("builder", event.payload()["seat"]?.jsonPrimitive?.content)
            assertEquals(w.m1.id.toString(), event.payload()["itemId"]?.jsonPrimitive?.content)

            assertEquals("unauthenticated", w.claim(w.m2, a.token).wholeCode(), "the token stopped working")
            assertEquals(1, w.seats(run).seatRows().size, "default seats lists only the active coordinator row")
            assertTrue("done" in w.seats(run, ""","ended":true""").toString(), "the ended listing shows the outcome")
        }

    @Test
    fun `S8 end_seat releases only the ending seat's claims and leaves a sibling seat of the same principal holding its own`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val b = w.assignOk(run, w.m3, "planner")
            assertTrue(w.claim(w.m1, a.token).ok())
            assertTrue(w.claim(w.m3, b.token).ok(), "fixture: the sibling seat claims too and does not evict the first")

            val end = w.endSeat(a.token)

            assertTrue(end.ok(), end.toString())
            assertNull(w.itemRow(w.m1)["claimed_by"], "the ending seat's claim is gone")
            assertEquals(w.orchId, w.itemRow(w.m3)["claimed_by"], "control: the sibling's claim is still in place")
            assertEquals(b.assignmentId, w.uuidOf(w.itemRow(w.m3)["claim_assignment_id"]).toString())
            assertFalse(w.m3.id.toString() in end.findElement("released").toString(), "released names only the ending seat's items")
        }

    @Test
    fun `S8 a seat that held nothing ends with an empty released list`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val end = w.endSeat(a.token, "abandoned")
            assertTrue(end.ok(), end.toString())
            assertEquals("abandoned", end.find("outcome"))
            assertEquals("[]", end.findElement("released").toString())
        }

    @Test
    fun `S8 and S13 a keyed retry of end_seat after it committed meets the dead token first - unauthenticated, nothing written twice`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val key = ""","idempotencyKey":"end-key-1""""
            assertTrue(w.endSeat(a.token, "done", key).ok(), "the first call commits")
            val eventsAfterFirst = w.rig.rows().count { it.type == "seat.ended" }

            val retry = w.endSeat(a.token, "done", key)
            val fresh = w.endSeat(a.token, "done")

            assertEquals("unauthenticated", retry.wholeCode(), "arbitration 2: S13 decides: $retry")
            assertEquals("unauthenticated", fresh.wholeCode(), "a fresh call with the dead token: $fresh")
            assertEquals(eventsAfterFirst, w.rig.rows().count { it.type == "seat.ended" }, "no second seat.ended")
        }

    // ------------------------------------------------------------------ S17 end_seat refusals and limits

    @Test
    fun `S17 end_seat without a token or with the coordinator token is forbidden on the kind seat, and the seat stays active`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")

            assertForbiddenKind(w.endSeat(null), "seat", "no token")
            assertForbiddenKind(w.endSeat(run.coordinatorToken), "seat", "the coordinator token")
            assertEquals("active", w.seatById(assertNotNull(a.assignmentId))["status"], "neither refusal ended the seat")
            assertEquals("active", w.seatsOfRun(run.runId).single { it["seat"] == "coordinator" }["status"], "nor the coordinator row")

            assertTrue(w.endSeat(a.token).ok(), "control: the seat's own token ends it")
        }

    private suspend fun fresh(
        w: SeatWorld,
        run: Opened
    ): Assigned = w.assignOk(run, w.m1, "builder")

    private fun links(count: Int): String = (1..count).joinToString(",", "[", "]") { """{"kind":"url","ref":"https://example.test/$it"}""" }

    @Test
    fun `S17 end_seat limits - a long summary, too many links, a bad kind or ref, a bad or missing outcome - are invalid_request`() =
        world { w ->
            val run = w.open()
            val longRef = "r".repeat(201)
            val refusals =
                listOf(
                    Triple("a 501-character summary", """,""" + """"summary":"${"x".repeat(501)}"""", "/summary"),
                    Triple("11 links", ""","links":${links(11)}""", "/links"),
                    Triple("a link of kind file", ""","links":[{"kind":"file","ref":"a"}]""", "/links/0/kind"),
                    Triple(
                        "a bad kind in the second link",
                        ""","links":[{"kind":"url","ref":"a"},{"kind":"file","ref":"b"}]""",
                        "/links/1/kind"
                    ),
                    Triple("an empty ref", ""","links":[{"kind":"url","ref":""}]""", "/links/0/ref"),
                    Triple("a blank ref", ""","links":[{"kind":"url","ref":"   "}]""", "/links/0/ref"),
                    Triple("a 201-character ref", ""","links":[{"kind":"url","ref":"$longRef"}]""", "/links/0/ref")
                )
            for ((label, extra, pointer) in refusals) {
                val seat = fresh(w, run)
                val reply = w.endSeat(seat.token, "done", extra)
                assertEquals("invalid_request", reply.code(), "$label: $reply")
                assertTrue(reply.pointers().any { it.endsWith(pointer) }, "$label: pointer $pointer in ${reply.pointers()}")
                assertEquals("active", w.seatById(assertNotNull(seat.assignmentId))["status"], "$label: the seat is still active")
            }
            for (outcome in listOf("superseded", "run_closed", "bogus", "Done")) {
                val seat = fresh(w, run)
                val reply = w.endSeat(seat.token, outcome)
                assertEquals("invalid_request", reply.code(), "outcome [$outcome]: $reply")
                assertTrue(reply.pointers().any { it.endsWith("/outcome") }, "outcome [$outcome]: ${reply.pointers()}")
                assertEquals("active", w.seatById(assertNotNull(seat.assignmentId))["status"], "outcome [$outcome]: still active")
            }
            val missing = fresh(w, run)
            val none = w.ops("runs_write", """{"op":"end_seat"}""", token = missing.token)
            assertEquals("invalid_request", none.code(), none.toString())
            assertTrue(none.pointers().any { it.endsWith("/outcome") }, "pointer: ${none.pointers()}")
            assertEquals("active", w.seatById(assertNotNull(missing.assignmentId))["status"])
        }

    @Test
    fun `S17 end_seat boundaries - a 500-character summary, 10 links, a 200-character ref and every link kind - are accepted`() =
        world { w ->
            val run = w.open()
            val accepted =
                listOf(
                    "a 500-character summary" to ""","summary":"${"x".repeat(500)}"""",
                    "10 links" to ""","links":${links(10)}""",
                    "a 200-character ref" to ""","links":[{"kind":"commit","ref":"${"r".repeat(200)}"}]""",
                    "no summary and no links" to "",
                    "every link kind" to
                        ""","links":[{"kind":"item","ref":"a"},{"kind":"note","ref":"b"},{"kind":"doc","ref":"c"},{"kind":"commit","ref":"d"},{"kind":"url","ref":"e"}]"""
                )
            for ((label, extra) in accepted) {
                val seat = fresh(w, run)
                val reply = w.endSeat(seat.token, "done", extra)
                assertTrue(reply.ok(), "$label: $reply")
                assertEquals("ended", w.seatById(assertNotNull(seat.assignmentId))["status"], label)
            }
        }

    @Test
    fun `S17 probe the same link twice is stored as given and does not fail the call`() =
        world { w ->
            val run = w.open()
            val seat = fresh(w, run)
            val twice = """{"kind":"commit","ref":"abc123"}"""
            val reply = w.endSeat(seat.token, "done", ""","links":[$twice,$twice]""")
            assertTrue(reply.ok(), reply.toString())
            val stored = Json.parseToJsonElement(w.seatById(assertNotNull(seat.assignmentId))["links"] as String).jsonArray
            assertEquals(2, stored.size, "the oracle names no dedup rule, so the list is kept as given")
        }

    // ------------------------------------------------------------------ S9 seats

    @Test
    fun `S9 seats lists the coordinator and the active seats oldest first, ended ones only on request, and never a token or a principal`() =
        world { w ->
            val run = w.open()
            clock.advanceSeconds(1)
            val first = w.assignOk(run, w.m1, "builder")
            clock.advanceSeconds(1)
            val second = w.assignOk(run, w.m2, "implementer")
            clock.advanceSeconds(1)
            val third = w.assignOk(run, w.m1, "builder")

            val active = w.seats(run)
            assertTrue(active.ok(), active.toString())
            val activeRows = active.seatRows()
            assertEquals(
                listOf("coordinator" to 1, "implementer" to 1, "builder" to 2),
                activeRows.map {
                    it.getValue("seat").jsonPrimitive.content to
                        it
                            .getValue("attempt")
                            .jsonPrimitive.content
                            .toInt()
                },
                "active only, oldest first"
            )
            val coordinatorRow = activeRows.first()
            assertTrue(
                coordinatorRow["itemId"].let {
                    it == null || it is kotlinx.serialization.json.JsonNull
                },
                "the pseudo-seat has no item: $coordinatorRow"
            )
            assertTrue(
                w.m2.id.toString() in activeRows[1].toString() && w.m1.id.toString() in activeRows[2].toString(),
                "each row names its item"
            )

            val all = w.seats(run, ""","ended":true""")
            assertEquals(
                listOf("coordinator" to 1, "builder" to 1, "implementer" to 1, "builder" to 2),
                all.seatRows().map {
                    it.getValue("seat").jsonPrimitive.content to
                        it
                            .getValue("attempt")
                            .jsonPrimitive.content
                            .toInt()
                },
                "ended included, oldest first"
            )
            val forItem = w.seats(run, ""","ended":true,"itemId":"${w.m1.id}"""")
            assertEquals(
                listOf(1, 2),
                forItem.seatRows().map {
                    it
                        .getValue("attempt")
                        .jsonPrimitive.content
                        .toInt()
                },
                "one item's seats, the coordinator excluded"
            )
            val forItemActive = w.seats(run, ""","itemId":"${w.m1.id}"""")
            assertEquals(
                listOf(2),
                forItemActive.seatRows().map {
                    it
                        .getValue("attempt")
                        .jsonPrimitive.content
                        .toInt()
                }
            )

            val everything = active.toString() + all.toString() + forItem.toString()
            val hashes = w.seatsOfRun(run.runId).map { it["token_hash"] as String }
            for (secret in listOfNotNull(run.coordinatorToken, first.token, second.token, third.token) + hashes) {
                assertFalse(secret in everything, "no token or token hash is echoed: $secret")
            }
            val keys = all.allKeys()
            assertTrue(keys.intersect(tokenKeys).isEmpty(), "no token key: $keys")
            assertTrue(
                keys.none {
                    it.contains("principal", ignoreCase = true) || it.contains("claimAssignment", ignoreCase = true)
                },
                "no principal or claim assignment: $keys"
            )
            assertFalse(w.orchId in everything, "the principal id of a seat row is not on the wire")
        }

    @Test
    fun `S9 seats of an unknown run is not_found, of a run outside the scope is forbidden on the root, and a read-only caller may read`() =
        world { w ->
            val run = w.open()
            val other = w.open(members = listOf(w.e), rootItem = w.root2)
            val ghost = w.select("seats", ""","runId":"${UUID.randomUUID()}"""")
            assertEquals("not_found", ghost.code(), ghost.toString())
            assertEquals("run", ghost.find("kind", ghost.error()?.get("detail")))

            val outside = w.select("seats", ""","runId":"${other.runId}"""", by = w.rootScoped)
            assertEquals("forbidden", outside.code(), outside.toString())
            assertEquals("root", outside.find("scope", outside.error()))
            // control: the same scoped caller reads the run of its own root
            assertTrue(w.select("seats", ""","runId":"${run.runId}"""", by = w.rootScoped).ok())
            assertTrue(w.select("seats", ""","runId":"${run.runId}"""", by = w.readOnly).ok(), "the read capability is enough")
        }

    @Test
    fun `S9 seats is not offered on the director profile`() =
        world { w ->
            val run = w.open()
            val onDirector = w.director("runs_read", """{"select":"seats","runId":"${run.runId}"}""", w.orch)
            assertEquals("invalid_request", onDirector.code(), onDirector.toString())
            assertTrue(onDirector.pointers().any { it.endsWith("/select") }, "pointer: ${onDirector.pointers()}")
            assertTrue(w.seats(run).ok(), "control: the orchestration profile serves it")
        }

    // ------------------------------------------------------------------ S18 close and update

    private fun runRow(
        w: SeatWorld,
        run: Opened
    ): Map<String, Any?> = w.rows("SELECT * FROM runs WHERE id = ?", UUID.fromString(run.runId)).single()

    @Test
    fun `S18 close completed with a member seat active is invalid_run_state, close cancelled ends every seat with run_closed`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, a.token).ok())

            val refused = w.close(run, "completed", run.coordinatorToken)
            assertEquals("invalid_run_state", refused.code(), refused.toString())
            assertEquals("open", runRow(w, run)["status"])
            assertTrue(w.seatsOfRun(run.runId).all { it["status"] == "active" }, "every seat is still active")
            assertEquals(w.orchId, w.itemRow(w.m1)["claimed_by"], "the claim is still held")

            val closed = w.close(run, "cancelled", run.coordinatorToken)
            assertTrue(closed.ok(), closed.toString())
            assertEquals("closed", runRow(w, run)["status"])
            assertEquals("cancelled", runRow(w, run)["terminal_reason"])
            val rows = w.seatsOfRun(run.runId)
            assertEquals(2, rows.size)
            assertTrue(
                rows.all {
                    it["status"] == "ended" && it["outcome"] == "run_closed"
                },
                "every seat, the coordinator included, ended with run_closed: $rows"
            )
            assertNull(w.itemRow(w.m1)["claimed_by"], "the member's claim is released by the cascade")
            assertEquals("unauthenticated", w.claim(w.m2, a.token).wholeCode(), "the seat token died with the run")
            assertEquals(
                "unauthenticated",
                w.select("get", ""","runId":"${run.runId}"""", token = run.coordinatorToken).wholeCode(),
                "so did the coordinator token"
            )
        }

    @Test
    fun `S18 close completed with only the coordinator row active is accepted, and the coordinator token dies with the run`() =
        world { w ->
            val run = w.open()
            val closed = w.close(run, "completed", run.coordinatorToken)
            assertTrue(closed.ok(), closed.toString())
            assertEquals("closed", runRow(w, run)["status"])
            assertEquals("completed", runRow(w, run)["terminal_reason"])
            val row = w.seatsOfRun(run.runId).single()
            assertEquals("ended", row["status"])
            assertEquals("run_closed", row["outcome"])
            assertEquals("unauthenticated", w.close(run, "cancelled", run.coordinatorToken).wholeCode())
        }

    @Test
    fun `S18 completed close with an ended member seat is accepted - only active seats block it`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.endSeat(a.token).ok())
            assertTrue(w.close(run, "completed", run.coordinatorToken).ok())
            val rows = w.seatsOfRun(run.runId)
            assertEquals(setOf("done", "run_closed"), rows.map { it["outcome"] as String }.toSet(), "the seat keeps its own outcome")
        }

    @Test
    fun `D3 close is for the coordinator token, the director or a human - the opener without a token is refused and the run stays open`() =
        world { w ->
            val run = w.open()
            assertForbiddenKind(w.close(run, "cancelled", null), "coordinator,director,human", "the opener without a token")
            assertForbiddenKind(w.close(run, "cancelled", null, by = w.other), "coordinator,director,human", "another principal")
            assertEquals("open", runRow(w, run)["status"])

            // a human closes without any token
            val human = w.close(run, "cancelled", null, by = w.human)
            assertTrue(human.ok(), human.toString())
            assertEquals("closed", runRow(w, run)["status"])
            assertTrue(
                w.seatsOfRun(run.runId).all { it["status"] == "ended" },
                "the cascade ended the coordinator row of a run closed by a human"
            )

            // control: the coordinator token closes
            val second = w.open(members = listOf(w.d))
            assertTrue(w.close(second, "cancelled", second.coordinatorToken).ok())
        }

    @Test
    fun `D3 update is for the coordinator token only - the opener without a token and a human are refused`() =
        world { w ->
            val run = w.open()
            val update = """{"op":"update","runId":"${run.runId}","hints":{"phase":"two"}}"""

            assertForbiddenKind(w.ops("runs_write", update), "coordinator", "the opener without a token")
            assertForbiddenKind(w.ops("runs_write", update, by = w.human), "coordinator", "a human without a token")
            assertEquals(1L, runRow(w, run)["version"], "nothing was updated")

            val ok = w.ops("runs_write", update, token = run.coordinatorToken)
            assertTrue(ok.ok(), ok.toString())
            assertEquals(2L, runRow(w, run)["version"])
        }

    @Test
    fun `D3 a human credential presenting a seat token is not a human any more - pause is refused under a token and allowed without`() =
        world { w ->
            val run = w.open(by = w.human)
            val pause = """{"op":"pause","runId":"${run.runId}"}"""
            val underToken = w.director("runs_write", """{"seatToken":"${run.coordinatorToken}","ops":[$pause]}""", w.human)
            assertEquals("forbidden", underToken.code(), underToken.toString())
            assertEquals("principal_kind", underToken.find("scope", underToken.error()))
            assertEquals("open", runRow(w, run)["status"], "the run was not paused")

            val plain = w.director("runs_write", """{"ops":[$pause]}""", w.human)
            assertTrue(plain.ok(), "control: the same human without a token pauses: $plain")
            assertEquals("paused", runRow(w, run)["status"])
        }
    // ------------------------------------------------------------------ S10 heartbeat usage and liveness

    private fun usageOf(
        w: SeatWorld,
        assignmentId: String?
    ): Pair<Long, Long> = w.seatById(assertNotNull(assignmentId)).let { (it["usage_tokens"] as Long) to (it["usage_cost_micro"] as Long) }

    @Test
    fun `S10 heartbeat usage under a seat token is stored as tokens and micro-USD and keeps the maximum per field`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, a.token).ok())

            val first = w.heartbeat(w.m1, a.token, """{"tokens":100,"costUsd":0.5}""")
            assertTrue(first.ok(), first.toString())
            assertEquals("true", first.find("refreshed"), first.toString())
            assertEquals(100L to 500_000L, usageOf(w, a.assignmentId))

            assertTrue(w.heartbeat(w.m1, a.token, """{"tokens":50}""").ok())
            assertEquals(100L to 500_000L, usageOf(w, a.assignmentId), "a lower tokens figure keeps the maximum")
            assertTrue(w.heartbeat(w.m1, a.token, """{"costUsd":0.25}""").ok())
            assertEquals(100L to 500_000L, usageOf(w, a.assignmentId), "a lower cost figure keeps the maximum")
            assertTrue(w.heartbeat(w.m1, a.token, """{"tokens":150,"costUsd":0.75}""").ok())
            assertEquals(150L to 750_000L, usageOf(w, a.assignmentId), "both rise")
            assertTrue(w.heartbeat(w.m1, a.token).ok(), "a heartbeat without usage")
            assertEquals(150L to 750_000L, usageOf(w, a.assignmentId), "and changes nothing")
        }

    @Test
    fun `S10 usage of a heartbeat that fails is not stored`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            // the seat holds nothing, so the heartbeat is not_claim_holder
            val refused = w.heartbeat(w.m1, a.token, """{"tokens":999,"costUsd":9.0}""")
            assertEquals("not_claim_holder", refused.code(), refused.toString())
            assertEquals(0L to 0L, usageOf(w, a.assignmentId), "nothing was stored")
            assertTrue(w.claim(w.m1, a.token).ok())
            assertTrue(
                w.heartbeat(w.m1, a.token, """{"tokens":999,"costUsd":9.0}""").ok(),
                "control: once it holds the item the same call stores"
            )
            assertEquals(999L to 9_000_000L, usageOf(w, a.assignmentId))
        }

    @Test
    fun `S19 usage without a seat token is invalid_request on the usage pointer with the reason requires a seat token`() =
        world { w ->
            w.open()
            // a principal-level claim, so the heartbeat itself is fine and only the usage is at fault
            assertTrue(w.claim(w.m3, null).ok())

            val noToken = w.heartbeat(w.m3, null, """{"tokens":1}""")

            assertEquals("invalid_request", noToken.code(), noToken.toString())
            assertTrue(noToken.pointers().any { it.endsWith("/usage") }, "pointer: ${noToken.pointers()} in $noToken")
            assertTrue("requires a seat token" in noToken.toString(), noToken.toString())
            assertTrue(w.heartbeat(w.m3, null).ok(), "control: a heartbeat without usage needs no token")
        }

    private suspend fun badUsage(
        w: SeatWorld,
        usage: String
    ): Pair<Reply, Assigned> {
        val run = w.open()
        val a = w.assignOk(run, w.m1, "builder")
        assertTrue(w.claim(w.m1, a.token).ok())
        val reply = w.heartbeat(w.m1, a.token, usage)
        assertEquals(0L to 0L, usageOf(w, a.assignmentId), "no refused figure was stored")
        assertTrue(w.heartbeat(w.m1, a.token, """{"tokens":0,"costUsd":0}""").ok(), "control: zero is valid")
        return reply to a
    }

    @Test
    fun `S19 negative tokens in usage is invalid_request on the usage tokens pointer`() =
        world { w ->
            val (reply, _) = badUsage(w, """{"tokens":-1}""")
            assertEquals("invalid_request", reply.code(), reply.toString())
            assertTrue(reply.pointers().any { it.endsWith("/usage/tokens") }, "pointer: ${reply.pointers()} in $reply")
        }

    @Test
    fun `S19 negative costUsd in usage is invalid_request on the usage costUsd pointer`() =
        world { w ->
            val (reply, _) = badUsage(w, """{"costUsd":-0.5}""")
            assertEquals("invalid_request", reply.code(), reply.toString())
            assertTrue(reply.pointers().any { it.endsWith("/usage/costUsd") }, "pointer: ${reply.pointers()} in $reply")
        }

    @Test
    fun `S19 usage that is not an object is invalid_request on the usage pointer`() =
        world { w ->
            // The oracle fixes the three figure pointers only; a usage that is not an object is a decode fault of the element.
            val (reply, _) = badUsage(w, "\"lots\"")
            assertEquals("invalid_request", reply.code(), reply.toString())
            assertTrue(reply.pointers().any { it.startsWith("/ops/0") }, "pointer: ${reply.pointers()} in $reply")
        }

    @Test
    fun `D9 a token-carrying write touches last_seen_at - skipped under 5 s, written from 5 s, never by a select or token-less call`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            clock.advanceSeconds(20)
            val t0 = clock.now()

            assertTrue(w.claim(w.m1, a.token).ok())
            assertEquals(t0, w.stamp(w.seatById(assertNotNull(a.assignmentId))["last_seen_at"]), "the first token-carrying write touches")

            clock.advance(Duration.ofMillis(4_999))
            assertTrue(w.heartbeat(w.m1, a.token).ok())
            assertEquals(t0, w.stamp(w.seatById(assertNotNull(a.assignmentId))["last_seen_at"]), "4.999 s old: skipped")
            clock.advance(Duration.ofMillis(1))
            assertTrue(w.heartbeat(w.m1, a.token).ok())
            assertEquals(t0.plusSeconds(5), w.stamp(w.seatById(assertNotNull(a.assignmentId))["last_seen_at"]), "5 s old: written")

            // a select does not touch, even under a token; a control write 30 s later does
            clock.advanceSeconds(30)
            assertTrue(w.seats(run).ok())
            assertTrue(w.select("seats", ""","runId":"${run.runId}"""", token = a.token).ok(), "a select may carry a token")
            assertEquals(t0.plusSeconds(5), w.stamp(w.seatById(assertNotNull(a.assignmentId))["last_seen_at"]), "selects do not touch")
            assertTrue(w.heartbeat(w.m1, a.token).ok())
            assertEquals(
                t0.plusSeconds(35),
                w.stamp(w.seatById(assertNotNull(a.assignmentId))["last_seen_at"]),
                "the control write touches"
            )

            // a token-less write touches no seat
            clock.advanceSeconds(30)
            assertTrue(w.claim(w.m3, null).ok())
            assertEquals(
                t0.plusSeconds(35),
                w.stamp(w.seatById(assertNotNull(a.assignmentId))["last_seen_at"]),
                "a token-less call touches nothing"
            )
        }

    @Test
    fun `D9 a write under the coordinator token touches the coordinator row and the run's coordinator_seen_at`() =
        world { w ->
            val run = w.open()
            clock.advanceSeconds(40)
            val now = clock.now()
            assertTrue(w.assign(run, w.m1, "builder").reply.ok())

            val coordinator = w.seatsOfRun(run.runId).single { it["seat"] == "coordinator" }
            assertEquals(now, w.stamp(coordinator["last_seen_at"]), "the coordinator row is touched")
            assertEquals(now, w.stamp(runRow(w, run)["coordinator_seen_at"]), "and so is the run's sign of life")
        }

    // ------------------------------------------------------------------ S4 attribution

    @Test
    fun `S4 a call under a seat token stamps the run and seat on its events and its call_log row, a token-less call stamps none`() =
        world { w ->
            val run = w.open()
            val runUuid = UUID.fromString(run.runId)
            val a = w.assignOk(run, w.m1, "builder")

            val mark = w.rig.maxSeq()
            val claim = w.claim(w.m1, a.token)
            assertTrue(claim.ok(), claim.toString())
            val seatRows = w.rig.rowsAfter(mark)
            assertTrue(seatRows.isNotEmpty(), "fixture: the claim wrote events")
            for (row in seatRows) {
                assertEquals(runUuid, row.runId, "${row.type}: run")
                assertEquals("builder", row.seat, "${row.type}: seat")
            }
            val seatLog = w.callLogRowOf(claim)
            assertEquals(runUuid, w.uuidOf(seatLog["run_id"]))
            assertEquals("builder", seatLog["seat"])

            // the same shape of call with no token carries neither
            val mark2 = w.rig.maxSeq()
            val plain = w.claim(w.m3, null, by = w.other)
            assertTrue(plain.ok(), plain.toString())
            val plainRows = w.rig.rowsAfter(mark2)
            assertTrue(plainRows.isNotEmpty(), "fixture: the token-less claim wrote events")
            assertTrue(plainRows.all { it.runId == null && it.seat == null }, "no token, no stamp: $plainRows")
            val plainLog = w.callLogRowOf(plain)
            assertNull(plainLog["run_id"])
            assertNull(plainLog["seat"])
        }

    @Test
    fun `S4 a write under the coordinator token is stamped with the run and the coordinator pseudo-seat`() =
        world { w ->
            val run = w.open()
            val mark = w.rig.maxSeq()
            val assigned = w.assign(run, w.m1, "builder")
            assertTrue(assigned.reply.ok(), assigned.reply.toString())

            val rows = w.rig.rowsAfter(mark).filter { it.type == "seat.assigned" }
            assertEquals(1, rows.size)
            assertEquals(UUID.fromString(run.runId), rows.single().runId)
            assertEquals("coordinator", rows.single().seat)
            assertEquals("coordinator", w.callLogRowOf(assigned.reply)["seat"])
        }

    // ------------------------------------------------------------------ S5 and S21 the holder pair

    @Test
    fun `S5 two seats of one principal each hold a claim - the second seat's claim does not evict the first`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val b = w.assignOk(run, w.m2, "implementer")
            assertTrue(w.claim(w.m1, a.token).ok())
            val mark = w.rig.maxSeq()

            assertTrue(w.claim(w.m2, b.token).ok())

            assertEquals(w.orchId, w.itemRow(w.m1)["claimed_by"], "X is still claimed")
            assertEquals(a.assignmentId, w.uuidOf(w.itemRow(w.m1)["claim_assignment_id"]).toString(), "by seat A's pair")
            assertEquals(b.assignmentId, w.uuidOf(w.itemRow(w.m2)["claim_assignment_id"]).toString(), "Y is held by seat B's pair")
            assertTrue(
                w.rig.rowsAfter(mark).none { it.type == "claim.released" },
                "no claim was released: ${w.rig.rowsAfter(mark).map { it.type }}"
            )
        }

    @Test
    fun `S5 control - one token-less principal claiming a second item still supersedes its first claim`() =
        world { w ->
            assertTrue(w.claim(w.m1, null).ok())
            val mark = w.rig.maxSeq()
            assertTrue(w.claim(w.m2, null).ok())
            assertNull(w.itemRow(w.m1)["claimed_by"], "the pre-seat behaviour is unchanged for a principal-level holder")
            assertEquals(1, w.rig.rowsAfter(mark).count { it.type == "claim.released" })
        }

    @Test
    fun `S21 heartbeat and release belong to the exact holder - the same principal with no token or another seat is not_claim_holder`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val b = w.assignOk(run, w.m1, "checker")
            assertTrue(w.claim(w.m1, a.token).ok())

            for ((label, token) in listOf("no token" to null, "another seat of the principal" to b.token)) {
                assertEquals("not_claim_holder", w.heartbeat(w.m1, token).code(), "heartbeat, $label")
                assertEquals("not_claim_holder", w.release(w.m1, token).code(), "release, $label")
            }
            assertEquals(a.assignmentId, w.uuidOf(w.itemRow(w.m1)["claim_assignment_id"]).toString(), "the claim is untouched")

            val beat = w.heartbeat(w.m1, a.token)
            assertTrue(beat.ok() && beat.find("refreshed") == "true", "control: the exact holder refreshes: $beat")
            val released = w.release(w.m1, a.token)
            assertTrue(released.ok() && released.find("released") == "true", "control: the exact holder releases: $released")
            assertNull(w.itemRow(w.m1)["claimed_by"])
            assertNull(w.itemRow(w.m1)["claim_assignment_id"])
        }

    @Test
    fun `S21 a principal-level claim is not held by a seat of that principal`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m3, null).ok())

            assertEquals("not_claim_holder", w.heartbeat(w.m3, a.token).code(), "a seat does not hold the principal's own claim")
            assertEquals("not_claim_holder", w.release(w.m3, a.token).code())
            assertTrue(w.heartbeat(w.m3, null).ok(), "control: the principal itself does")
            assertNull(w.itemRow(w.m3)["claim_assignment_id"])
        }

    // ------------------------------------------------------------------ S6 notes owned by (principal, seat)

    @Test
    fun `S6 a note written under a seat token is owned by the principal and that seat, and only that exact pair rewrites it`() =
        world { w ->
            val run = w.open()
            val builder = w.assignOk(run, w.m1, "builder")
            val checker = w.assignOk(run, w.m1, "checker")

            assertTrue(w.noteUpsert(w.m1, "scratch", builder.token, body = "first").ok())
            val created = assertNotNull(w.noteRow(w.m1, "scratch"))
            assertEquals(w.orchId, created["owner_id"])
            assertEquals("builder", created["seat"], "attribution (R21 ruling 8; D11 is superseded)")
            assertEquals("builder", created["owner_seat"], "the owner seat is the creator's seat")
            assertEquals(1L, created["version"])

            assertTrue(w.noteUpsert(w.m1, "scratch", builder.token, body = "second").ok(), "the same pair rewrites")
            assertEquals(2L, assertNotNull(w.noteRow(w.m1, "scratch"))["version"])
            assertEquals("builder", assertNotNull(w.noteRow(w.m1, "scratch"))["seat"], "the owner's own rewrite is attributed to its seat")
            assertEquals("builder", assertNotNull(w.noteRow(w.m1, "scratch"))["owner_seat"], "the owner seat is kept")

            val otherSeat = w.noteUpsert(w.m1, "scratch", checker.token, body = "intruder")
            assertEquals("note_owned_by_other", otherSeat.code(), otherSeat.toString())
            assertEquals("builder", otherSeat.find("ownerSeat", otherSeat.error()), otherSeat.toString())
            val noSeat = w.noteUpsert(w.m1, "scratch", null, body = "intruder")
            assertEquals("note_owned_by_other", noSeat.code(), "the same principal without a seat is a different pair: $noSeat")
            assertEquals("second", assertNotNull(w.noteRow(w.m1, "scratch"))["body"], "no refused write changed the note")
        }

    @Test
    fun `S6 a token-less note and a seat note of one principal are distinct pairs in both directions`() =
        world { w ->
            val run = w.open()
            val builder = w.assignOk(run, w.m1, "builder")

            assertTrue(w.noteUpsert(w.m1, "plain-note", null, body = "mine").ok())
            val plain = assertNotNull(w.noteRow(w.m1, "plain-note"))
            assertEquals(w.orchId, plain["owner_id"])
            assertNull(plain["seat"], "a token-less note has no attribution seat")
            assertNull(plain["owner_seat"], "a token-less note has no owner seat")
            val seated = w.noteUpsert(w.m1, "plain-note", builder.token, body = "seated")
            assertEquals("note_owned_by_other", seated.code(), seated.toString())
            assertTrue(w.noteUpsert(w.m1, "plain-note", null, body = "again").ok(), "control: the token-less pair rewrites its own note")
            assertEquals(2L, assertNotNull(w.noteRow(w.m1, "plain-note"))["version"])
        }

    // ------------------------------------------------------------------ W1-W4 the two-column note model (R21 ruling 8)
    //
    // Oracle: the orchestrator-decisions note of 95a22e6e (R21 decision Ruling 8; task-scope D11 is SUPERSEDED): `notes.seat` is
    // ATTRIBUTION, written on every successful write from the caller's token seat and NULL without a token; `notes.owner_seat` is
    // written only together with `owner_id` (create: the caller's seat; adoption of an unowned note: the caller's seat; admin
    // takeover: NULL; every other write: unchanged); rule 3 compares (owner_id, owner_seat) with (principal, callerSeat) and
    // `note_owned_by_other.ownerSeat` reports the OWNER seat.

    @Test
    fun `W1 a shared-key write by another seat rewrites the attribution seat and keeps the owner pair`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val b = w.assignOk(run, w.m1, "checker")

            assertTrue(w.noteUpsert(w.m1, "shared-notes", a.token, body = "by builder").ok())
            val created = assertNotNull(w.noteRow(w.m1, "shared-notes"))
            assertEquals(w.orchId, created["owner_id"])
            assertEquals("builder", created["seat"], "create: attribution is the creator's seat")
            assertEquals("builder", created["owner_seat"], "create: the owner seat is the creator's seat")

            val bySeatB = w.noteUpsert(w.m1, "shared-notes", b.token, body = "by checker")
            assertTrue(bySeatB.ok(), "a shared key accepts the sibling seat: $bySeatB")
            val after = assertNotNull(w.noteRow(w.m1, "shared-notes"))
            assertEquals(2L, after["version"])
            assertEquals("checker", after["seat"], "attribution follows the latest writer's seat")
            assertEquals("builder", after["owner_seat"], "the owner seat is unchanged by another seat's write")
            assertEquals(w.orchId, after["owner_id"], "the owner is unchanged")

            val noToken = w.noteUpsert(w.m1, "shared-notes", null, body = "no token")
            assertTrue(noToken.ok(), "a shared key accepts a token-less write: $noToken")
            val last = assertNotNull(w.noteRow(w.m1, "shared-notes"))
            assertNull(last["seat"], "a token-less write attributes no seat")
            assertEquals("builder", last["owner_seat"], "a token-less write does not touch the owner seat")
            assertEquals(w.orchId, last["owner_id"])
        }

    @Test
    fun `W2 the same principal under another seat on a non-shared key is note_owned_by_other and reports the owner seat`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val b = w.assignOk(run, w.m1, "checker")
            assertTrue(w.noteUpsert(w.m1, "scratch", a.token, body = "first").ok())

            val refused = w.noteUpsert(w.m1, "scratch", b.token, body = "intruder")
            assertEquals("note_owned_by_other", refused.code(), refused.toString())
            assertEquals("builder", refused.find("ownerSeat", refused.error()), "the owner seat is reported: $refused")
            val row = assertNotNull(w.noteRow(w.m1, "scratch"))
            assertEquals(1L, row["version"], "the refused write changed nothing")
            assertEquals("builder", row["owner_seat"])
            assertEquals("builder", row["seat"])
            assertEquals("first", row["body"])
        }

    @Test
    fun `W2 rule 3 and the refusal read the owner seat, not the attribution seat`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val b = w.assignOk(run, w.m1, "checker")
            assertTrue(w.noteUpsert(w.m1, "scratch", a.token, body = "first").ok())
            // Fixture: the attribution seat diverges from the owner seat (it can, after a shared-key write by another seat).
            w.exec("UPDATE notes SET seat = 'checker' WHERE work_item_id = ? AND key = 'scratch'", w.m1.id)
            val diverged = assertNotNull(w.noteRow(w.m1, "scratch"))
            assertEquals("checker", diverged["seat"])
            assertEquals("builder", diverged["owner_seat"])

            val bySeatB = w.noteUpsert(w.m1, "scratch", b.token, body = "intruder")
            assertEquals("note_owned_by_other", bySeatB.code(), "seat checker equals the ATTRIBUTION seat but not the owner seat: $bySeatB")
            assertEquals("builder", bySeatB.find("ownerSeat", bySeatB.error()), "ownerSeat is the owner seat, not checker: $bySeatB")

            val bySeatA = w.noteUpsert(w.m1, "scratch", a.token, body = "owner again")
            assertTrue(bySeatA.ok(), "the owner pair (principal, builder) rewrites although the attribution seat reads checker: $bySeatA")
            val row = assertNotNull(w.noteRow(w.m1, "scratch"))
            assertEquals("builder", row["seat"], "the owner's write is attributed to its own seat again")
            assertEquals("builder", row["owner_seat"])
            assertEquals(2L, row["version"])
        }

    @Test
    fun `W3 a legacy note adopted under a seat token records the caller as owner and the caller seat as owner seat`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            w.legacyNote(w.m1, "legacy-one")
            w.legacyNote(w.m1, "legacy-two")
            for (key in listOf("legacy-one", "legacy-two")) {
                val before = assertNotNull(w.noteRow(w.m1, key))
                assertNull(before["owner_id"], "fixture: $key is unowned")
                assertNull(before["owner_seat"], "fixture: $key has no owner seat")
                assertNull(before["seat"], "fixture: $key has no attribution seat")
            }

            assertTrue(w.noteUpsert(w.m1, "legacy-one", a.token, body = "adopted under a seat").ok())
            val adopted = assertNotNull(w.noteRow(w.m1, "legacy-one"))
            assertEquals(w.orchId, adopted["owner_id"])
            assertEquals("builder", adopted["owner_seat"], "adoption: the owner seat is the caller's seat")
            assertEquals("builder", adopted["seat"], "adoption: attribution is the caller's seat")

            assertTrue(w.noteUpsert(w.m1, "legacy-two", null, body = "adopted without a token").ok())
            val bare = assertNotNull(w.noteRow(w.m1, "legacy-two"))
            assertEquals(w.orchId, bare["owner_id"], "control: a token-less adoption still records the owner")
            assertNull(bare["owner_seat"], "control: a token-less adoption has no owner seat")
            assertNull(bare["seat"], "control: a token-less adoption has no attribution seat")
        }

    @Test
    fun `W4 a token-less write by the owner principal to a note owned by the principal and a seat is refused`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            assertTrue(w.noteUpsert(w.m1, "scratch", a.token, body = "mine").ok())

            val refused = w.noteUpsert(w.m1, "scratch", null, body = "no token")
            assertEquals("note_owned_by_other", refused.code(), "(principal, no seat) differs from (principal, builder): $refused")
            assertEquals("builder", refused.find("ownerSeat", refused.error()), refused.toString())
            val row = assertNotNull(w.noteRow(w.m1, "scratch"))
            assertEquals("mine", row["body"])
            assertEquals(1L, row["version"])
            assertEquals("builder", row["owner_seat"])
            assertEquals("builder", row["seat"])

            assertTrue(w.noteUpsert(w.m1, "scratch", a.token, body = "mine again").ok(), "control: the exact pair rewrites")
            assertEquals(2L, assertNotNull(w.noteRow(w.m1, "scratch"))["version"])
        }

    @Test
    fun `W3 an admin takeover writes no owner seat and the taken note keeps no seat of its previous owner`() =
        world { w ->
            val run = w.open(by = w.admin)
            val adminSeat = w.assignOk(run, w.m1, "builder", by = w.admin)
            assertTrue(w.noteUpsert(w.m1, "scratch", adminSeat.token, body = "owned under a seat", by = w.admin).ok())
            val owned = assertNotNull(w.noteRow(w.m1, "scratch"))
            assertEquals("api:seat-admin", owned["owner_id"])
            assertEquals("builder", owned["owner_seat"], "fixture: the note is owned by (admin, builder)")

            val taken = w.noteUpsert(w.m1, "scratch", null, body = "taken over", extra = ""","takeover":true""", by = w.admin)
            assertTrue(taken.ok(), "a token-less ADMIN takeover succeeds: $taken")
            val row = assertNotNull(w.noteRow(w.m1, "scratch"))
            assertEquals("api:seat-admin", row["owner_id"], "the takeover writes the caller as owner")
            assertNull(row["owner_seat"], "a takeover is never under a token: the owner seat is NULL")
            assertNull(row["seat"], "a token-less write attributes no seat")
        }

    @Test
    fun `S20 an ADMIN principal loses the takeover capability under a seat token and keeps it without one`() =
        world { w ->
            val run = w.open(by = w.admin)
            val adminSeat = w.assignOk(run, w.m1, "builder", by = w.admin)
            assertTrue(w.noteUpsert(w.m1, "owned-by-orch", null, body = "orch").ok(), "fixture: a note owned by another principal")

            val underToken =
                w.noteUpsert(
                    w.m1,
                    "owned-by-orch",
                    adminSeat.token,
                    body = "taken",
                    extra = ""","takeover":true""",
                    by = w.admin
                )
            assertEquals("forbidden", underToken.code(), underToken.toString())
            assertEquals("capability", underToken.find("scope", underToken.error()), underToken.toString())
            assertEquals("admin", underToken.find("required", underToken.error()), underToken.toString())
            assertEquals(w.orchId, assertNotNull(w.noteRow(w.m1, "owned-by-orch"))["owner_id"], "the note was not taken over")

            val plain = w.noteUpsert(w.m1, "owned-by-orch", null, body = "taken", extra = ""","takeover":true""", by = w.admin)
            assertTrue(plain.ok(), "control: the same ADMIN call without a token takes it over: $plain")
            assertEquals("api:seat-admin", assertNotNull(w.noteRow(w.m1, "owned-by-orch"))["owner_id"])
        }

    // ------------------------------------------------------------------ S11 idempotency per assignment

    @Test
    fun `S11 an idempotency key is namespaced per assignment - the same key replays for the same seat and runs fresh for a sibling seat`() =
        world { w ->
            val run = w.open()
            val a = w.assignOk(run, w.m1, "builder")
            val b = w.assignOk(run, w.m1, "checker")
            val key = ""","idempotencyKey":"note-key-1""""

            val first = w.noteUpsert(w.m1, "keyed", a.token, body = "same", extra = key)
            assertTrue(first.ok(), first.toString())
            val replay = w.noteUpsert(w.m1, "keyed", a.token, body = "same", extra = key)
            assertTrue(replay.ok(), replay.toString())
            assertEquals("true", replay.find("replayed"), "the same seat replays the stored result: $replay")
            assertEquals(1L, assertNotNull(w.noteRow(w.m1, "keyed"))["version"], "one effect")

            val sibling = w.noteUpsert(w.m1, "keyed", b.token, body = "same", extra = key)
            assertNull(sibling.find("replayed"), "a sibling seat of the same principal does not see seat A's record: $sibling")
            assertEquals("note_owned_by_other", sibling.code(), "it runs fresh and meets the ownership rule instead: $sibling")

            val ids = w.rows("SELECT principal_id FROM idempotency_records").map { it["principal_id"] as String }
            assertTrue("${w.orchId}#${a.assignmentId}" in ids, "the record is under the composed principal: $ids")
            assertFalse(w.orchId in ids, "no record is under the bare principal: $ids")
        }
}

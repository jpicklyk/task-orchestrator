package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The fixed instant both legs' clocks report (the plan's "fixed ids + clock"). */
internal val PARITY_NOW: Instant = Instant.parse("2026-10-10T12:00:00Z")

internal val PARITY_CLOCK: Clock = Clock { PARITY_NOW }

/** Seeds work items with caller-chosen ids through the undecorated repository (no event rows, same on both legs). */
class ParitySeeder(
    private val repo: WorkItemRepository,
    private val deps: DependencyStore? = null
) {
    /**
     * Seeds a dependency with a caller-chosen id through the undecorated store (no event rows, same on both legs). The
     * `createdAt` is the fixed [PARITY_NOW], so list order falls to the id tiebreak. The domain invariants (distinct ends,
     * no threshold on RELATES_TO) are enforced by [Dependency] itself.
     */
    suspend fun dependency(
        id: UUID,
        from: UUID,
        to: UUID,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ): Dependency {
        val store = checkNotNull(deps) { "this seeder was built without a dependency store" }
        return store.create(
            Dependency(id = id, fromItemId = from, toItemId = to, type = type, unblockAt = unblockAt, createdAt = PARITY_NOW)
        )
    }

    /** Creates an item; a child's depth and root are derived from its parent so the domain invariants hold by construction. */
    suspend fun item(
        id: UUID,
        title: String,
        parent: UUID? = null,
        tags: String? = null
    ): WorkItem {
        val p = parent?.let { checkNotNull(repo.getById(it)) { "seed parent $it does not exist" } }
        return checkNotNull(
            repo.create(
                WorkItem(
                    id = id,
                    title = title,
                    parentId = parent,
                    depth = (p?.depth ?: -1) + 1,
                    rootId = p?.let { it.rootId ?: it.id },
                    tags = tags
                )
            )
        )
    }
}

/**
 * The production REST topology (real [EventLogRig] composition, ContentNegotiation first, then [installRestApiRoutes]) with
 * [registry] mounted behind `POST /api/v1/ops/{tool}`. Shared by the harness and the route tests so both exercise one wiring.
 */
internal fun Application.installParityRestApp(
    rig: EventLogRig,
    authConfig: ApiAuthConfig.Bearer,
    registry: ToolRegistry,
    callLog: CallLogSink = CallLogSink.NONE
) {
    install(ContentNegotiation) { json(McpJson) }
    installRestApiRoutes(
        apiConfig = authConfig,
        eventBus = null,
        effectiveProvider = rig.composition.toolContext.repositoryProvider,
        apiTokenEntries = authConfig.tokens.mapValues { (_, principal) -> BearerTokenStore.TokenEntry(principal, expiresAt = null) },
        allowQueryToken = false,
        serverName = "parity-harness",
        serverVersion = "test",
        actorAuthEnabled = rig.composition.actorAuthEnabled,
        noteSchemaService = rig.composition.noteSchemaService,
        toolContext = rig.composition.toolContext,
        degradedModePolicy = rig.composition.degradedModePolicy,
        callLog = callLog,
        toolRegistry = registry,
        toolDispatcher = buildToolDispatcher(rig.composition.toolContext)
    )
}

/** An [ApiAuthConfig.Bearer] holding exactly [token]. */
internal fun parityAuthConfig(token: ParityToken): ApiAuthConfig.Bearer =
    ApiAuthConfig.Bearer(tokens = mapOf(HashBytes(sha256(token.raw)) to token.principal()))

/** The [CallAccess] an MCP leg runs under so it carries the REST leg's identity (principal, scope, capabilities). */
internal fun parityAccess(token: ParityToken): CallAccess =
    // The same builder the REST route uses, with the default redaction (API_REDACT_NOTE_ATTRIBUTION on): both legs
    // then agree on whether note attribution is visible.
    token.principal().toCallAccess(redactNoteAttribution = true)

internal class McpResult(
    val isError: Boolean,
    val structured: JsonObject,
    val reqIdMeta: String?,
    val text: String
)

/**
 * One MCP tool call through a real SDK client/server pair over an in-memory transport. [access] null leaves the adapter's
 * default seam (unrestricted, actor lifted from the arguments).
 */
internal suspend fun callMcp(
    toolContext: ToolExecutionContext,
    registry: ToolRegistry,
    access: ((String?) -> CallAccess)?,
    tool: String,
    args: JsonObject
): McpResult {
    val dispatcher = buildToolDispatcher(toolContext)
    val adapter = if (access == null) McpOperationAdapter(dispatcher) else McpOperationAdapter(dispatcher, access = access)
    val server = Server(serverInfo = Implementation(name = "parity-server", version = "1.0.0"), options = inMemoryTestServerOptions())
    adapter.register(server, registry, ToolProfile.ORCHESTRATION)
    val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
    val client =
        Client(
            clientInfo = Implementation(name = "parity-client", version = "1.0.0"),
            options = ClientOptions(capabilities = ClientCapabilities())
        )
    server.createSession(serverTransport)
    client.connect(clientTransport)
    try {
        val result =
            client.callTool(
                buildCallToolRequest {
                    name = tool
                    arguments(args)
                }
            )
        return McpResult(
            isError = result.isError == true,
            structured = assertNotNull(result.structuredContent, "MCP result carries structuredContent"),
            reqIdMeta =
                result.meta
                    ?.get("reqId")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            text =
                result.content
                    .filterIsInstance<TextContent>()
                    .single()
                    .text
        )
    } finally {
        closeInMemoryPair(client, server)
    }
}

/** What a harness run observed (the assertions already passed when this is returned). */
class ParityOutcome(
    val restStatus: Int,
    /** The REST body with `meta.reqId` replaced; equal to the MCP body byte for byte. */
    val body: String,
    val snapshot: Map<String, List<Map<String, Any?>>>,
    val dbChanged: Boolean
)

/**
 * The parity harness (plan 3.2, P2): runs one [ParityScenario] over two identically seeded SQLite databases, one reached
 * through MCP (real SDK client/server, `McpOperationAdapter` with the access seam set to the scenario's token) and one through
 * `POST /api/v1/ops/{tool}` (real `installRestApiRoutes` wiring, bearer auth), and asserts:
 *  - the two bodies are byte-equal once `meta.reqId` is replaced, and each leg's reqId channel (X-Req-Id / _meta.reqId)
 *    equals its body's `meta.reqId`;
 *  - the REST status equals the scenario's literal oracle and, for an error body, the status of its error code;
 *  - the MCP `isError` flag equals `!ok`;
 *  - the work_items / notes / dependencies / events snapshots of the two databases are equal, ignoring ids that are generated
 *    and every timestamp.
 */
class ParityHarness(
    private val registry: ToolRegistry
) {
    private class Leg(
        val db: SqliteTestDatabase,
        val dir: Path,
        val rig: EventLogRig
    ) : AutoCloseable {
        override fun close() {
            runCatching { db.close() }
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private fun openLeg(seed: suspend ParitySeeder.() -> Unit): Leg {
        val db = SqliteTestDatabase.open(clock = PARITY_CLOCK)
        val dir = Files.createTempDirectory("parity-leg-")
        try {
            val rig = EventLogRig.build(db, dir, clock = PARITY_CLOCK)
            runBlocking {
                val provider = db.repositoryProvider()
                ParitySeeder(provider.workItemRepository(), provider.dependencyRepository()).seed()
            }
            return Leg(db, dir, rig)
        } catch (e: Throwable) {
            runCatching { db.close() }
            runCatching { dir.toFile().deleteRecursively() }
            throw e
        }
    }

    private class RestResult(
        val status: Int,
        val body: String,
        val reqIdHeader: String?
    )

    private fun runRest(
        leg: Leg,
        s: ParityScenario
    ): RestResult {
        var result: RestResult? = null
        testApplication {
            application { installParityRestApp(leg.rig, parityAuthConfig(s.token), registry) }
            val response =
                client.post("/api/v1/ops/${s.tool}") {
                    header(HttpHeaders.Authorization, "Bearer ${s.token.raw}")
                    contentType(ContentType.Application.Json)
                    setBody(s.arguments)
                }
            result = RestResult(response.status.value, response.bodyAsText(), response.headers["X-Req-Id"])
        }
        return checkNotNull(result) { "REST leg produced no response" }
    }

    fun run(s: ParityScenario): ParityOutcome {
        val restLeg = openLeg(s.seed)
        val mcpLeg =
            try {
                openLeg(s.seed)
            } catch (e: Throwable) {
                restLeg.close()
                throw e
            }
        try {
            val rawBefore = rawTables(restLeg.db.jdbcUrl)
            val before = project(rawBefore)
            assertEquals(before, snapshot(mcpLeg.db.jdbcUrl), "${s.name}: the two legs must start from identical seeded databases")
            val known = knownIds(rawBefore, s.arguments)

            val rest = runRest(restLeg, s)
            val mcp =
                runBlocking {
                    callMcp(
                        mcpLeg.rig.composition.toolContext,
                        registry,
                        { parityAccess(s.token) },
                        s.tool,
                        Json.parseToJsonElement(s.arguments).jsonObject
                    )
                }

            val restBody = Json.parseToJsonElement(rest.body).jsonObject
            val restReqId =
                assertNotNull(
                    restBody["meta"]
                        ?.jsonObject
                        ?.get("reqId")
                        ?.jsonPrimitive
                        ?.content,
                    "${s.name}: REST meta.reqId"
                )
            assertEquals(restReqId, rest.reqIdHeader, "${s.name}: X-Req-Id equals the body's meta.reqId")
            val mcpReqId =
                assertNotNull(
                    mcp.structured["meta"]
                        ?.jsonObject
                        ?.get("reqId")
                        ?.jsonPrimitive
                        ?.content,
                    "${s.name}: MCP meta.reqId"
                )
            assertEquals(mcpReqId, mcp.reqIdMeta, "${s.name}: _meta.reqId equals the body's meta.reqId")
            assertNotEquals(restReqId, mcpReqId, "${s.name}: each call gets its own reqId")

            val restRawAfter = rawTables(restLeg.db.jdbcUrl)
            val mcpRawAfter = rawTables(mcpLeg.db.jdbcUrl)
            val (restNorm, restAfter) = tokenizeGeneratedIds(restRawAfter, normalize(rest.body, restReqId), known)
            val (mcpNorm, mcpAfter) = tokenizeGeneratedIds(mcpRawAfter, normalize(mcp.structured.toString(), mcpReqId), known)
            assertEquals(mcpNorm, restNorm, "${s.name}: body parity between MCP and REST")

            val ok = restBody["ok"]?.jsonPrimitive?.boolean ?: error("${s.name}: body has no ok flag")
            assertEquals(!ok, mcp.isError, "${s.name}: MCP isError is the negation of ok")
            val code =
                restBody["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content
            assertEquals(s.expectCode, code, "${s.name}: error code")
            assertEquals(s.expectStatus, rest.status, "${s.name}: REST status")
            if (code != null) {
                assertEquals(
                    ErrorCode.entries.single { it.wire == code }.httpStatus,
                    rest.status,
                    "${s.name}: REST status equals the error code's catalog status"
                )
            } else {
                assertEquals(200, rest.status, "${s.name}: an ok body is 200")
            }
            for (needle in s.expectContains) assertTrue(needle in restNorm, "${s.name}: body must contain $needle but was $restNorm")
            for (needle in s.expectAbsent) assertFalse(needle in restNorm, "${s.name}: body must not contain $needle but was $restNorm")

            for (table in restAfter.keys) {
                assertEquals(restAfter.getValue(table), mcpAfter.getValue(table), "${s.name}: $table snapshot parity between REST and MCP")
            }
            val changed = project(restRawAfter) != before
            s.expectDbChanged?.let { assertEquals(it, changed, "${s.name}: database changed") }
            return ParityOutcome(rest.status, restNorm, restAfter, changed)
        } finally {
            restLeg.close()
            mcpLeg.close()
        }
    }

    private fun normalize(
        body: String,
        reqId: String
    ): String {
        val marker = "\"reqId\":\"$reqId\""
        assertTrue(marker in body, "body carries its reqId marker: $body")
        // A stamp on the fixed clock is compared exactly; a stamp some store takes from the wall clock instead (note rows are
        // stamped by the repository, not the injected clock) differs between the legs by milliseconds, so it must still be an
        // ISO-8601 instant and is masked.
        val fixed = PARITY_NOW.toString()
        return body.replace(marker, "\"reqId\":\"<REQ>\"").replace(TIMESTAMP) { match ->
            val value = match.groupValues[2]
            if (value == fixed) {
                match.value
            } else {
                Instant.parse(value)
                "\"${match.groupValues[1]}\":\"<WALL>\""
            }
        }
    }

    companion object {
        private val TABLES = listOf("work_items", "notes", "dependencies", "events", "documents", "document_versions", "links")

        private val TIMESTAMP = Regex("\"(createdAt|modifiedAt)\":\"([^\"]*)\"")

        /**
         * The comparable content of every table: generated ids (all `id` columns except work_items, whose ids are seeded),
         * `seq`, request/session correlation ids, an event's transport `host` and every timestamp column are dropped; rows are
         * sorted (events by `seq`). `host` records the caller's transport identity, which differs between the legs by design
         * (Q7: MCP records `clientInfo.name`, REST the `X-Client-Host` header or user agent).
         */
        fun snapshot(jdbcUrl: String): Map<String, List<Map<String, Any?>>> = project(rawTables(jdbcUrl))

        /** Every column of every table, no column dropped; events are ordered by `seq`, other tables are unordered. */
        internal fun rawTables(jdbcUrl: String): Map<String, List<Map<String, Any?>>> =
            DriverManager.getConnection(jdbcUrl).use { connection ->
                TABLES.associateWith { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT * FROM $table").use { rows ->
                            val meta = rows.metaData
                            val raw =
                                buildList {
                                    while (rows.next()) {
                                        add((1..meta.columnCount).associate { meta.getColumnLabel(it) to cell(rows.getObject(it)) })
                                    }
                                }
                            if (table == "events") raw.sortedBy { it["seq"] as Long } else raw
                        }
                    }
                }
            }

        /**
         * [raw] without the columns that legitimately differ between two runs of the same call: `seq`, request/session
         * correlation ids, every timestamp (but not the `unblock_at` threshold, which is data), the transport `host` of an event, and generated `id` columns (all but work_items).
         * Row order is kept.
         */
        private fun withoutNoise(raw: Map<String, List<Map<String, Any?>>>): Map<String, List<Map<String, Any?>>> =
            raw.mapValues { (table, rows) ->
                rows.map { row ->
                    row.filterKeys { label ->
                        !(
                            label == "seq" ||
                                label == "req_id" ||
                                label == "session_id" ||
                                (label == "host" && table == "events") ||
                                label == "at" ||
                                (label.endsWith("_at") && label != "unblock_at")
                        ) &&
                            !(label == "id" && table != "work_items")
                    }
                }
            }

        private fun sorted(tables: Map<String, List<Map<String, Any?>>>): Map<String, List<Map<String, Any?>>> =
            tables.mapValues { (table, rows) -> if (table == "events") rows else rows.sortedBy { it.toString() } }

        /** The [snapshot] projection of [rawTables] output: noise columns dropped, non-event rows sorted. */
        internal fun project(raw: Map<String, List<Map<String, Any?>>>): Map<String, List<Map<String, Any?>>> = sorted(withoutNoise(raw))

        private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val HEX_BLOB = Regex("[0-9a-f]{32}")

        private fun dashed(hex: String): String =
            "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"

        /** The lowercase dashed UUIDs a cell carries: a dashed UUID inside text, or a whole-cell 32-hex blob. */
        private fun uuidsIn(cell: Any?): List<String> =
            when {
                cell !is String -> emptyList()
                HEX_BLOB.matches(cell) -> listOf(dashed(cell))
                else -> UUID_TEXT.findAll(cell).map { it.value.lowercase() }.toList()
            }

        /**
         * The ids that exist before the call (every UUID in the seeded database) or that the caller named in [arguments]. Any
         * other UUID seen afterwards was generated by the call and differs between the two legs.
         */
        private fun knownIds(
            rawBefore: Map<String, List<Map<String, Any?>>>,
            arguments: String
        ): Set<String> =
            buildSet {
                for (rows in rawBefore.values) for (row in rows) for (cell in row.values) addAll(uuidsIn(cell))
                addAll(UUID_TEXT.findAll(arguments).map { it.value.lowercase() })
            }

        /**
         * Replaces every generated id (a UUID not in [known]) by a positional token `<IDn>`, numbered by first appearance: the
         * events table by `seq` first, then [body]. A generated id seen only in another table becomes `<GEN>`. Applied to the
         * body, to dashed UUIDs inside text and JSON cells, and to whole-cell 32-hex blobs, so the two legs compare equal exactly
         * when the generated ids play the same roles. Returns the tokenized body and the projected (see [project]) tables.
         */
        private fun tokenizeGeneratedIds(
            rawWithNoise: Map<String, List<Map<String, Any?>>>,
            body: String,
            known: Set<String>
        ): Pair<String, Map<String, List<Map<String, Any?>>>> {
            // noise columns carry per-call generated ids (request and session ids): they must not take token numbers
            val raw = withoutNoise(rawWithNoise)
            val tokens = LinkedHashMap<String, String>()

            fun note(uuid: String) {
                if (uuid !in known && uuid !in tokens) tokens[uuid] = "<ID${tokens.size + 1}>"
            }
            for (row in raw.getValue("events")) for (cell in row.values) uuidsIn(cell).forEach(::note)
            UUID_TEXT.findAll(body).forEach { note(it.value.lowercase()) }

            fun swap(uuid: String): String {
                val lower = uuid.lowercase()
                return if (lower in known) uuid else tokens[lower] ?: "<GEN>"
            }

            fun apply(cell: Any?): Any? =
                when {
                    cell !is String -> cell
                    HEX_BLOB.matches(cell) -> if (dashed(cell) in known) cell else swap(dashed(cell))
                    else -> UUID_TEXT.replace(cell) { swap(it.value) }
                }
            val tokenizedBody = UUID_TEXT.replace(body) { swap(it.value) }
            val tables = raw.mapValues { (_, rows) -> rows.map { row -> row.mapValues { apply(it.value) } } }
            return tokenizedBody to sorted(tables)
        }

        private fun cell(value: Any?): Any? =
            when (value) {
                is Int -> value.toLong()
                is ByteArray -> value.joinToString("") { "%02x".format(it) }
                else -> value
            }
    }
}

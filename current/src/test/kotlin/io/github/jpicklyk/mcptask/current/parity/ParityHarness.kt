package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.support.sha256Hex
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswer
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.RunPauseReason
import io.github.jpicklyk.mcptask.current.domain.model.RunSeat
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatOutcome
import io.github.jpicklyk.mcptask.current.domain.model.RunSeatStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.test.EnvelopeConformance
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.liftTestActor
import io.github.jpicklyk.mcptask.current.test.pushConfig
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

/**
 * The KNOWN token of the coordinator pseudo-seat [ParitySeeder.run] seeds for a run opened by someone (R22 D7: `open` creates it, so
 * a run has one): the run's 32 hex digits and `_coordtoken`, which is 43 characters of the base64url alphabet and unique per run.
 * A scenario presents it as the top-level `seatToken`, as the opener principal.
 */
internal fun parityCoordinatorToken(run: UUID): String = run.toString().replace("-", "") + "_coordtoken"

/** Seeds work items with caller-chosen ids through the undecorated repository (no event rows, same on both legs). */
class ParitySeeder(
    private val repo: WorkItemRepository,
    private val deps: DependencyStore? = null,
    private val provider: RepositoryProvider? = null,
    private val unitOfWork: UnitOfWork? = null
) {
    /**
     * Appends [yaml] as the next config version of [rootId] through the undecorated store inside a write unit (no event rows, the
     * same on both legs; the version is stamped by the fixed parity clock).
     */
    suspend fun config(
        rootId: UUID,
        yaml: String
    ) {
        pushConfig(
            checkNotNull(provider) { "this seeder was built without a repository provider" },
            checkNotNull(unitOfWork) { "this seeder was built without a unit of work" },
            rootId,
            yaml
        )
    }

    /** Stashes the rule document `rule/[key]` under [rootId] through the undecorated repository (no event rows). */
    suspend fun rule(
        rootId: UUID,
        key: String,
        body: String
    ) {
        checkNotNull(provider) { "this seeder was built without a repository provider" }
            .planDocumentRepository()
            .stash(rootId, "rule/$key", body)
    }

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

    /**
     * Appends [records] to `call_log` through the undecorated store (no event rows, the same on both legs), so a `report`
     * scenario reads identical rows on both legs. Returns how many rows were inserted (a duplicate `req_id` is ignored).
     */
    suspend fun callLog(vararg records: CallLogRecord): Int =
        checkNotNull(provider) { "this seeder was built without a repository provider" }
            .callLogStore()
            .append(records.toList())

    /**
     * Appends [records] to `events` through the UNDECORATED event store inside a write unit (so no further event rows are recorded,
     * the same on both legs), so a `report` scenario reads identical event rows on both legs. A record carries its own instant, root,
     * entity, type and payload; the sequence number is assigned by the table. Returns how many rows were appended.
     */
    suspend fun events(vararg records: EventRecord): Int {
        val store = checkNotNull(provider) { "this seeder was built without a repository provider" }.eventStore()
        val uow = checkNotNull(unitOfWork) { "this seeder was built without a unit of work" }
        var appended = 0
        uow.inUnit("parity.seedEvents") { appended = store.append(records.toList()).size }
        return appended
    }

    /**
     * Seeds a run through the undecorated [io.github.jpicklyk.mcptask.current.application.port.RunStore] inside a write unit (no
     * event rows, the same on both legs), with [items] as its members. A run with an [openedBy] also gets its coordinator seat with
     * the known token [parityCoordinatorToken] (active, or ended `run_closed` for a closed run). The state fields are derived from [status] by
     * construction: a paused run carries a [pauseReason], a closed run a [terminalReason], a `closedAt` and a `closedBy`, so a
     * fixture cannot name an inconsistent combination. Instants are fixed, so both legs seed identical rows.
     */
    suspend fun run(
        id: UUID,
        rootId: UUID,
        items: List<UUID> = emptyList(),
        status: RunStatus = RunStatus.OPEN,
        openedBy: String? = null,
        openedByKind: String? = null,
        pauseReason: RunPauseReason = RunPauseReason.MANUAL,
        terminalReason: RunTerminalReason = RunTerminalReason.COMPLETED,
        openedAt: Instant = PARITY_NOW.minusSeconds(3_600),
        closedAt: Instant = PARITY_NOW.minusSeconds(60),
        marks: Map<String, Instant> = emptyMap(),
        coordinatorToken: String? = if (openedBy != null) parityCoordinatorToken(id) else null
    ): Run {
        val store = checkNotNull(provider) { "this seeder was built without a repository provider" }.runStore()
        val uow = checkNotNull(unitOfWork) { "this seeder was built without a unit of work" }
        lateinit var created: Run
        uow.inUnit("parity.seedRun") {
            created =
                store.create(
                    Run(
                        id = id,
                        rootId = rootId,
                        status = status,
                        pauseReason = if (status == RunStatus.PAUSED) pauseReason else null,
                        terminalReason = if (status == RunStatus.CLOSED) terminalReason else null,
                        openedBy = openedBy,
                        openedByKind = openedByKind,
                        openedAt = openedAt,
                        closedBy = if (status == RunStatus.CLOSED) openedBy else null,
                        closedAt = if (status == RunStatus.CLOSED) closedAt else null,
                        stallAfterSeconds = Run.DEFAULT_STALL_AFTER_SECONDS,
                        marks = marks
                    )
                )
            if (items.isNotEmpty()) store.addMembers(id, items, openedAt)
            if (coordinatorToken != null) {
                // R22 D7: the coordinator pseudo-seat `open` creates, held by the opener. A closed run's coordinator row is ENDED
                // (outcome run_closed, ended when the run closed), exactly the state the close cascade leaves, so its token is dead.
                val closed = status == RunStatus.CLOSED
                checkNotNull(provider).runSeatStore().create(
                    RunSeat(
                        id = UUID.nameUUIDFromBytes("parity-coordinator:$id".toByteArray()),
                        runId = id,
                        itemId = null,
                        seat = RunSeat.COORDINATOR,
                        attempt = 1,
                        principalId = checkNotNull(openedBy) { "a coordinator seat needs the opener's identity" },
                        tokenHash = sha256Hex(coordinatorToken.toByteArray(Charsets.UTF_8)),
                        status = if (closed) RunSeatStatus.ENDED else RunSeatStatus.ACTIVE,
                        outcome = if (closed) RunSeatOutcome.RUN_CLOSED else null,
                        assignedAt = openedAt,
                        endedAt = if (closed) closedAt else null
                    )
                )
            }
        }
        return created
    }

    /**
     * Seeds a seat assignment with a KNOWN token through the undecorated seat store inside a write unit (no event rows, the same on
     * both legs). The stored form is the lowercase hex SHA-256 of the token's UTF-8 bytes; the caller presents [token] as the
     * top-level `seatToken` and is accepted only as [principalId]. An ended seat carries the `run_closed` outcome and an end
     * instant, so the state invariants hold by construction. [itemId] is null exactly for the `coordinator` pseudo-seat.
     */
    suspend fun seat(
        runId: UUID,
        itemId: UUID?,
        seat: String,
        principalId: String,
        token: String,
        attempt: Int = 1,
        ended: Boolean = false,
        assignedAt: Instant = PARITY_NOW.minusSeconds(3_000)
    ): RunSeat {
        val uow = checkNotNull(unitOfWork) { "this seeder was built without a unit of work" }
        lateinit var created: RunSeat
        uow.inUnit("parity.seedSeat") {
            created =
                checkNotNull(provider) { "this seeder was built without a repository provider" }.runSeatStore().create(
                    RunSeat(
                        id = UUID.nameUUIDFromBytes("parity-seat:$runId:$itemId:$seat:$attempt".toByteArray()),
                        runId = runId,
                        itemId = itemId,
                        seat = seat,
                        attempt = attempt,
                        principalId = principalId,
                        tokenHash = sha256Hex(token.toByteArray(Charsets.UTF_8)),
                        status = if (ended) RunSeatStatus.ENDED else RunSeatStatus.ACTIVE,
                        outcome = if (ended) RunSeatOutcome.RUN_CLOSED else null,
                        assignedAt = assignedAt,
                        endedAt = if (ended) PARITY_NOW.minusSeconds(60) else null
                    )
                )
        }
        return created
    }

    /**
     * Seeds a decision request with a caller-chosen id through the undecorated decision store inside a write unit (no event rows, the
     * same on both legs; R24). The state fields are derived from [status] by construction: only an answered decision carries an
     * answer, and an acknowledged one carries its acknowledgement, so a fixture cannot name an inconsistent combination. Instants are
     * fixed, so both legs seed identical rows. The scope key is the item id text, else the run id text, else `root`, like the service.
     */
    suspend fun decision(
        id: UUID,
        rootId: UUID,
        itemId: UUID? = null,
        runId: UUID? = null,
        decisionClass: String = "question",
        answerers: DecisionRequestAnswerers = DecisionRequestAnswerers.ANY_ACTOR,
        severity: DecisionRequestSeverity = DecisionRequestSeverity.LOW,
        status: DecisionRequestStatus = DecisionRequestStatus.OPEN,
        dedupeKey: String = "seed-$id",
        question: String = "Which one?",
        optionKeys: List<String> = listOf("a", "b"),
        raisedById: String = "api:rw-coord",
        raisedByKind: String = "service",
        raisedBySeat: String? = null,
        raisedAt: Instant = PARITY_NOW.minusSeconds(600),
        expiresAt: Instant? = null
    ): DecisionRequest {
        val store = checkNotNull(provider) { "this seeder was built without a repository provider" }.decisionRequestStore()
        val uow = checkNotNull(unitOfWork) { "this seeder was built without a unit of work" }
        lateinit var created: DecisionRequest
        uow.inUnit("parity.seedDecision") {
            created =
                store.create(
                    DecisionRequest(
                        id = id,
                        rootId = rootId,
                        runId = runId,
                        itemId = itemId,
                        decisionClass = decisionClass,
                        answerers = answerers,
                        severity = severity,
                        status = status,
                        dedupeKey = dedupeKey,
                        scopeKey = itemId?.toString() ?: runId?.toString() ?: DecisionRequest.SCOPE_ROOT,
                        question = question,
                        options = optionKeys.map { DecisionRequestOption(key = it, label = "Label ${it.uppercase()}") },
                        raisedById = raisedById,
                        raisedByKind = raisedByKind,
                        raisedBySeat = raisedBySeat,
                        raisedAt = raisedAt,
                        expiresAt = expiresAt,
                        ackById = if (status == DecisionRequestStatus.ACKNOWLEDGED) "api:rw-ack" else null,
                        ackByKind = if (status == DecisionRequestStatus.ACKNOWLEDGED) "human" else null,
                        ackAt = if (status == DecisionRequestStatus.ACKNOWLEDGED) raisedAt.plusSeconds(60) else null,
                        answer =
                            if (status == DecisionRequestStatus.ANSWERED) {
                                DecisionRequestAnswer(
                                    option = optionKeys.first(),
                                    answeredById = "api:rw-human",
                                    answeredByKind = "human",
                                    answeredByProof = "issued",
                                    answeredAt = raisedAt.plusSeconds(120)
                                )
                            } else {
                                null
                            }
                    )
                )
        }
        return created
    }

    /**
     * Creates an item in [role] (queue unless told otherwise); a child's depth and root are derived from its parent so the domain
     * invariants hold by construction.
     */
    suspend fun item(
        id: UUID,
        title: String,
        parent: UUID? = null,
        tags: String? = null,
        role: Role = Role.QUEUE
    ): WorkItem {
        val p = parent?.let { checkNotNull(repo.getById(it)) { "seed parent $it does not exist" } }
        return checkNotNull(
            repo.create(
                WorkItem(
                    id = id,
                    title = title,
                    parentId = parent,
                    depth = (p?.depth ?: -1) + 1,
                    rootId = p?.let { it.rootId ?: it.id } ?: id,
                    tags = tags,
                    role = role
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
        apiTokenEntries = authConfig.tokens,
        allowQueryToken = false,
        serverName = "parity-harness",
        serverVersion = "test",
        serverConfig = rig.composition.serverConfig,
        toolContext = rig.composition.toolContext,
        callLog = callLog,
        toolRegistry = registry,
        toolDispatcher = buildToolDispatcher(rig.composition.toolContext),
        appConfig = AppConfig.fromEnv()
    )
}

/** An [ApiAuthConfig.Bearer] holding exactly [token]. */
internal fun parityAuthConfig(token: ParityToken): ApiAuthConfig.Bearer =
    ApiAuthConfig.Bearer(tokens = mapOf(HashBytes(sha256(token.raw)) to BearerTokenStore.TokenEntry(token.principal(), expiresAt = null)))

/** The [CallAccess] an MCP leg runs under so it carries the REST leg's identity (principal, scope, capabilities). */
internal fun parityAccess(token: ParityToken): CallAccess =
    // The same builder the REST route uses, with the default redaction (API_REDACT_NOTE_ATTRIBUTION on): both legs
    // then agree on whether note attribution is visible.
    token.principal().toCallAccess(redactNoteAttribution = true)

/**
 * Q32 (S6): every body a parity leg receives is checked against the envelope oracle, so each ProductionParity scenario's MCP
 * and REST bodies conform wherever the catalog runs (this harness, NotesParityTest, DocsParityTest).
 */
internal fun assertParityBodyConforms(
    body: String,
    label: String,
    arguments: String = ""
) {
    EnvelopeConformance.assertConforms(Json.parseToJsonElement(body), label, continuation = "\"cursor\"" in arguments)
}

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
    args: JsonObject,
    profile: ToolProfile = ToolProfile.ORCHESTRATION
): McpResult {
    val dispatcher = buildToolDispatcher(toolContext)
    // No access given: the MCP default path, where a top-level `actor` fixture selects the caller (liftTestActor) and
    // the call otherwise runs as the local principal. The server itself never reads a body `actor` since R14.
    val (callArgs, lifted) = if (access == null) liftTestActor(args) else args to null
    val adapter = McpOperationAdapter(dispatcher, access = access ?: { _ -> checkNotNull(lifted) })
    val server = Server(serverInfo = Implementation(name = "parity-server", version = "1.0.0"), options = inMemoryTestServerOptions())
    adapter.register(server, registry, profile)
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
                    arguments(callArgs)
                }
            )
        result.structuredContent?.let { assertParityBodyConforms(it.toString(), "MCP $tool body", args.toString()) }
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
                ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.unitOfWork()).seed()
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
        s: ParityScenario,
        credential: Boolean = true
    ): RestResult {
        var result: RestResult? = null
        testApplication {
            application { installParityRestApp(leg.rig, parityAuthConfig(s.token), registry) }
            val response =
                client.post("/api/v1/ops/${s.tool}") {
                    if (credential) header(HttpHeaders.Authorization, "Bearer ${s.token.raw}")
                    contentType(ContentType.Application.Json)
                    setBody(s.arguments)
                }
            val text = response.bodyAsText()
            // A refusal by the auth plugin (no credential) is checked for its code and status by the caller, not for the full
            // envelope shape: the plugin, not the dispatcher, writes it (R14 D8).
            if (credential) assertParityBodyConforms(text, "${s.name}: REST body", s.arguments)
            result = RestResult(response.status.value, text, response.headers["X-Req-Id"])
        }
        return checkNotNull(result) { "REST leg produced no response" }
    }

    fun run(s: ParityScenario): ParityOutcome {
        if (s.kind == ParityKind.UNAUTHENTICATED) return runUnauthenticated(s)
        // R5 AC6: the MCP leg runs on a profile whose set contains the member; an empty-set member has no MCP leg at all.
        val mcpProfile = ParityCoverage.mcpLegProfile(registry, s.tool, s.member) ?: return runRestOnly(s)
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
            val mcpRawBefore = rawTables(mcpLeg.db.jdbcUrl)
            val before = project(rawBefore)
            assertEquals(before, project(mcpRawBefore), "${s.name}: the two legs must start from identical seeded databases")
            val known = knownIds(rawBefore, s.arguments)

            val rest = runRest(restLeg, s)
            val mcp =
                runBlocking {
                    callMcp(
                        mcpLeg.rig.composition.toolContext,
                        registry,
                        { parityAccess(s.token) },
                        s.tool,
                        Json.parseToJsonElement(s.arguments).jsonObject,
                        mcpProfile
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
            val (restNorm, restAfter) = tokenizeGeneratedIds(restRawAfter, normalize(rest.body, restReqId, seededStamps(rawBefore)), known)
            val (mcpNorm, mcpAfter) =
                tokenizeGeneratedIds(mcpRawAfter, normalize(mcp.structured.toString(), mcpReqId, seededStamps(mcpRawBefore)), known)
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

    private fun JsonObject.errorCode(): String? =
        this["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content

    /**
     * An UNAUTHENTICATED scenario (R14, decision D8): the REST leg sends the call with NO Authorization header and the MCP leg
     * is handed an access that carries no principal. Both must answer `unauthenticated` with its catalog status and leave the
     * database untouched. The error `message` is deliberately not compared (REST is refused by the auth plugin, MCP by the
     * dispatcher). A control pair then sends the identical call WITH the scenario's token on both legs and must not be refused as
     * unauthenticated, so the refusal is caused by the missing credential and by nothing else about the call.
     */
    private fun runUnauthenticated(s: ParityScenario): ParityOutcome {
        val restLeg = openLeg(s.seed)
        val mcpLeg =
            try {
                openLeg(s.seed)
            } catch (e: Throwable) {
                restLeg.close()
                throw e
            }
        try {
            val before = project(rawTables(restLeg.db.jdbcUrl))
            assertEquals(
                before,
                project(rawTables(mcpLeg.db.jdbcUrl)),
                "${s.name}: the two legs must start from identical seeded databases"
            )
            val unauthenticatedStatus = ErrorCode.entries.single { it.wire == "unauthenticated" }.httpStatus

            val rest = runRest(restLeg, s, credential = false)
            val restBody = Json.parseToJsonElement(rest.body).jsonObject
            assertEquals(false, restBody["ok"]?.jsonPrimitive?.boolean, "${s.name}: REST refusal is not ok: ${rest.body}")
            assertEquals("unauthenticated", restBody.errorCode(), "${s.name}: REST error code: ${rest.body}")
            assertEquals(s.expectStatus, rest.status, "${s.name}: REST status")
            assertEquals(unauthenticatedStatus, rest.status, "${s.name}: REST status equals the catalog status of unauthenticated")
            assertEquals(before, project(rawTables(restLeg.db.jdbcUrl)), "${s.name}: the REST refusal wrote nothing")

            val spec = checkNotNull(registry[s.tool]) { "${s.name}: tool ${s.tool} is not registered" }
            val profile = ToolProfile.entries.firstOrNull { spec in registry.visible(it) }
            val args = Json.parseToJsonElement(s.arguments).jsonObject
            if (profile != null) {
                val mcp =
                    runBlocking {
                        callMcp(mcpLeg.rig.composition.toolContext, registry, { _ -> CallAccess.unauthenticated() }, s.tool, args, profile)
                    }
                assertTrue(mcp.isError, "${s.name}: the MCP refusal is a tool error: ${mcp.text}")
                assertEquals("unauthenticated", mcp.structured.errorCode(), "${s.name}: MCP error code: ${mcp.text}")
                assertEquals(before, project(rawTables(mcpLeg.db.jdbcUrl)), "${s.name}: the MCP refusal wrote nothing")
            }

            // Controls: the same call with the scenario's own credential is not refused as unauthenticated on either leg.
            val controlRest = runRest(restLeg, s, credential = true)
            assertNotEquals(unauthenticatedStatus, controlRest.status, "${s.name}: control REST call with a token: ${controlRest.body}")
            if (profile != null) {
                val controlMcp =
                    runBlocking {
                        callMcp(mcpLeg.rig.composition.toolContext, registry, { _ -> parityAccess(s.token) }, s.tool, args, profile)
                    }
                assertNotEquals("unauthenticated", controlMcp.structured.errorCode(), "${s.name}: control MCP call with a principal")
            }
            return ParityOutcome(rest.status, rest.body, before, false)
        } finally {
            restLeg.close()
            mcpLeg.close()
        }
    }

    /**
     * The scenario of a REST-only member (empty profile set, R5 AC5/AC6): only the REST leg runs the body and database
     * expectations, and the MCP leg asserts the member is UNKNOWN on every profile (`invalid_request`, nothing written).
     */
    private fun runRestOnly(s: ParityScenario): ParityOutcome {
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
            val mcpRawBefore = rawTables(mcpLeg.db.jdbcUrl)
            val before = project(rawBefore)
            assertEquals(before, project(mcpRawBefore), "${s.name}: the two legs must start from identical seeded databases")
            val known = knownIds(rawBefore, s.arguments)

            val rest = runRest(restLeg, s)
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
            val restRawAfter = rawTables(restLeg.db.jdbcUrl)
            val (restNorm, restAfter) = tokenizeGeneratedIds(restRawAfter, normalize(rest.body, restReqId, seededStamps(rawBefore)), known)
            val ok = restBody["ok"]?.jsonPrimitive?.boolean ?: error("${s.name}: body has no ok flag")
            val code =
                restBody["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content
            assertEquals(s.expectCode, code, "${s.name}: error code")
            assertEquals(s.expectStatus, rest.status, "${s.name}: REST status")
            if (code == null) assertTrue(ok && rest.status == 200, "${s.name}: an ok body is 200")
            for (needle in s.expectContains) assertTrue(needle in restNorm, "${s.name}: body must contain $needle but was $restNorm")
            for (needle in s.expectAbsent) assertFalse(needle in restNorm, "${s.name}: body must not contain $needle but was $restNorm")
            val changed = project(restRawAfter) != before
            s.expectDbChanged?.let { assertEquals(it, changed, "${s.name}: database changed") }

            // The MCP half: the member is unknown on every profile, so nothing is served and nothing is written.
            val spec = checkNotNull(registry[s.tool]) { "${s.name}: tool ${s.tool} is not registered" }
            for (profile in ToolProfile.entries) {
                if (spec !in registry.visible(profile)) continue
                val mcp =
                    runBlocking {
                        callMcp(
                            mcpLeg.rig.composition.toolContext,
                            registry,
                            { parityAccess(s.token) },
                            s.tool,
                            Json.parseToJsonElement(s.arguments).jsonObject,
                            profile
                        )
                    }
                assertTrue(mcp.isError, "${s.name}: a REST-only member must not run on the ${profile.wire} MCP profile: ${mcp.text}")
                val mcpCode =
                    mcp.structured["error"]
                        ?.jsonObject
                        ?.get("code")
                        ?.jsonPrimitive
                        ?.content
                if (spec is OpsToolSpec<*>) {
                    // An ops tool reports a hidden op as a failed ELEMENT: the whole call is partial_failure (207) and the element
                    // carries invalid_request at the op selector (envelope: a failed element never fails its batch siblings).
                    val body = mcp.structured.toString()
                    assertEquals("partial_failure", mcpCode, "${s.name}: unknown on ${profile.wire}, like any hidden op: ${mcp.text}")
                    assertTrue(
                        "\"code\":\"invalid_request\"" in body && "/ops/0/op" in body,
                        "${s.name}: the element is invalid_request at /ops/0/op on ${profile.wire}: $body"
                    )
                } else {
                    assertEquals("invalid_request", mcpCode, "${s.name}: unknown on ${profile.wire}, like any hidden member: ${mcp.text}")
                }
                assertEquals(
                    project(mcpRawBefore),
                    project(rawTables(mcpLeg.db.jdbcUrl)),
                    "${s.name}: the refused ${profile.wire} call must write nothing"
                )
            }
            return ParityOutcome(rest.status, restNorm, restAfter, changed)
        } finally {
            restLeg.close()
            mcpLeg.close()
        }
    }

    /** Every instant a seeded row of [raw] carries (the seeder stamps work items with the wall clock, not the fixed one). */
    private fun seededStamps(raw: Map<String, List<Map<String, Any?>>>): Set<Instant> =
        raw.values
            .flatten()
            .flatMap { it.values }
            .mapNotNull { cell -> (cell as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } }
            .toSet()

    /**
     * A stamp the call wrote is on the fixed clock (Q32: notes are stamped by the unit's clock too), so it is compared
     * exactly. Only a stamp a SEEDED row carries may be off the fixed clock (the seeder stamps items with the wall clock,
     * which differs between the legs by milliseconds): it is masked, and only because [seeded] names it.
     */
    private fun normalize(
        body: String,
        reqId: String,
        seeded: Set<Instant>
    ): String {
        val marker = "\"reqId\":\"$reqId\""
        assertTrue(marker in body, "body carries its reqId marker: $body")
        val fixed = PARITY_NOW.toString()
        return body
            .replace(marker, "\"reqId\":\"<REQ>\"")
            .replace(SEAT_TOKEN) { match ->
                // R22: `open` returns a random coordinatorToken and `assign` a random seatToken (43 base64url characters, returned once);
                // the two legs issue different values, so the value is masked and only its form (exactly 43 characters of the
                // alphabet) is compared.
                "\"${match.groupValues[1]}\":\"<TOKEN>\""
            }.replace(PROFILE) { _ ->
                // R24: an answer records the profile it was served on (`director` on the MCP leg, `rest` on `/ops/{tool}`), which differs
                // between the two legs by design (as the kind refusals of pause and resume do): only its presence is compared.
                "\"profile\":\"<PROFILE>\""
            }.replace(TIMESTAMP) { match ->
                val value = match.groupValues[2]
                if (value == fixed) {
                    match.value
                } else {
                    assertTrue(Instant.parse(value) in seeded, "a stamp the call wrote must be the fixed clock's, got $value: $body")
                    "\"${match.groupValues[1]}\":\"<SEEDED>\""
                }
            }
    }

    companion object {
        private val TABLES =
            listOf(
                "work_items",
                "notes",
                "dependencies",
                "events",
                "documents",
                "document_versions",
                "links",
                "config_versions",
                "config_heads",
                "runs",
                "run_items",
                "run_seats",
                "decision_requests"
            )

        private val TIMESTAMP = Regex("\"(createdAt|modifiedAt)\":\"([^\"]*)\"")

        /** The served profile that the provenance of an answer records. */
        private val PROFILE = Regex("\"profile\":\"(?:director|rest|orchestration|knowledge)\"")

        /** A seat token the server issued: `"seatToken"` / `"coordinatorToken"` and exactly 43 base64url characters. */
        private val SEAT_TOKEN = Regex("\"(seatToken|coordinatorToken)\":\"[A-Za-z0-9_-]{43}\"")

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
                    row
                        .filterKeys { label ->
                            !(
                                label == "seq" ||
                                    label == "req_id" ||
                                    label == "session_id" ||
                                    // config_heads.version_id points at a generated config_versions id (the seeded versions get a
                                    // fresh id per leg), the same kind of per-leg generated id as the dropped `id` columns
                                    (label == "version_id" && table == "config_heads") ||
                                    (label == "host" && table == "events") ||
                                    // R22: the stored SHA-256 of an issued seat token; the token is random per call, so the hash differs
                                    // between the legs by design (a SEEDED seat's hash is compared through its row and the call's outcome)
                                    (label == "token_hash" && table == "run_seats") ||
                                    // R24: the profile an answer was served on (`director` on the MCP leg, `rest` on `/ops/{tool}`)
                                    (label == "answered_profile" && table == "decision_requests") ||
                                    // R24: the transport host of an answer, which differs between the legs by design like an event's (Q7)
                                    (label == "answered_host" && table == "decision_requests") ||
                                    label == "at" ||
                                    (label.endsWith("_at") && label != "unblock_at")
                            ) &&
                                !(label == "id" && table != "work_items")
                        }.let { kept ->
                            // R24: an event's payload names the profile an answer was served on, which differs between the legs by design
                            val data = kept["data"]
                            if (table == "events" &&
                                data is String
                            ) {
                                kept + ("data" to PROFILE.replace(data, "\"profile\":\"<PROFILE>\""))
                            } else {
                                kept
                            }
                        }
                }
            }

        private fun sorted(tables: Map<String, List<Map<String, Any?>>>): Map<String, List<Map<String, Any?>>> =
            tables.mapValues { (table, rows) -> if (table == "events") rows else rows.sortedBy { it.toString() } }

        /** The [snapshot] projection of [rawTables] output: noise columns dropped, non-event rows sorted. */
        internal fun project(raw: Map<String, List<Map<String, Any?>>>): Map<String, List<Map<String, Any?>>> =
            sorted(withoutNoise(withDocumentRowids(raw)))

        /**
         * [raw] with each `document_versions.document_id` replaced by its document's `doc_rowid`: the id is generated per leg (a seeded
         * rule document gets a fresh one on each leg, like the dropped `documents.id`), while the rowid is assigned in write order and
         * so keeps the version-to-document link comparable.
         */
        private fun withDocumentRowids(raw: Map<String, List<Map<String, Any?>>>): Map<String, List<Map<String, Any?>>> {
            val rowids = raw["documents"].orEmpty().associate { it["id"] to it["doc_rowid"] }
            val versions = raw["document_versions"] ?: return raw
            return raw + ("document_versions" to versions.map { row -> row + ("document_id" to "doc#" + rowids[row["document_id"]]) })
        }

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
            val raw = withoutNoise(withDocumentRowids(rawWithNoise))
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

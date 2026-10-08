package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.config.ManagePlanDocumentsTool
import io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigTool
import io.github.jpicklyk.mcptask.current.application.tools.config.QueryRulesTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.QueryDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.QueryNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetBlockedItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetNextItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetNextStatusTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.health.ReadinessMarker
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.cors.configureCors
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.installRequestCorrelation
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.dependencyRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.dependencyWriteRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.effectiveConfigRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemGateRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemWriteRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.noteRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.noteWriteRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.planDocumentRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.projectConfigRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.resourceLeaseRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ruleRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.searchRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.serviceRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.transitionRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.wellKnownRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Current (v3) MCP Server implementation for the Task Orchestrator.
 *
 * Initializes database, configures MCP SDK server, registers tools, and starts the transport.
 * Transport is selected via the MCP_TRANSPORT environment variable:
 *   - "stdio" (default) — standard input/output
 *   - "http" — Ktor CIO HTTP server with Streamable HTTP transport
 *
 * @param version The server version string.
 * @param shutdownCoordinator Shutdown coordinator for graceful shutdown. Cleanup actions drain LIFO, so
 *   registration order is: database (first, drained last), JWKS providers, MCP server, HTTP server.
 * @param appConfig Typed environment snapshot, read ONCE at construction. Built before
 *   [databaseManager] so the DB layer reads its config from the same snapshot. Injectable for tests.
 * @param onBeforeTransportStart Test seam invoked as the first statement inside the try block
 *   wrapping the actual transport start (arg "stdio" or "http"), before the real start is
 *   attempted. No-op by default; production behaviour is unchanged.
 * @param stdioInput Test seam supplying the stdio transport's input stream; a lambda so constructing
 *   the server never touches System.in. Defaults to System.in.
 * @param stdioOutput Test seam supplying the stdio transport's output stream; a lambda so constructing
 *   the server never touches System.out. Defaults to System.out.
 */
class CurrentMcpServer(
    private val version: String,
    private val shutdownCoordinator: ShutdownCoordinator = ShutdownCoordinator(),
    private val appConfig: AppConfig = AppConfig.fromEnv(),
    internal val onBeforeTransportStart: (String) -> Unit = {},
    internal val stdioInput: () -> InputStream = { System.`in` },
    internal val stdioOutput: () -> OutputStream = { System.out }
) {
    private val logger = LoggerFactory.getLogger(CurrentMcpServer::class.java)

    // Build the AppConfig snapshot FIRST (constructor arg above), then DatabaseManager from it:
    // DatabaseManager is constructed early (a field, before run()) and reads DB env, so the snapshot
    // must exist before it.
    private val databaseManager = DatabaseManager(appConfig = appConfig)
    private var mcpSdkServer: Server? = null

    /**
     * Runs the server (see [runServer]) and, for any outcome that never reached serving, closes the
     * database pools before returning: pooled connections stay open, unlike the old connection-per-transaction
     * model, and would otherwise outlive a failed or repair-only start.
     */
    fun run(): StartupOutcome {
        var outcome: StartupOutcome? = null
        try {
            outcome = runServer()
            return outcome
        } finally {
            if (outcome !is Started) databaseManager.shutdown()
        }
    }

    /**
     * Configures and runs the MCP server.
     *
     * Blocks until the server is closed, then returns [Started] — or returns [Failed] immediately
     * (without ever serving) when database init, schema update, transport dispatch, or the
     * readiness-marker write fails. Callers ([CurrentMain.main]) MUST inspect the result: a [Failed]
     * outcome no longer just logs and returns like a success would — see [StartupOutcome].
     */
    private fun runServer(): StartupOutcome =
        runBlocking {
            logger.info("Initializing Current (v3) MCP server...")

            // Readiness marker: written only once the server is actually serving (inside the
            // transport runners below, after the transport confirms it started), and cleared on
            // every shutdown path. This is the Docker HEALTHCHECK's readiness signal -- see
            // ReadinessMarker's kdoc for why a marker file, not an HTTP probe.
            //
            // Cleared FIRST, before DB init: after SIGKILL/OOM the previous process never ran its
            // shutdown clear, and the container's writable layer keeps the stale file across a
            // restart, so the HEALTHCHECK would report healthy during DB init/migration or after a
            // failed start. Fail closed if the stale marker cannot be removed -- swallowing the
            // error would leave exactly that false-healthy signal in place.
            val readinessMarker = ReadinessMarker(Paths.get(appConfig.readinessFile))
            try {
                readinessMarker.clear()
            } catch (e: Exception) {
                val detail = "Failed to clear stale readiness marker at ${readinessMarker.path}: ${e.message}"
                logger.error(detail, e)
                return@runBlocking Failed(Reason.READINESS_MARKER, detail)
            }

            // Initialize database (DatabaseManager already holds the AppConfig snapshot)
            val dbPath = appConfig.databasePath
            if (!databaseManager.initialize(dbPath)) {
                logger.error("Failed to initialize database at: $dbPath")
                return@runBlocking Failed(Reason.DATABASE_INIT, "Failed to initialize database at: $dbPath")
            }
            // Registered right after initialize so the LIFO drain closes the database LAST, after
            // the HTTP server, MCP server and JWKS providers registered later have stopped.
            shutdownCoordinator.addCleanupAction("Close Database") {
                databaseManager.shutdown()
            }
            if (!databaseManager.updateSchema()) {
                logger.error("Failed to update database schema")
                return@runBlocking Failed(Reason.SCHEMA_UPDATE, "Failed to update database schema")
            }
            logger.info("Database initialized at: $dbPath")

            // FLYWAY_REPAIR=true: the schema manager already ran repair (not migrate) inside
            // updateSchema() above and it succeeded. Exit here — before ServerComposition is built
            // and before the readiness marker is written — so a repair-only run never serves and
            // the Docker HEALTHCHECK never reports healthy for it.
            if (appConfig.flywayRepair) {
                logger.info("FLYWAY_REPAIR=true: repair completed successfully; exiting without serving.")
                return@runBlocking RepairCompleted
            }

            // Delegate the entire object-graph construction (repositories, config services, actor
            // verifier, REST/SSE wiring, tool context) to the manual composition root. This class
            // stays lifecycle-only.
            val composition =
                ServerComposition(appConfig, databaseManager, shutdownCoordinator).build()
            val toolContext = composition.toolContext
            val apiWiring = composition.apiWiring
            val noteSchemaService = composition.noteSchemaService
            val degradedModePolicy = composition.degradedModePolicy
            val idempotencyCache = composition.idempotencyCache

            // Build tool list (shared with tests via buildMcpTools())
            val tools = buildMcpTools()

            // Configure MCP server
            val serverName = appConfig.mcpServerName
            val toolNames = tools.joinToString(", ") { it.name }
            val server = configureServer(serverName, tools.size, toolNames)
            mcpSdkServer = server

            // Register MCP tools
            val adapter = McpToolAdapter()
            adapter.registerToolsWithServer(server, tools, toolContext)
            logger.info("Registered ${tools.size} MCP tools")

            val toolCount = tools.size

            // Transport dispatch
            val transportType = appConfig.mcpTransport
            val outcome: StartupOutcome =
                when (transportType) {
                    "stdio" -> {
                        // stdio transport does NOT serve the REST/SSE API. When the API is enabled the
                        // tool context still uses the decorated provider, so MCP-tool writes publish to
                        // the bus — but with no SSE subscribers (no HTTP server), publish() is a cheap
                        // no-op. Document the gap rather than expand scope to serve SSE over stdio.
                        if (apiWiring.eventBus != null) {
                            logger.info(
                                "API config is enabled but MCP_TRANSPORT=stdio: the SSE endpoint is " +
                                    "only served under MCP_TRANSPORT=http. Event publishing is a no-op " +
                                    "(no subscribers) in stdio mode."
                            )
                        }
                        runStdioTransport(server, serverName, toolCount, readinessMarker)
                    }
                    "http" ->
                        runHttpTransport(
                            server,
                            serverName,
                            toolCount,
                            apiWiring,
                            noteSchemaService,
                            toolContext,
                            degradedModePolicy,
                            idempotencyCache,
                            composition.actorAuthEnabled,
                            readinessMarker
                        )
                    else -> {
                        logger.error("Unknown MCP_TRANSPORT: '$transportType'. Valid values: stdio, http")
                        Failed(
                            Reason.UNKNOWN_TRANSPORT,
                            "Unknown MCP_TRANSPORT: '$transportType'. Valid values: stdio, http"
                        )
                    }
                }

            logger.info("MCP server shut down")
            outcome
        }

    /**
     * Closes the MCP server. Can be called from external shutdown triggers.
     */
    suspend fun close() {
        mcpSdkServer?.close()
    }

    private fun registerCommonCleanup(server: Server) {
        shutdownCoordinator.addCleanupAction("Close MCP Server") {
            runBlocking { server.close() }
        }
    }

    private suspend fun runStdioTransport(
        server: Server,
        serverName: String,
        toolCount: Int,
        readinessMarker: ReadinessMarker
    ): StartupOutcome {
        logger.info("Starting MCP server with stdio transport...")

        val transport =
            StdioServerTransport(
                inputStream = stdioInput().asSource().buffered(),
                outputStream = stdioOutput().asSink().buffered()
            )

        registerCommonCleanup(server)

        val done = Job()

        // Registered BEFORE createSession: the transport fires its close callback exactly once, and an
        // immediate EOF (e.g. empty stdin) could otherwise fire it before a late handler is attached.
        // Server.onClose runs only from Server.close(), so stdin EOF needs this transport-level hook.
        transport.onClose {
            logger.info("stdio transport closed")
            done.complete()
        }

        server.onClose {
            logger.info("Server closed")
            done.complete()
        }

        try {
            onBeforeTransportStart("stdio")
            server.createSession(transport)
        } catch (e: Exception) {
            logger.error("Error in stdio server connection: ${e.message}", e)
            runCatching { readinessMarker.clear() }
            return Failed(Reason.TRANSPORT_START, "Failed to start stdio transport: ${e.message}")
        }

        try {
            readinessMarker.markReady()
        } catch (e: Exception) {
            logger.error("Failed to write readiness marker at ${readinessMarker.path}: ${e.message}", e)
            return Failed(Reason.READINESS_MARKER, "Failed to write readiness marker: ${e.message}")
        }

        logger.info("Current (v3) MCP server running as '$serverName' v$version with $toolCount tools")
        try {
            done.join()
        } catch (e: Exception) {
            logger.error("Error in stdio server connection: ${e.message}", e)
        } finally {
            readinessMarker.clear()
        }

        // Reached on stdin EOF (or any other close). If a signal already started shutdown, skip it so
        // the log does not misattribute the cause. Run off the runBlocking thread: the coordinator's
        // "Close MCP Server" action itself calls runBlocking { server.close() }.
        if (!shutdownCoordinator.isShutdownInitiated()) {
            withContext(Dispatchers.IO) { shutdownCoordinator.initiateShutdown("stdin EOF") }
        }
        return Started
    }

    private suspend fun runHttpTransport(
        server: Server,
        serverName: String,
        toolCount: Int,
        apiWiring: ApiWiring,
        noteSchemaService: WorkItemSchemaService,
        toolContext: ToolExecutionContext,
        degradedModePolicy: DegradedModePolicy,
        idempotencyCache: IdempotencyCache,
        actorAuthEnabled: Boolean,
        readinessMarker: ReadinessMarker,
    ): StartupOutcome {
        val host = appConfig.mcpHttpHost
        val port = appConfig.mcpHttpPort
        logger.info("Starting MCP server with HTTP transport on $host:$port/mcp ...")

        // SECURITY WARNING: the /mcp Streamable HTTP endpoint is UNAUTHENTICATED by design — MCP
        // clients reach it with no REST bearer token, so the ApiBearerAuth plugin exempts /mcp even
        // when the REST API is enabled (see installRestApiRoutes / McpRestAuthBypassTest). Anyone who
        // can reach $host:$port gets full read/write/delete via every MCP tool. This MUST be fronted
        // by a reverse proxy / mTLS / network fencing before exposing the port. Bind is controlled by
        // MCP_HTTP_HOST (default 0.0.0.0 so Docker port-mapping works; set 127.0.0.1 for loopback-only
        // local runs). Logged loudly at startup so operators cannot miss it.
        logger.warn(
            "SECURITY: /mcp over HTTP is UNAUTHENTICATED. Anyone who can reach {}:{} has full " +
                "read/write/delete access to all MCP tools. Front it with a reverse proxy, mTLS, or a " +
                "private network — do NOT expose this port to untrusted callers. Bind address is set " +
                "via MCP_HTTP_HOST (currently '{}'); use 127.0.0.1 for loopback-only local runs.",
            host,
            port,
            host,
        )
        if (apiWiring.apiConfig !is ApiAuthConfig.Disabled) {
            logger.warn(
                "SECURITY: API_ENABLED=true authenticates /api/v1 routes but does NOT protect /mcp. " +
                    "The /mcp endpoint remains unauthenticated regardless of the REST API auth mode.",
            )
        }
        if (apiWiring.apiConfig is ApiAuthConfig.Unauthenticated) {
            logger.warn(
                "SECURITY: API_AUTH_MODE=none + API_ALLOW_UNAUTHENTICATED=true — the /api/v1 REST " +
                    "API is UNAUTHENTICATED. Anyone who can reach {}:{} has full read/write/delete " +
                    "access to all config and data via the REST API (same exposure as /mcp above). " +
                    "This MUST be fronted by a reverse proxy / mTLS / private network, or bound to " +
                    "127.0.0.1 for a loopback-only local run — do NOT expose this port to untrusted " +
                    "callers.",
                host,
                port,
            )
        }
        if (host == "0.0.0.0") {
            logger.warn(
                "SECURITY: MCP HTTP transport is bound to 0.0.0.0 (all interfaces). If this is not a " +
                    "container behind a reverse proxy / private network, set MCP_HTTP_HOST=127.0.0.1.",
            )
        }

        // Phase 6: reuse the API wiring resolved ONCE in run() — the SAME bus and decorated
        // provider already feeding the MCP tool context. Do NOT re-resolve here, or the SSE route
        // would subscribe to a different bus than the one MCP-tool writes publish to.
        val apiConfig = apiWiring.apiConfig
        val eventBus = apiWiring.eventBus
        val effectiveProvider = apiWiring.effectiveProvider
        val apiTokenEntries = apiWiring.tokenEntries
        val allowQueryToken = apiWiring.allowQueryToken
        val jwksVerifier = apiWiring.jwksVerifier

        val done = Job()
        val ktorServer =
            embeddedServer(CIO, host = host, port = port) {
                // MCP transport (/mcp) + optional REST API (/api/v1) wiring is extracted into
                // installMcpStreamableHttp() and installRestApiRoutes() so the SAME production code
                // path is exercised by tests (see McpStreamableHttpTransportTest). The MCP mount must
                // come first: mcpStreamableHttp installs the SSE plugin that the events route reuses.
                installMcpStreamableHttp(server, appConfig)
                installRestApiRoutes(
                    apiConfig = apiConfig,
                    eventBus = eventBus,
                    effectiveProvider = effectiveProvider,
                    apiTokenEntries = apiTokenEntries,
                    allowQueryToken = allowQueryToken,
                    serverName = serverName,
                    serverVersion = version,
                    actorAuthEnabled = actorAuthEnabled,
                    noteSchemaService = noteSchemaService,
                    toolContext = toolContext,
                    degradedModePolicy = degradedModePolicy,
                    idempotencyCache = idempotencyCache,
                    jwksVerifier = jwksVerifier,
                    appConfig = appConfig,
                )
            }

        // Stop the engine only if start() actually succeeded. A CIO engine's server job is LAZY: calling
        // stop() on a never-started engine joins that job, which STARTS it and binds host:port just to
        // tear it down again. After a transport-start failure that meant every registered shutdown path
        // re-attempted the bind at exit, surfacing an uncaught BindException (or briefly binding a port
        // this process never served on).
        val httpStarted = AtomicBoolean(false)

        // Registered MCP-close first, then HTTP stop: the LIFO drain stops HTTP, closes MCP, then
        // (registered earlier still) the JWKS providers and the database.
        registerCommonCleanup(server)
        shutdownCoordinator.addCleanupAction("Stop HTTP Server") {
            if (httpStarted.get()) ktorServer.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
            done.complete()
        }

        server.onClose {
            logger.info("Server closed")
        }

        try {
            onBeforeTransportStart("http")
            ktorServer.start(wait = false)
            httpStarted.set(true)
        } catch (e: Exception) {
            logger.error("Error in HTTP server: ${e.message}", e)
            runCatching { readinessMarker.clear() }
            return Failed(Reason.TRANSPORT_START, "Failed to start http transport: ${e.message}")
        }

        try {
            readinessMarker.markReady()
        } catch (e: Exception) {
            logger.error("Failed to write readiness marker at ${readinessMarker.path}: ${e.message}", e)
            return Failed(Reason.READINESS_MARKER, "Failed to write readiness marker: ${e.message}")
        }

        logger.info("Current (v3) MCP server running as '$serverName' v$version via HTTP on $host:$port/mcp with $toolCount tools")
        try {
            done.join()
        } catch (e: Exception) {
            logger.error("Error in HTTP server: ${e.message}", e)
        } finally {
            readinessMarker.clear()
        }
        return Started
    }

    /**
     * Configures the MCP SDK server with capabilities for tools and logging.
     */
    private fun configureServer(
        serverName: String,
        toolCount: Int,
        toolNames: String
    ): Server =
        Server(
            serverInfo =
                Implementation(
                    name = serverName,
                    version = version
                ),
            options =
                ServerOptions(
                    capabilities = productionServerCapabilities()
                ),
            instructions = "Current (v3) MCP Task Orchestrator — $toolCount tools: $toolNames"
        )
}

/**
 * The [ServerCapabilities] this server advertises in `initialize` results: `tools` (with
 * `listChanged`) and `logging` — no `prompts` or `resources`. Extracted from [CurrentMcpServer]'s
 * private `configureServer` so a test can assert on it directly.
 *
 * `logging` is kept even though the old MCP protocol-level logging service was removed (it never
 * actually emitted anything — see AR-78/item b5081c9b): [McpToolAdapter] still emits
 * `notifications/message` directly (via
 * `clientConnection.sendLoggingMessage`) on validation errors, per-root-config-unavailable
 * failures, and internal errors, and MCP requires servers that emit log notifications to declare
 * the `logging` capability. `prompts`/`resources` are removed because no `addPrompt`/`addResource`
 * exists anywhere in this server — they were advertised-but-empty surfaces.
 *
 * `tools.listChanged` stays true, so every tool MUST be registered before any session connects: in
 * kotlin-sdk 0.12.0 a post-connect `addTool` sends `tools/list_changed` from an SDK notification job
 * with no exception handler, and a session whose transport has closed makes it throw an uncaught
 * "Transport is not ready" (bug 947cc2ec, seen in tests).
 */
internal fun productionServerCapabilities(): ServerCapabilities =
    ServerCapabilities(
        tools = ServerCapabilities.Tools(listChanged = true),
        logging = JsonObject(emptyMap())
    )

/**
 * Builds the canonical list of MCP tools registered with the server.
 *
 * Extracted from [CurrentMcpServer.run] so tests can register the exact same tool set the
 * production server exposes (see McpStreamableHttpTransportTest), avoiding a hard-coded tool
 * count or a drifting parallel list.
 */
internal fun buildMcpTools(): List<ToolDefinition> =
    listOf(
        // Phase 1: CRUD
        ManageItemsTool(),
        QueryItemsTool(),
        ManageNotesTool(),
        QueryNotesTool(),
        // Phase 2: Dependencies
        ManageDependenciesTool(),
        QueryDependenciesTool(),
        // Phase 2: Workflow
        AdvanceItemTool(),
        ClaimItemTool(),
        GetNextStatusTool(),
        GetNextItemTool(),
        GetBlockedItemsTool(),
        // Phase 3: Compound operations
        CompleteTreeTool(),
        CreateWorkTreeTool(),
        // Phase 3: Context
        GetContextTool(),
        // Per-root schema layering: transport-agnostic config sync
        ManageProjectConfigTool(YamlConfigDocumentParser),
        // Per-root plan document store: dual ingestion (REST PUT + MCP stash)
        ManagePlanDocumentsTool(),
        // Git-tracked rule text: read-only view over rule/<key> plan documents (A3, 840e700a)
        QueryRulesTool(),
    )

/**
 * Installs the MCP Streamable HTTP transport at `/mcp` plus the JSON + CORS plugins it shares with
 * the REST API.
 *
 * **Plugin-ordering contract (do not reorder):**
 * 0. [installHostAllowlist] — the Host-header allowlist guard (DNS-rebinding protection). Listed
 *    first for readability; it intercepts at the `Setup` phase and finishes rejected calls, so it
 *    precedes everything below and (in [installRestApiRoutes]) `ApiBearerAuth` regardless of
 *    install order, covering `/mcp`, every `/api/v1` route, and `/.well-known`.
 * 1. [ContentNegotiation] with `McpJson` is installed first so both `/mcp` and the `/api/v1` routes
 *    use the same JSON config (`explicitNulls=false`, `encodeDefaults=true`). `mcpStreamableHttp` detects CN
 *    is already installed and skips its own (logging a benign "already installed" warning).
 * 2. [CORS] — env-driven allowlist, locked-down default (empty = no cross-origin). With Host
 *    already pinned to the allowlist by step 0, a same-origin request here implies an allowlisted
 *    host too.
 * 3. [mcpStreamableHttp] mounts `/mcp` and **installs the Ktor `SSE` plugin itself** (SDK 0.12.0+).
 *    Therefore callers MUST NOT `install(SSE)` separately: a second install throws
 *    `DuplicatePluginException` at startup and the HTTP server never comes up. The SSE plugin
 *    installed here is reused by the `/api/v1/events` route in [installRestApiRoutes].
 *
 * **One Host guard, not two.** Since SDK 0.13.0 `mcpStreamableHttp` ships its own route-scoped
 * DNS-rebinding guard, on by default with a loopback-only allowlist. It is turned off here
 * (`enableDnsRebindingProtection = false`) because [installHostAllowlist] in step 0 is this
 * server's single Host authority: it honours `MCP_ALLOWED_HOSTS`, covers `/api/v1` and
 * `/.well-known` as well as `/mcp`, and its semantics are pinned by `DnsRebindingHostAllowlistTest`.
 * Leaving the SDK guard on would reject requests that allowlist admits: any configured
 * non-loopback host, a request with no `Host` header, and a host with one trailing dot. No
 * `Origin` protection is given up: in its default form (`allowedOrigins = null`) the SDK guard
 * checks `Host` only. A foreign `Origin` is rejected by [CORS] in step 2, on every request.
 *
 * Extracted from [CurrentMcpServer.runHttpTransport] so the exact production wiring is testable.
 */
internal fun Application.installMcpStreamableHttp(
    server: Server,
    appConfig: AppConfig = AppConfig.fromEnv(),
) {
    installHostAllowlist(appConfig)
    install(ContentNegotiation) {
        json(McpJson)
    }
    install(CORS) {
        configureCors(appConfig)
    }
    mcpStreamableHttp(enableDnsRebindingProtection = false) {
        server
    }
}

/**
 * Registers the REST API routes under `/api/v1` when the API is enabled. No-op when
 * [apiConfig] is [ApiAuthConfig.Disabled] (default-off) — `/mcp` still works without these routes.
 *
 * Must be called AFTER [installMcpStreamableHttp]: the SSE-backed `/api/v1/events` stream relies on
 * the SSE plugin that `mcpStreamableHttp` installed.
 */
internal fun Application.installRestApiRoutes(
    apiConfig: ApiAuthConfig,
    eventBus: ApiEventBus?,
    effectiveProvider: RepositoryProvider,
    apiTokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
    allowQueryToken: Boolean,
    serverName: String,
    serverVersion: String,
    actorAuthEnabled: Boolean,
    noteSchemaService: WorkItemSchemaService,
    toolContext: ToolExecutionContext,
    degradedModePolicy: DegradedModePolicy,
    idempotencyCache: IdempotencyCache,
    jwksVerifier: JwksApiVerifier? = null,
    appConfig: AppConfig = AppConfig.fromEnv(),
) {
    if (apiConfig is ApiAuthConfig.Disabled) return

    installRequestCorrelation()

    routing {
        // Authenticated routes under /api/v1 — auth plugin enforces bearer/JWKS
        route("/api/v1") {
            install(ApiBearerAuth) {
                authConfig = apiConfig
                // Use pre-loaded token entries (already loaded for event bus wiring at startup)
                tokenEntries = apiTokenEntries
                // Wire the REST JWT verifier so jwks mode actually authenticates. Without this the
                // plugin's Jwks branch finds a null verifier and 401s every request (the bug being
                // fixed). Null in bearer/disabled modes — the plugin never reads it there.
                this.jwksVerifier = jwksVerifier
                // ApiBearerAuth is an APPLICATION plugin — it intercepts every request, not just
                // /api/v1, REGARDLESS of where install() is textually called (registering a route
                // in a separate, sibling route("/api/v1") block does NOT scope it away — Ktor has
                // no route-scoping for plugins installed via createApplicationPlugin; only
                // createRouteScopedPlugin gets that). Two independent exemptions are needed via the
                // plugin's public-path bypass:
                // - /mcp: a separate protocol/transport that must remain reachable WITHOUT a REST
                //   bearer token, or enabling the REST API 401s MCP-over-HTTP clients (which send no
                //   REST token). See McpRestAuthBypassTest.
                // - /api/v1/events: the SSE endpoint performs its own real authentication inline (see
                //   sseInlineAuthPlugin in EventRoutes.kt, including the opt-in ?token= query-param
                //   path and unauthenticated-mode handling) and must not be pre-empted by
                //   ApiBearerAuth's header-only check. publicPaths matches on the request path with
                //   NO query string (see AuthenticationPlugin.kt), so this exact entry also matches
                //   "/api/v1/events?token=<...>".
                publicPaths = publicPaths + "/mcp" + "/api/v1/events"
            }
            serviceRoutes(
                repositoryProvider = effectiveProvider,
                serverName = serverName,
                serverVersion = serverVersion,
                actorAuthEnabled = actorAuthEnabled,
            )
            // Phase 3: read API — items, notes, dependencies, transitions, search
            itemRoutes(effectiveProvider)
            itemGateRoutes(effectiveProvider, toolContext.configResolver)
            noteRoutes(effectiveProvider)
            dependencyRoutes(effectiveProvider)
            transitionRoutes(
                effectiveProvider,
                redactAttribution = appConfig.apiRedactNoteAttribution,
            )
            searchRoutes(effectiveProvider)
            // Phase 4: config/schema-discovery + status-graph
            configRoutes(noteSchemaService)
            // Phase 5: write API — items, notes, dependencies, advance
            itemWriteRoutes(
                effectiveProvider,
                degradedModePolicy,
                idempotencyCache,
                toolContext.advanceServiceFactory(),
                toolContext.unitOfWork,
                warnOnClaimedAdvance = appConfig.apiWarnOnClaimedAdvance,
            )
            noteWriteRoutes(effectiveProvider, degradedModePolicy, idempotencyCache, toolContext.unitOfWork)
            dependencyWriteRoutes(effectiveProvider, degradedModePolicy, toolContext.unitOfWork)
            // Phase 1 (project-config-rest-endpoint): per-root config read/write/delete —
            // converges on the same ProjectConfigPushService the manage_project_config MCP tool uses.
            projectConfigRoutes(effectiveProvider, toolContext.unitOfWork)
            // Additive per-root effective (layered) config view — same LayeredConfig the MCP
            // configResolver already computes, surfaced as one REST resource (AR-42).
            effectiveConfigRoutes(effectiveProvider, toolContext.configResolver, noteSchemaService)
            // plan_documents store: per-root plan document read/write —
            // converges on the same PlanDocumentService the manage_plan_documents MCP tool uses.
            planDocumentRoutes(effectiveProvider, toolContext.unitOfWork)
            // Git-tracked rule text: read-only view over rule/<key> plan documents, converging on
            // the same RuleService the query_rules MCP tool uses (A3, 840e700a).
            ruleRoutes(effectiveProvider)
            // Operator resource-lease read + force-release — cross-project, server-wide (no rootId scope).
            resourceLeaseRoutes(effectiveProvider, toolContext.unitOfWork)
        }
        // Phase 6: real-time SSE event stream — registered in a separate, sibling
        // `route("/api/v1")` block from the one above. NOTE: this sibling-route registration does
        // NOT shield /api/v1/events from the ApiBearerAuth application plugin installed above --
        // that plugin intercepts every request regardless of route nesting (see the publicPaths
        // comment above). /api/v1/events is exempted via publicPaths instead, and the SSE route's
        // own inline pre-flight auth (sseInlineAuthPlugin, a route-scoped plugin) then performs the
        // real authentication, including the opt-in `?token=` query-param path that ApiBearerAuth
        // (header-only) would otherwise reject before our handler, and the unauthenticated-mode
        // bypass.
        if (eventBus != null) {
            route("/api/v1") {
                eventRoutes(
                    eventBus = eventBus,
                    tokenEntries = apiTokenEntries,
                    allowQueryToken = allowQueryToken,
                    jwksVerifier = jwksVerifier,
                    authCheckIntervalSeconds = appConfig.apiSseAuthCheckIntervalSeconds,
                    authConfig = apiConfig,
                    workItemRepository = effectiveProvider.workItemRepository(),
                    redactAttribution = appConfig.apiRedactNoteAttribution,
                )
            }
        }
        // Discovery endpoint — no auth, mounted at root
        wellKnownRoutes(serverName = serverName, serverVersion = serverVersion)
    }
}

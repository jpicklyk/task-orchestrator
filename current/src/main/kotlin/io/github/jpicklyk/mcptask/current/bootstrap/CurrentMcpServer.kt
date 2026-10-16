package io.github.jpicklyk.mcptask.current.bootstrap

import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.RequestDecoder
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.support.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStep
import io.github.jpicklyk.mcptask.current.application.upgrade.DataStepException
import io.github.jpicklyk.mcptask.current.infrastructure.ExpirySweeper
import io.github.jpicklyk.mcptask.current.infrastructure.IdempotencyPruner
import io.github.jpicklyk.mcptask.current.infrastructure.RetentionPruner
import io.github.jpicklyk.mcptask.current.infrastructure.UsageDailyFolder
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.health.ReadinessMarker
import io.github.jpicklyk.mcptask.current.infrastructure.identity.LOCAL_PRINCIPAL
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.cors.configureCors
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.installApiStatusPages
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.installRequestCorrelation
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.directorOpsRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.opsRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configSchemaRoute
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.noteResourceRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.resourceLeaseRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.serviceRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.wellKnownRoutes
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpAuthResolution
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpSessionPrincipals
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerSdkSessions
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installHostAllowlist
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.path
import io.ktor.server.response.respond
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Current (4.0) MCP Server implementation for the Task Orchestrator.
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
 *   [databaseManager] so the DB layer reads its config from the same snapshot. Required: only the entry point reads the process environment (`AppConfig.fromEnv()`); tests inject their own.
 * @param onBeforeTransportStart Test seam invoked as the first statement inside the try block
 *   wrapping the actual transport start (arg "stdio" or "http"), before the real start is
 *   attempted. No-op by default; production behaviour is unchanged.
 * @param stdioInput Test seam supplying the stdio transport's input stream; a lambda so constructing
 *   the server never touches System.in. Defaults to System.in.
 * @param stdioOutput Test seam supplying the stdio transport's output stream; a lambda so constructing
 *   the server never touches System.out. Defaults to System.out.
 * @param extraDataSteps Test seam: data steps registered beside the production steps. Empty in production.
 */
class CurrentMcpServer(
    private val version: String,
    private val shutdownCoordinator: ShutdownCoordinator = ShutdownCoordinator(),
    private val appConfig: AppConfig,
    internal val onBeforeTransportStart: (String) -> Unit = {},
    internal val stdioInput: () -> InputStream = { System.`in` },
    internal val stdioOutput: () -> OutputStream = { System.out },
    internal val extraDataSteps: List<DataStep> = emptyList()
) {
    private val logger = LoggerFactory.getLogger(CurrentMcpServer::class.java)

    // Build the AppConfig snapshot FIRST (constructor arg above), then DatabaseManager from it:
    // DatabaseManager is constructed early (a field, before run()) and reads DB env, so the snapshot
    // must exist before it.
    private val databaseManager = DatabaseManager(appConfig = appConfig)
    private var mcpSdkServers: List<Server> = emptyList()

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
            logger.info("Initializing Current (4.0) MCP server...")

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

            // MCP_TOOL_PROFILE is validated on both transports (fail closed, like an unknown MCP_TRANSPORT) but
            // applied to stdio only: HTTP serves every profile on its own path. Checked before anything is
            // opened, so a bad value never touches the database or writes the readiness marker.
            val toolProfile = ToolProfile.parse(appConfig.mcpToolProfileRaw)
            if (toolProfile == null) {
                val detail =
                    "Unknown MCP_TOOL_PROFILE: '${appConfig.mcpToolProfileRaw}'. Valid values: " +
                        ToolProfile.entries.joinToString(", ") { it.wire }
                logger.error(detail)
                return@runBlocking Failed(Reason.UNKNOWN_TOOL_PROFILE, detail)
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
                ServerComposition(appConfig, databaseManager, shutdownCoordinator, extraDataSteps = extraDataSteps).build()
            // How /mcp establishes its caller (R14). HTTP with the REST API disabled serves /mcp only under the
            // MCP_ALLOW_UNAUTHENTICATED=true opt-in (V9); otherwise the start fails here, before any background worker,
            // transport or readiness marker exists.
            val mcpAuth =
                when (
                    val resolution =
                        McpAuthMode.resolve(
                            appConfig.mcpTransport,
                            composition.apiWiring.apiConfig,
                            composition.apiWiring.tokenEntries,
                            composition.apiWiring.jwksVerifier,
                            // JWT scope.root_ids anchors must be depth-0 roots (R15), on /mcp exactly as on /api/v1.
                            { ids -> ScopeGuard.nonRoots(ids, composition.apiWiring.effectiveProvider.workItemRepository()) },
                            { Instant.now() },
                            appConfig.mcpAllowUnauthenticated
                        )
                ) {
                    is McpAuthResolution.Refused -> {
                        logger.error(resolution.detail)
                        return@runBlocking Failed(Reason.MCP_AUTH, resolution.detail)
                    }
                    is McpAuthResolution.Resolved -> resolution.mode
                }
            // Data steps run after Flyway and before anything else touches the data: no background writer, no
            // transport and no readiness marker exist yet. A failed step or an unapplied once step fails the start.
            try {
                composition.dataStepRunner.run()
            } catch (e: DataStepException) {
                logger.error("Data steps failed: {}", e.message, e)
                return@runBlocking Failed(Reason.DATA_STEPS, "Data steps failed: ${e.message}")
            }
            val toolContext = composition.toolContext
            val apiWiring = composition.apiWiring
            val serverConfig = composition.serverConfig

            // Expired idempotency records are pruned once now and then hourly. Registered AFTER Close Database
            // so the LIFO drain stops the pruner first.
            val idempotencyPruner = IdempotencyPruner(composition.unitOfWork)
            idempotencyPruner.start()
            shutdownCoordinator.addCleanupAction("Stop Idempotency Pruner") {
                runBlocking { idempotencyPruner.stop() }
            }

            // Rows older than the RETENTION_* windows are pruned in the background, first pass after readiness, then
            // hourly. Each pass first folds the completed days of call_log into usage_daily. Built and registered here (AFTER
            // Close Database, so the LIFO drain stops it first) but launched
            // only by the transport runner once the readiness marker is written: a start that fails before that
            // (unknown transport, bind failure, marker write) never has a pass in flight while run() closes the pools.
            val retentionPruner =
                RetentionPruner(
                    composition.unitOfWork,
                    appConfig.retention,
                    appConfig.apiSseBufferSize,
                    folder = UsageDailyFolder(composition.unitOfWork)
                )
            shutdownCoordinator.addCleanupAction("Stop Retention Pruner") {
                runBlocking { retentionPruner.stop() }
            }

            // Expired claims are reported and lapsed leases removed once now and then hourly. Registered after the
            // pruner (and so after Close Database): the LIFO drain stops it before the database closes.
            val expirySweeper = ExpirySweeper(toolContext.claimService)
            expirySweeper.start()
            shutdownCoordinator.addCleanupAction("Stop Expiry Sweeper") {
                runBlocking { expirySweeper.stop() }
            }

            // The call log is written off the request path in batches. Registered AFTER the pruner (and so after
            // Close Database), so the LIFO drain runs: stop HTTP, close MCP, drain this writer, stop the pruner, close
            // the database. In-flight calls finish before the drain, and the drain finishes before the database closes.
            // The writer also adds each batch's token estimates into its runs' observed_tokens (R27).
            val callLogWriter =
                CallLogWriter(
                    composition.unitOfWork,
                    SqliteCallLogStore(databaseManager),
                    runStore = toolContext.repositoryProvider.runStore()
                )
            callLogWriter.start()
            shutdownCoordinator.addCleanupAction("Stop Call Log Writer") {
                runBlocking { callLogWriter.stop() }
            }

            val serverName = appConfig.mcpServerName

            // The tools (ToolSpec registry), served by profile.
            val registry = ToolRegistry(buildToolSpecs())
            // ONE dispatcher serves MCP and REST (`POST /api/v1/ops/{tool}`), so both produce the same bytes.
            val toolDispatcher = buildToolDispatcher(toolContext)
            // HTTP: a tool call runs as the principal its session is bound to (McpBearerAuth binds it). Stdio: the local principal.
            val sdkSessions = ServerSdkSessions()
            val sessionPrincipals = McpSessionPrincipals(appConfig.apiRedactNoteAttribution, sdkSessions)
            val stdioAccess = LOCAL_PRINCIPAL.toCallAccess(appConfig.apiRedactNoteAttribution)
            val operationAdapter =
                McpOperationAdapter(
                    toolDispatcher,
                    callLog = callLogWriter,
                    access = if (appConfig.mcpTransport == "http") sessionPrincipals::access else { _ -> stdioAccess }
                )

            fun serverFor(profile: ToolProfile): Server =
                buildProfileServer(profile, serverName, version, registry, operationAdapter).also(sdkSessions::serve)
            logger.info(
                "Tool profiles: orchestration={} tools, knowledge={} tools, director={} tools",
                registry.visible(ToolProfile.ORCHESTRATION).size,
                registry.visible(ToolProfile.KNOWLEDGE).size,
                registry.visible(ToolProfile.DIRECTOR).size
            )

            // Transport dispatch
            val transportType = appConfig.mcpTransport
            val outcome: StartupOutcome =
                when (transportType) {
                    "stdio" -> {
                        // stdio transport does NOT serve the REST/SSE API. Every write still records
                        // its events rows (the decorator is always installed); with no HTTP server
                        // there is simply no SSE subscriber, and another process serving
                        // MCP_TRANSPORT=http over the same database streams these rows.
                        if (apiWiring.eventBus != null) {
                            logger.info(
                                "API config is enabled but MCP_TRANSPORT=stdio: the SSE endpoint is " +
                                    "only served under MCP_TRANSPORT=http. Events rows are still " +
                                    "recorded; nothing streams from this process."
                            )
                        }
                        val server = serverFor(toolProfile)
                        mcpSdkServers = listOf(server)
                        runStdioTransport(server, serverName, readinessMarker, onReady = retentionPruner::launch)
                    }
                    "http" -> {
                        val server = serverFor(ToolProfile.ORCHESTRATION)
                        val knowledgeServer = serverFor(ToolProfile.KNOWLEDGE)
                        val directorServer = serverFor(ToolProfile.DIRECTOR)
                        mcpSdkServers = listOf(server, knowledgeServer, directorServer)
                        runHttpTransport(
                            server,
                            knowledgeServer,
                            directorServer,
                            serverName,
                            apiWiring,
                            serverConfig,
                            toolContext,
                            readinessMarker,
                            mcpAuth,
                            sessionPrincipals,
                            callLogWriter,
                            registry,
                            toolDispatcher,
                            onReady = retentionPruner::launch
                        )
                    }
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
        mcpSdkServers.forEach { it.close() }
    }

    /** ONE cleanup action that closes every built [servers] (the name is pinned by ShutdownDrainOrderTest). */
    private fun registerCommonCleanup(servers: List<Server>) {
        shutdownCoordinator.addCleanupAction("Close MCP Server") {
            runBlocking { servers.forEach { it.close() } }
        }
    }

    private suspend fun runStdioTransport(
        server: Server,
        serverName: String,
        readinessMarker: ReadinessMarker,
        onReady: () -> Unit = {}
    ): StartupOutcome {
        logger.info("Starting MCP server with stdio transport...")

        val transport =
            StdioServerTransport(
                inputStream = stdioInput().asSource().buffered(),
                outputStream = stdioOutput().asSink().buffered()
            )

        registerCommonCleanup(listOf(server))

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
        // Serving: only now start background work a failed start must not leave running (the retention pruner).
        onReady()

        logger.info("Current (4.0) MCP server running as '$serverName' v$version over stdio")
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
        knowledgeServer: Server,
        directorServer: Server,
        serverName: String,
        apiWiring: ApiWiring,
        serverConfig: ServerConfigSource,
        toolContext: ToolExecutionContext,
        readinessMarker: ReadinessMarker,
        mcpAuth: McpAuthMode,
        sessionPrincipals: McpSessionPrincipals,
        callLog: CallLogSink = CallLogSink.NONE,
        toolRegistry: ToolRegistry = ToolRegistry(buildToolSpecs()),
        toolDispatcher: ToolDispatcher = buildToolDispatcher(toolContext),
        onReady: () -> Unit = {},
    ): StartupOutcome {
        val host = appConfig.mcpHttpHost
        val port = appConfig.mcpHttpPort
        logger.info("Starting MCP server with HTTP transport on $host:$port/mcp ...")

        // SECURITY: /mcp authenticates exactly like /api/v1 when the API runs a credential mode (McpBearerAuth over the
        // same resolver). The two local modes below serve every caller as the local principal (ADMIN, unrestricted):
        // logged loudly so operators cannot miss it.
        if (apiWiring.apiConfig is ApiAuthConfig.Unauthenticated) {
            logger.warn(
                "SECURITY: API_AUTH_MODE=none + API_ALLOW_UNAUTHENTICATED=true  -  the /api/v1 REST API and /mcp are " +
                    "UNAUTHENTICATED. Anyone who can reach {}:{} has full read/write/delete access to all config and " +
                    "data as the local principal. This MUST be fronted by a reverse proxy / mTLS / private network, or " +
                    "bound to 127.0.0.1 for a loopback-only local run  -  do NOT expose this port to untrusted callers.",
                host,
                port,
            )
        } else if (mcpAuth is McpAuthMode.Local) {
            logger.warn(
                "SECURITY: MCP_ALLOW_UNAUTHENTICATED=true with the REST API disabled  -  /mcp is UNAUTHENTICATED. " +
                    "Anyone who can reach {}:{} has full read/write/delete access to all MCP tools as the local " +
                    "principal. Front it with a reverse proxy, mTLS, or a private network, or bind it to 127.0.0.1 " +
                    "(MCP_HTTP_HOST) for a loopback-only local run.",
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

        // Phase 6: reuse the API wiring resolved ONCE in run() — the SAME bus and provider already
        // feeding the MCP tool context. Do NOT re-resolve here, or the SSE route
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
                installMcpStreamableHttp(server, appConfig, knowledgeServer, directorServer, mcpAuth, sessionPrincipals)
                installRestApiRoutes(
                    apiConfig = apiConfig,
                    eventBus = eventBus,
                    effectiveProvider = effectiveProvider,
                    apiTokenEntries = apiTokenEntries,
                    allowQueryToken = allowQueryToken,
                    serverName = serverName,
                    serverVersion = version,
                    serverConfig = serverConfig,
                    toolContext = toolContext,
                    jwksVerifier = jwksVerifier,
                    appConfig = appConfig,
                    callLog = callLog,
                    toolRegistry = toolRegistry,
                    toolDispatcher = toolDispatcher,
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
        registerCommonCleanup(listOf(server, knowledgeServer, directorServer))
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
        // Serving: only now start background work a failed start must not leave running (the retention pruner).
        onReady()

        logger.info(
            "Current (4.0) MCP server running as '$serverName' v$version via HTTP on $host:$port/mcp (+ /mcp/knowledge, /mcp/director)"
        )
        try {
            done.join()
        } catch (e: Exception) {
            logger.error("Error in HTTP server: ${e.message}", e)
        } finally {
            readinessMarker.clear()
        }
        return Started
    }
}

/**
 * The [ServerCapabilities] this server advertises in `initialize` results: `tools` (with
 * `listChanged`) and `logging` — no `prompts` or `resources`. Extracted from [CurrentMcpServer]'s
 * private `configureServer` so a test can assert on it directly.
 *
 * `logging` is still declared although no tool path emits `notifications/message` any more (the 3.x
 * adapter that sent them on validation, config-unavailable and internal errors was removed by Q32;
 * [McpOperationAdapter] sends none). Dropping it would be a client-visible capability change, left
 * to a later item. `prompts`/`resources` are removed because no `addPrompt`/`addResource` exists
 * anywhere in this server — they were advertised-but-empty surfaces.
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
 * The `initialize` `instructions` of [profile]'s Server: one sentence naming the profile and `describe`, no tool-name
 * list (plan section 3.1). The director profile's sentence is shorter: its tools are reads only. The orchestration
 * profile adds the one place `seatToken` is documented (R22): a top-level field of every call made under a run seat,
 * on every tool, unadvertised in the per-tool schemas.
 */
internal fun profileInstructions(profile: ToolProfile): String =
    when (profile) {
        ToolProfile.DIRECTOR ->
            "Task Orchestrator MCP server, director tool profile: call any tool with describe: true for its reads."
        ToolProfile.KNOWLEDGE ->
            "Task Orchestrator MCP server, ${profile.wire} tool profile: call any tool with describe: true " +
                "for its selects, operations and parameters."
        ToolProfile.ORCHESTRATION ->
            "Task Orchestrator MCP server, ${profile.wire} tool profile: call any tool with describe: true " +
                "for its selects, operations and parameters. Under a run seat, pass its token as the top-level " +
                "seatToken of every call."
    }

/**
 * Builds the MCP [Server] of [profile] with every tool registered (all before any session connects, see
 * [productionServerCapabilities]): the [registry] tools visible in [profile].
 */
internal fun buildProfileServer(
    profile: ToolProfile,
    serverName: String,
    version: String,
    registry: ToolRegistry,
    operationAdapter: McpOperationAdapter
): Server {
    val server =
        Server(
            serverInfo = Implementation(name = serverName, version = version),
            options = ServerOptions(capabilities = productionServerCapabilities()),
            instructions = profileInstructions(profile)
        )
    operationAdapter.register(server, registry, profile)
    return server
}

/** The one [ToolDispatcher] over [toolContext], shared by the MCP adapter and `POST /api/v1/ops/{tool}`. */
internal fun buildToolDispatcher(toolContext: ToolExecutionContext): ToolDispatcher =
    ToolDispatcher(
        RequestDecoder(),
        BatchRunner(toolContext.unitOfWork, toolContext.idempotency),
        toolContext
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
 * 3. [McpBearerAuth]  -  route-scoped on the MCP mount (`/mcp`, or `/mcp/{profile?}` with profile servers), installed
 *    before [mcpStreamableHttp] on the same route node (Ktor merges the two), so every `/mcp` request is
 *    authenticated per [mcpAuth] and bound to its session in [sessionPrincipals] before the SDK sees it. A refusal is
 *    the 4.0 error envelope. In [installRestApiRoutes], `ApiBearerAuth` delegates `/mcp` to it.
 * 4. [mcpStreamableHttp] mounts `/mcp` and **installs the Ktor `SSE` plugin itself** (SDK 0.12.0+).
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
 * Extracted from [CurrentMcpServer.runHttpTransport] so the exact production wiring is testable. Production passes
 * [mcpAuth] and [sessionPrincipals] explicitly (the latter shared with the MCP adapter); [mcpAuth] has no default (a forgotten
 * argument must not fail open to local mode); the default binding table serves tests that exercise the transport alone.
 */
internal fun Application.installMcpStreamableHttp(
    server: Server,
    appConfig: AppConfig,
    knowledgeServer: Server? = null,
    directorServer: Server? = null,
    mcpAuth: McpAuthMode,
    sessionPrincipals: McpSessionPrincipals =
        McpSessionPrincipals(
            appConfig.apiRedactNoteAttribution,
            ServerSdkSessions().apply { listOfNotNull(server, knowledgeServer, directorServer).forEach(::serve) }
        ),
) {
    installHostAllowlist(appConfig)
    install(ContentNegotiation) {
        json(McpJson)
    }
    install(CORS) {
        configureCors(appConfig)
    }
    // The exact, case-sensitive sub-paths that are mounted: a null profile Server is not mounted (its path is a 404).
    val profileServers =
        buildMap {
            if (knowledgeServer != null) put(MCP_KNOWLEDGE_PATH, knowledgeServer)
            if (directorServer != null) put(MCP_DIRECTOR_PATH, directorServer)
        }

    fun authenticate(path: String) {
        routing {
            route(path) {
                install(McpBearerAuth) {
                    mode = mcpAuth
                    sessions = sessionPrincipals
                    directorPath = MCP_DIRECTOR_PATH
                }
            }
        }
    }
    if (profileServers.isEmpty()) {
        authenticate(MCP_PATH)
        mcpStreamableHttp(enableDnsRebindingProtection = false) {
            server
        }
        return
    }
    // Several profiles, ONE mount: a second mcpStreamableHttp call would install(SSE) again and throw
    // DuplicatePluginException. The route takes an optional segment and the Server factory (called only for a new
    // session) picks the profile from the exact path, so a session keeps the profile it was initialised with.
    // Any other /mcp/<x> is a 404 at the Setup phase, right after the Host guard: it never reaches the mount (so it
    // never falls back to the orchestration Server) and never reaches ApiBearerAuth (so it is not a 401).
    intercept(ApplicationCallPipeline.Setup) {
        val path = call.request.path()
        if (path.startsWith("/mcp/") && path !in profileServers && path != "/mcp/") {
            call.respond(HttpStatusCode.NotFound)
            finish()
        }
    }
    authenticate(MCP_PROFILE_ROUTE)
    mcpStreamableHttp(path = MCP_PROFILE_ROUTE, enableDnsRebindingProtection = false) {
        profileServers[call.request.path()] ?: server
    }
}

/** The MCP endpoint path (the SDK's default mount). */
internal const val MCP_PATH = "/mcp"

/** The route of the MCP mount when profile servers are served: `/mcp` plus one optional profile segment. */
private const val MCP_PROFILE_ROUTE = "/mcp/{profile?}"

/** The exact, case-sensitive path of the knowledge-profile MCP endpoint. */
internal const val MCP_KNOWLEDGE_PATH = "/mcp/knowledge"

/** The exact, case-sensitive path of the director-profile MCP endpoint. */
internal const val MCP_DIRECTOR_PATH = "/mcp/director"

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
    @Suppress("UNUSED_PARAMETER") serverConfig: ServerConfigSource,
    toolContext: ToolExecutionContext,
    jwksVerifier: JwksApiVerifier? = null,
    appConfig: AppConfig,
    callLog: CallLogSink = CallLogSink.NONE,
    toolRegistry: ToolRegistry = ToolRegistry(buildToolSpecs()),
    toolDispatcher: ToolDispatcher = buildToolDispatcher(toolContext),
) {
    if (apiConfig is ApiAuthConfig.Disabled) return

    val nonRootAnchors: suspend (Set<UUID>) -> Set<UUID> = { ids -> ScopeGuard.nonRoots(ids, effectiveProvider.workItemRepository()) }

    // The REST safety net: an uncaught /api/v1 exception becomes a 500 `internal` body, never exception text.
    // Installed only here (API enabled) and scoped to /api/v1 paths, so API-disabled deployments are untouched.
    installApiStatusPages()
    installRequestCorrelation(callLog, toolContext.clock)

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
                // JWT scope.root_ids anchors must be depth-0 roots (R15); bearer anchors are validated at startup.
                this.nonRootAnchors = nonRootAnchors
                // ApiBearerAuth is an APPLICATION plugin — it intercepts every request, not just
                // /api/v1, REGARDLESS of where install() is textually called (registering a route
                // in a separate, sibling route("/api/v1") block does NOT scope it away — Ktor has
                // no route-scoping for plugins installed via createApplicationPlugin; only
                // createRouteScopedPlugin gets that). Two paths have another authority:
                // - /mcp (and /mcp/knowledge, /mcp/director): McpBearerAuth, route-scoped on the MCP
                //   mount, authenticates them with the same resolver (installMcpStreamableHttp).
                //   They are DELEGATED, never public: this plugin neither checks nor attaches a
                //   principal there, so /mcp is never unauthenticated by omission and never
                //   authenticated twice.
                // - /api/v1/events: the SSE endpoint performs its own real authentication inline (see
                //   sseInlineAuthPlugin in EventRoutes.kt, including the opt-in ?token= query-param
                //   path and unauthenticated-mode handling) and must not be pre-empted by
                //   ApiBearerAuth's header-only check. publicPaths matches on the request path with
                //   NO query string (see AuthenticationPlugin.kt), so this exact entry also matches
                //   "/api/v1/events?token=<...>".
                delegatedPrefixes = setOf(MCP_PATH)
                publicPaths = publicPaths + "/api/v1/events"
            }
            serviceRoutes(
                repositoryProvider = effectiveProvider,
                serverName = serverName,
                serverVersion = serverVersion,
                verifier =
                    when (apiConfig) {
                        is ApiAuthConfig.Jwks -> "jwks"
                        is ApiAuthConfig.Bearer -> "bearer"
                        else -> "none"
                    },
                maxStale = (apiConfig as? ApiAuthConfig.Jwks)?.maxStaleSeconds,
                configService = toolContext.configService,
            )
            // Phase 3: read API — items (through the items_read select), notes, dependencies
            itemRoutes(toolRegistry, toolDispatcher, redactNoteAttribution = appConfig.apiRedactNoteAttribution)
            // Note resource reads: thin translations to notes_read over the same dispatcher /ops uses (writes are /ops/notes_write).
            noteResourceRoutes(toolRegistry, toolDispatcher, redactNoteAttribution = appConfig.apiRedactNoteAttribution)
            // The published config JSON Schema; every other config read and write is an op of the `config` tool.
            configSchemaRoute()
            // Operator resource-lease read + force-release — cross-project, server-wide (no rootId scope).
            resourceLeaseRoutes(toolContext.claimService)
            // Canonical RPC route over the tool registry (Q6): per-op capability and scope are enforced
            // by the dispatcher from the bearer principal; mounted last so it never shadows a resource route.
            opsRoutes(toolRegistry, toolDispatcher, redactNoteAttribution = appConfig.apiRedactNoteAttribution)
            // The director tool profile over REST; the only path a `profile: director` credential may call.
            directorOpsRoutes(toolRegistry, toolDispatcher, redactNoteAttribution = appConfig.apiRedactNoteAttribution)
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
                    nonRootAnchors = nonRootAnchors,
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

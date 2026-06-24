package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
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
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.cors.configureCors
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.dependencyRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.dependencyWriteRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemWriteRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.noteRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.noteWriteRoutes
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Current (v3) MCP Server implementation for the Task Orchestrator.
 *
 * Initializes database, configures MCP SDK server, registers tools, and starts the transport.
 * Transport is selected via the MCP_TRANSPORT environment variable:
 *   - "stdio" (default) — standard input/output
 *   - "http" — Ktor CIO HTTP server with Streamable HTTP transport
 *
 * @param version The server version string.
 * @param shutdownCoordinator Optional shutdown coordinator for graceful shutdown.
 * @param appConfig Typed environment snapshot, read ONCE at construction. Built before
 *   [databaseManager] so the DB layer reads its config from the same snapshot. Injectable for tests.
 */
class CurrentMcpServer(
    private val version: String,
    private val shutdownCoordinator: ShutdownCoordinator? = null,
    private val appConfig: AppConfig = AppConfig.fromEnv()
) {
    private val logger = LoggerFactory.getLogger(CurrentMcpServer::class.java)

    // Build the AppConfig snapshot FIRST (constructor arg above), then DatabaseManager from it:
    // DatabaseManager is constructed early (a field, before run()) and reads DB env, so the snapshot
    // must exist before it.
    private val databaseManager = DatabaseManager(appConfig = appConfig)
    private var mcpSdkServer: Server? = null

    /**
     * Configures and runs the MCP server.
     * This method will block until the server is closed.
     */
    fun run() =
        runBlocking {
            logger.info("Initializing Current (v3) MCP server...")

            // Initialize database (DatabaseManager already holds the AppConfig snapshot)
            val dbPath = appConfig.databasePath
            if (!databaseManager.initialize(dbPath)) {
                logger.error("Failed to initialize database at: $dbPath")
                return@runBlocking
            }
            if (!databaseManager.updateSchema()) {
                logger.error("Failed to update database schema")
                return@runBlocking
            }
            logger.info("Database initialized at: $dbPath")

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
            val mcpLoggingService = composition.mcpLoggingService

            // Build tool list (shared with tests via buildMcpTools())
            val tools = buildMcpTools()

            // Configure MCP server
            val serverName = appConfig.mcpServerName
            val toolNames = tools.joinToString(", ") { it.name }
            val server = configureServer(serverName, tools.size, toolNames)
            mcpSdkServer = server

            // Bind MCP logging service to the server (must happen before clients connect)
            mcpLoggingService.bindServer(server)

            // Register MCP tools
            val adapter = McpToolAdapter()
            adapter.registerToolsWithServer(server, tools, toolContext)
            logger.info("Registered ${tools.size} MCP tools")

            val toolCount = tools.size

            // Lifecycle logging — these are no-ops until a client connects (sessions map is empty at this point)
            mcpLoggingService.info("mcp-task-orchestrator.server", "Database initialized at: $dbPath")
            mcpLoggingService.info("mcp-task-orchestrator.server", "Server ready with $toolCount tools")

            // Transport dispatch
            val transportType = appConfig.mcpTransport
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
                    runStdioTransport(server, serverName, toolCount)
                }
                "http" ->
                    runHttpTransport(
                        server,
                        serverName,
                        toolCount,
                        apiWiring,
                        noteSchemaService,
                        degradedModePolicy,
                        idempotencyCache,
                        composition.actorAuthEnabled
                    )
                else -> logger.error("Unknown MCP_TRANSPORT: '$transportType'. Valid values: stdio, http")
            }

            logger.info("MCP server shut down")
        }

    /**
     * Closes the MCP server. Can be called from external shutdown triggers.
     */
    suspend fun close() {
        mcpSdkServer?.close()
    }

    private fun registerCommonCleanup(server: Server) {
        shutdownCoordinator?.addCleanupAction("Close MCP Server") {
            runBlocking { server.close() }
        }
        shutdownCoordinator?.addCleanupAction("Close Database") {
            databaseManager.shutdown()
        }
    }

    private suspend fun runStdioTransport(
        server: Server,
        serverName: String,
        toolCount: Int
    ) {
        logger.info("Starting MCP server with stdio transport...")

        val transport =
            StdioServerTransport(
                inputStream = System.`in`.asSource().buffered(),
                outputStream = System.out.asSink().buffered()
            )

        registerCommonCleanup(server)

        val done = Job()
        server.onClose {
            logger.info("Server closed")
            done.complete()
            if (shutdownCoordinator == null) databaseManager.shutdown()
        }

        try {
            server.createSession(transport)
            logger.info("Current (v3) MCP server running as '$serverName' v$version with $toolCount tools")
            done.join()
        } catch (e: Exception) {
            logger.error("Error in stdio server connection: ${e.message}", e)
        }
    }

    private suspend fun runHttpTransport(
        server: Server,
        serverName: String,
        toolCount: Int,
        apiWiring: ApiWiring,
        noteSchemaService: WorkItemSchemaService,
        degradedModePolicy: DegradedModePolicy,
        idempotencyCache: IdempotencyCache,
        actorAuthEnabled: Boolean,
    ) {
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
                    degradedModePolicy = degradedModePolicy,
                    idempotencyCache = idempotencyCache,
                    jwksVerifier = jwksVerifier,
                    appConfig = appConfig,
                )
            }

        shutdownCoordinator?.addCleanupAction("Stop HTTP Server") {
            ktorServer.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
            done.complete()
        }
        registerCommonCleanup(server)

        server.onClose {
            logger.info("Server closed")
            if (shutdownCoordinator == null) databaseManager.shutdown()
        }

        if (shutdownCoordinator == null) {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    ktorServer.stop(1000, 5000)
                    done.complete()
                    databaseManager.shutdown()
                }
            )
        }

        try {
            ktorServer.start(wait = false)
            logger.info("Current (v3) MCP server running as '$serverName' v$version via HTTP on $host:$port/mcp with $toolCount tools")
            done.join()
        } catch (e: Exception) {
            logger.error("Error in HTTP server: ${e.message}", e)
        }
    }

    /**
     * Configures the MCP SDK server with capabilities for tools, prompts, and resources.
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
                    capabilities =
                        ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = true),
                            prompts = ServerCapabilities.Prompts(listChanged = true),
                            resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                            logging = JsonObject(emptyMap())
                        )
                ),
            instructions = "Current (v3) MCP Task Orchestrator — $toolCount tools: $toolNames"
        )
}

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
    )

/**
 * Installs the MCP Streamable HTTP transport at `/mcp` plus the JSON + CORS plugins it shares with
 * the REST API.
 *
 * **Plugin-ordering contract (do not reorder):**
 * 1. [ContentNegotiation] with `McpJson` is installed first so both `/mcp` and the `/api/v1` routes
 *    use the same JSON config (`explicitNulls=false`, `encodeDefaults=true`). `mcpStreamableHttp` detects CN
 *    is already installed and skips its own (logging a benign "already installed" warning).
 * 2. [CORS] — env-driven allowlist, locked-down default (empty = no cross-origin).
 * 3. [mcpStreamableHttp] mounts `/mcp` and **installs the Ktor `SSE` plugin itself** (SDK 0.12.0).
 *    Therefore callers MUST NOT `install(SSE)` separately: a second install throws
 *    `DuplicatePluginException` at startup and the HTTP server never comes up. The SSE plugin
 *    installed here is reused by the `/api/v1/events` route in [installRestApiRoutes].
 *
 * Extracted from [CurrentMcpServer.runHttpTransport] so the exact production wiring is testable.
 */
internal fun Application.installMcpStreamableHttp(
    server: Server,
    appConfig: AppConfig = AppConfig.fromEnv(),
) {
    install(ContentNegotiation) {
        json(McpJson)
    }
    install(CORS) {
        configureCors(appConfig)
    }
    mcpStreamableHttp {
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
    degradedModePolicy: DegradedModePolicy,
    idempotencyCache: IdempotencyCache,
    jwksVerifier: JwksApiVerifier? = null,
    appConfig: AppConfig = AppConfig.fromEnv(),
) {
    if (apiConfig is ApiAuthConfig.Disabled) return

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
                // /api/v1. The MCP Streamable HTTP endpoint (/mcp) is a separate protocol with its
                // own transport and must remain reachable WITHOUT a REST bearer token. Exempt it via
                // the plugin's public-path bypass; otherwise enabling the REST API 401s /mcp and
                // breaks MCP-over-HTTP clients (which send no REST token). See McpRestAuthBypassTest.
                publicPaths = publicPaths + "/mcp"
            }
            serviceRoutes(
                repositoryProvider = effectiveProvider,
                serverName = serverName,
                serverVersion = serverVersion,
                actorAuthEnabled = actorAuthEnabled,
            )
            // Phase 3: read API — items, notes, dependencies, transitions, search
            itemRoutes(effectiveProvider)
            noteRoutes(effectiveProvider)
            dependencyRoutes(effectiveProvider)
            transitionRoutes(
                effectiveProvider,
                redactAttribution = appConfig.apiRedactNoteAttribution,
                redactProof = appConfig.apiRedactActorProof,
            )
            searchRoutes(effectiveProvider)
            // Phase 4: config/schema-discovery + status-graph
            configRoutes(noteSchemaService)
            // Phase 5: write API — items, notes, dependencies, advance
            itemWriteRoutes(
                effectiveProvider,
                degradedModePolicy,
                idempotencyCache,
                noteSchemaService,
                warnOnClaimedAdvance = appConfig.apiWarnOnClaimedAdvance,
            )
            noteWriteRoutes(effectiveProvider, degradedModePolicy, idempotencyCache)
            dependencyWriteRoutes(effectiveProvider, degradedModePolicy)
        }
        // Phase 6: real-time SSE event stream — registered OUTSIDE the ApiBearerAuth block (as a
        // sibling `route("/api/v1/events")`) so the ApiBearerAuth plugin does NOT intercept it. The
        // SSE route does its own inline pre-flight auth, which additionally supports the opt-in
        // `?token=` query-param path that ApiBearerAuth (header-only) would reject before our handler.
        if (eventBus != null) {
            route("/api/v1") {
                eventRoutes(
                    eventBus = eventBus,
                    tokenEntries = apiTokenEntries,
                    allowQueryToken = allowQueryToken,
                    jwksVerifier = jwksVerifier,
                    authCheckIntervalSeconds = appConfig.apiSseAuthCheckIntervalSeconds,
                )
            }
        }
        // Discovery endpoint — no auth, mounted at root
        wellKnownRoutes(serverName = serverName, serverVersion = serverVersion)
    }
}

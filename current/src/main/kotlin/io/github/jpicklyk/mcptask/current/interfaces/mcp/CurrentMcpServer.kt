package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.QueryDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.QueryNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetBlockedItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetNextItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetNextStatusTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlNoteSchemaService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
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
 */
class CurrentMcpServer(
    private val version: String,
    private val shutdownCoordinator: ShutdownCoordinator? = null
) {
    private val logger = LoggerFactory.getLogger(CurrentMcpServer::class.java)
    private val databaseManager = DatabaseManager()
    private var mcpSdkServer: Server? = null

    /**
     * Configures and runs the MCP server.
     * This method will block until the server is closed.
     */
    fun run() = runBlocking {
        logger.info("Initializing Current (v3) MCP server...")

        // Initialize database
        val dbPath = DatabaseConfig.databasePath
        if (!databaseManager.initialize(dbPath)) {
            logger.error("Failed to initialize database at: $dbPath")
            return@runBlocking
        }
        if (!databaseManager.updateSchema()) {
            logger.error("Failed to update database schema")
            return@runBlocking
        }
        logger.info("Database initialized at: $dbPath")

        // Initialize repository provider and tool context
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        val noteSchemaService = YamlNoteSchemaService()
        val toolContext = ToolExecutionContext(repositoryProvider, noteSchemaService)
        logger.info("Repository provider and tool context initialized")

        // Configure MCP server
        val serverName = System.getenv("MCP_SERVER_NAME") ?: "mcp-task-orchestrator-current"
        val server = configureServer(serverName)
        mcpSdkServer = server

        // Register MCP tools
        val adapter = McpToolAdapter()
        val tools = listOf(
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
            GetNextStatusTool(),
            GetNextItemTool(),
            GetBlockedItemsTool(),
            // Phase 3: Compound operations
            CompleteTreeTool(),
            CreateWorkTreeTool(),
            // Phase 3: Context
            GetContextTool()
        )
        adapter.registerToolsWithServer(server, tools, toolContext)
        logger.info("Registered ${tools.size} MCP tools")

        val toolCount = tools.size

        // Transport dispatch
        val transportType = System.getenv("MCP_TRANSPORT")?.lowercase() ?: "stdio"
        when (transportType) {
            "stdio" -> runStdioTransport(server, serverName, toolCount)
            "http"  -> runHttpTransport(server, serverName, toolCount)
            else    -> logger.error("Unknown MCP_TRANSPORT: '$transportType'. Valid values: stdio, http")
        }

        logger.info("MCP server shut down")
    }

    /**
     * Closes the MCP server. Can be called from external shutdown triggers.
     */
    suspend fun close() {
        mcpSdkServer?.close()
    }

    private suspend fun runStdioTransport(server: Server, serverName: String, toolCount: Int) {
        logger.info("Starting MCP server with stdio transport...")

        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered()
        )

        shutdownCoordinator?.addCleanupAction("Close MCP Server") {
            runBlocking { server.close() }
        }
        shutdownCoordinator?.addCleanupAction("Close Database") {
            databaseManager.shutdown()
        }

        val done = Job()
        server.onClose {
            logger.info("Server closed")
            done.complete()
            if (shutdownCoordinator == null) databaseManager.shutdown()
        }

        try {
            server.connect(transport)
            logger.info("Current (v3) MCP server running as '$serverName' v$version with $toolCount tools")
            done.join()
        } catch (e: Exception) {
            logger.error("Error in stdio server connection: ${e.message}", e)
        }
    }

    private suspend fun runHttpTransport(server: Server, serverName: String, toolCount: Int) {
        val host = System.getenv("MCP_HTTP_HOST") ?: "0.0.0.0"
        val port = System.getenv("MCP_HTTP_PORT")?.toIntOrNull() ?: 3001
        logger.info("Starting MCP server with HTTP transport on $host:$port/mcp ...")

        val done = Job()
        val ktorServer = embeddedServer(CIO, host = host, port = port) {
            mcpStreamableHttp {
                server
            }
        }

        shutdownCoordinator?.addCleanupAction("Stop HTTP Server") {
            ktorServer.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
            done.complete()
        }
        shutdownCoordinator?.addCleanupAction("Close MCP Server") {
            runBlocking { server.close() }
        }
        shutdownCoordinator?.addCleanupAction("Close Database") {
            databaseManager.shutdown()
        }

        if (shutdownCoordinator == null) {
            Runtime.getRuntime().addShutdownHook(Thread {
                ktorServer.stop(1000, 5000)
                done.complete()
                databaseManager.shutdown()
            })
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
    private fun configureServer(serverName: String): Server {
        return Server(
            serverInfo = Implementation(
                name = serverName,
                version = version
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    prompts = ServerCapabilities.Prompts(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                    logging = JsonObject(emptyMap())
                )
            ),
            instructions = "Current (v3) MCP Task Orchestrator — 13 tools: manage_items, query_items, manage_notes, query_notes, manage_dependencies, query_dependencies, advance_item, get_next_status, get_next_item, get_blocked_items, complete_tree, create_work_tree, get_context"
        )
    }
}

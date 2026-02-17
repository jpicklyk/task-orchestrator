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
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
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
 * Initializes database, configures MCP SDK server, registers tools, and starts stdio transport.
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

        // Set up stdio transport
        logger.info("Starting MCP server with stdio transport...")

        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered()
        )

        // Register cleanup actions with coordinator if present
        shutdownCoordinator?.addCleanupAction("Close MCP Server") {
            runBlocking { server.close() }
        }
        shutdownCoordinator?.addCleanupAction("Close Database") {
            databaseManager.shutdown()
        }

        // Connect to the transport
        val done = Job()
        server.onClose {
            logger.info("Server closed")
            done.complete()

            // If no coordinator, fall back to direct database shutdown
            if (shutdownCoordinator == null) {
                databaseManager.shutdown()
            }
        }

        try {
            server.connect(transport)
            logger.info("Current (v3) MCP server running as '$serverName' v$version with ${tools.size} tools")

            // Wait until the server is closed
            done.join()
        } catch (e: Exception) {
            logger.error("Error in server connection: ${e.message}", e)
        }

        logger.info("MCP server shut down")
    }

    /**
     * Closes the MCP server. Can be called from external shutdown triggers.
     */
    suspend fun close() {
        mcpSdkServer?.close()
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

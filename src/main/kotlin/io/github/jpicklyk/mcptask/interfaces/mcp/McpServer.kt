package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.application.service.TemplateInitializer
import io.github.jpicklyk.mcptask.application.service.TemplateInitializerImpl
import io.github.jpicklyk.mcptask.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.dependency.*
import io.github.jpicklyk.mcptask.application.tools.feature.*
import io.github.jpicklyk.mcptask.application.tools.project.*
import io.github.jpicklyk.mcptask.application.tools.section.*
import io.github.jpicklyk.mcptask.application.tools.task.*
import io.github.jpicklyk.mcptask.application.tools.template.*
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.interfaces.mcp.McpServerAiGuidance.configureAiGuidance
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * MCP Server implementation for the Task Orchestrator.
 * This class acts as an adapter between the MCP protocol and the application layer.
 */
class McpServer(
    private val version: String
) {
    private val logger = LoggerFactory.getLogger(McpServer::class.java)
    private val databaseManager = DatabaseManager()
    private lateinit var repositoryProvider: RepositoryProvider
    private lateinit var toolExecutionContext: ToolExecutionContext
    private val toolAdapter = McpToolAdapter()
    private lateinit var templateInitializer: TemplateInitializer
    
    /**
     * Configures and runs the MCP server.
     * This method will block until the server is closed.
     */
    fun run() = runBlocking {
        logger.info("Initializing MCP server...")

        // Initialize database
        initializeDatabase()

        // Initialize repository provider
        repositoryProvider = DefaultRepositoryProvider(databaseManager)

        // Initialize tool execution context
        toolExecutionContext = ToolExecutionContext(repositoryProvider)

        // Initialize template initializer
        templateInitializer = TemplateInitializerImpl(repositoryProvider.templateRepository())

        // Initialize templates
        initializeTemplates()

        // Configure the server
        val server = configureServer()

        // Set up transport (currently only stdio is supported)
        val transportType = System.getenv("MCP_TRANSPORT") ?: "stdio"

        if (transportType.lowercase() != "stdio") {
            logger.error("Unsupported transport type: $transportType. Only 'stdio' is currently supported.")
            return@runBlocking
        }

        logger.info("Starting MCP server with stdio transport...")

        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered()
        )

        // Connect to the transport
        val done = Job()
        server.onClose {
            logger.info("Server closed")
            done.complete()

            // Close the database connection when the server closes
            databaseManager.shutdown()
        }

        try {
            server.connect(transport)
            // Wait until the server is closed
            done.join()
        } catch (e: Exception) {
            logger.error("Error in server connection: ${e.message}", e)
        }

        logger.info("MCP server shut down")
    }

    /**
     * Configures the server with tools and capabilities.
     */
    private fun configureServer(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = getServerName(),
                version = version
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    prompts = ServerCapabilities.Prompts(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                    logging = JsonObject(emptyMap())
                )
            )
        )

        // Register tools with the server
        registerTools(server)

        // Configure AI guidance
        server.configureAiGuidance()

        // Note: You may see an error in the logs like:
        // "Error handling notification: notifications/initialized - java.util.NoSuchElementException: Key method is missing in the map."
        // This appears to be an internal issue with the Kotlin SDK's notification handling system.
        // It doesn't affect the actual functionality of the server and can be safely ignored.

        return server
    }


    /**
     * Initializes the database connection and updates schema.
     */
    private fun initializeDatabase() {
        logger.info("Initializing database...")

        // Get a database path from the environment or use default
        val databasePath = System.getenv("DATABASE_PATH") ?: "data/tasks.db"

        // Initialize the database manager
        databaseManager.initialize(databasePath)

        // Update database schema
        databaseManager.updateSchema()

        logger.info("Database initialized at: $databasePath")
    }

    /**
     * Initializes the predefined templates.
     */
    private fun initializeTemplates() {
        logger.info("Initializing templates...")

        try {
            templateInitializer.initializeTemplates()
            logger.info("Templates initialized successfully")
        } catch (e: Exception) {
            logger.error("Error initializing templates", e)
        }
    }
    
    /**
     * Registers all MCP tools with the server using the adapter.
     */
    private fun registerTools(server: Server) {
        logger.info("Registering MCP tools...")

        // Create tool instances
        val tools = createTools()

        // Use the adapter to register all tools with the server
        toolAdapter.registerToolsWithServer(server, tools, toolExecutionContext)
        
        logger.info("MCP tools registered")
    }

    /**
     * Creates all tool instances that should be registered with the server.
     */
    private fun createTools(): List<ToolDefinition> {
        return listOf(
            // Task management tools
            CreateTaskTool(),
            UpdateTaskTool(null, null),
            GetTaskTool(),
            DeleteTaskTool(null, null),
            SearchTasksTool(),
            GetTaskOverviewTool(),

            // Dependency management tools
            CreateDependencyTool(),
            GetTaskDependenciesTool(),
            DeleteDependencyTool(),

            // Feature management tools
            CreateFeatureTool(),
            UpdateFeatureTool(),
            GetFeatureTool(),
            DeleteFeatureTool(),
            SearchFeaturesTool(),

            // Project management tools
            CreateProjectTool(),
            GetProjectTool(),
            UpdateProjectTool(),
            DeleteProjectTool(),
            SearchProjectsTool(),

            // Section management tools
            AddSectionTool(null, null),
            GetSectionsTool(),
            UpdateSectionTool(null, null),
            DeleteSectionTool(),
            BulkUpdateSectionsTool(),
            BulkCreateSectionsTool(),
            BulkDeleteSectionsTool(),
            // Enhanced section tools for context-efficiency
            UpdateSectionTextTool(),
            UpdateSectionMetadataTool(),
            ReorderSectionsTool(),

            // Template management tools
            CreateTemplateTool(),
            GetTemplateTool(),
            ApplyTemplateTool(null, null),
            ListTemplatesTool(),
            AddTemplateSectionTool(),
            UpdateTemplateMetadataTool(),
            DeleteTemplateTool(),
            EnableTemplateTool(),
            DisableTemplateTool()
        )
    }

    /**
     * Gets the server name from the configuration.
     */
    private fun getServerName(): String {
        return System.getenv("MCP_SERVER_NAME") ?: "mcp-task-orchestrator"
    }
}
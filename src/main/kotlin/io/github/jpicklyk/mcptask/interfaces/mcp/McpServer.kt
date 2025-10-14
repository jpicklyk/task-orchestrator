package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.application.service.TemplateInitializer
import io.github.jpicklyk.mcptask.application.service.TemplateInitializerImpl
import io.github.jpicklyk.mcptask.application.tools.GetBlockedTasksTool
import io.github.jpicklyk.mcptask.application.tools.GetOverviewTool
import io.github.jpicklyk.mcptask.application.tools.ListTagsTool
import io.github.jpicklyk.mcptask.application.tools.SetStatusTool
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
import io.github.jpicklyk.mcptask.interfaces.mcp.MarkdownResourceProvider.configureMarkdownResources
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
        logger.info(getServerDescription())

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
     * Configures the server with comprehensive metadata, tools, and capabilities.
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
        
        // Add comprehensive server metadata and description
        configureServerMetadata(server)

        // Register tools with the server
        registerTools(server)

        // Configure AI guidance
        server.configureAiGuidance()

        // Configure markdown resources
        server.configureMarkdownResources(repositoryProvider)

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
            BulkUpdateTasksTool(),
            GetTaskTool(),
            DeleteTaskTool(null, null),
            SearchTasksTool(),
            GetOverviewTool(),
            SetStatusTool(),
            ListTagsTool(),
            GetBlockedTasksTool(),
            TaskToMarkdownTool(),

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
            FeatureToMarkdownTool(),

            // Project management tools
            CreateProjectTool(),
            GetProjectTool(),
            UpdateProjectTool(),
            DeleteProjectTool(),
            SearchProjectsTool(),
            ProjectToMarkdownTool(),

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
     * Configures comprehensive server metadata to provide LLMs with detailed
     * understanding of the Task Orchestrator's capabilities and purpose.
     */
    private fun configureServerMetadata(server: Server) {
        // The MCP SDK doesn't provide direct server description APIs,
        // but the comprehensive tool descriptions we've added serve as
        // the primary source of capability documentation for LLMs.
        
        logger.info("Task Orchestrator MCP Server (v$version) - Comprehensive project management and workflow orchestration")
        logger.info("Capabilities: Task management, Feature organization, Template-driven workflows, Dependency tracking")
        logger.info("Tools registered: ${createTools().size} tools across ${getToolCategories().size} categories")
        logger.info("Architecture: Hierarchical organization (Projects → Features → Tasks → Sections)")
        logger.info("Workflow Integration: Template application, Git workflow guidance, Section-based documentation")
    }
    
    /**
     * Gets the comprehensive tool categories for metadata reporting.
     */
    private fun getToolCategories(): Set<String> {
        return setOf(
            "Task Management",
            "Feature Management", 
            "Project Management",
            "Template Management",
            "Section Management",
            "Dependency Management",
            "Search and Query",
            "Workflow Orchestration"
        )
    }
    
    /**
     * Gets the server name with enhanced metadata context.
     */
    private fun getServerName(): String {
        val baseName = System.getenv("MCP_SERVER_NAME") ?: "mcp-task-orchestrator"
        return "$baseName-v$version"
    }
    
    /**
     * Gets comprehensive server description for logging and metadata.
     */
    private fun getServerDescription(): String {
        return """
            MCP Task Orchestrator - Comprehensive project management and workflow automation server.
            
            CORE CAPABILITIES:
            • Hierarchical project organization (Projects → Features → Tasks → Sections)
            • Template-driven task and feature creation with standardized documentation
            • Advanced search and filtering across all project entities
            • Dependency tracking and workflow management
            • Git workflow integration with step-by-step guidance
            • Context-efficient section-based content organization
            • Locking system for concurrent operation safety
            
            ENTITY TYPES:
            • Projects: Top-level organization containers
            • Features: Mid-level functionality groupings
            • Tasks: Primary work items with status tracking
            • Sections: Detailed content blocks for documentation
            • Templates: Reusable documentation and workflow patterns
            • Dependencies: Task relationship and workflow management
            
            WORKFLOW PATTERNS:
            • Template-first approach for consistent documentation
            • Progressive detail addition through sections
            • Status-driven task lifecycle management
            • Priority and complexity-based work planning
            • Tag-based categorization and filtering
            
            INTEGRATION FEATURES:
            • Automatic template application during entity creation
            • Bulk operations for efficiency (bulk_create_sections, bulk_update_sections)
            • Context-efficient search and overview tools
            • Git workflow prompts with MCP tool integration
            • Real-time status tracking and progress monitoring
            
            BUILT FOR: AI-assisted project management, development workflow automation,
            comprehensive task tracking, and structured documentation management.
        """.trimIndent()
    }
}
package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.TemplateInitializer
import io.github.jpicklyk.mcptask.application.service.TemplateInitializerImpl
import io.github.jpicklyk.mcptask.application.service.agent.AgentRecommendationService
import io.github.jpicklyk.mcptask.application.service.agent.AgentRecommendationServiceImpl
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionServiceImpl
import io.github.jpicklyk.mcptask.infrastructure.filesystem.AgentDirectoryManager
import io.github.jpicklyk.mcptask.infrastructure.filesystem.TaskOrchestratorConfigManager
import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.application.tools.QueryContainerTool
import io.github.jpicklyk.mcptask.application.tools.QueryTemplatesTool
import io.github.jpicklyk.mcptask.application.tools.QueryWorkflowStateTool
import io.github.jpicklyk.mcptask.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.dependency.ManageDependencyTool
import io.github.jpicklyk.mcptask.application.tools.dependency.QueryDependenciesTool
import io.github.jpicklyk.mcptask.application.tools.section.ManageSectionsTool
import io.github.jpicklyk.mcptask.application.tools.section.QuerySectionsTool
import io.github.jpicklyk.mcptask.application.tools.tag.*
import io.github.jpicklyk.mcptask.application.tools.task.GetNextTaskTool
import io.github.jpicklyk.mcptask.application.tools.task.GetBlockedTasksTool
import io.github.jpicklyk.mcptask.application.tools.status.GetNextStatusTool
import io.github.jpicklyk.mcptask.application.tools.template.ApplyTemplateTool
import io.github.jpicklyk.mcptask.application.tools.template.ManageTemplateTool
import io.github.jpicklyk.mcptask.application.tools.agent.*
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
    private lateinit var agentDirectoryManager: AgentDirectoryManager
    private lateinit var configManager: TaskOrchestratorConfigManager
    private lateinit var agentRecommendationService: AgentRecommendationService
    private lateinit var statusValidator: StatusValidator
    private lateinit var statusProgressionService: StatusProgressionService
    
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

        // Initialize agent directory manager and recommendation service
        agentDirectoryManager = AgentDirectoryManager()
        configManager = TaskOrchestratorConfigManager()
        agentRecommendationService = AgentRecommendationServiceImpl(agentDirectoryManager)

        // Initialize status progression services
        statusValidator = StatusValidator()
        statusProgressionService = StatusProgressionServiceImpl(statusValidator)

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

        // Configure agent resources
        AgentResources.configure(
            server,
            agentDirectoryManager,
            agentRecommendationService,
            repositoryProvider.taskRepository()
        )

        // Skills and Hooks resources removed - .claude/ directory setup no longer supported
        // Skills are now user-managed in .claude/ directory if using Claude Code
        // SkillsAndHooksResources.configure(server, configManager)

        // Configure tool documentation resources
        ToolDocumentationResources.configure(server)

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
            // ========== v2.0 CONSOLIDATED TOOLS ==========

            // Container management - Unified operations for Projects/Features/Tasks
            QueryContainerTool(),
            ManageContainerTool(null, null),

            // Section management - Unified operations for all section types
            QuerySectionsTool(null, null),
            ManageSectionsTool(null, null),

            // Template management - Read, write, and apply operations
            QueryTemplatesTool(null, null),
            ManageTemplateTool(null, null),
            ApplyTemplateTool(null, null),

            // Dependency management - Query and manage task dependencies
            QueryDependenciesTool(null, null),
            ManageDependencyTool(null, null),

            // Tag management - Discovery and organization
            ListTagsTool(),
            GetTagUsageTool(),
            RenameTagTool(),

            // Workflow optimization - Task recommendations and blocking analysis
            GetNextTaskTool(),
            GetBlockedTasksTool(),

            // Status progression - Intelligent workflow recommendations
            GetNextStatusTool(statusProgressionService),
            QueryWorkflowStateTool(),

            // Orchestration - AI workflow automation and coordination
            SetupProjectTool(),
            GetAgentDefinitionTool(),
            RecommendAgentTool()
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
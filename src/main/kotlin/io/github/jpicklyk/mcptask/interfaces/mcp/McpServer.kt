package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.TemplateInitializer
import io.github.jpicklyk.mcptask.application.service.TemplateInitializerImpl
import io.github.jpicklyk.mcptask.application.service.cascade.CascadeService
import io.github.jpicklyk.mcptask.application.service.cascade.CascadeServiceImpl
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionServiceImpl
import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.application.tools.QueryContainerTool
import io.github.jpicklyk.mcptask.application.tools.QueryTemplatesTool
import io.github.jpicklyk.mcptask.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.application.tools.dependency.QueryDependenciesTool
import io.github.jpicklyk.mcptask.application.tools.section.ManageSectionsTool
import io.github.jpicklyk.mcptask.application.tools.section.QuerySectionsTool
import io.github.jpicklyk.mcptask.application.tools.task.GetNextTaskTool
import io.github.jpicklyk.mcptask.application.tools.task.GetBlockedTasksTool
import io.github.jpicklyk.mcptask.application.tools.status.GetNextStatusTool
import io.github.jpicklyk.mcptask.application.tools.status.QueryRoleTransitionsTool
import io.github.jpicklyk.mcptask.application.tools.status.RequestTransitionTool
import io.github.jpicklyk.mcptask.application.tools.template.ApplyTemplateTool
import io.github.jpicklyk.mcptask.application.tools.template.ManageTemplateTool
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.interfaces.mcp.McpServerAiGuidance.configureAiGuidance
import io.github.jpicklyk.mcptask.interfaces.mcp.MarkdownResourceProvider.configureMarkdownResources
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
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
    private val version: String,
    private val shutdownCoordinator: ShutdownCoordinator? = null
) {
    private val logger = LoggerFactory.getLogger(McpServer::class.java)
    private val databaseManager = DatabaseManager()
    private lateinit var repositoryProvider: RepositoryProvider
    private lateinit var toolExecutionContext: ToolExecutionContext
    private val toolAdapter = McpToolAdapter()
    private var mcpSdkServer: Server? = null
    private lateinit var templateInitializer: TemplateInitializer
    private lateinit var statusValidator: StatusValidator
    private lateinit var statusProgressionService: StatusProgressionService
    private lateinit var cascadeService: CascadeService
    
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

        // Initialize status progression services (before tool context so tools can use them)
        statusValidator = StatusValidator()
        statusProgressionService = StatusProgressionServiceImpl(statusValidator)

        // Initialize cascade service with role aggregation rules from config
        val aggregationRules = CascadeServiceImpl.loadAggregationRules()
        cascadeService = CascadeServiceImpl(
            statusProgressionService = statusProgressionService,
            statusValidator = statusValidator,
            taskRepository = repositoryProvider.taskRepository(),
            featureRepository = repositoryProvider.featureRepository(),
            projectRepository = repositoryProvider.projectRepository(),
            dependencyRepository = repositoryProvider.dependencyRepository(),
            sectionRepository = repositoryProvider.sectionRepository(),
            aggregationRules = aggregationRules,
            roleTransitionRepository = repositoryProvider.roleTransitionRepository()
        )

        // Initialize tool execution context with status progression service and cascade service
        toolExecutionContext = ToolExecutionContext(repositoryProvider, statusProgressionService, cascadeService)

        // Initialize template initializer
        templateInitializer = TemplateInitializerImpl(repositoryProvider.templateRepository())

        // Initialize templates
        initializeTemplates()

        // Configure the server
        val server = configureServer()
        mcpSdkServer = server

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
     * Configures the server with comprehensive metadata, tools, and capabilities.
     *
     * The `instructions` parameter is **Layer 1** of the two-layer setup architecture
     * documented in [TaskOrchestratorResources]. It is delivered to agents once during
     * MCP `initialize` and tells them to check for the version marker
     * `<!-- mcp-task-orchestrator-setup: vN -->` in their CLAUDE.md. If missing or outdated,
     * agents read the on-demand MCP resource `task-orchestrator://guidelines/setup-instructions`
     * (Layer 2) to install the full workflow instruction block.
     *
     * @see TaskOrchestratorResources.SETUP_INSTRUCTIONS_VERSION version constant shared by both layers
     * @see TaskOrchestratorResources.addSetupInstructionsResource Layer 2 resource registration
     */
    private fun configureServer(): Server {
        // Layer 1: Short versioned pointer sent to every agent at init (~80 tokens).
        // Directs agents to Layer 2 (MCP resource) when their CLAUDE.md is missing or outdated.
        // See TaskOrchestratorResources KDoc for the full two-layer architecture explanation.
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
            ),
            instructions = """
                Check your project's agent instructions file for the marker <!-- mcp-task-orchestrator-setup: ${TaskOrchestratorResources.SETUP_INSTRUCTIONS_VERSION} -->. If missing or an older version, read the MCP resource task-orchestrator://guidelines/setup-instructions and follow its setup steps to add essential workflow rules.
            """.trimIndent()
        )
        
        // Add comprehensive server metadata and description
        configureServerMetadata(server)

        // Register tools with the server
        registerTools(server)

        // Configure AI guidance
        server.configureAiGuidance()

        // Configure markdown resources
        server.configureMarkdownResources(repositoryProvider)

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
     *
     * Total: 14 tools
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
            ManageDependenciesTool(null, null),

            // Workflow optimization - Task recommendations and blocking analysis
            GetNextTaskTool(),
            GetBlockedTasksTool(),

            // Status progression - Intelligent workflow recommendations
            GetNextStatusTool(statusProgressionService),
            RequestTransitionTool(statusProgressionService),
            QueryRoleTransitionsTool()
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
            • Batch operations for efficiency (all manage_container operations use arrays)
            • Context-efficient search and overview tools
            • Git workflow templates with MCP tool integration
            • Real-time status tracking and progress monitoring
            
            BUILT FOR: AI-assisted project management, development workflow automation,
            comprehensive task tracking, and structured documentation management.
        """.trimIndent()
    }
}
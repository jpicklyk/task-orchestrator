package io.github.jpicklyk.mcptask.application.tools.agent

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.filesystem.OrchestrationSetupManager
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for initializing Claude Code orchestration system.
 *
 * Creates .claude/ directory structure including:
 * - agents/task-orchestrator/ - 8 specialized subagent definitions
 * - skills/ - 6 lightweight coordination skills
 * - .taskorchestrator/ configuration files
 *
 * This tool is idempotent - safe to run multiple times, will skip existing files.
 */
class SetupClaudeOrchestrationTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.SYSTEM

    override val name: String = "setup_claude_orchestration"

    override val title: String = "Setup Claude Code Orchestration System"

    override val description: String = """Initializes Claude Code integration for Task Orchestrator.

        Prerequisites:
        - Run setup_project first to create .taskorchestrator/ configuration

        What This Creates:
        - `.claude/agents/task-orchestrator/` - 4 specialized subagent definitions (v2.0 architecture)
        - `.claude/skills/` - 6 Skills for lightweight coordination workflows
        - `.claude/output-styles/` - Task Orchestrator output style
        - Decision gates in CLAUDE.md (if not present)

        Subagents (v2.0 Architecture - Complex Implementation):
        Specialized agents for complex reasoning and implementation work:
        - Implementation Specialist (haiku model) - Standard work with Skills loaded dynamically
        - Senior Engineer (sonnet model) - Complex problems, bugs, blockers, debugging
        - Feature Architect (opus model) - Feature design and PRD creation
        - Planning Specialist (sonnet model) - Task breakdown and execution graphs

        Skills (Lightweight Coordination - Quick Workflows):
        Lightweight patterns for quick coordination tasks (2-5 tool calls):
        - dependency-analysis: Analyze task dependencies, identify blocked tasks, find bottlenecks
        - dependency-orchestration: Manage dependency creation, updates, and validation
        - feature-orchestration: Coordinate feature lifecycle and task breakdown
        - hook-builder: Interactive hook creation tool (project-specific hooks not auto-installed)
        - status-progression: Guide status transitions with validation and workflow rules
        - task-orchestration: Coordinate task lifecycle, recommendations, and completion

        Each Skill includes: SKILL.md (workflow guide), examples.md (working examples).

        Note on Compatibility:
        This MCP server works with any MCP client (Claude Desktop, Claude Code, Cursor, Windsurf, etc.).
        The .claude/ directory structure follows Claude Code conventions for optimal compatibility
        but the MCP tools work independently of the client being used.

        Parameters: None required

        Usage notes:
        - Idempotent: Safe to run multiple times (skips existing files)
        - Won't overwrite user customizations
        - Returns list of created vs skipped files
        - Preserves directory structure for skills (includes supporting files)

        When to use:
        - First setup of agent system
        - Setting up new project or after cloning repository
        - Restoring default configurations
        - Enabling v2.0 features (config-driven status validation)

        After setup:
        - Use recommend_agent(taskId) to get subagent recommendations
        - Use get_agent_definition(agentName) to read subagent files
        - Edit .claude/agents/task-orchestrator/*.md to customize subagent behavior
        - Browse .claude/skills/ for skill documentation
        - Use hook-builder skill to create project-specific hooks
        - Commit .claude/ and .taskorchestrator/ directories for team sharing

        Related tools: recommend_agent, get_agent_definition

        For detailed examples and patterns: task-orchestrator://docs/tools/setup-claude-orchestration
        """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(emptyMap()),
        required = emptyList()
    )

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Whether the operation succeeded")
                    )
                ),
                "message" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Human-readable message describing the result")
                    )
                ),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Setup operation results"),
                        "properties" to JsonObject(
                            mapOf(
                                "directoryCreated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether directory was newly created")
                                    )
                                ),
                                "agentFilesCreated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("List of agent files that were newly created"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "agentFilesSkipped" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("List of agent files that already existed"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "directory" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Path to the .claude directory")
                                    )
                                ),
                                "totalAgents" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("Total number of agent files (created + skipped)")
                                    )
                                ),
                                "skillsDirectoryCreated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether skills directory was newly created")
                                    )
                                ),
                                "skillsCopied" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("List of skills that were newly copied"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "skillsSkipped" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "description" to JsonPrimitive("List of skills that already existed"),
                                        "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                                    )
                                ),
                                "skillsDirectory" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Path to the skills directory")
                                    )
                                ),
                                "totalSkills" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive("Total number of skills")
                                    )
                                ),
                                "configCreated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether config.yaml was newly created")
                                    )
                                ),
                                "configPath" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Path to the config.yaml file")
                                    )
                                ),
                                "v2ModeEnabled" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether v2.0 config-driven mode is enabled")
                                    )
                                ),
                                "outputStyleDirectoryCreated" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether output-style directory was newly created")
                                    )
                                ),
                                "outputStyleCopied" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("boolean"),
                                        "description" to JsonPrimitive("Whether output-style file was newly copied")
                                    )
                                ),
                                "outputStylePath" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive("Path to the output-style file")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        ),
        required = listOf("success", "message")
    )

    override fun validateParams(params: JsonElement) {
        // No parameters required
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing setup_claude_orchestration tool")

        return try {
            val orchestrationSetupManager = OrchestrationSetupManager()

            // Check prerequisite: .taskorchestrator directory must exist
            if (!orchestrationSetupManager.taskOrchestratorDirExists()) {
                logger.warn(".taskorchestrator directory not found - prerequisite not met")
                return errorResponse(
                    message = "Prerequisite not met: .taskorchestrator/ directory does not exist",
                    code = ErrorCodes.VALIDATION_ERROR,
                    details = "Run setup_project tool first to create core Task Orchestrator configuration (.taskorchestrator/)"
                )
            }

            // Step 1: Create .claude directory structure
            logger.info("Creating .claude/agents/ directory structure...")
            val directoryCreated = orchestrationSetupManager.createDirectoryStructure()

            // Step 2: Copy Claude-specific agent template files
            logger.info("Copying Claude Code agent template files...")
            val copiedAgentFiles = orchestrationSetupManager.copyDefaultAgentTemplates()

            // Step 3: Inject decision gates into CLAUDE.md
            logger.info("Injecting decision gates into CLAUDE.md...")
            val decisionGatesInjected = orchestrationSetupManager.injectDecisionGatesIntoClaude()

            // Step 4: Create skills directory
            logger.info("Creating .claude/skills/ directory structure...")
            val skillsDirectoryCreated = orchestrationSetupManager.createSkillsDirectory()

            // Step 5: Copy skill templates
            logger.info("Copying skill templates...")
            val copiedSkills = orchestrationSetupManager.copySkillTemplates()

            // Step 6: Create output-style directory
            logger.info("Creating .claude/output-styles/ directory structure...")
            val outputStyleDirectoryCreated = orchestrationSetupManager.createOutputStyleDirectory()

            // Step 7: Copy output-style file
            logger.info("Copying output-style file...")
            val outputStyleCopied = orchestrationSetupManager.copyOutputStyleFile()

            // Calculate skipped files
            val allAgentFiles = OrchestrationSetupManager.DEFAULT_AGENT_FILES
            val skippedAgentFiles = allAgentFiles.filter { it !in copiedAgentFiles }
            val allSkills = OrchestrationSetupManager.SKILL_DIRECTORIES
            val skippedSkills = allSkills.filter { it !in copiedSkills }

            // Build response message
            val message = buildString {
                append("Claude Code integration setup ")
                if (directoryCreated || skillsDirectoryCreated || outputStyleDirectoryCreated) {
                    append("completed successfully. ")
                } else {
                    append("verified. ")
                }

                if (copiedAgentFiles.isNotEmpty()) {
                    append("Created ${copiedAgentFiles.size} agent file(s). ")
                }
                if (skippedAgentFiles.isNotEmpty()) {
                    append("Skipped ${skippedAgentFiles.size} existing agent file(s). ")
                }

                if (copiedSkills.isNotEmpty()) {
                    append("Created ${copiedSkills.size} skill(s). ")
                }
                if (skippedSkills.isNotEmpty()) {
                    append("Skipped ${skippedSkills.size} existing skill(s). ")
                }

                if (outputStyleCopied) {
                    append("Created output-style file. ")
                } else {
                    append("Output-style file already exists. ")
                }

                if (decisionGatesInjected) {
                    append("Injected decision gates into CLAUDE.md.")
                } else {
                    append("Decision gates already present in CLAUDE.md.")
                }
            }

            successResponse(
                data = buildJsonObject {
                    put("directoryCreated", directoryCreated)
                    put("agentFilesCreated", JsonArray(copiedAgentFiles.map { JsonPrimitive(it) }))
                    put("agentFilesSkipped", JsonArray(skippedAgentFiles.map { JsonPrimitive(it) }))
                    put("directory", orchestrationSetupManager.getClaudeDir().toString())
                    put("totalAgents", allAgentFiles.size)
                    put("decisionGatesInjected", decisionGatesInjected)
                    put("skillsDirectoryCreated", skillsDirectoryCreated)
                    put("skillsCopied", JsonArray(copiedSkills.map { JsonPrimitive(it) }))
                    put("skillsSkipped", JsonArray(skippedSkills.map { JsonPrimitive(it) }))
                    put("skillsDirectory", orchestrationSetupManager.getSkillsDir().toString())
                    put("totalSkills", allSkills.size)
                    put("outputStyleDirectoryCreated", outputStyleDirectoryCreated)
                    put("outputStyleCopied", outputStyleCopied)
                    put("outputStylePath", orchestrationSetupManager.getOutputStyleDir().resolve(
                        OrchestrationSetupManager.OUTPUT_STYLE_FILE
                    ).toString())
                },
                message = message
            )
        } catch (e: Exception) {
            logger.error("Error setting up Claude orchestration", e)
            errorResponse(
                message = "Failed to setup Claude Code orchestration system",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error occurred during orchestration setup"
            )
        }
    }
}

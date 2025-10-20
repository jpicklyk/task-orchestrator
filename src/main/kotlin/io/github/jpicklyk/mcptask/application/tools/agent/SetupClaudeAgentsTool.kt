package io.github.jpicklyk.mcptask.application.tools.agent

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.filesystem.ClaudeAgentDirectoryManager
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for initializing Claude Code agent configuration.
 *
 * Creates .claude/agents/ directory structure, copies Claude-specific agent
 * template files from embedded resources, and sets up agent configuration.
 *
 * This tool is idempotent - safe to run multiple times, will skip existing files.
 */
class SetupClaudeAgentsTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.SYSTEM

    override val name: String = "setup_claude_agents"

    override val title: String = "Setup Claude Code Agent Configuration"

    override val description: String = """Initializes agent configuration system with subagents, skills, and orchestration config.

        What This Creates:
        - `.claude/agents/task-orchestrator/` - 8 specialized subagent definitions
        - `.claude/skills/` - 2 Skills for lightweight coordination workflows
        - `.taskorchestrator/agent-mapping.yaml` - Agent routing configuration
        - `.taskorchestrator/config.yaml` - Orchestration configuration (enables v2.0 features)
        - Decision gates in CLAUDE.md (if not present)

        Subagents (Complex Implementation - Deep Work):
        Specialized agents for complex code implementation and documentation work:
        - Backend Engineer, Frontend Developer, Database Engineer (sonnet model)
        - Test Engineer, Technical Writer (sonnet model)
        - Feature Architect (opus model) - Feature design and architecture
        - Planning Specialist (sonnet model) - Task breakdown and planning
        - Bug Triage Specialist (sonnet model) - Bug analysis and triage

        Skills (Lightweight Coordination - Quick Workflows):
        Lightweight patterns for quick coordination tasks (2-5 tool calls):
        - dependency-analysis: Analyze task dependencies, identify blocked tasks, find bottlenecks
        - hook-builder: Interactive hook creation tool (project-specific hooks not auto-installed)

        Each Skill includes: SKILL.md (workflow guide), examples.md (working examples), troubleshooting.

        Configuration Files:
        - agent-mapping.yaml: Maps task tags to appropriate subagents
        - config.yaml: Status workflows, validation rules, quality gates, parallelism settings

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

        For detailed examples and patterns: task-orchestrator://docs/tools/setup-claude-agents
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
        logger.info("Executing setup_claude_agents tool")

        return try {
            val agentDirectoryManager = ClaudeAgentDirectoryManager()

            // Step 1: Create directory structure
            logger.info("Creating .claude/agents/ directory structure...")
            val directoryCreated = agentDirectoryManager.createDirectoryStructure()

            // Step 2: Copy Claude-specific agent template files
            logger.info("Copying Claude Code agent template files...")
            val copiedAgentFiles = agentDirectoryManager.copyDefaultAgentTemplates()

            // Step 3: Create .taskorchestrator directory
            logger.info("Creating .taskorchestrator/ directory structure...")
            val taskOrchestratorDirCreated = agentDirectoryManager.createTaskOrchestratorDirectory()

            // Step 4: Copy agent-mapping.yaml file
            logger.info("Copying agent-mapping.yaml configuration file...")
            val agentMappingCopied = agentDirectoryManager.copyAgentMappingFile()

            // Step 5: Copy config.yaml file (enables v2.0 mode)
            logger.info("Copying config.yaml configuration file...")
            val configCopied = agentDirectoryManager.copyConfigFile()

            // Step 6: Inject decision gates into CLAUDE.md
            logger.info("Injecting decision gates into CLAUDE.md...")
            val decisionGatesInjected = agentDirectoryManager.injectDecisionGatesIntoClaude()

            // Step 7: Create skills directory
            logger.info("Creating .claude/skills/ directory structure...")
            val skillsDirectoryCreated = agentDirectoryManager.createSkillsDirectory()

            // Step 8: Copy skill templates
            logger.info("Copying skill templates...")
            val copiedSkills = agentDirectoryManager.copySkillTemplates()

            // Calculate skipped files
            val allAgentFiles = ClaudeAgentDirectoryManager.DEFAULT_AGENT_FILES
            val skippedAgentFiles = allAgentFiles.filter { it !in copiedAgentFiles }
            val allSkills = ClaudeAgentDirectoryManager.SKILL_DIRECTORIES
            val skippedSkills = allSkills.filter { it !in copiedSkills }

            // Build response message
            val message = buildString {
                append("Claude Code agent system setup ")
                if (directoryCreated || taskOrchestratorDirCreated || skillsDirectoryCreated) {
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

                if (agentMappingCopied) {
                    append("Created agent-mapping.yaml. ")
                } else {
                    append("Agent-mapping.yaml already exists. ")
                }
                if (configCopied) {
                    append("Created config.yaml (v2.0 mode enabled). ")
                } else {
                    append("Config.yaml already exists. ")
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
                    put("directory", agentDirectoryManager.getClaudeDir().toString())
                    put("totalAgents", allAgentFiles.size)
                    put("taskOrchestratorDirCreated", taskOrchestratorDirCreated)
                    put("agentMappingCreated", agentMappingCopied)
                    put("agentMappingPath", agentDirectoryManager.getTaskOrchestratorDir().resolve(
                        ClaudeAgentDirectoryManager.AGENT_MAPPING_FILE
                    ).toString())
                    put("configCreated", configCopied)
                    put("configPath", agentDirectoryManager.getTaskOrchestratorDir().resolve(
                        ClaudeAgentDirectoryManager.CONFIG_FILE
                    ).toString())
                    put("v2ModeEnabled", configCopied)
                    put("decisionGatesInjected", decisionGatesInjected)
                    put("skillsDirectoryCreated", skillsDirectoryCreated)
                    put("skillsCopied", JsonArray(copiedSkills.map { JsonPrimitive(it) }))
                    put("skillsSkipped", JsonArray(skippedSkills.map { JsonPrimitive(it) }))
                    put("skillsDirectory", agentDirectoryManager.getSkillsDir().toString())
                    put("totalSkills", allSkills.size)
                },
                message = message
            )
        } catch (e: Exception) {
            logger.error("Error setting up Claude agents", e)
            errorResponse(
                message = "Failed to setup Claude Code agent system",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error occurred during agent setup"
            )
        }
    }
}

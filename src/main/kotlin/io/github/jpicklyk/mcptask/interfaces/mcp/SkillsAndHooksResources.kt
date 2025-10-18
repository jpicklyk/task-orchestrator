package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.infrastructure.filesystem.ClaudeAgentDirectoryManager
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * MCP Resources for Skills and Hooks Discovery and Management.
 *
 * These resources enable AI agents to discover available Skills and Hooks,
 * get Skill definitions, and retrieve Hook implementation details.
 */
object SkillsAndHooksResources {

    private val logger = LoggerFactory.getLogger(SkillsAndHooksResources::class.java)
    private val json = Json { prettyPrint = true; encodeDefaults = false }

    /**
     * Configures all Skills and Hooks MCP resources with the server.
     */
    fun configure(
        server: Server,
        directoryManager: ClaudeAgentDirectoryManager
    ) {
        addListSkillsResource(server, directoryManager)
        addSkillDefinitionResource(server, directoryManager)
        addListHooksResource(server, directoryManager)
        addHookDefinitionResource(server, directoryManager)
    }

    /**
     * Adds resource for listing all available Skills.
     * URI: task-orchestrator://skills/list
     */
    private fun addListSkillsResource(
        server: Server,
        directoryManager: ClaudeAgentDirectoryManager
    ) {
        server.addResource(
            uri = "task-orchestrator://skills/list",
            name = "Available Skills",
            description = "Lists all available Skills in the task-orchestrator collection for lightweight coordination operations",
            mimeType = "application/json"
        ) { _ ->
            try {
                val skillsDir = directoryManager.getSkillsDir()

                val skills = if (Files.exists(skillsDir)) {
                    Files.list(skillsDir)
                        .filter { Files.isDirectory(it) }
                        .map { dir ->
                            val skillName = dir.fileName.toString()
                            val skillFile = dir.resolve("SKILL.md")
                            val description = if (Files.exists(skillFile)) {
                                // Try to extract description from SKILL.md frontmatter or first paragraph
                                val content = Files.readString(skillFile)
                                extractDescription(content)
                            } else {
                                "No description available"
                            }

                            buildJsonObject {
                                put("name", skillName)
                                put("description", description)
                                put("path", ".claude/skills/task-orchestrator/$skillName")
                                putJsonArray("files") {
                                    Files.list(dir).use { fileStream ->
                                        fileStream.filter { Files.isRegularFile(it) }
                                            .forEach { file ->
                                                add(file.fileName.toString())
                                            }
                                    }
                                }
                            }
                        }
                        .toList()
                } else {
                    emptyList()
                }

                val responseData = buildJsonObject {
                    putJsonArray("skills") {
                        skills.forEach { add(it) }
                    }
                    put("count", skills.size)
                    put("collection", "task-orchestrator")
                    put("directory", ".claude/skills/task-orchestrator")
                }

                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = "task-orchestrator://skills/list",
                            mimeType = "application/json",
                            text = json.encodeToString(JsonElement.serializer(), responseData)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to list Skills", e)
                val errorResponse = buildJsonObject {
                    put("error", "Failed to list Skills: ${e.message}")
                    putJsonArray("skills") { }
                    put("count", 0)
                }
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = "task-orchestrator://skills/list",
                            mimeType = "application/json",
                            text = json.encodeToString(JsonElement.serializer(), errorResponse)
                        )
                    )
                )
            }
        }
    }

    /**
     * Adds resource for getting Skill definition file by name.
     * URI: task-orchestrator://skills/definition/{skillName}/{fileName}
     */
    private fun addSkillDefinitionResource(
        server: Server,
        directoryManager: ClaudeAgentDirectoryManager
    ) {
        server.addResource(
            uri = "task-orchestrator://skills/definition",
            name = "Skill Definition File",
            description = "Returns Skill definition file content (SKILL.md, examples.md, templates/, etc.). Path parameters: {skillName}/{fileName}",
            mimeType = "text/markdown"
        ) { request ->
            try {
                val uri = request.uri ?: "task-orchestrator://skills/definition"
                val (skillName, fileName) = extractSkillNameAndFile(uri)

                if (skillName == null) {
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = "text/plain",
                                text = "Error: Missing skill name in URI path. Usage: task-orchestrator://skills/definition/{skillName}/{fileName}"
                            )
                        )
                    )
                }

                val skillsDir = directoryManager.getSkillsDir().resolve(skillName)

                if (!Files.exists(skillsDir)) {
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = "text/plain",
                                text = "Error: Skill not found: $skillName\n\nRun setup_claude_agents tool to initialize Skills."
                            )
                        )
                    )
                }

                // If no fileName specified, return SKILL.md by default
                val targetFile = skillsDir.resolve(fileName ?: "SKILL.md")

                if (!Files.exists(targetFile)) {
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = "text/plain",
                                text = "Error: File not found: ${fileName ?: "SKILL.md"} in skill: $skillName"
                            )
                        )
                    )
                }

                val content = Files.readString(targetFile)

                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = uri,
                            mimeType = if (fileName?.endsWith(".md") == true || fileName == null) "text/markdown" else "text/plain",
                            text = content
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to get Skill definition", e)
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = request.uri ?: "task-orchestrator://skills/definition",
                            mimeType = "text/plain",
                            text = "Error: Failed to get Skill definition: ${e.message}"
                        )
                    )
                )
            }
        }
    }

    /**
     * Adds resource for listing all available Hooks.
     * URI: task-orchestrator://hooks/list
     */
    private fun addListHooksResource(
        server: Server,
        directoryManager: ClaudeAgentDirectoryManager
    ) {
        server.addResource(
            uri = "task-orchestrator://hooks/list",
            name = "Available Hooks",
            description = "Lists all available Hooks in the task-orchestrator collection for automated side effects",
            mimeType = "application/json"
        ) { _ ->
            try {
                val hooksDir = directoryManager.getHooksDir()

                // List README files in the hooks directory that aren't the main README
                val hookReadmes = if (Files.exists(hooksDir)) {
                    Files.list(hooksDir)
                        .filter { Files.isRegularFile(it) && it.fileName.toString().contains("-README.md") && it.fileName.toString() != "README.md" }
                        .map { file ->
                            val hookName = file.fileName.toString().removeSuffix("-README.md")

                            buildJsonObject {
                                put("name", hookName)
                                put("readmePath", ".claude/hooks/task-orchestrator/${file.fileName}")

                                // Try to find associated script
                                val scriptFile = hooksDir.resolve("scripts/$hookName.sh")
                                if (Files.exists(scriptFile)) {
                                    put("scriptPath", ".claude/hooks/task-orchestrator/scripts/$hookName.sh")
                                }
                            }
                        }
                        .toList()
                } else {
                    emptyList()
                }

                val responseData = buildJsonObject {
                    putJsonArray("hooks") {
                        hookReadmes.forEach { add(it) }
                    }
                    put("count", hookReadmes.size)
                    put("collection", "task-orchestrator")
                    put("directory", ".claude/hooks/task-orchestrator")
                    putJsonObject("directories") {
                        put("scripts", ".claude/hooks/task-orchestrator/scripts")
                        put("templates", ".claude/hooks/task-orchestrator/templates")
                    }
                }

                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = "task-orchestrator://hooks/list",
                            mimeType = "application/json",
                            text = json.encodeToString(JsonElement.serializer(), responseData)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to list Hooks", e)
                val errorResponse = buildJsonObject {
                    put("error", "Failed to list Hooks: ${e.message}")
                    putJsonArray("hooks") { }
                    put("count", 0)
                }
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = "task-orchestrator://hooks/list",
                            mimeType = "application/json",
                            text = json.encodeToString(JsonElement.serializer(), errorResponse)
                        )
                    )
                )
            }
        }
    }

    /**
     * Adds resource for getting Hook definition file by name.
     * URI: task-orchestrator://hooks/definition/{hookName}/{fileType}
     * where fileType can be: readme, script, or template
     */
    private fun addHookDefinitionResource(
        server: Server,
        directoryManager: ClaudeAgentDirectoryManager
    ) {
        server.addResource(
            uri = "task-orchestrator://hooks/definition",
            name = "Hook Definition File",
            description = "Returns Hook definition file content (README, script, or template). Path parameters: {hookName}/{fileType}",
            mimeType = "text/markdown"
        ) { request ->
            try {
                val uri = request.uri ?: "task-orchestrator://hooks/definition"
                val (hookName, fileType) = extractHookNameAndFileType(uri)

                if (hookName == null) {
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = "text/plain",
                                text = "Error: Missing hook name in URI path. Usage: task-orchestrator://hooks/definition/{hookName}/{fileType}\nfileType options: readme, script, template"
                            )
                        )
                    )
                }

                val hooksDir = directoryManager.getHooksDir()

                val targetFile = when (fileType?.lowercase()) {
                    "script" -> hooksDir.resolve("scripts/$hookName.sh")
                    "template" -> hooksDir.resolve("templates/$hookName.config.example.json")
                    else -> hooksDir.resolve("$hookName-README.md") // default to readme
                }

                if (!Files.exists(targetFile)) {
                    return@addResource ReadResourceResult(
                        contents = listOf(
                            TextResourceContents(
                                uri = uri,
                                mimeType = "text/plain",
                                text = "Error: Hook file not found: ${targetFile.fileName}\n\nRun setup_claude_agents tool to initialize Hooks."
                            )
                        )
                    )
                }

                val content = Files.readString(targetFile)
                val fileName = targetFile.fileName.toString()
                val mimeType = when {
                    fileName.endsWith(".md") -> "text/markdown"
                    fileName.endsWith(".sh") -> "text/x-shellscript"
                    fileName.endsWith(".json") -> "application/json"
                    else -> "text/plain"
                }

                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = uri,
                            mimeType = mimeType,
                            text = content
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to get Hook definition", e)
                ReadResourceResult(
                    contents = listOf(
                        TextResourceContents(
                            uri = request.uri ?: "task-orchestrator://hooks/definition",
                            mimeType = "text/plain",
                            text = "Error: Failed to get Hook definition: ${e.message}"
                        )
                    )
                )
            }
        }
    }

    /**
     * Extract description from SKILL.md file content.
     * Looks for description in YAML frontmatter or first paragraph.
     */
    private fun extractDescription(content: String): String {
        // Try to extract from YAML frontmatter
        val frontmatterRegex = Regex("^---\\s*\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL)
        val frontmatterMatch = frontmatterRegex.find(content)

        if (frontmatterMatch != null) {
            val frontmatter = frontmatterMatch.groupValues[1]
            val descriptionLine = frontmatter.lines().find { it.trim().startsWith("description:") }
            if (descriptionLine != null) {
                return descriptionLine.substringAfter("description:").trim().removeSurrounding("\"")
            }
        }

        // Fallback: extract first non-empty paragraph after frontmatter
        val afterFrontmatter = if (frontmatterMatch != null) {
            content.substringAfter("---", "").substringAfter("---", "")
        } else {
            content
        }

        val firstParagraph = afterFrontmatter.trim()
            .lines()
            .dropWhile { it.trim().isEmpty() || it.trim().startsWith("#") }
            .takeWhile { it.trim().isNotEmpty() }
            .joinToString(" ")
            .trim()

        return firstParagraph.take(200) + if (firstParagraph.length > 200) "..." else ""
    }

    /**
     * Extract skill name and file name from URI path.
     * Example: task-orchestrator://skills/definition/feature-management/SKILL.md
     */
    private fun extractSkillNameAndFile(uri: String): Pair<String?, String?> {
        return try {
            val pathPart = uri.substringBefore("?")
            val parts = pathPart.split("/")
            val definitionIndex = parts.indexOf("definition")

            if (definitionIndex >= 0 && definitionIndex < parts.size - 1) {
                val skillName = parts[definitionIndex + 1]
                val fileName = if (definitionIndex < parts.size - 2) {
                    parts[definitionIndex + 2]
                } else {
                    null
                }
                skillName to fileName
            } else {
                null to null
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract skill name and file from URI: $uri", e)
            null to null
        }
    }

    /**
     * Extract hook name and file type from URI path.
     * Example: task-orchestrator://hooks/definition/task-complete-commit/script
     */
    private fun extractHookNameAndFileType(uri: String): Pair<String?, String?> {
        return try {
            val pathPart = uri.substringBefore("?")
            val parts = pathPart.split("/")
            val definitionIndex = parts.indexOf("definition")

            if (definitionIndex >= 0 && definitionIndex < parts.size - 1) {
                val hookName = parts[definitionIndex + 1]
                val fileType = if (definitionIndex < parts.size - 2) {
                    parts[definitionIndex + 2]
                } else {
                    null
                }
                hookName to fileType
            } else {
                null to null
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract hook name and file type from URI: $uri", e)
            null to null
        }
    }
}

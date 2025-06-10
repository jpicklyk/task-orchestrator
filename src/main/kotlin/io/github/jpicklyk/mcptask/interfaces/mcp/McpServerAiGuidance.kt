package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject

/**
 * Extension functions to provide AI guidance for the MCP server.
 */
object McpServerAiGuidance {

    /**
     * Configures AI guidance for the MCP server.
     * 
     * Note: This previously contained instruction "prompts" that were actually documentation.
     * Those have been removed as they don't align with MCP prompt concepts.
     * MCP prompts should be user-invokable templates for specific interactions.
     * 
     * Usage guidance should instead be provided through:
     * - Enhanced tool descriptions and parameter documentation
     * - Server-level metadata and descriptions
     * - MCP resources for detailed documentation (if needed)
     */
    fun Server.configureAiGuidance() {
        // Add template management guidance
        TemplateMgtGuidance.configureTemplateManagementGuidance(this)
    }

}
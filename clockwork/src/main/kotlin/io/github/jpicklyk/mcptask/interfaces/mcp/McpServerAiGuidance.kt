package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Extension functions to provide AI guidance for the MCP server.
 *
 * Guidance is delivered through MCP Resources (discoverable reference docs)
 * registered in [TaskOrchestratorResources]. No MCP prompts are registered;
 * tool descriptions and resources provide all necessary context.
 */
object McpServerAiGuidance {

    /**
     * Configures AI guidance for the MCP server.
     *
     * Usage guidance is provided through:
     * - MCP Resources (discoverable guidelines for AI agents)
     * - Enhanced tool descriptions and parameter documentation
     * - Server-level metadata and descriptions
     */
    fun Server.configureAiGuidance() {
        // Configure MCP Resources for AI guidelines
        TaskOrchestratorResources.configure(this)
    }
}
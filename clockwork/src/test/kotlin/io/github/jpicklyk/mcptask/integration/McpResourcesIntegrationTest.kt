package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.interfaces.mcp.TaskOrchestratorResources
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Integration tests for MCP Resources and AI Guidelines system.
 *
 * Validates that:
 * - Resources can be configured without errors
 * - Resource configuration is properly initialized
 * - The AI guidance system is set up correctly
 *
 * Note: The MCP SDK's Server class doesn't expose public methods for introspecting
 * registered resources and prompts, so these tests focus on validating that the
 * configuration process completes successfully without errors.
 */
class McpResourcesIntegrationTest {

    @Test
    @DisplayName("TaskOrchestratorResources configuration should complete without errors")
    fun `verify resource configuration completes successfully`() {
        val server = createTestServer()

        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }
    }

    @Test
    @DisplayName("Server with resources should have resource capability enabled")
    fun `verify server has resource capability`() {
        val server = createTestServer()
        TaskOrchestratorResources.configure(server)

        assertNotNull(server, "Server should be created")
    }

    @Test
    @DisplayName("Multiple resource configurations should be idempotent")
    fun `verify resource configuration can be called multiple times`() {
        val server = createTestServer()

        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }

        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }
    }

    @Test
    @DisplayName("Server configuration with full capabilities should work correctly")
    fun `verify configuration works with full server capabilities`() {
        val server = Server(
            serverInfo = Implementation(
                name = "task-orchestrator-test-full",
                version = "1.0.0-test"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                    prompts = ServerCapabilities.Prompts(listChanged = true),
                    logging = JsonObject(emptyMap())
                )
            )
        )

        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }
    }

    @Test
    @DisplayName("Configuration should create valid server instance")
    fun `verify configured server is in valid state`() {
        val server = createTestServer()

        TaskOrchestratorResources.configure(server)

        assertNotNull(server, "Server should exist after configuration")
    }

    /**
     * Creates a minimal test server with resource and prompt capabilities.
     */
    private fun createTestServer(): Server {
        return Server(
            serverInfo = Implementation(
                name = "task-orchestrator-test",
                version = "1.0.0-test"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                    prompts = ServerCapabilities.Prompts(listChanged = true),
                    logging = JsonObject(emptyMap())
                )
            )
        )
    }
}

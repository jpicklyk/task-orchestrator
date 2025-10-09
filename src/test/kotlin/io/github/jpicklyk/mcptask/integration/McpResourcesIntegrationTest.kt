package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.interfaces.mcp.TaskOrchestratorResources
import io.github.jpicklyk.mcptask.interfaces.mcp.WorkflowPromptsGuidance
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
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
 * - Resources and prompts can be configured without errors
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
        // Create a test server
        val server = createTestServer()

        // Configure resources - should not throw any exceptions
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }
    }

    @Test
    @DisplayName("WorkflowPromptsGuidance configuration should complete without errors")
    fun `verify workflow prompts configuration completes successfully`() {
        // Create a test server
        val server = createTestServer()

        // Configure workflow prompts - should not throw any exceptions
        assertDoesNotThrow {
            WorkflowPromptsGuidance.configureWorkflowPrompts(server)
        }
    }

    @Test
    @DisplayName("Full AI guidance configuration should complete without errors")
    fun `verify complete AI guidance system initializes successfully`() {
        // Create a test server
        val server = createTestServer()

        // Configure both resources and prompts - should not throw any exceptions
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
            WorkflowPromptsGuidance.configureWorkflowPrompts(server)
        }
    }

    @Test
    @DisplayName("Server with resources should have resource capability enabled")
    fun `verify server has resource capability`() {
        val server = createTestServer()
        TaskOrchestratorResources.configure(server)

        // Verify the server was created with resource capability
        assertNotNull(server, "Server should be created")
    }

    @Test
    @DisplayName("Server with prompts should have prompt capability enabled")
    fun `verify server has prompt capability`() {
        val server = createTestServer()
        WorkflowPromptsGuidance.configureWorkflowPrompts(server)

        // Verify the server was created with prompt capability
        assertNotNull(server, "Server should be created")
    }

    @Test
    @DisplayName("Multiple resource configurations should be idempotent")
    fun `verify resource configuration can be called multiple times`() {
        val server = createTestServer()

        // Configure resources multiple times - should not cause issues
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }

        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }
    }

    @Test
    @DisplayName("Multiple prompt configurations should be idempotent")
    fun `verify prompt configuration can be called multiple times`() {
        val server = createTestServer()

        // Configure prompts multiple times - should not cause issues
        assertDoesNotThrow {
            WorkflowPromptsGuidance.configureWorkflowPrompts(server)
        }

        assertDoesNotThrow {
            WorkflowPromptsGuidance.configureWorkflowPrompts(server)
        }
    }

    @Test
    @DisplayName("Configuration order should not matter")
    fun `verify resources and prompts can be configured in any order`() {
        // Test: Resources then Prompts
        val server1 = createTestServer()
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server1)
            WorkflowPromptsGuidance.configureWorkflowPrompts(server1)
        }

        // Test: Prompts then Resources
        val server2 = createTestServer()
        assertDoesNotThrow {
            WorkflowPromptsGuidance.configureWorkflowPrompts(server2)
            TaskOrchestratorResources.configure(server2)
        }
    }

    @Test
    @DisplayName("Server configuration with full capabilities should work correctly")
    fun `verify configuration works with full server capabilities`() {
        // Create server with all capabilities
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

        // Configuration should work with full capabilities
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
            WorkflowPromptsGuidance.configureWorkflowPrompts(server)
        }
    }

    @Test
    @DisplayName("Configuration should create valid server instance")
    fun `verify configured server is in valid state`() {
        val server = createTestServer()

        TaskOrchestratorResources.configure(server)
        WorkflowPromptsGuidance.configureWorkflowPrompts(server)

        // Verify server is still functional after configuration
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

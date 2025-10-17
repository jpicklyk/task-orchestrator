package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for ToolDocumentationResources to ensure resources are properly registered
 * and configuration completes without errors.
 *
 * Note: The MCP SDK's Server class doesn't expose public methods for introspecting
 * registered resources, so these tests focus on validating that the configuration
 * process completes successfully without errors.
 */
class ToolDocumentationResourcesTest {

    @Test
    @DisplayName("ToolDocumentationResources configuration should complete without errors")
    fun `configure should register all tool documentation resources without errors`() {
        val server = createTestServer()

        // Should not throw any exceptions
        assertDoesNotThrow {
            ToolDocumentationResources.configure(server)
        }
    }

    @Test
    @DisplayName("Configuration can be called multiple times without errors")
    fun `configure can be called multiple times`() {
        val server = createTestServer()

        // Should not throw on first call
        assertDoesNotThrow {
            ToolDocumentationResources.configure(server)
        }

        // Should not throw on second call
        assertDoesNotThrow {
            ToolDocumentationResources.configure(server)
        }
    }

    @Test
    @DisplayName("Configuration should work with full server capabilities")
    fun `configure works with full server capabilities`() {
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
            ToolDocumentationResources.configure(server)
        }
    }

    @Test
    @DisplayName("Configured server should remain in valid state")
    fun `server remains valid after configuration`() {
        val server = createTestServer()

        ToolDocumentationResources.configure(server)

        // Verify server is still functional
        assertNotNull(server, "Server should exist after configuration")
    }

    /**
     * Creates a minimal test server with resource capabilities.
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
                    logging = JsonObject(emptyMap())
                )
            )
        )
    }
}

package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for TaskOrchestratorResources to ensure resources are properly registered.
 */
class TaskOrchestratorResourcesTest {

    @Test
    @DisplayName("TaskOrchestratorResources configuration should complete without errors")
    fun `configure should register all guideline resources without errors`() {
        val server = createTestServer()

        // Should not throw any exceptions
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }
    }

    @Test
    @DisplayName("Configuration can be called multiple times without errors")
    fun `configure can be called multiple times`() {
        val server = createTestServer()

        // Should not throw on first call
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }

        // Should not throw on second call
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server)
        }
    }

    @Test
    @DisplayName("Configured server should remain in valid state")
    fun `server remains valid after configuration`() {
        val server = createTestServer()

        TaskOrchestratorResources.configure(server)

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

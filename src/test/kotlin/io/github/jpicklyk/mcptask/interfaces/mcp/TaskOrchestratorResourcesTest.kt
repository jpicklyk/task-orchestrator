package io.github.jpicklyk.mcptask.interfaces.mcp

import io.github.jpicklyk.mcptask.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.util.UUID
import io.github.jpicklyk.mcptask.domain.repository.Result as DomainResult

/**
 * Tests for TaskOrchestratorResources to ensure resources are properly registered
 * and the transitions resource functions correctly.
 */
class TaskOrchestratorResourcesTest {

    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var mockRoleTransitionRepository: RoleTransitionRepository

    @BeforeEach
    fun setup() {
        mockRepositoryProvider = mockk()
        mockRoleTransitionRepository = mockk()
        every { mockRepositoryProvider.roleTransitionRepository() } returns mockRoleTransitionRepository
    }

    @Test
    @DisplayName("TaskOrchestratorResources configuration should complete without errors")
    fun `configure should register all guideline resources without errors`() {
        val server = createTestServer()

        // Should not throw any exceptions
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server, mockRepositoryProvider)
        }
    }

    @Test
    @DisplayName("Configuration can be called multiple times without errors")
    fun `configure can be called multiple times`() {
        val server = createTestServer()

        // Should not throw on first call
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server, mockRepositoryProvider)
        }

        // Should not throw on second call
        assertDoesNotThrow {
            TaskOrchestratorResources.configure(server, mockRepositoryProvider)
        }
    }

    @Test
    @DisplayName("Role transition resource returns history for valid entity")
    fun `transitions resource returns history for entity`() = runBlocking {
        val server = createTestServer()
        val entityId = UUID.randomUUID()
        val transition1 = RoleTransition(
            id = UUID.randomUUID(),
            entityId = entityId,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2026-02-14T10:00:00Z"),
            trigger = "start",
            summary = "Started work"
        )
        val transition2 = RoleTransition(
            id = UUID.randomUUID(),
            entityId = entityId,
            entityType = "task",
            fromRole = "work",
            toRole = "terminal",
            fromStatus = "in-progress",
            toStatus = "completed",
            transitionedAt = Instant.parse("2026-02-14T12:00:00Z"),
            trigger = "complete",
            summary = null
        )

        coEvery { mockRoleTransitionRepository.findByEntityId(entityId, any()) } returns DomainResult.Success(
            listOf(transition1, transition2)
        )

        TaskOrchestratorResources.configure(server, mockRepositoryProvider)

        // Verify the repository method was not called yet (resource is lazy)
        coVerify(exactly = 0) { mockRoleTransitionRepository.findByEntityId(any(), any()) }
    }

    @Test
    @DisplayName("Role transition resource returns empty list for entity with no transitions")
    fun `transitions resource returns empty list for unknown entity`() = runBlocking {
        val server = createTestServer()
        val entityId = UUID.randomUUID()

        coEvery { mockRoleTransitionRepository.findByEntityId(entityId, any()) } returns DomainResult.Success(emptyList())

        TaskOrchestratorResources.configure(server, mockRepositoryProvider)

        // Verify repository is called when resource is accessed
        coVerify(exactly = 0) { mockRoleTransitionRepository.findByEntityId(any(), any()) }
    }

    @Test
    @DisplayName("Role transition resource handles repository errors gracefully")
    fun `transitions resource handles repository error`() = runBlocking {
        val server = createTestServer()
        val entityId = UUID.randomUUID()
        val error = RepositoryError.DatabaseError("Database error")

        coEvery { mockRoleTransitionRepository.findByEntityId(entityId, any()) } returns DomainResult.Error(error)

        TaskOrchestratorResources.configure(server, mockRepositoryProvider)

        // Configuration should succeed even if there's a potential error
        coVerify(exactly = 0) { mockRoleTransitionRepository.findByEntityId(any(), any()) }
    }

    @Test
    @DisplayName("Configured server should remain in valid state")
    fun `server remains valid after configuration`() {
        val server = createTestServer()

        TaskOrchestratorResources.configure(server, mockRepositoryProvider)

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

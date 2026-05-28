package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.ktor.server.application.ApplicationCall
import io.ktor.util.Attributes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuthorizationPluginTest {
    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private fun makePrincipal(
        capabilities: Set<ApiCapability> = setOf(ApiCapability.READ),
        rootIds: Set<UUID>? = null,
        tagsInclude: Set<String> = emptySet(),
    ): ApiPrincipal =
        ApiPrincipal(
            tokenId = "test-token",
            scope = ApiScope(rootIds = rootIds, tagsInclude = tagsInclude),
            capabilities = capabilities,
            authMode = ApiAuthMode.BEARER,
        )

    private fun makeCall(principal: ApiPrincipal?): ApplicationCall {
        val call = mockk<ApplicationCall>()
        val attrs = Attributes()
        if (principal != null) {
            attrs.put(ApiPrincipalKey, principal)
        }
        every { call.attributes } returns attrs
        return call
    }

    private fun makeWorkItem(
        id: UUID = UUID.randomUUID(),
        parentId: UUID? = null,
        tags: String? = null,
    ): WorkItem =
        WorkItem(
            id = id,
            title = "Test item",
            type = "task",
            priority = Priority.MEDIUM,
            role = Role.QUEUE,
            parentId = parentId,
            tags = tags,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
        )

    // -------------------------------------------------------------------------
    // hasCapability
    // -------------------------------------------------------------------------

    @Test
    fun `hasCapability returns true when principal has exact capability`() {
        val call = makeCall(makePrincipal(capabilities = setOf(ApiCapability.READ)))
        assertTrue(hasCapability(call, ApiCapability.READ))
    }

    @Test
    fun `hasCapability returns false when principal lacks capability`() {
        val call = makeCall(makePrincipal(capabilities = setOf(ApiCapability.READ)))
        assertFalse(hasCapability(call, ApiCapability.WRITE_ITEMS))
    }

    @Test
    fun `hasCapability returns true for ADMIN principal on any capability`() {
        val call = makeCall(makePrincipal(capabilities = setOf(ApiCapability.ADMIN)))
        assertTrue(hasCapability(call, ApiCapability.READ))
        assertTrue(hasCapability(call, ApiCapability.WRITE_ITEMS))
        assertTrue(hasCapability(call, ApiCapability.ADVANCE))
        assertTrue(hasCapability(call, ApiCapability.MANAGE_DEPENDENCIES))
    }

    @Test
    fun `hasCapability returns false when no principal attached`() {
        val call = makeCall(null)
        assertFalse(hasCapability(call, ApiCapability.READ))
    }

    @Test
    fun `hasCapability recognises WRITE_NOTES capability`() {
        val call = makeCall(makePrincipal(capabilities = setOf(ApiCapability.READ, ApiCapability.WRITE_NOTES)))
        assertTrue(hasCapability(call, ApiCapability.WRITE_NOTES))
        assertFalse(hasCapability(call, ApiCapability.WRITE_ITEMS))
    }

    // -------------------------------------------------------------------------
    // enforceScopeForItem — unrestricted scope (rootIds = null)
    // -------------------------------------------------------------------------

    @Test
    fun `unrestricted principal always passes scope check`() =
        runTest {
            val itemId = UUID.randomUUID()
            val call = makeCall(makePrincipal(rootIds = null))
            val repo = mockk<WorkItemRepository>()
            // Should not call findAncestorChains for unrestricted principals
            assertTrue(enforceScopeForItem(call, itemId, repo))
        }

    // -------------------------------------------------------------------------
    // enforceScopeForItem — root_ids scope enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `item in scope passes when it IS a root`() =
        runTest {
            val rootId = UUID.randomUUID()
            val call = makeCall(makePrincipal(rootIds = setOf(rootId)))
            val repo = mockk<WorkItemRepository>()
            // The item itself is rootId, so the ancestor chain is empty (it's a root).
            coEvery { repo.findAncestorChains(setOf(rootId)) } returns
                Result.Success(mapOf(rootId to emptyList()))

            assertTrue(enforceScopeForItem(call, rootId, repo))
        }

    @Test
    fun `item in scope passes when ancestor is a root`() =
        runTest {
            val rootId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val root = makeWorkItem(id = rootId)
            val call = makeCall(makePrincipal(rootIds = setOf(rootId)))
            val repo = mockk<WorkItemRepository>()
            // The child's ancestor chain contains the root.
            coEvery { repo.findAncestorChains(setOf(childId)) } returns
                Result.Success(mapOf(childId to listOf(root)))

            assertTrue(enforceScopeForItem(call, childId, repo))
        }

    @Test
    fun `item NOT in scope fails when ancestor chain does not include root`() =
        runTest {
            val allowedRoot = UUID.randomUUID()
            val otherRoot = UUID.randomUUID()
            val itemId = UUID.randomUUID()
            val other = makeWorkItem(id = otherRoot)
            val call = makeCall(makePrincipal(rootIds = setOf(allowedRoot)))
            val repo = mockk<WorkItemRepository>()
            // Item belongs to a different root.
            coEvery { repo.findAncestorChains(setOf(itemId)) } returns
                Result.Success(mapOf(itemId to listOf(other)))

            assertFalse(enforceScopeForItem(call, itemId, repo))
        }

    @Test
    fun `item with no principal attached returns false`() =
        runTest {
            val call = makeCall(null)
            val repo = mockk<WorkItemRepository>()
            assertFalse(enforceScopeForItem(call, UUID.randomUUID(), repo))
        }

    // -------------------------------------------------------------------------
    // enforceScopeForItem — tag scope enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `item with matching tag passes tag scope check`() =
        runTest {
            val itemId = UUID.randomUUID()
            val item = makeWorkItem(id = itemId, tags = "feature,api")
            val call = makeCall(makePrincipal(rootIds = null, tagsInclude = setOf("api")))
            val repo = mockk<WorkItemRepository>()
            // rootIds null -> no ancestor chain lookup needed.
            // Tag check requires getById.
            coEvery { repo.getById(itemId) } returns Result.Success(item)

            assertTrue(enforceScopeForItem(call, itemId, repo))
        }

    @Test
    fun `item without matching tag fails tag scope check`() =
        runTest {
            val itemId = UUID.randomUUID()
            val item = makeWorkItem(id = itemId, tags = "bug,database")
            val call = makeCall(makePrincipal(rootIds = null, tagsInclude = setOf("api", "feature")))
            val repo = mockk<WorkItemRepository>()
            coEvery { repo.getById(itemId) } returns Result.Success(item)

            assertFalse(enforceScopeForItem(call, itemId, repo))
        }

    @Test
    fun `item with no tags fails tag scope check when tags required`() =
        runTest {
            val itemId = UUID.randomUUID()
            val item = makeWorkItem(id = itemId, tags = null)
            val call = makeCall(makePrincipal(rootIds = null, tagsInclude = setOf("feature")))
            val repo = mockk<WorkItemRepository>()
            coEvery { repo.getById(itemId) } returns Result.Success(item)

            assertFalse(enforceScopeForItem(call, itemId, repo))
        }

    @Test
    fun `empty tagsInclude means no tag constraint — item passes`() =
        runTest {
            val itemId = UUID.randomUUID()
            val call = makeCall(makePrincipal(rootIds = null, tagsInclude = emptySet()))
            val repo = mockk<WorkItemRepository>()
            // No ancestor chain or tag lookup needed for unrestricted scope with no tag filter.
            assertTrue(enforceScopeForItem(call, itemId, repo))
        }

    @Test
    fun `repository error on findAncestorChains returns false`() =
        runTest {
            val rootId = UUID.randomUUID()
            val itemId = UUID.randomUUID()
            val call = makeCall(makePrincipal(rootIds = setOf(rootId)))
            val repo = mockk<WorkItemRepository>()
            coEvery { repo.findAncestorChains(setOf(itemId)) } returns
                Result.Error(RepositoryError.DatabaseError("DB error"))

            assertFalse(enforceScopeForItem(call, itemId, repo))
        }
}

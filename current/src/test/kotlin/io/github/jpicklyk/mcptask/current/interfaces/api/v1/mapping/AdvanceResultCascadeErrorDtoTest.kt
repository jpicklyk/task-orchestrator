package io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping

import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.unscopedUnitOfWork
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item a87394a6 — scenario S6 of the frozen `test-plan`
 * note: `AdvanceResult.toDto(existingNoteKeys)` maps a non-null `AdvanceCascadeEvent.error` through
 * to `CascadeEventDto.error` unchanged, and a null `error` maps to null.
 *
 * Oracle [C]: this item's own `diagnosis` Fix 3/4 — "Mappers.kt maps each AdvanceCascadeEvent ->
 * CascadeEventDto incl. error = event.error" (an identity mapping, cited verbatim). The
 * `cascadeEvents` field name on both `AdvanceResult` and the REST DTO is confirmed by existing
 * src/test evidence: `AdvanceServiceTest` accesses `success.result.cascadeEvents`, and
 * `AdvanceParityTest` reads the REST response's `restJson["cascadeEvents"]` — the same name at
 * both layers, with no `@SerialName` override in evidence anywhere in src/test.
 *
 * This test drives a real `AdvanceOutcome.Success.result` out of `AdvanceService.advance()` (never
 * constructed by hand — `AdvanceResult`'s full constructor was not among the supplied declarations)
 * and maps it with the real `toDto()` extension, exactly as `AdvanceItemTool`/`CompleteTreeTool`/
 * the REST advance route do. Harness mirrors `AdvanceServiceLeaseGateTest`'s start-cascade fixtures
 * (MockK repositories, `inTransaction` passthrough) — never the implementer's changed function
 * bodies, never a diff.
 */
class AdvanceResultCascadeErrorDtoTest {
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyStore
    private lateinit var roleTransitionRepo: TransitionStore
    private lateinit var noteRepo: NoteStore
    private lateinit var leaseRepo: LeaseStore

    @BeforeEach
    fun setUp() {
        workItemRepo = mockk()
        depRepo = mockk()
        roleTransitionRepo = mockk()
        noteRepo = mockk()
        leaseRepo = mockk()

        coEvery { workItemRepo.update(any()) } answers { firstArg() }
        coEvery { roleTransitionRepo.create(any()) } returns mockk()
        coEvery { noteRepo.findByItemId(any()) } returns emptyList()
        every { depRepo.findByToItemId(any()) } returns emptyList()
        every { depRepo.findByFromItemId(any()) } returns emptyList()
    }

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE,
        title: String = "Item",
        parentId: UUID? = null,
    ): WorkItem =
        WorkItem(
            id = id,
            title = title,
            role = role,
            parentId = parentId,
            depth = if (parentId != null) 1 else 0,
        )

    private fun serviceWith(requirementsByItem: Map<UUID, List<ResourceRequirement>>): AdvanceService =
        AdvanceService(
            workItemRepository = workItemRepo,
            roleTransitionRepository = roleTransitionRepo,
            dependencyRepository = depRepo,
            noteRepository = noteRepo,
            statusLabelService = NoOpStatusLabelService,
            schemaResolver = { null },
            resourceLeaseRepository = leaseRepo,
            resourceRequirementsResolver = { item -> requirementsByItem[item.id] ?: emptyList() },
            resourceRegistryResolver = { emptyMap() },
            resourceLeasesEnforced = true,
            unitOfWork = unscopedUnitOfWork(),
        )

    private fun exclusive(key: String): ResourceRequirement = ResourceRequirement(key, ResourceMode.EXCLUSIVE, null)

    private fun lease(
        holderItemId: UUID,
        key: String,
    ): ResourceLease {
        val now = Instant.now()
        return ResourceLease(
            resourceKey = key,
            holderItemId = holderItemId,
            acquiredAt = now,
            expiresAt = now.plusSeconds(600),
            originalAcquiredAt = now,
            version = 0,
        )
    }

    @Test
    fun `S6 toDto maps a non-null cascade error through unchanged`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(parentId) } returns parent
            coEvery { leaseRepo.acquireAll(parentId, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(parentId, "staging-db")))
            coEvery { workItemRepo.update(match { it.id == parentId }) } throws IllegalStateException("boom")
            coEvery { leaseRepo.releaseAllForItem(parentId) } returns LeaseReleaseResult.Success(1)

            val outcome =
                serviceWith(mapOf(parentId to listOf(exclusive("staging-db")))).advance(
                    child,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val domainCascade = success.result.cascadeEvents.single()
            assertTrue(domainCascade.error?.isNotBlank() == true, "fixture must produce a non-null domain error to map")

            val dto = success.result.toDto(existingNoteKeys = emptySet())
            val dtoCascade = dto.cascadeEvents.single()

            assertEquals(domainCascade.error, dtoCascade.error, "toDto must map error = event.error unchanged")
        }

    @Test
    fun `S6 toDto maps a null cascade error to null`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(parentId) } returns parent
            coEvery { leaseRepo.acquireAll(parentId, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(parentId, "staging-db")))
            // Parent apply succeeds this time: the cascade is applied, not failed.
            coEvery { workItemRepo.update(match { it.id == parentId }) } answers { firstArg() }

            val outcome =
                serviceWith(mapOf(parentId to listOf(exclusive("staging-db")))).advance(
                    child,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val domainCascade = success.result.cascadeEvents.single()
            assertTrue(domainCascade.applied)
            assertNull(domainCascade.error)

            val dto = success.result.toDto(existingNoteKeys = emptySet())
            val dtoCascade = dto.cascadeEvents.single()

            assertNull(dtoCascade.error, "a successful (non-failing) cascade must map to a null DTO error")
        }

    @Test
    fun `toDto maps roleBlocked and omits blockers for a BLOCKED parent terminal cascade`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.BLOCKED, title = "Parent")
            val child = makeItem(role = Role.WORK, title = "Child", parentId = parentId)
            coEvery { workItemRepo.getById(child.id) } returns child
            coEvery { workItemRepo.getById(parentId) } returns parent
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns mapOf(Role.TERMINAL to 1)
            coEvery { leaseRepo.releaseAllForItem(any()) } returns LeaseReleaseResult.Success(0)

            val outcome =
                serviceWith(emptyMap()).advance(child, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val dtoCascade =
                success.result
                    .toDto(existingNoteKeys = emptySet())
                    .cascadeEvents
                    .single()
            assertEquals(false, dtoCascade.applied)
            assertTrue(dtoCascade.roleBlocked)
            assertEquals(false, dtoCascade.dependencyBlocked)
            assertNull(dtoCascade.blockers)
        }

    @Test
    fun `toDto maps dependencyBlocked and blockers for a dependency-blocked parent terminal cascade`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val blockerId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.WORK, title = "Parent")
            val child = makeItem(role = Role.WORK, title = "Child", parentId = parentId)
            val blocker = makeItem(id = blockerId, role = Role.QUEUE, title = "Blocker")
            coEvery { workItemRepo.getById(child.id) } returns child
            coEvery { workItemRepo.getById(parentId) } returns parent
            coEvery { workItemRepo.getById(blockerId) } returns blocker
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns mapOf(Role.TERMINAL to 1)
            coEvery { leaseRepo.releaseAllForItem(any()) } returns LeaseReleaseResult.Success(0)
            every { depRepo.findByToItemId(parentId) } returns
                listOf(Dependency(fromItemId = blockerId, toItemId = parentId, type = DependencyType.BLOCKS))

            val outcome =
                serviceWith(emptyMap()).advance(child, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val dtoCascade =
                success.result
                    .toDto(existingNoteKeys = emptySet())
                    .cascadeEvents
                    .single()
            assertEquals(false, dtoCascade.applied)
            assertTrue(dtoCascade.dependencyBlocked)
            assertEquals(false, dtoCascade.roleBlocked)
            val blockerDto = dtoCascade.blockers!!.single()
            assertEquals(blockerId.toString(), blockerDto.fromItemId)
            assertEquals("queue", blockerDto.currentRole)
            assertEquals("terminal", blockerDto.requiredRole)
        }

    @Test
    fun `toDto leaves roleBlocked dependencyBlocked and blockers unset for an applied terminal cascade`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.WORK, title = "Parent")
            val child = makeItem(role = Role.WORK, title = "Child", parentId = parentId)
            coEvery { workItemRepo.getById(child.id) } returns child
            coEvery { workItemRepo.getById(parentId) } returns parent
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns mapOf(Role.TERMINAL to 1)
            coEvery { leaseRepo.releaseAllForItem(any()) } returns LeaseReleaseResult.Success(0)

            val outcome =
                serviceWith(emptyMap()).advance(child, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val dtoCascade =
                success.result
                    .toDto(existingNoteKeys = emptySet())
                    .cascadeEvents
                    .single()
            assertTrue(dtoCascade.applied)
            assertEquals(false, dtoCascade.roleBlocked)
            assertEquals(false, dtoCascade.dependencyBlocked)
            assertNull(dtoCascade.blockers)
        }
}

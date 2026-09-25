package io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping

import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
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
    private lateinit var depRepo: DependencyRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var leaseRepo: ResourceLeaseRepository

    @BeforeEach
    fun setUp() {
        workItemRepo = mockk()
        depRepo = mockk()
        roleTransitionRepo = mockk()
        noteRepo = mockk()
        leaseRepo = mockk()

        coEvery { workItemRepo.dbNow() } returns Instant.now()
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { workItemRepo.inTransaction(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        coEvery { noteRepo.findByItemId(any()) } returns Result.Success(emptyList())
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

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { leaseRepo.acquireAll(parentId, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(parentId, "staging-db")))
            coEvery { workItemRepo.update(match { it.id == parentId }) } returns
                Result.Error(RepositoryError.ConflictError("boom"))
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

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { leaseRepo.acquireAll(parentId, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(parentId, "staging-db")))
            // Parent apply succeeds this time: the cascade is applied, not failed.
            coEvery { workItemRepo.update(match { it.id == parentId }) } answers { Result.Success(firstArg()) }

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
}

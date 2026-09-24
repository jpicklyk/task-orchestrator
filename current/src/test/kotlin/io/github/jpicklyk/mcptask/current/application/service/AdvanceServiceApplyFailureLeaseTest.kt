package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item a87394a6 (AR-15 slice: release leases when apply or
 * cascade fails; add `error` to [AdvanceCascadeEvent]).
 *
 * Oracles: `diagnosis` Fix 1-3 [R], `current/docs/workflow-guide.md` §11 :1117-1121 (a lease is
 * held only while in WORK) [L], `ResourceLease.kt`/`ResourceLeaseRepository.kt` KDoc — `version ==
 * 0` means "created by this call" [F], and this item's own `error` field contract [C]. Scenario
 * ids (S1-S3, S4, S7-S12 + probes) match the frozen `test-plan` note; do not renumber. S5 needs no
 * lease machinery so it is folded into this file too. S6 (DTO mapping) lives in
 * `AdvanceResultCascadeErrorDtoTest.kt` per the file-ownership row.
 *
 * Per the test-author blindness rule this file was written against the declarations supplied in
 * the dispatch prompt (AdvanceService's constructor and `advance()` signature, `AdvanceCascadeEvent`,
 * `ResourceLease`/`ResourceLeaseRepository`, `LeaseAcquireResult`/`LeaseReleaseResult`,
 * `AdvanceFailure.ApplyFailed`) plus the existing `AdvanceServiceLeaseGateTest` and
 * `AdvanceServiceTest` harnesses (fakes, mock wiring conventions) — never the implementer's changed
 * function bodies, never a diff.
 */
class AdvanceServiceApplyFailureLeaseTest {
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

        // Default lease behavior: acquires succeed with nothing, releases free one row. Individual
        // tests override as needed.
        coEvery { leaseRepo.acquireAll(any(), any(), any()) } returns LeaseAcquireResult.Success(emptyList())
        coEvery { leaseRepo.releaseAllForItem(any()) } returns LeaseReleaseResult.Success(1)
    }

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE,
        previousRole: Role? = null,
        title: String = "Item",
        parentId: UUID? = null,
    ): WorkItem =
        WorkItem(
            id = id,
            title = title,
            role = role,
            previousRole = previousRole,
            parentId = parentId,
            depth = if (parentId != null) 1 else 0,
        )

    private fun serviceWith(
        requirements: List<ResourceRequirement> = emptyList(),
        schema: WorkItemSchema? = null,
        schemaResolver: (suspend (WorkItem) -> WorkItemSchema?)? = null,
        leaseRepository: ResourceLeaseRepository? = leaseRepo,
        resourceLeasesEnforced: Boolean = true,
        requirementsByItem: Map<UUID, List<ResourceRequirement>>? = null,
    ): AdvanceService =
        AdvanceService(
            workItemRepository = workItemRepo,
            roleTransitionRepository = roleTransitionRepo,
            dependencyRepository = depRepo,
            noteRepository = noteRepo,
            statusLabelService = NoOpStatusLabelService,
            schemaResolver = schemaResolver ?: { schema },
            resourceLeaseRepository = leaseRepository,
            resourceRequirementsResolver = { item ->
                requirementsByItem?.get(item.id) ?: requirements
            },
            resourceRegistryResolver = { emptyMap() },
            resourceLeasesEnforced = resourceLeasesEnforced,
        )

    private fun exclusive(key: String): ResourceRequirement = ResourceRequirement(key, ResourceMode.EXCLUSIVE, null)

    private fun schemaOf(vararg entries: NoteSchemaEntry): WorkItemSchema = WorkItemSchema(type = "test", notes = entries.toList())

    /** A lease "created by this call" ([version] defaults to 0 per [ResourceLease] KDoc). */
    private fun lease(
        holderItemId: UUID,
        key: String,
        version: Int = 0,
    ): ResourceLease {
        val now = Instant.now()
        return ResourceLease(
            resourceKey = key,
            holderItemId = holderItemId,
            acquiredAt = now,
            expiresAt = now.plusSeconds(600),
            originalAcquiredAt = now,
            version = version,
        )
    }

    /** Stubs the conflict that models an apply-step failure, per `diagnosis`'s reproduction recipe. */
    private fun failApplyFor(
        id: UUID,
        message: String = "row version moved underneath the request",
    ) {
        coEvery { workItemRepo.update(match { it.id == id }) } returns Result.Error(RepositoryError.ConflictError(message))
    }

    // ──────────────────────────────────────────────
    // S1/S2 — primary advance: acquire succeeds, apply fails -> compensating release
    // ──────────────────────────────────────────────

    @Test
    fun `S1 start into work with a freshly acquired exclusive lease releases it when apply fails`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { workItemRepo.getById(item.id) } returns Result.Success(item)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(item.id, "staging-db")))
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            assertIs<AdvanceFailure.ApplyFailed>(failure.failure)
            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(item.id) }
        }

    @Test
    fun `S2 resume from BLOCKED into work with a freshly acquired lease releases it when apply fails`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.BLOCKED, previousRole = Role.WORK)
            coEvery { workItemRepo.getById(item.id) } returns Result.Success(item)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(item.id, "staging-db")))
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "resume",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            assertIs<AdvanceFailure.ApplyFailed>(failure.failure)
            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(item.id) }
        }

    // ──────────────────────────────────────────────
    // S3/S4 — start cascade: parent apply fails -> compensating release + non-blank error
    // ──────────────────────────────────────────────

    @Test
    fun `S3 S4 a start cascade whose parent apply fails releases the parent lease and records a non-blank error`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { leaseRepo.acquireAll(parentId, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(parentId, "staging-db")))
            failApplyFor(parentId)

            val outcome =
                serviceWith(
                    requirementsByItem = mapOf(parentId to listOf(exclusive("staging-db"))),
                ).advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome, "the child's own advance must still succeed")
            val cascade = success.result.cascadeEvents.single()
            assertEquals(parentId, cascade.itemId)
            assertFalse(cascade.applied)
            assertTrue(cascade.error?.isNotBlank() == true, "S4: cascade.error must be a non-blank reason")
            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(parentId) }
        }

    // ──────────────────────────────────────────────
    // S5 — terminal cascade: parent apply fails -> non-blank error (no lease machinery involved)
    // ──────────────────────────────────────────────

    @Test
    fun `S5 a terminal cascade whose parent apply fails is not applied and records a non-blank error`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.WORK, title = "Parent")
            val child = makeItem(id = childId, role = Role.WORK, title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns Result.Success(mapOf(Role.TERMINAL to 1))
            failApplyFor(parentId)

            val outcome =
                serviceWith().advance(child, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val cascade = success.result.cascadeEvents.single()
            assertFalse(cascade.applied)
            assertTrue(cascade.error?.isNotBlank() == true)
            // No resources were declared anywhere in this scenario: the terminal-cascade failure path
            // must not touch the lease store at all.
            verify { leaseRepo wasNot Called }
        }

    // ──────────────────────────────────────────────
    // S7 — a release DBError does not change the reported failure
    // ──────────────────────────────────────────────

    @Test
    fun `S7 a release DBError still reports ApplyFailed with the same message as the no-resource failure`(): Unit =
        runBlocking {
            val conflictMessage = "row version moved underneath the request"

            val baseline = makeItem(role = Role.QUEUE)
            coEvery { workItemRepo.getById(baseline.id) } returns Result.Success(baseline)
            failApplyFor(baseline.id, conflictMessage)
            val baselineOutcome =
                serviceWith().advance(baseline, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val baselineFailure = assertIs<AdvanceFailure.ApplyFailed>(assertIs<AdvanceOutcome.Failure>(baselineOutcome).failure)

            val withResource = makeItem(role = Role.QUEUE)
            coEvery { workItemRepo.getById(withResource.id) } returns Result.Success(withResource)
            coEvery { leaseRepo.acquireAll(withResource.id, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(withResource.id, "staging-db")))
            coEvery { leaseRepo.releaseAllForItem(withResource.id) } returns
                LeaseReleaseResult.DBError(IllegalStateException("db down"))
            failApplyFor(withResource.id, conflictMessage)
            val withResourceOutcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    withResource,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )
            val withResourceFailure =
                assertIs<AdvanceFailure.ApplyFailed>(assertIs<AdvanceOutcome.Failure>(withResourceOutcome).failure)

            assertEquals(
                baselineFailure.message,
                withResourceFailure.message,
                "a release DBError must not alter the reported apply-failure message",
            )
            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(withResource.id) }
        }

    // ──────────────────────────────────────────────
    // S8 — a concurrent transition that already reached WORK must not have its lease pulled
    // ──────────────────────────────────────────────

    @Test
    fun `S8 no release when the re-read shows the item already reached WORK`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(item.id, "staging-db")))
            failApplyFor(item.id)
            // A concurrent call already moved the item into WORK and now legitimately owns the lease.
            coEvery { workItemRepo.getById(item.id) } returns Result.Success(item.update { it.copy(role = Role.WORK) })

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            assertIs<AdvanceOutcome.Failure>(outcome)
            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }

    // ──────────────────────────────────────────────
    // S9 — a mixed (or all-refreshed) acquire result is never released
    // ──────────────────────────────────────────────

    @Test
    fun `S9 a mixed fresh-and-refreshed acquire result is never released on apply failure`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { workItemRepo.getById(item.id) } returns Result.Success(item)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns
                LeaseAcquireResult.Success(
                    listOf(lease(item.id, "k1", version = 0), lease(item.id, "k2", version = 1)),
                )
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("k1"), exclusive("k2"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            assertIs<AdvanceOutcome.Failure>(outcome)
            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }

    @Test
    fun `S9b an acquire result that is entirely refreshed (all version greater than zero) is never released`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { workItemRepo.getById(item.id) } returns Result.Success(item)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(item.id, "staging-db", version = 3)))
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            assertIs<AdvanceOutcome.Failure>(outcome)
            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }

    // ──────────────────────────────────────────────
    // S10 — no declared resources: the lease store is never touched, even on apply failure
    // ──────────────────────────────────────────────

    @Test
    fun `S10 no declared resources means the lease repository is never touched even when apply fails`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            failApplyFor(item.id)

            val outcome =
                serviceWith().advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            assertIs<AdvanceOutcome.Failure>(outcome)
            verify { leaseRepo wasNot Called }
        }

    // ──────────────────────────────────────────────
    // S11 — the three suppression/off-switch cases release zero leases
    // ──────────────────────────────────────────────

    @Test
    fun `S11a a contended acquire never reaches apply, so zero leases are released`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { leaseRepo.acquireAll(any(), any(), any()) } returns
                LeaseAcquireResult.Contended(listOf("staging-db"), retryAfterMs = 5_000)
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            assertIs<AdvanceOutcome.Failure>(outcome)
            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }

    @Test
    fun `S11b the RESOURCE_LEASES_ENFORCED kill switch off skips acquisition, so zero leases are released`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db")), resourceLeasesEnforced = false).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            assertIs<AdvanceOutcome.Failure>(outcome)
            coVerify(exactly = 0) { leaseRepo.acquireAll(any(), any(), any()) }
            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }

    @Test
    fun `S11c enforceResourceLeases false at the call site skips acquisition, so zero leases are released`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true,
                    enforceResourceLeases = false,
                )

            assertIs<AdvanceOutcome.Failure>(outcome)
            coVerify(exactly = 0) { leaseRepo.acquireAll(any(), any(), any()) }
            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }

    // ──────────────────────────────────────────────
    // S12 — a gate- or resource-suppressed cascade is not an apply failure: error stays null
    // ──────────────────────────────────────────────

    @Test
    fun `S12a a resource-suppressed start cascade leaves error null`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { leaseRepo.acquireAll(parentId, any(), any()) } returns
                LeaseAcquireResult.Contended(listOf("staging-db"), retryAfterMs = 5_000)

            val outcome =
                serviceWith(
                    requirementsByItem = mapOf(parentId to listOf(exclusive("staging-db"))),
                ).advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val cascade = success.result.cascadeEvents.single()
            assertTrue(cascade.resourceBlocked)
            assertNull(cascade.error, "a resource-suppressed cascade is not an apply failure")
        }

    @Test
    fun `S12b a gate-blocked terminal cascade leaves error null`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.WORK, title = "Parent")
            val child = makeItem(id = childId, role = Role.WORK, title = "Child", parentId = parentId)
            val parentSchema = schemaOf(NoteSchemaEntry("review", Role.REVIEW, required = true, description = "review"))

            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns Result.Success(mapOf(Role.TERMINAL to 1))
            coEvery { noteRepo.findByItemId(childId) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(emptyList())

            val outcome =
                serviceWith(
                    schemaResolver = { it -> if (it.id == parentId) parentSchema else null },
                ).advance(child, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val cascade = success.result.cascadeEvents.single()
            assertTrue(cascade.gateBlocked)
            assertFalse(cascade.applied)
            assertNull(cascade.error, "a gate-blocked cascade is not an apply failure")
        }

    // ──────────────────────────────────────────────
    // Probes (test-author skill §6 / dispatch contract Probes list)
    // ──────────────────────────────────────────────

    @Test
    fun `probe two requirements with only one contended still releases zero leases`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { workItemRepo.getById(item.id) } returns Result.Success(item)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns
                LeaseAcquireResult.Contended(listOf("prod-cred"), retryAfterMs = 10_000)
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"), exclusive("prod-cred"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            assertIs<AdvanceOutcome.Failure>(outcome)
            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }

    @Test
    fun `probe a release Success with zero rows released does not alter the ApplyFailed outcome`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { workItemRepo.getById(item.id) } returns Result.Success(item)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(item.id, "staging-db")))
            coEvery { leaseRepo.releaseAllForItem(item.id) } returns LeaseReleaseResult.Success(0)
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            assertIs<AdvanceFailure.ApplyFailed>(failure.failure)
            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(item.id) }
        }

    @Test
    fun `probe an acquireAll Success with an empty lease list is a no-op on apply failure`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { workItemRepo.getById(item.id) } returns Result.Success(item)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns LeaseAcquireResult.Success(emptyList())
            failApplyFor(item.id)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            assertIs<AdvanceOutcome.Failure>(outcome)
            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }

    @Test
    fun `probe replaying two failing advances releases once per call`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { workItemRepo.getById(item.id) } returns Result.Success(item)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(item.id, "staging-db")))
            failApplyFor(item.id)

            val service = serviceWith(requirements = listOf(exclusive("staging-db")))
            repeat(2) {
                service.advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            }

            coVerify(exactly = 2) { leaseRepo.releaseAllForItem(item.id) }
        }

    @Test
    fun `probe a successful apply after acquiring a fresh lease performs zero compensating release`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { leaseRepo.acquireAll(item.id, any(), any()) } returns
                LeaseAcquireResult.Success(listOf(lease(item.id, "staging-db")))

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                )

            assertIs<AdvanceOutcome.Success>(outcome)
            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }
}

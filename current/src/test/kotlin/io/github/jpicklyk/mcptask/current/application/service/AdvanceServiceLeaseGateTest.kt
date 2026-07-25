package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for step 4.5 of [AdvanceService] — the resource-lease gate — plus every release path
 * out of [Role.WORK] and the start-cascade suppression behavior.
 *
 * Conventions mirror [AdvanceServiceTest]: MockK repositories, `inTransaction` delegating straight
 * to its block, no real database. The lease repository and the two resource resolvers are injected
 * per-test so the enforcement matrix can be driven row by row.
 */
class AdvanceServiceLeaseGateTest {
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

        // Default lease behavior: acquires succeed, releases free one row.
        coEvery { leaseRepo.acquireAll(any(), any(), any()) } returns LeaseAcquireResult.Success(emptyList())
        coEvery { leaseRepo.releaseAllForItem(any()) } returns LeaseReleaseResult.Success(1)
    }

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE,
        previousRole: Role? = null,
        title: String = "Item",
        parentId: UUID? = null
    ): WorkItem =
        WorkItem(
            id = id,
            title = title,
            role = role,
            previousRole = previousRole,
            parentId = parentId,
            depth = if (parentId != null) 1 else 0
        )

    /**
     * Builds a service wired with the shared mocks plus the resource collaborators under test.
     * Every resource parameter is defaulted to its dormant value so each test states only the row
     * of the enforcement matrix it exercises.
     */
    private fun serviceWith(
        requirements: List<ResourceRequirement> = emptyList(),
        registry: Map<String, ResourceDefinition> = emptyMap(),
        schema: WorkItemSchema? = null,
        leaseRepository: ResourceLeaseRepository? = leaseRepo,
        resourceLeasesEnforced: Boolean = true,
        requirementsByItem: Map<UUID, List<ResourceRequirement>>? = null
    ): AdvanceService =
        AdvanceService(
            workItemRepository = workItemRepo,
            roleTransitionRepository = roleTransitionRepo,
            dependencyRepository = depRepo,
            noteRepository = noteRepo,
            statusLabelService = NoOpStatusLabelService,
            schemaResolver = { schema },
            resourceLeaseRepository = leaseRepository,
            resourceRequirementsResolver = { item ->
                requirementsByItem?.get(item.id) ?: requirements
            },
            resourceRegistryResolver = { registry },
            resourceLeasesEnforced = resourceLeasesEnforced
        )

    /** Captures the [RoleTransition] audit row written by the primary transition. */
    private fun captureTransition(): io.mockk.CapturingSlot<RoleTransition> {
        val slot = slot<RoleTransition>()
        coEvery { roleTransitionRepo.create(capture(slot)) } returns Result.Success(mockk())
        return slot
    }

    private fun exclusive(
        key: String,
        ttlSeconds: Int? = null
    ) = ResourceRequirement(key, ResourceMode.EXCLUSIVE, ttlSeconds)

    private fun advisory(key: String) = ResourceRequirement(key, ResourceMode.ADVISORY, null)

    // ──────────────────────────────────────────────
    // Short-circuit: the common (non-adopter) path
    // ──────────────────────────────────────────────

    @Test
    fun `no declared resources means ZERO lease repository interactions on start into work`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)

            val outcome =
                serviceWith().advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true
                )

            assertIs<AdvanceOutcome.Success>(outcome)
            // The whole point of the short-circuit: an item without resources must never touch the
            // lease store — not acquireAll, not releaseAllForItem, not a read.
            verify { leaseRepo wasNot Called }
        }

    @Test
    fun `no declared resources leaves caller credentialRefs untouched`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            val transition = captureTransition()

            serviceWith().advance(
                item,
                "start",
                null,
                null,
                null,
                DegradedModePolicy.ACCEPT_CACHED,
                enforceOwnership = true,
                credentialRefs = listOf("some-unregistered-label")
            )

            // Rung-1 behavior preserved: with no declarations and an empty registry there is no
            // set-membership check, so an arbitrary (well-formatted) label still passes through.
            assertEquals(listOf("some-unregistered-label"), transition.captured.consumedCredentials)
        }

    // ──────────────────────────────────────────────
    // Acquisition
    // ──────────────────────────────────────────────

    @Test
    fun `exclusive requirement is acquired on start into work`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            val requests = slot<List<Pair<String, Int>>>()
            coEvery { leaseRepo.acquireAll(item.id, any(), capture(requests)) } returns
                LeaseAcquireResult.Success(emptyList())

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db", ttlSeconds = 600))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true
                )

            assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(listOf("staging-db" to 600), requests.captured)
        }

    @Test
    fun `resume from BLOCKED into work acquires leases`(): Unit =
        runBlocking {
            // The note gate keys on trigger == start/complete and so ignores resume; the resource
            // gate keys on targetRole, which is why resume is covered.
            val item = makeItem(role = Role.BLOCKED, previousRole = Role.WORK)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "resume",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true
                )

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.WORK, success.result.newRole)
            coVerify(exactly = 1) { leaseRepo.acquireAll(item.id, any(), any()) }
        }

    @Test
    fun `contended acquire is rejected with ALL contended keys and the retry hint`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { leaseRepo.acquireAll(any(), any(), any()) } returns
                LeaseAcquireResult.Contended(listOf("staging-db", "prod-cred"), retryAfterMs = 45_000)

            val outcome =
                serviceWith(
                    requirements = listOf(exclusive("staging-db"), exclusive("prod-cred"))
                ).advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            val blocked = assertIs<AdvanceFailure.ResourceLeaseUnavailable>(failure.failure)
            assertEquals(listOf("staging-db", "prod-cred"), blocked.contendedResources)
            assertEquals(45_000L, blocked.retryAfterMs)
            assertEquals(Role.WORK, blocked.targetRole)
            // Nothing was persisted — the transition never reached applyTransition.
            coVerify(exactly = 0) { roleTransitionRepo.create(any()) }
        }

    @Test
    fun `lease store DBError is surfaced as a transient rejection with the default backoff`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            coEvery { leaseRepo.acquireAll(any(), any(), any()) } returns
                LeaseAcquireResult.DBError(IllegalStateException("SQLITE_BUSY_SNAPSHOT"))

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true
                )

            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            val blocked = assertIs<AdvanceFailure.ResourceLeaseUnavailable>(failure.failure)
            assertEquals(listOf("staging-db"), blocked.contendedResources)
            assertEquals(AdvanceService.LEASE_DB_ERROR_RETRY_AFTER_MS, blocked.retryAfterMs)
            assertTrue(blocked.message.contains("transient"), "message should name the transient cause")
        }

    @Test
    fun `advisory requirement is never leased but IS derived into consumedCredentials`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            val transition = captureTransition()

            val outcome =
                serviceWith(requirements = listOf(advisory("shared-runner"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true
                )

            assertIs<AdvanceOutcome.Success>(outcome)
            coVerify(exactly = 0) { leaseRepo.acquireAll(any(), any(), any()) }
            assertEquals(listOf("shared-runner"), transition.captured.consumedCredentials)
        }

    @Test
    fun `derived keys precede caller extras and duplicates collapse`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            val transition = captureTransition()

            serviceWith(
                requirements = listOf(exclusive("staging-db"), advisory("shared-runner")),
                registry = mapOf("extra-cred" to ResourceDefinition("extra-cred"))
            ).advance(
                item,
                "start",
                null,
                null,
                null,
                DegradedModePolicy.ACCEPT_CACHED,
                true,
                // "staging-db" duplicates a derived key; "extra-cred" is a registry-known extra.
                credentialRefs = listOf("staging-db", "extra-cred")
            )

            assertEquals(
                listOf("staging-db", "shared-runner", "extra-cred"),
                transition.captured.consumedCredentials
            )
        }

    // ──────────────────────────────────────────────
    // credentialRefs set-membership tightening
    // ──────────────────────────────────────────────

    @Test
    fun `caller credentialRef outside declared and registry keys is rejected`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)

            val outcome =
                serviceWith(
                    requirements = listOf(exclusive("staging-db")),
                    registry = mapOf("prod-cred" to ResourceDefinition("prod-cred"))
                ).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                    credentialRefs = listOf("staging-db", "who-knows")
                )

            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            val validation = assertIs<AdvanceFailure.ValidationFailed>(failure.failure)
            assertTrue(validation.message.contains("who-knows"), "error must name the unknown ref")
            coVerify(exactly = 0) { leaseRepo.acquireAll(any(), any(), any()) }
        }

    @Test
    fun `caller credentialRef naming a registry-only key is accepted`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)

            val outcome =
                serviceWith(
                    requirements = listOf(exclusive("staging-db")),
                    registry = mapOf("prod-cred" to ResourceDefinition("prod-cred"))
                ).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true,
                    credentialRefs = listOf("prod-cred")
                )

            assertIs<AdvanceOutcome.Success>(outcome)
        }

    // ──────────────────────────────────────────────
    // TTL resolution
    // ──────────────────────────────────────────────

    @Test
    fun `TTL prefers the requirement override then the registry default then 3600`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            val requests = slot<List<Pair<String, Int>>>()
            coEvery { leaseRepo.acquireAll(any(), any(), capture(requests)) } returns
                LeaseAcquireResult.Success(emptyList())

            serviceWith(
                requirements =
                    listOf(
                        exclusive("has-override", ttlSeconds = 120),
                        exclusive("registry-default"),
                        exclusive("unregistered")
                    ),
                registry =
                    mapOf(
                        "has-override" to ResourceDefinition("has-override", defaultTtlSeconds = 999),
                        "registry-default" to ResourceDefinition("registry-default", defaultTtlSeconds = 1800)
                    )
            ).advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            assertEquals(
                listOf(
                    "has-override" to 120,
                    "registry-default" to 1800,
                    "unregistered" to AdvanceService.DEFAULT_RESOURCE_TTL_SECONDS
                ),
                requests.captured
            )
        }

    @Test
    fun `TTL is clamped to the 1 to 86400 second bounds`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)
            val requests = slot<List<Pair<String, Int>>>()
            coEvery { leaseRepo.acquireAll(any(), any(), capture(requests)) } returns
                LeaseAcquireResult.Success(emptyList())

            serviceWith(
                requirements = listOf(exclusive("too-small", ttlSeconds = 0), exclusive("too-big", ttlSeconds = 999_999))
            ).advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            assertEquals(
                listOf(
                    "too-small" to AdvanceService.MIN_RESOURCE_TTL_SECONDS,
                    "too-big" to AdvanceService.MAX_RESOURCE_TTL_SECONDS
                ),
                requests.captured
            )
        }

    // ──────────────────────────────────────────────
    // Enforcement matrix: the two off switches
    // ──────────────────────────────────────────────

    @Test
    fun `enforceResourceLeases false skips acquisition entirely`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true,
                    credentialRefs = emptyList(),
                    enforceResourceLeases = false
                )

            assertIs<AdvanceOutcome.Success>(outcome)
            coVerify(exactly = 0) { leaseRepo.acquireAll(any(), any(), any()) }
        }

    @Test
    fun `RESOURCE_LEASES_ENFORCED kill switch off skips acquisition even when the caller enforces`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)

            val outcome =
                serviceWith(
                    requirements = listOf(exclusive("staging-db")),
                    resourceLeasesEnforced = false
                ).advance(
                    item,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true,
                    credentialRefs = emptyList(),
                    enforceResourceLeases = true
                )

            assertIs<AdvanceOutcome.Success>(outcome)
            coVerify(exactly = 0) { leaseRepo.acquireAll(any(), any(), any()) }
        }

    @Test
    fun `kill switch parses only the literal false as disabled`() {
        assertTrue(AdvanceService.resourceLeasesEnforcedFromEnv { null })
        assertTrue(AdvanceService.resourceLeasesEnforcedFromEnv { "true" })
        assertTrue(AdvanceService.resourceLeasesEnforcedFromEnv { "0" })
        assertTrue(!AdvanceService.resourceLeasesEnforcedFromEnv { "false" })
        assertTrue(!AdvanceService.resourceLeasesEnforcedFromEnv { "FALSE" })
    }

    // ──────────────────────────────────────────────
    // Release paths — every exit from WORK
    // ──────────────────────────────────────────────

    @Test
    fun `complete out of work releases leases`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.WORK)

            val outcome =
                serviceWith(requirements = listOf(exclusive("staging-db"))).advance(
                    item,
                    "complete",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    true
                )

            assertIs<AdvanceOutcome.Success>(outcome)
            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(item.id) }
        }

    @Test
    fun `cancel out of work releases leases`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.WORK)

            serviceWith().advance(item, "cancel", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(item.id) }
        }

    @Test
    fun `block out of work releases leases`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.WORK)

            serviceWith().advance(item, "block", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(item.id) }
        }

    @Test
    fun `hold out of work releases leases`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.WORK)

            serviceWith().advance(item, "hold", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(item.id) }
        }

    @Test
    fun `a transition that does not leave work does not release`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.QUEUE)

            serviceWith().advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            coVerify(exactly = 0) { leaseRepo.releaseAllForItem(any()) }
        }

    @Test
    fun `release runs even when the kill switch is off`(): Unit =
        runBlocking {
            // Releasing is always safe: leases taken while enforcement was on must still be freed.
            val item = makeItem(role = Role.WORK)

            serviceWith(resourceLeasesEnforced = false).advance(
                item,
                "complete",
                null,
                null,
                null,
                DegradedModePolicy.ACCEPT_CACHED,
                true
            )

            coVerify(exactly = 1) { leaseRepo.releaseAllForItem(item.id) }
        }

    @Test
    fun `a release DBError is logged and does NOT fail the transition`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.WORK)
            coEvery { leaseRepo.releaseAllForItem(any()) } returns
                LeaseReleaseResult.DBError(IllegalStateException("db down"))

            val outcome =
                serviceWith().advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            // The role change already committed; the TTL is the backstop for the orphaned lease.
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.TERMINAL, success.result.newRole)
        }

    // ──────────────────────────────────────────────
    // Start-cascade suppression
    // ──────────────────────────────────────────────

    @Test
    fun `a contended parent start cascade is suppressed while the child advance still succeeds`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            // The child declares nothing; the parent needs an exclusive resource that is taken.
            coEvery { leaseRepo.acquireAll(parentId, any(), any()) } returns
                LeaseAcquireResult.Contended(listOf("staging-db"), retryAfterMs = 5_000)

            val outcome =
                serviceWith(
                    requirementsByItem = mapOf(parentId to listOf(exclusive("staging-db")))
                ).advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.WORK, success.result.newRole, "the child's own advance must still succeed")

            val cascade = success.result.cascadeEvents.single()
            assertEquals(parentId, cascade.itemId)
            assertTrue(!cascade.applied, "the parent cascade must NOT be applied")
            assertTrue(cascade.resourceBlocked, "the cascade must be flagged resourceBlocked")
            assertEquals(listOf("staging-db"), cascade.contendedResources)
        }

    @Test
    fun `an uncontended parent start cascade acquires the parent leases and applies`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)

            val outcome =
                serviceWith(
                    requirementsByItem = mapOf(parentId to listOf(exclusive("staging-db")))
                ).advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val cascade = success.result.cascadeEvents.single()
            assertTrue(cascade.applied)
            assertTrue(!cascade.resourceBlocked)
            // Cascades carry no actor, so the lease's audit actor is null.
            coVerify(exactly = 1) { leaseRepo.acquireAll(parentId, null, listOf("staging-db" to 3600)) }
        }

    @Test
    fun `start cascade acquisition is skipped when the kill switch is off`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)

            val outcome =
                serviceWith(
                    requirementsByItem = mapOf(parentId to listOf(exclusive("staging-db"))),
                    resourceLeasesEnforced = false
                ).advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertTrue(
                success.result.cascadeEvents
                    .single()
                    .applied
            )
            coVerify(exactly = 0) { leaseRepo.acquireAll(any(), any(), any()) }
        }

    @Test
    fun `a null lease repository does not break an item that declares resources`(): Unit =
        runBlocking {
            // Defensive wiring-bug path: the gate logs an error and proceeds rather than wedging.
            val item = makeItem(role = Role.QUEUE)

            val outcome =
                serviceWith(
                    requirements = listOf(exclusive("staging-db")),
                    leaseRepository = null
                ).advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)

            assertIs<AdvanceOutcome.Success>(outcome)
        }
}

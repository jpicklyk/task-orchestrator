package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for item 473e4f49 — "Start cascades bypass the parent note gate".
 *
 * Decision (Path C, recorded in the item's queue-phase note): a START cascade into
 * [Role.WORK] must be gated on the parent's own CURRENT-phase required notes, exactly
 * mirroring the [GatePredicate.missingForComplete] suppression shape already used by
 * [AdvanceService]'s terminal-cascade path (`gateBlocked` + `gateMissingNotes`, `applied = false`,
 * the child's own advance unaffected). REOPEN cascades deliberately keep the old bypass —
 * that asymmetry is intentional, not a leftover bug (see the reopen scenario below).
 *
 * Conventions mirror [AdvanceServiceLeaseGateTest]: MockK repositories, `inTransaction`
 * delegating straight to its block, no real database. `schemaResolver` is keyed by item id so
 * the child and the parent can carry independent schemas (or no schema at all) per scenario.
 */
class AdvanceServiceStartCascadeGateTest {
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
        coEvery { leaseRepo.acquireAll(any(), any(), any()) } returns LeaseAcquireResult.Success(emptyList())
        coEvery { leaseRepo.releaseAllForItem(any()) } returns LeaseReleaseResult.Success(0)
    }

    // ──────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE,
        parentId: UUID? = null,
        title: String = "Item"
    ): WorkItem =
        WorkItem(
            id = id,
            title = title,
            role = role,
            parentId = parentId,
            depth = if (parentId != null) 1 else 0
        )

    /** A minimal "feature-implementation"-shaped schema: one required queue note by default. */
    private fun featureSchema(
        notes: List<NoteSchemaEntry> =
            listOf(
                NoteSchemaEntry(key = "specification", role = Role.QUEUE, required = true, description = "Spec")
            ),
        lifecycleMode: LifecycleMode = LifecycleMode.AUTO
    ) = WorkItemSchema(type = "feature-implementation", lifecycleMode = lifecycleMode, notes = notes)

    private fun filledNote(
        itemId: UUID,
        key: String = "specification",
        role: Role = Role.QUEUE
    ) = Note(itemId = itemId, key = key, role = role.name.lowercase(), body = "Filled content for $key")

    private fun blankNote(
        itemId: UUID,
        key: String = "specification",
        role: Role = Role.QUEUE
    ) = Note(itemId = itemId, key = key, role = role.name.lowercase(), body = "   ")

    private fun exclusive(key: String) = ResourceRequirement(key, ResourceMode.EXCLUSIVE, null)

    /**
     * Builds a service wired with the shared mocks. [schemasById] resolves each item's schema by
     * id (defaulting every unlisted id to schema-free) so the child and the parent can be given
     * independent schemas per scenario, matching how the real `ToolExecutionContext.resolveSchema`
     * is per-item.
     */
    private fun serviceWith(
        schemasById: Map<UUID, WorkItemSchema?> = emptyMap(),
        requirementsByItem: Map<UUID, List<ResourceRequirement>> = emptyMap(),
        registry: Map<String, ResourceDefinition> = emptyMap(),
        leaseRepository: ResourceLeaseRepository? = leaseRepo
    ): AdvanceService =
        AdvanceService(
            workItemRepository = workItemRepo,
            roleTransitionRepository = roleTransitionRepo,
            dependencyRepository = depRepo,
            noteRepository = noteRepo,
            statusLabelService = NoOpStatusLabelService,
            schemaResolver = { item -> schemasById[item.id] },
            resourceLeaseRepository = leaseRepository,
            resourceRequirementsResolver = { item -> requirementsByItem[item.id] ?: emptyList() },
            resourceRegistryResolver = { registry },
            resourceLeasesEnforced = true
        )

    // ──────────────────────────────────────────────
    // S1 — core: start cascade suppressed by an unfilled parent note
    // ──────────────────────────────────────────────

    @Test
    fun `S1 start cascade is suppressed when the parent has an unfilled required queue note`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)
            val schema = featureSchema()

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { noteRepo.findByItemId(child.id) } returns Result.Success(listOf(filledNote(child.id)))
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(emptyList())

            val outcome =
                serviceWith(schemasById = mapOf(child.id to schema, parentId to schema))
                    .advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.WORK, success.result.newRole, "the child's own advance must still succeed")

            val cascade = success.result.cascadeEvents.single()
            assertEquals(parentId, cascade.itemId)
            assertEquals(Role.WORK, cascade.targetRole)
            assertFalse(cascade.applied, "the parent start cascade must NOT be applied")
            assertTrue(cascade.gateBlocked, "must report gateBlocked, mirroring the terminal-cascade shape")
            assertEquals(setOf("specification"), cascade.gateMissingNotes.map { it.key }.toSet())

            // The parent's row must never actually be written — the gate runs before apply.
            coVerify(exactly = 0) { workItemRepo.update(match { it.id == parentId }) }
        }

    // ──────────────────────────────────────────────
    // S2 — happy: parent note filled, cascade applies
    // ──────────────────────────────────────────────

    @Test
    fun `S2 start cascade applies once the parent required queue note is filled`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)
            val schema = featureSchema()

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { noteRepo.findByItemId(child.id) } returns Result.Success(listOf(filledNote(child.id)))
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(listOf(filledNote(parentId)))

            val outcome =
                serviceWith(schemasById = mapOf(child.id to schema, parentId to schema))
                    .advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val cascade = success.result.cascadeEvents.single()
            assertTrue(cascade.applied, "the parent cascade must be applied once its note is filled")
            assertFalse(cascade.gateBlocked, "gateBlocked must not be set on an applied cascade")
            assertTrue(cascade.gateMissingNotes.isEmpty())
            coVerify(exactly = 1) { workItemRepo.update(match { it.id == parentId && it.role == Role.WORK }) }
        }

    // ──────────────────────────────────────────────
    // S3 — happy: schema-free parent cascades freely
    // ──────────────────────────────────────────────

    @Test
    fun `S3 start cascade applies freely when the parent has no schema`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)

            // No entry in schemasById for either id => schemaResolver returns null for both.
            val outcome =
                serviceWith().advance(
                    child,
                    "start",
                    null,
                    null,
                    null,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true
                )

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val cascade = success.result.cascadeEvents.single()
            assertTrue(cascade.applied, "a schema-free parent must cascade without any gate check")
            assertFalse(cascade.gateBlocked)
            coVerify(exactly = 1) { workItemRepo.update(match { it.id == parentId && it.role == Role.WORK }) }
        }

    // ──────────────────────────────────────────────
    // S4 — asymmetry: reopen cascade still bypasses the gate
    // ──────────────────────────────────────────────

    @Test
    fun `S4 reopen cascade bypasses the note gate even with zero parent notes`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.TERMINAL, title = "Parent")
            val child = makeItem(role = Role.TERMINAL, title = "Child", parentId = parentId)
            // Parent carries the full feature-implementation schema but ZERO notes are filled.
            val schema = featureSchema()

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(emptyList())

            val outcome =
                serviceWith(schemasById = mapOf(parentId to schema))
                    .advance(child, "reopen", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.QUEUE, success.result.newRole)

            val cascade = success.result.cascadeEvents.single()
            assertEquals(parentId, cascade.itemId)
            assertEquals(Role.WORK, cascade.targetRole)
            assertTrue(cascade.applied, "reopen cascades must bypass the note gate by design")
            assertFalse(cascade.gateBlocked, "gateBlocked must be absent on a reopen cascade")
        }

    // ──────────────────────────────────────────────
    // S5 — regression: terminal cascade gate is untouched
    // ──────────────────────────────────────────────

    @Test
    fun `S5 terminal cascade gate behavior is unchanged by the start-cascade gate`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.WORK, title = "Parent")
            val child = makeItem(role = Role.WORK, title = "Child", parentId = parentId)
            val schema = featureSchema()

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns
                Result.Success(mapOf(Role.TERMINAL to 1))
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(emptyList())

            // Child itself is schema-free so its own gate trivially passes on "complete".
            val outcome =
                serviceWith(schemasById = mapOf(parentId to schema))
                    .advance(child, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.TERMINAL, success.result.newRole)

            val cascade = success.result.cascadeEvents.single()
            assertEquals(parentId, cascade.itemId)
            assertEquals(Role.TERMINAL, cascade.targetRole)
            assertFalse(cascade.applied, "terminal cascade must remain gate-blocked, as before this fix")
            assertTrue(cascade.gateBlocked)
            assertTrue(cascade.gateMissingNotes.isNotEmpty())
            coVerify(exactly = 0) { workItemRepo.update(match { it.id == parentId }) }
        }

    // ──────────────────────────────────────────────
    // P1 — only required notes count
    // ──────────────────────────────────────────────

    @Test
    fun `P1 an unfilled optional note never blocks the start cascade`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)
            val schema =
                featureSchema(
                    notes =
                        listOf(
                            NoteSchemaEntry(key = "specification", role = Role.QUEUE, required = true, description = "Spec"),
                            NoteSchemaEntry(key = "context", role = Role.QUEUE, required = false, description = "Optional context")
                        )
                )

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            // Required note filled, optional note absent entirely.
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(listOf(filledNote(parentId)))

            val outcome =
                serviceWith(schemasById = mapOf(parentId to schema))
                    .advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val cascade = success.result.cascadeEvents.single()
            assertTrue(cascade.applied, "an absent OPTIONAL note must never block the cascade")
            assertFalse(cascade.gateBlocked)
        }

    // ──────────────────────────────────────────────
    // P2 — parent already WORK: no cascade, gate never consulted
    // ──────────────────────────────────────────────

    @Test
    fun `P2 no cascade event and no gate check when the parent is already in WORK`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.WORK, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)
            val schema = featureSchema()

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)

            val outcome =
                serviceWith(schemasById = mapOf(parentId to schema))
                    .advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertTrue(success.result.cascadeEvents.isEmpty(), "detectStartCascades must be empty when the parent is not QUEUE")
            coVerify(exactly = 0) { noteRepo.findByItemId(parentId) }
        }

    // ──────────────────────────────────────────────
    // P3 — immediate-parent-only: no ancestor recursion
    // ──────────────────────────────────────────────

    @Test
    fun `P3 start cascade covers only the immediate parent, never the grandparent`(): Unit =
        runBlocking {
            val grandparentId = UUID.randomUUID()
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent", parentId = grandparentId)
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)
            val schema = featureSchema()

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(listOf(filledNote(parentId)))
            // Deliberately NO stub for grandparentId: if a recursion regression crept in and the
            // implementation tried to fetch/gate-check the grandparent, this test fails loudly
            // (MockK throws on an unstubbed call) rather than silently passing.

            val outcome =
                serviceWith(schemasById = mapOf(parentId to schema))
                    .advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(1, success.result.cascadeEvents.size, "exactly ONE cascade event — the immediate parent only")
            val cascade = success.result.cascadeEvents.single()
            assertEquals(parentId, cascade.itemId)
            assertTrue(cascade.applied)
            assertFalse(cascade.gateBlocked)
        }

    // ──────────────────────────────────────────────
    // P4 — whitespace-only note body counts as unfilled
    // ──────────────────────────────────────────────

    @Test
    fun `P4 a whitespace-only note body is treated as unfilled and blocks the cascade`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)
            val schema = featureSchema()

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            // The note row exists but its body is whitespace-only.
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(listOf(blankNote(parentId)))

            val outcome =
                serviceWith(schemasById = mapOf(parentId to schema))
                    .advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val cascade = success.result.cascadeEvents.single()
            assertFalse(cascade.applied, "a whitespace-only body must not satisfy the gate")
            assertTrue(cascade.gateBlocked)
            assertEquals(setOf("specification"), cascade.gateMissingNotes.map { it.key }.toSet())
        }

    // ──────────────────────────────────────────────
    // P5 — replay: a second "start" past WORK produces no new start-cascade event
    // ──────────────────────────────────────────────

    @Test
    fun `P5 a start trigger moving WORK to REVIEW produces no start-cascade event`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            // Child schema has a review phase so WORK -> start lands on REVIEW, not TERMINAL —
            // and REVIEW is neither the WORK nor the TERMINAL cascade branch, so no cascade should
            // even be attempted.
            val childSchema =
                featureSchema(
                    notes =
                        listOf(
                            NoteSchemaEntry(key = "specification", role = Role.QUEUE, required = true, description = "Spec"),
                            NoteSchemaEntry(key = "implementation-notes", role = Role.WORK, required = true, description = "Impl"),
                            NoteSchemaEntry(key = "review-checklist", role = Role.REVIEW, required = true, description = "Review")
                        )
                )
            val child = makeItem(role = Role.WORK, title = "Child", parentId = parentId)

            coEvery { noteRepo.findByItemId(child.id) } returns
                Result.Success(listOf(filledNote(child.id, key = "implementation-notes", role = Role.WORK)))

            val outcome =
                serviceWith(schemasById = mapOf(child.id to childSchema))
                    .advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.REVIEW, success.result.newRole)
            assertTrue(success.result.cascadeEvents.isEmpty(), "moving into REVIEW must not trigger any start-cascade event")
            coVerify(exactly = 0) { workItemRepo.getById(parentId) }
        }

    // ──────────────────────────────────────────────
    // P6 — ordering: the note gate precedes resource-lease acquisition
    // ──────────────────────────────────────────────

    @Test
    fun `P6 the note gate blocks before the resource lease is ever acquired for the parent`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
            val child = makeItem(role = Role.QUEUE, title = "Child", parentId = parentId)
            val schema = featureSchema()

            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(emptyList())

            val outcome =
                serviceWith(
                    schemasById = mapOf(parentId to schema),
                    requirementsByItem = mapOf(parentId to listOf(exclusive("staging-db")))
                ).advance(child, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = true)

            val success = assertIs<AdvanceOutcome.Success>(outcome)
            val cascade = success.result.cascadeEvents.single()
            assertTrue(cascade.gateBlocked, "gate check must run and block")
            assertFalse(cascade.resourceBlocked, "resource gate must never even run once the note gate blocked")
            assertTrue(cascade.contendedResources.isEmpty())
            coVerify(exactly = 0) { leaseRepo.acquireAll(parentId, any(), any()) }
        }
}

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for the shared [AdvanceService] — the single advance pipeline used by both the MCP
 * `advance_item` tool (enforceOwnership = true) and the REST advance route (enforceOwnership = false).
 *
 * Repositories are mocked (MockK). `inTransaction` delegates to its block directly — there is no
 * real DB transaction in these unit tests. The schema resolver is supplied per-test.
 */
class AdvanceServiceTest {
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository
    private lateinit var noteRepo: NoteRepository

    @BeforeEach
    fun setUp() {
        workItemRepo = mockk()
        depRepo = mockk()
        roleTransitionRepo = mockk()
        noteRepo = mockk()

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
        previousRole: Role? = null,
        title: String = "Item",
        parentId: UUID? = null,
        claimedBy: String? = null,
        claimExpiresAt: Instant? = null,
    ): WorkItem {
        // Capture a single instant so claimedAt == originalClaimedAt. Two separate Instant.now()
        // calls drift by microseconds on Linux CI's high-resolution clock and would flip the
        // WorkItem.validate() invariant originalClaimedAt <= claimedAt (masked locally on Windows).
        val claimInstant = if (claimedBy != null) Instant.now() else null
        return WorkItem(
            id = id,
            title = title,
            role = role,
            previousRole = previousRole,
            parentId = parentId,
            depth = if (parentId != null) 1 else 0,
            claimedBy = claimedBy,
            claimedAt = claimInstant,
            claimExpiresAt = claimExpiresAt,
            originalClaimedAt = claimInstant,
        )
    }

    /** Builds a service with a schema resolver that returns [schema] for every item. */
    private fun serviceWith(schema: WorkItemSchema? = null): AdvanceService =
        AdvanceService(
            workItemRepository = workItemRepo,
            roleTransitionRepository = roleTransitionRepo,
            dependencyRepository = depRepo,
            noteRepository = noteRepo,
            statusLabelService = NoOpStatusLabelService,
            schemaResolver = { schema },
        )

    private fun schema(vararg entries: NoteSchemaEntry): WorkItemSchema = WorkItemSchema(type = "test", notes = entries.toList())

    // ──────────────────────────────────────────────
    // Basic resolve/apply (schema-free)
    // ──────────────────────────────────────────────

    @Test
    fun `start QUEUE to WORK succeeds with no schema`(): Unit =
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
                    enforceOwnership = true,
                )
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.QUEUE, success.result.previousRole)
            assertEquals(Role.WORK, success.result.newRole)
            assertTrue(success.result.applied)
        }

    // ──────────────────────────────────────────────
    // Gate: start
    // ──────────────────────────────────────────────

    @Test
    fun `start gate PASSES when required current-phase note is filled`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()
            val item = makeItem(id = id, role = Role.QUEUE)
            val sc = schema(NoteSchemaEntry("spec", Role.QUEUE, required = true, description = "spec"))
            coEvery { noteRepo.findByItemId(id) } returns
                Result.Success(listOf(Note(itemId = id, key = "spec", role = "queue", body = "filled")))

            val outcome =
                serviceWith(sc).advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.WORK, success.result.newRole)
        }

    @Test
    fun `start gate FAILS when required current-phase note is missing`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()
            val item = makeItem(id = id, role = Role.QUEUE)
            val sc = schema(NoteSchemaEntry("spec", Role.QUEUE, required = true, description = "spec"))
            coEvery { noteRepo.findByItemId(id) } returns Result.Success(emptyList())

            val outcome =
                serviceWith(sc).advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            val gate = assertIs<AdvanceFailure.GateBlocked>(failure.failure)
            assertEquals(listOf("spec"), gate.missingNotes.map { it.key })
            assertEquals(Role.WORK, gate.targetRole)
            assertTrue(gate.message.contains("queue"))
        }

    @Test
    fun `start gate ignores required notes for OTHER phases`(): Unit =
        runBlocking {
            // A required WORK-phase note must not block a QUEUE→WORK start.
            val id = UUID.randomUUID()
            val item = makeItem(id = id, role = Role.QUEUE)
            val sc =
                schema(
                    NoteSchemaEntry("spec", Role.QUEUE, required = false, description = "spec"),
                    NoteSchemaEntry("impl", Role.WORK, required = true, description = "impl"),
                )
            coEvery { noteRepo.findByItemId(id) } returns Result.Success(emptyList())

            val outcome =
                serviceWith(sc).advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            assertIs<AdvanceOutcome.Success>(outcome)
        }

    // ──────────────────────────────────────────────
    // Gate: complete
    // ──────────────────────────────────────────────

    @Test
    fun `complete gate FAILS when any required note across phases is missing`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()
            val item = makeItem(id = id, role = Role.WORK)
            val sc =
                schema(
                    NoteSchemaEntry("spec", Role.QUEUE, required = true, description = "spec"),
                    NoteSchemaEntry("impl", Role.WORK, required = true, description = "impl"),
                )
            // Only "spec" filled — "impl" is still missing.
            coEvery { noteRepo.findByItemId(id) } returns
                Result.Success(listOf(Note(itemId = id, key = "spec", role = "queue", body = "x")))

            val outcome =
                serviceWith(sc).advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            val gate = assertIs<AdvanceFailure.GateBlocked>(failure.failure)
            assertEquals(listOf("impl"), gate.missingNotes.map { it.key })
        }

    @Test
    fun `complete gate PASSES when all required notes filled`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()
            val item = makeItem(id = id, role = Role.WORK)
            val sc =
                schema(
                    NoteSchemaEntry("spec", Role.QUEUE, required = true, description = "spec"),
                    NoteSchemaEntry("impl", Role.WORK, required = true, description = "impl"),
                )
            coEvery { noteRepo.findByItemId(id) } returns
                Result.Success(
                    listOf(
                        Note(itemId = id, key = "spec", role = "queue", body = "x"),
                        Note(itemId = id, key = "impl", role = "work", body = "y"),
                    ),
                )

            val outcome =
                serviceWith(sc).advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.TERMINAL, success.result.newRole)
        }

    // ──────────────────────────────────────────────
    // Cascade emission on terminal
    // ──────────────────────────────────────────────

    @Test
    fun `terminal cascade emitted when completing the last child`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.WORK, title = "Parent")
            val child = makeItem(id = childId, role = Role.WORK, title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns Result.Success(mapOf(Role.TERMINAL to 1))

            val outcome =
                serviceWith().advance(child, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(1, success.result.cascadeEvents.size)
            val cascade = success.result.cascadeEvents.first()
            assertEquals(parentId, cascade.itemId)
            assertEquals("Parent", cascade.title)
            assertEquals(Role.WORK, cascade.previousRole)
            assertEquals(Role.TERMINAL, cascade.targetRole)
            assertTrue(cascade.applied)
        }

    @Test
    fun `terminal cascade gate-blocked when parent has unfilled required notes`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val parent = makeItem(id = parentId, role = Role.WORK, title = "Parent")
            val child = makeItem(id = childId, role = Role.WORK, title = "Child", parentId = parentId)
            val sc = schema(NoteSchemaEntry("review", Role.REVIEW, required = true, description = "review"))

            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns Result.Success(mapOf(Role.TERMINAL to 1))
            // Child has all its notes; parent is missing the required review note.
            coEvery { noteRepo.findByItemId(childId) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(parentId) } returns Result.Success(emptyList())

            // Child schema has no required notes for complete, but parent does → cascade gate-block.
            val service =
                AdvanceService(
                    workItemRepo,
                    roleTransitionRepo,
                    depRepo,
                    noteRepo,
                    NoOpStatusLabelService,
                    schemaResolver = { it -> if (it.id == parentId) sc else null },
                )
            val outcome = service.advance(child, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(1, success.result.cascadeEvents.size)
            val cascade = success.result.cascadeEvents.first()
            assertTrue(cascade.gateBlocked)
            assertFalse(cascade.applied)
            assertEquals(listOf("review"), cascade.gateMissingNotes.map { it.key })
        }

    // ──────────────────────────────────────────────
    // Unblock detection
    // ──────────────────────────────────────────────

    @Test
    fun `unblocked items detected after completing a blocker`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val downstreamId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.WORK)
            val downstream = makeItem(id = downstreamId, role = Role.QUEUE, title = "Downstream")
            val terminalItem = item.update { it.copy(role = Role.TERMINAL) }

            // AdvanceService receives the item as a parameter and only re-fetches the blocker during
            // the unblock check (isFullyUnblocked) — AFTER apply — so getById(itemId) must return the
            // now-terminal blocker for the downstream item to count as fully unblocked.
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(terminalItem)
            coEvery { workItemRepo.getById(downstreamId) } returns Result.Success(downstream)

            val dep = Dependency(fromItemId = itemId, toItemId = downstreamId, type = DependencyType.BLOCKS)
            every { depRepo.findByFromItemId(itemId) } returns listOf(dep)
            every { depRepo.findByToItemId(downstreamId) } returns listOf(dep)
            every { depRepo.findByFromItemId(downstreamId) } returns emptyList()

            val outcome =
                serviceWith().advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(1, success.result.unblockedItems.size)
            assertEquals(
                downstreamId,
                success.result.unblockedItems
                    .first()
                    .itemId
            )
            assertEquals(
                "Downstream",
                success.result.unblockedItems
                    .first()
                    .title
            )
        }

    // ──────────────────────────────────────────────
    // enforceOwnership true vs false
    // ──────────────────────────────────────────────

    @Test
    fun `enforceOwnership true rejects advance on item claimed by another agent`(): Unit =
        runBlocking {
            val item =
                makeItem(
                    role = Role.QUEUE,
                    claimedBy = "other-agent",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )
            val actor = ActorClaim(id = "me", kind = ActorKind.SUBAGENT)
            val verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")

            val outcome =
                serviceWith().advance(
                    item,
                    "start",
                    null,
                    actor,
                    verification,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true,
                )
            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            assertIs<AdvanceFailure.OwnershipRejected>(failure.failure)
        }

    @Test
    fun `enforceOwnership false allows advance on item claimed by another agent`(): Unit =
        runBlocking {
            val item =
                makeItem(
                    role = Role.QUEUE,
                    claimedBy = "other-agent",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )
            val actor = ActorClaim(id = "api:token", kind = ActorKind.EXTERNAL)
            val verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")

            val outcome =
                serviceWith().advance(
                    item,
                    "start",
                    null,
                    actor,
                    verification,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = false,
                )
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.WORK, success.result.newRole)
            // The actor is still recorded for audit even though ownership is bypassed.
            assertEquals("api:token", success.result.actorClaim?.id)
        }
}

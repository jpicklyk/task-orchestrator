package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteRoleTransitionRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteRoleTransitionRepositoryTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var transitionRepository: SQLiteRoleTransitionRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var testItemId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            database = sqliteDb.database
            databaseManager = sqliteDb.databaseManager
            transitionRepository = SQLiteRoleTransitionRepository(databaseManager)
            workItemRepository = SQLiteWorkItemRepository(databaseManager)

            // Create a work item for foreign key references
            val item = WorkItem(title = "Test item")
            workItemRepository.create(item)
            testItemId = item.id
        }

    // --- Create ---

    @Test
    fun `create transition`() =
        runBlocking {
            val transition =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start",
                    summary = "Starting work"
                )
            val result = transitionRepository.create(transition)
            assertNotNull(result)
            assertEquals(transition.id, result.id)
            assertEquals("queue", result.fromRole)
            assertEquals("work", result.toRole)
            assertEquals("start", result.trigger)
            assertEquals("Starting work", result.summary)
        }

    @Test
    fun `create transition with status labels`() =
        runBlocking {
            val transition =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "work",
                    toRole = "review",
                    fromStatusLabel = "in-progress",
                    toStatusLabel = "in-review",
                    trigger = "complete"
                )
            val result = transitionRepository.create(transition)
            assertNotNull(result)
            assertEquals("in-progress", result.fromStatusLabel)
            assertEquals("in-review", result.toStatusLabel)
        }

    // --- findByItemId ---

    @Test
    fun `findByItemId returns transitions ordered by transitionedAt DESC`() =
        runBlocking {
            val now = Instant.now()
            val t1 =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start",
                    transitionedAt = now.minus(2, ChronoUnit.HOURS)
                )
            val t2 =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "work",
                    toRole = "review",
                    trigger = "complete",
                    transitionedAt = now.minus(1, ChronoUnit.HOURS)
                )
            val t3 =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "review",
                    toRole = "terminal",
                    trigger = "complete",
                    transitionedAt = now
                )
            transitionRepository.create(t1)
            transitionRepository.create(t2)
            transitionRepository.create(t3)

            val result = transitionRepository.findByItemId(testItemId)
            assertNotNull(result)
            assertEquals(3, result.size)
            // Should be newest first
            assertEquals("terminal", result[0].toRole)
            assertEquals("review", result[1].toRole)
            assertEquals("work", result[2].toRole)
        }

    @Test
    fun `findByItemId returns empty for item with no transitions`() =
        runBlocking {
            val result = transitionRepository.findByItemId(UUID.randomUUID())
            assertNotNull(result)
            assertTrue(result.isEmpty())
        }

    @Test
    fun `findByItemId respects limit`() =
        runBlocking {
            val now = Instant.now()
            for (i in 0..4) {
                transitionRepository.create(
                    RoleTransition(
                        itemId = testItemId,
                        fromRole = "queue",
                        toRole = "work",
                        trigger = "start",
                        transitionedAt = now.plus(i.toLong(), ChronoUnit.MINUTES)
                    )
                )
            }

            val result = transitionRepository.findByItemId(testItemId, limit = 3)
            assertNotNull(result)
            assertEquals(3, result.size)
        }

    // --- findByTimeRange ---

    @Test
    fun `findByTimeRange returns transitions in range`() =
        runBlocking {
            val now = Instant.now()
            val hourAgo = now.minus(1, ChronoUnit.HOURS)
            val twoHoursAgo = now.minus(2, ChronoUnit.HOURS)
            val threeHoursAgo = now.minus(3, ChronoUnit.HOURS)

            transitionRepository.create(
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start",
                    transitionedAt = threeHoursAgo
                )
            )
            transitionRepository.create(
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "work",
                    toRole = "review",
                    trigger = "complete",
                    transitionedAt = hourAgo
                )
            )

            // Query for range that includes only the second transition
            val result =
                transitionRepository.findByTimeRange(
                    startTime = twoHoursAgo,
                    endTime = now
                )
            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("review", result[0].toRole)
        }

    @Test
    fun `findByTimeRange with role filter`() =
        runBlocking {
            val now = Instant.now()
            val hourAgo = now.minus(1, ChronoUnit.HOURS)
            val twoHoursAgo = now.minus(2, ChronoUnit.HOURS)

            transitionRepository.create(
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start",
                    transitionedAt = hourAgo
                )
            )
            transitionRepository.create(
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "work",
                    toRole = "review",
                    trigger = "complete",
                    transitionedAt = hourAgo.plus(10, ChronoUnit.MINUTES)
                )
            )

            // Filter for role "review" - should match the second transition (toRole = review)
            val result =
                transitionRepository.findByTimeRange(
                    startTime = twoHoursAgo,
                    endTime = now,
                    role = "review"
                )
            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("review", result[0].toRole)
        }

    @Test
    fun `findByTimeRange with role filter matches fromRole too`() =
        runBlocking {
            val now = Instant.now()
            val hourAgo = now.minus(1, ChronoUnit.HOURS)

            transitionRepository.create(
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "work",
                    toRole = "terminal",
                    trigger = "complete",
                    transitionedAt = hourAgo
                )
            )

            // Filter for role "work" - should match because fromRole = "work"
            val result =
                transitionRepository.findByTimeRange(
                    startTime = hourAgo.minus(1, ChronoUnit.MINUTES),
                    endTime = now,
                    role = "work"
                )
            assertNotNull(result)
            assertEquals(1, result.size)
        }

    @Test
    fun `findByTimeRange returns empty for range with no transitions`() =
        runBlocking {
            val now = Instant.now()

            transitionRepository.create(
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start",
                    transitionedAt = now
                )
            )

            // Query a range before the transition
            val result =
                transitionRepository.findByTimeRange(
                    startTime = now.minus(3, ChronoUnit.HOURS),
                    endTime = now.minus(2, ChronoUnit.HOURS)
                )
            assertNotNull(result)
            assertTrue(result.isEmpty())
        }

    // --- deleteByItemId ---

    @Test
    fun `deleteByItemId removes all transitions for item`() =
        runBlocking {
            transitionRepository.create(
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start"
                )
            )
            transitionRepository.create(
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "work",
                    toRole = "review",
                    trigger = "complete"
                )
            )

            val result = transitionRepository.deleteByItemId(testItemId)
            assertNotNull(result)
            assertEquals(2, result)

            val findResult = transitionRepository.findByItemId(testItemId)
            assertNotNull(findResult)
            assertTrue(findResult.isEmpty())
        }

    @Test
    fun `deleteByItemId returns 0 for item with no transitions`() =
        runBlocking {
            val result = transitionRepository.deleteByItemId(UUID.randomUUID())
            assertNotNull(result)
            assertEquals(0, result)
        }

    // --- Actor attribution ---

    @Test
    fun `create transition with actor claim`() =
        runBlocking {
            val actor =
                ActorClaim(
                    id = "agent-42",
                    kind = ActorKind.SUBAGENT,
                    parent = "orchestrator-1",
                    proof = "proof-token"
                )
            val verification =
                VerificationResult(
                    status = VerificationStatus.UNCHECKED,
                    verifier = "noop",
                    reason = null
                )
            val transition =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start",
                    actorClaim = actor,
                    verification = verification
                )
            transitionRepository.create(transition)

            val result = transitionRepository.findByItemId(testItemId)
            assertNotNull(result)
            assertEquals(1, result.size)
            val found = result[0]
            assertNotNull(found.actorClaim)
            assertEquals("agent-42", found.actorClaim.id)
            assertEquals(ActorKind.SUBAGENT, found.actorClaim.kind)
            assertEquals("orchestrator-1", found.actorClaim.parent)
            assertNull(found.actorClaim.proof, "raw proof must be scrubbed to null on read-back per item 983615e7 D5")
            assertNotNull(found.verification)
            assertEquals(VerificationStatus.UNCHECKED, found.verification.status)
            assertEquals("noop", found.verification.verifier)
            assertNull(found.verification.reason)
        }

    @Test
    fun `create transition without actor`() =
        runBlocking {
            val transition =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start"
                )
            transitionRepository.create(transition)

            val result = transitionRepository.findByItemId(testItemId)
            assertNotNull(result)
            assertEquals(1, result.size)
            val found = result[0]
            assertNull(found.actorClaim)
            assertNull(found.verification)
        }

    @Test
    fun `findByItemId returns actor and verification fields`() =
        runBlocking {
            val now = Instant.now()
            val actor = ActorClaim(id = "agent-1", kind = ActorKind.ORCHESTRATOR)
            val verification = VerificationResult(status = VerificationStatus.VERIFIED, verifier = "test-verifier")

            val withActor =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start",
                    transitionedAt = now.minus(1, ChronoUnit.HOURS),
                    actorClaim = actor,
                    verification = verification
                )
            val withoutActor =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "work",
                    toRole = "review",
                    trigger = "complete",
                    transitionedAt = now
                )
            transitionRepository.create(withActor)
            transitionRepository.create(withoutActor)

            val result = transitionRepository.findByItemId(testItemId)
            assertNotNull(result)
            assertEquals(2, result.size)

            // Results are newest-first; withoutActor has later transitionedAt
            val noActorFound = result[0]
            val actorFound = result[1]

            assertNull(noActorFound.actorClaim)
            assertNull(noActorFound.verification)

            assertNotNull(actorFound.actorClaim)
            assertEquals("agent-1", actorFound.actorClaim.id)
            assertEquals(ActorKind.ORCHESTRATOR, actorFound.actorClaim.kind)
            assertNotNull(actorFound.verification)
            assertEquals(VerificationStatus.VERIFIED, actorFound.verification.status)
            assertEquals("test-verifier", actorFound.verification.verifier)
        }

    // --- consumedCredentials (V14) ---

    @Test
    fun `create transition with empty consumedCredentials round-trips to empty list, not null`() =
        runBlocking {
            val transition =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start"
                    // consumedCredentials defaults to emptyList()
                )
            transitionRepository.create(transition)

            val result = transitionRepository.findByItemId(testItemId)
            assertNotNull(result)
            assertEquals(1, result.size)
            assertTrue(result[0].consumedCredentials.isEmpty())
        }

    @Test
    fun `create transition with multi-entry consumedCredentials round-trips in order`() =
        runBlocking {
            val refs = listOf("vault:prod-db-password", "github-pat-ci", "aws-role/deploy")
            val transition =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "work",
                    toRole = "review",
                    trigger = "complete",
                    consumedCredentials = refs
                )
            transitionRepository.create(transition)

            val result = transitionRepository.findByItemId(testItemId)
            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals(refs, result[0].consumedCredentials)
        }

    @Test
    fun `create transition with single-entry consumedCredentials round-trips`() =
        runBlocking {
            val transition =
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start",
                    consumedCredentials = listOf("vault:prod-db-password")
                )
            transitionRepository.create(transition)

            val result = transitionRepository.findByItemId(testItemId)
            assertNotNull(result)
            assertEquals(listOf("vault:prod-db-password"), result[0].consumedCredentials)
        }

    @Test
    fun `findByItemId respects offset`() =
        runBlocking {
            val base = Instant.parse("2026-01-01T00:00:00Z")
            repeat(5) { idx ->
                transitionRepository.create(
                    RoleTransition(
                        itemId = testItemId,
                        fromRole = "queue",
                        toRole = "work",
                        trigger = "start",
                        summary = "t$idx",
                        transitionedAt = base.plusSeconds(idx.toLong())
                    )
                )
            }

            val page = transitionRepository.findByItemId(testItemId, limit = 2, offset = 2)
            assertNotNull(page)
            assertEquals(listOf("t2", "t1"), page.map { it.summary })

            val beyond = transitionRepository.findByItemId(testItemId, limit = 2, offset = 10)
            assertNotNull(beyond)
            assertTrue(beyond.isEmpty())
        }

    @Test
    fun `findByItemId pages rows with identical timestamps exactly once`() =
        runBlocking {
            val ts = Instant.parse("2026-01-01T00:00:00Z")
            val created =
                (0 until 4).map { idx ->
                    val t =
                        RoleTransition(
                            itemId = testItemId,
                            fromRole = "queue",
                            toRole = "work",
                            trigger = "start",
                            summary = "same$idx",
                            transitionedAt = ts
                        )
                    transitionRepository.create(t)
                    t.id
                }
            val seen =
                (0 until 4).flatMap { off ->
                    val r = transitionRepository.findByItemId(testItemId, limit = 1, offset = off)
                    assertNotNull(r)
                    r.map { it.id }
                }
            assertEquals(created.toSet(), seen.toSet())
            assertEquals(4, seen.size)
        }
}

package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent test authorship for item a19fcf0a (P12, needs-test-author): [DependencyCommandService] directly.
 *
 * NEW-SURFACE (the service is introduced by the item). Narrowest-revert recipe from the frozen test-plan: keep the
 * service and `Dependency.normalized()`, make normalization the identity (return `this`), so every normalization,
 * duplicate and cycle scenario below goes behaviorally red instead of failing to compile.
 *
 * Oracles (frozen plan 3.4 and task-scope item 2): policy order is normalize, then within-request duplicates, then
 * duplicates against stored rows, then cycles; two edges are duplicates when they share the normalized
 * (from, to, type) whatever their unblockAt; a duplicate rejects the whole request and stores nothing; a restatement is
 * a duplicate, never a cycle; RELATES_TO never forms a cycle; `create` returns the stored rows, normalized, in request
 * order; `validateTreeEdges` is pure and runs normalize, the within-request duplicate check and then the cycle check;
 * `deleteByRelationship(from, to, IS_BLOCKED_BY)` removes the BLOCKS row from `to` to `from`, and with no type matches
 * stored rows from `from` to `to` of any type only. Error shapes are the declared DomainError / ErrorDetail
 * (DUPLICATE with Duplicate(DEPENDENCY, existingId), CYCLE_DETECTED with CycleDetected(path)).
 */
class DependencyCommandServiceTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var context: ToolExecutionContext
    private lateinit var service: DependencyCommandService

    private lateinit var a: UUID
    private lateinit var b: UUID
    private lateinit var c: UUID
    private lateinit var d: UUID
    private lateinit var e: UUID

    @BeforeEach
    fun setUp() {
        context = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
        service = context.dependencyCommandService
        runBlocking {
            a = item("A")
            b = item("B")
            c = item("C")
            d = item("D")
            e = item("E")
        }
    }

    private suspend fun item(title: String): UUID = context.workItemRepository().create(WorkItem(title = title)).id

    private fun dep(
        from: UUID,
        to: UUID,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ) = Dependency(fromItemId = from, toItemId = to, type = type, unblockAt = unblockAt)

    private suspend fun stored(vararg items: UUID): List<Dependency> =
        items.flatMap { context.dependencyRepository().findByItemId(it) }.distinctBy { it.id }

    private fun <T> Outcome<T>.ok(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("expected Ok, got: $this")
        }

    private fun <T> Outcome<T>.err(): DomainError =
        when (this) {
            is Outcome.Err -> error
            is Outcome.Ok -> fail("expected Err, got: $this")
        }

    // ------------------------------------------------------------------
    // create: normalization
    // ------------------------------------------------------------------

    @Test
    fun `create stores an IS_BLOCKED_BY input as a swapped BLOCKS row and returns the stored row`(): Unit =
        runBlocking {
            val input = dep(a, b, DependencyType.IS_BLOCKED_BY, unblockAt = "review")

            val created = service.create(listOf(input)).ok()

            val row = created.single()
            assertEquals(b, row.fromItemId, "b blocks a")
            assertEquals(a, row.toItemId)
            assertEquals(DependencyType.BLOCKS, row.type)
            assertEquals("review", row.unblockAt)
            assertEquals(input.id, row.id, "normalization keeps the input id")
            val persisted = stored(a, b).single()
            assertEquals(row.id, persisted.id)
            assertEquals(Triple(b, a, DependencyType.BLOCKS), Triple(persisted.fromItemId, persisted.toItemId, persisted.type))
            assertEquals("review", persisted.unblockAt)
        }

    @Test
    fun `create returns the stored rows in request order`(): Unit =
        runBlocking {
            val first = dep(a, b, DependencyType.IS_BLOCKED_BY)
            val second = dep(c, d, DependencyType.BLOCKS)
            val third = dep(e, c, DependencyType.IS_BLOCKED_BY)

            val created = service.create(listOf(first, second, third)).ok()

            assertEquals(listOf(first.id, second.id, third.id), created.map { it.id })
            assertEquals(
                listOf(
                    Triple(b, a, DependencyType.BLOCKS),
                    Triple(c, d, DependencyType.BLOCKS),
                    Triple(c, e, DependencyType.BLOCKS)
                ),
                created.map { Triple(it.fromItemId, it.toItemId, it.type) }
            )
        }

    @Test
    fun `create leaves a BLOCKS row and a RELATES_TO row as given`(): Unit =
        runBlocking {
            val created = service.create(listOf(dep(a, b, DependencyType.BLOCKS, "work"), dep(c, d, DependencyType.RELATES_TO))).ok()

            assertEquals(Triple(a, b, DependencyType.BLOCKS), Triple(created[0].fromItemId, created[0].toItemId, created[0].type))
            assertEquals("work", created[0].unblockAt)
            assertEquals(Triple(c, d, DependencyType.RELATES_TO), Triple(created[1].fromItemId, created[1].toItemId, created[1].type))
        }

    @Test
    fun `create of an empty list succeeds and stores nothing`(): Unit =
        runBlocking {
            assertEquals(emptyList<Dependency>(), service.create(emptyList()).ok())
            assertEquals(0, stored(a, b, c, d, e).size)
        }

    // ------------------------------------------------------------------
    // create: duplicates
    // ------------------------------------------------------------------

    @Test
    fun `create rejects an IS_BLOCKED_BY restatement of a stored BLOCKS row as a duplicate naming the stored id`(): Unit =
        runBlocking {
            val existing = service.create(listOf(dep(b, a, DependencyType.BLOCKS))).ok().single()

            val error = service.create(listOf(dep(a, b, DependencyType.IS_BLOCKED_BY))).err()

            assertEquals(ErrorCode.DUPLICATE, error.code)
            val detail = assertIs<ErrorDetail.Duplicate>(error.detail)
            assertEquals(EntityKind.DEPENDENCY, detail.kind)
            assertEquals(existing.id.toString(), detail.existingId)
            assertEquals(1, stored(a, b).size)
        }

    @Test
    fun `create rejects a restatement within one request in both orders and stores nothing`(): Unit =
        runBlocking {
            val blocksFirst = service.create(listOf(dep(b, a, DependencyType.BLOCKS), dep(a, b, DependencyType.IS_BLOCKED_BY))).err()
            assertEquals(ErrorCode.DUPLICATE, blocksFirst.code)
            assertEquals(0, stored(a, b).size)

            val aliasFirst = service.create(listOf(dep(a, b, DependencyType.IS_BLOCKED_BY), dep(b, a, DependencyType.BLOCKS))).err()
            assertEquals(ErrorCode.DUPLICATE, aliasFirst.code)
            assertEquals(0, stored(a, b).size)
        }

    @Test
    fun `create treats edges with different unblockAt as duplicates`(): Unit =
        runBlocking {
            val withinRequest =
                service.create(listOf(dep(a, b, DependencyType.BLOCKS, "work"), dep(a, b, DependencyType.BLOCKS, "terminal"))).err()
            assertEquals(ErrorCode.DUPLICATE, withinRequest.code)
            assertEquals(0, stored(a, b).size)

            service.create(listOf(dep(a, b, DependencyType.BLOCKS, "work"))).ok()
            val againstStored = service.create(listOf(dep(a, b, DependencyType.BLOCKS, "review"))).err()
            assertEquals(ErrorCode.DUPLICATE, againstStored.code)
            assertEquals("work", stored(a, b).single().unblockAt, "the stored threshold is not upgraded")
        }

    @Test
    fun `create rejects a repeated RELATES_TO edge as a duplicate`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b, DependencyType.RELATES_TO))).ok()

            val error = service.create(listOf(dep(a, b, DependencyType.RELATES_TO))).err()

            assertEquals(ErrorCode.DUPLICATE, error.code)
            assertEquals(1, stored(a, b).size)
        }

    @Test
    fun `create stores none of a request that contains a duplicate`(): Unit =
        runBlocking {
            service.create(listOf(dep(b, a, DependencyType.BLOCKS))).ok()

            val error = service.create(listOf(dep(c, d, DependencyType.BLOCKS), dep(a, b, DependencyType.IS_BLOCKED_BY))).err()

            assertEquals(ErrorCode.DUPLICATE, error.code)
            assertEquals(0, stored(c, d).size, "the valid sibling edge is not written")
        }

    // ------------------------------------------------------------------
    // create: cycles
    // ------------------------------------------------------------------

    @Test
    fun `create reports a cycle with its path when the new edge reverses a stored edge`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b, DependencyType.BLOCKS))).ok()

            val error = service.create(listOf(dep(b, a, DependencyType.BLOCKS))).err()

            assertEquals(ErrorCode.CYCLE_DETECTED, error.code)
            val detail = assertIs<ErrorDetail.CycleDetected>(error.detail)
            assertTrue(detail.path.containsAll(listOf(a, b)), "the path names both nodes of the cycle: ${detail.path}")
            assertEquals(1, stored(a, b).size)
        }

    @Test
    fun `create finds a cycle closed through a long chain of stored rows`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b), dep(b, c), dep(c, d), dep(d, e))).ok()

            // a IS_BLOCKED_BY e means e blocks a, closing a -> b -> c -> d -> e -> a
            val error = service.create(listOf(dep(a, e, DependencyType.IS_BLOCKED_BY))).err()

            assertEquals(ErrorCode.CYCLE_DETECTED, error.code)
            val detail = assertIs<ErrorDetail.CycleDetected>(error.detail)
            assertTrue(detail.path.containsAll(listOf(a, b, c, d, e)), "path: ${detail.path}")
        }

    @Test
    fun `create finds a cycle closed by edges of the same request across mixed types`(): Unit =
        runBlocking {
            val error =
                service
                    .create(
                        listOf(
                            dep(a, b, DependencyType.BLOCKS),
                            dep(c, b, DependencyType.IS_BLOCKED_BY),
                            dep(c, a, DependencyType.BLOCKS)
                        )
                    ).err()

            assertEquals(ErrorCode.CYCLE_DETECTED, error.code)
            assertEquals(0, stored(a, b, c).size)
        }

    @Test
    fun `create never reports a restatement as a cycle`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b, DependencyType.BLOCKS))).ok()

            // b IS_BLOCKED_BY a is a BLOCKS a -> b again
            val error = service.create(listOf(dep(b, a, DependencyType.IS_BLOCKED_BY))).err()

            assertEquals(ErrorCode.DUPLICATE, error.code)
        }

    @Test
    fun `create lets RELATES_TO sit against a blocking edge in either direction`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b, DependencyType.BLOCKS))).ok()

            service.create(listOf(dep(b, a, DependencyType.RELATES_TO))).ok()
            service.create(listOf(dep(a, b, DependencyType.RELATES_TO))).ok()

            assertEquals(3, stored(a, b).size)
        }

    @Test
    fun `create never stores an IS_BLOCKED_BY row`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b, DependencyType.IS_BLOCKED_BY), dep(c, d, DependencyType.IS_BLOCKED_BY))).ok()

            assertTrue(stored(a, b, c, d).none { it.type == DependencyType.IS_BLOCKED_BY })
            assertEquals(2, stored(a, b, c, d).size)
        }

    // ------------------------------------------------------------------
    // validateTreeEdges: pure policy over unsaved edges
    // ------------------------------------------------------------------

    @Test
    fun `validateTreeEdges returns the normalized edges in order without storing anything`(): Unit =
        runBlocking {
            val first = dep(a, b, DependencyType.IS_BLOCKED_BY, "work")
            val second = dep(c, d, DependencyType.RELATES_TO)

            val result = service.validateTreeEdges(listOf(first, second)).ok()

            assertEquals(listOf(first.id, second.id), result.map { it.id })
            assertEquals(Triple(b, a, DependencyType.BLOCKS), Triple(result[0].fromItemId, result[0].toItemId, result[0].type))
            assertEquals("work", result[0].unblockAt)
            assertEquals(Triple(c, d, DependencyType.RELATES_TO), Triple(result[1].fromItemId, result[1].toItemId, result[1].type))
            assertEquals(0, stored(a, b, c, d).size, "validation is pure")
        }

    @Test
    fun `validateTreeEdges rejects a restatement as a duplicate and a reversal as a cycle`(): Unit =
        runBlocking {
            val duplicate =
                service
                    .validateTreeEdges(
                        listOf(dep(a, b, DependencyType.BLOCKS), dep(b, a, DependencyType.IS_BLOCKED_BY))
                    ).err()
            assertEquals(ErrorCode.DUPLICATE, duplicate.code)

            val cycle = service.validateTreeEdges(listOf(dep(a, b, DependencyType.BLOCKS), dep(a, b, DependencyType.IS_BLOCKED_BY))).err()
            assertEquals(ErrorCode.CYCLE_DETECTED, cycle.code)
            val detail = assertIs<ErrorDetail.CycleDetected>(cycle.detail)
            assertTrue(detail.path.containsAll(listOf(a, b)))
        }

    @Test
    fun `validateTreeEdges accepts an empty list and a RELATES_TO pair`(): Unit =
        runBlocking {
            assertEquals(emptyList<Dependency>(), service.validateTreeEdges(emptyList()).ok())
            assertEquals(
                2,
                service.validateTreeEdges(listOf(dep(a, b, DependencyType.RELATES_TO), dep(b, a, DependencyType.RELATES_TO))).ok().size
            )
        }

    // ------------------------------------------------------------------
    // deleteByRelationship
    // ------------------------------------------------------------------

    @Test
    fun `deleteByRelationship with IS_BLOCKED_BY removes the BLOCKS row from the to side to the from side`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b, DependencyType.IS_BLOCKED_BY))).ok() // b BLOCKS a

            assertEquals(1, service.deleteByRelationship(a, b, DependencyType.IS_BLOCKED_BY).ok())
            assertEquals(0, stored(a, b).size)
        }

    @Test
    fun `deleteByRelationship with no type matches only rows stored from the from side to the to side`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b, DependencyType.IS_BLOCKED_BY))).ok() // stored b BLOCKS a

            assertEquals(0, service.deleteByRelationship(a, b, null).ok(), "no stored row runs from a to b")
            assertEquals(1, stored(a, b).size)

            assertEquals(1, service.deleteByRelationship(b, a, null).ok())
            assertEquals(0, stored(a, b).size)
        }

    @Test
    fun `deleteByRelationship with no type removes every type from the from side to the to side`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b, DependencyType.BLOCKS), dep(a, b, DependencyType.RELATES_TO))).ok()

            assertEquals(2, service.deleteByRelationship(a, b, null).ok())
            assertEquals(0, stored(a, b).size)
        }

    @Test
    fun `deleteByRelationship with RELATES_TO leaves the blocking row`(): Unit =
        runBlocking {
            service.create(listOf(dep(a, b, DependencyType.BLOCKS), dep(a, b, DependencyType.RELATES_TO))).ok()

            assertEquals(1, service.deleteByRelationship(a, b, DependencyType.RELATES_TO).ok())
            assertEquals(listOf(DependencyType.BLOCKS), stored(a, b).map { it.type })
        }

    @Test
    fun `deleteByRelationship of an absent relationship deletes zero`(): Unit =
        runBlocking {
            assertEquals(0, service.deleteByRelationship(a, b, DependencyType.IS_BLOCKED_BY).ok())
            assertEquals(0, service.deleteByRelationship(a, b, null).ok())
        }
}

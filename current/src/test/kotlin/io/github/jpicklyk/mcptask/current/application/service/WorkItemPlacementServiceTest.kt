package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.CountingUnitOfWork
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.sqlite.assertNoOutsideUnitWrites
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests [WorkItemPlacementService] against a real SQLite database: the single guard
 * ([WorkItemPlacementService.checkReparent]) and the placement-aware create / reparent write
 * pipeline shared by `manage_items` and `POST/PATCH /items`.
 */
class WorkItemPlacementServiceTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var plainRepo: WorkItemRepository

    /**
     * Counts top-level write units ([units], a [CountingUnitOfWork] the service is handed) and can script
     * lookup / write failures. Every other member forwards to the real repository.
     */
    private class SpyRepository(
        private val delegate: WorkItemRepository,
        uow: UnitOfWork
    ) : WorkItemRepository by delegate {
        var failAncestorChains = false
        var failUpdateFor: UUID? = null
        var onFirstTransaction: (suspend () -> Unit)? = null

        /** Runs [onFirstTransaction] once, inside the first unit, before the service's own block. */
        val units =
            CountingUnitOfWork(uow) { _ ->
                onFirstTransaction?.let {
                    onFirstTransaction = null
                    it()
                }
            }

        val transactionCount: Int get() = units.writes

        override suspend fun findAncestorChains(itemIds: Set<UUID>): Map<UUID, List<WorkItem>> =
            if (failAncestorChains) {
                throw IllegalStateException("ancestor lookup boom")
            } else {
                delegate.findAncestorChains(itemIds)
            }

        override suspend fun update(item: WorkItem): WorkItem? =
            if (item.id == failUpdateFor) throw IllegalStateException("update boom") else delegate.update(item)
    }

    @BeforeEach
    fun setUp() {
        val database =
            db.database
        plainRepo = db.repositoryProvider().workItemRepository()
    }

    private suspend fun root(title: String): WorkItem {
        val created = plainRepo.create(WorkItem(title = title, depth = 0))
        return plainRepo.update(created.copy(rootId = created.id))!!
    }

    private suspend fun child(
        parent: WorkItem,
        title: String,
        depthOverride: Int? = null
    ): WorkItem =
        (
            plainRepo.create(
                WorkItem(
                    title = title,
                    parentId = parent.id,
                    rootId = parent.rootId ?: parent.id,
                    depth = depthOverride ?: (parent.depth + 1)
                )
            )!!
        )

    private suspend fun load(id: UUID): WorkItem = plainRepo.getById(id)!!

    // ───────────────────────── checkReparent ─────────────────────────

    @Test
    fun `checkReparent returns Ok for a legal target`(): Unit =
        runBlocking {
            val r = root("R")
            val a = child(r, "A")
            val x = root("X")
            assertEquals(ReparentCheck.Ok, WorkItemPlacementService(plainRepo).checkReparent(x.id, a.id))
        }

    @Test
    fun `checkReparent reports ParentNotFound, SelfParent and DescendantCycle`(): Unit =
        runBlocking {
            val svc = WorkItemPlacementService(plainRepo)
            val x = root("X")
            val a = child(x, "A")
            val b = child(a, "B")
            // D's stored depth understates its true distance from X; the guard must not trust it.
            val d = child(b, "D", depthOverride = 1)
            val missing = UUID.randomUUID()

            assertEquals(ReparentCheck.ParentNotFound(missing), svc.checkReparent(x.id, missing))
            assertEquals(ReparentCheck.SelfParent, svc.checkReparent(x.id, x.id))
            assertEquals(ReparentCheck.DescendantCycle, svc.checkReparent(x.id, a.id))
            assertEquals(ReparentCheck.DescendantCycle, svc.checkReparent(x.id, d.id))
        }

    @Test
    fun `checkReparent fails closed when the ancestor lookup errors`(): Unit =
        runBlocking {
            val r = root("R")
            val a = child(r, "A")
            val x = root("X")
            val spy = SpyRepository(plainRepo, db.unitOfWork()).also { it.failAncestorChains = true }

            val check = WorkItemPlacementService(spy).checkReparent(x.id, a.id)

            assertIs<ReparentCheck.LookupFailed>(check)
            assertEquals("ancestor lookup boom", check.message)
            assertEquals(0, spy.transactionCount, "guard reads must never open a transaction")
        }

    @Test
    fun `parentExists reflects the repository`(): Unit =
        runBlocking {
            val svc = WorkItemPlacementService(plainRepo)
            val r = root("R")
            assertEquals(true, svc.parentExists(r.id))
            assertEquals(false, svc.parentExists(UUID.randomUUID()))
        }

    // ───────────────────────── create ─────────────────────────

    @Test
    fun `create a root item stamps depth 0 and self rootId in exactly one unit`(): Unit =
        runBlocking {
            val spy = SpyRepository(plainRepo, db.unitOfWork())
            val id = UUID.randomUUID()

            val outcome =
                db.assertNoOutsideUnitWrites {
                    WorkItemPlacementService(spy).create(spy.units, id, null) { depth, rootId ->
                        WorkItem(id = id, title = "root", depth = depth, rootId = rootId)
                    }
                }

            assertIs<PlacedWriteOutcome.Written>(outcome)
            assertEquals(0, outcome.item.depth)
            assertEquals(id, outcome.item.rootId)
            assertEquals(1, spy.transactionCount, "every write runs in exactly one unit (P5b D2)")
        }

    @Test
    fun `create under a parent resolves placement and writes in one transaction`(): Unit =
        runBlocking {
            val r = root("R")
            val a = child(r, "A")
            val spy = SpyRepository(plainRepo, db.unitOfWork())
            val id = UUID.randomUUID()

            val outcome =
                db.assertNoOutsideUnitWrites {
                    WorkItemPlacementService(spy).create(spy.units, id, a.id) { depth, rootId ->
                        WorkItem(id = id, title = "leaf", parentId = a.id, depth = depth, rootId = rootId)
                    }
                }

            assertIs<PlacedWriteOutcome.Written>(outcome)
            assertEquals(2, outcome.item.depth)
            assertEquals(r.id, outcome.item.rootId)
            assertEquals(1, spy.transactionCount)
        }

    @Test
    fun `create reports ParentNotFound when the parent is deleted inside the transaction and writes nothing`(): Unit =
        runBlocking {
            val r = root("R")
            val a = child(r, "A")
            val spy = SpyRepository(plainRepo, db.unitOfWork()).also { it.onFirstTransaction = { plainRepo.delete(a.id) } }
            val id = UUID.randomUUID()

            val outcome =
                db.assertNoOutsideUnitWrites {
                    WorkItemPlacementService(spy).create(spy.units, id, a.id) { depth, rootId ->
                        WorkItem(id = id, title = "leaf", parentId = a.id, depth = depth, rootId = rootId)
                    }
                }

            assertEquals(PlacedWriteOutcome.ParentNotFound(a.id), outcome)
            assertNull(plainRepo.getById(id))
        }

    @Test
    fun `create reports BuildFailed and writes nothing when build throws`(): Unit =
        runBlocking {
            val r = root("R")
            val id = UUID.randomUUID()

            val outcome =
                WorkItemPlacementService(
                    plainRepo
                ).create(db.unitOfWork(), id, r.id) { _, _ -> throw IllegalArgumentException("bad title") }

            assertEquals(PlacedWriteOutcome.BuildFailed("bad title"), outcome)
            assertNull(plainRepo.getById(id))
        }

    // ───────────────────────── update ─────────────────────────

    @Test
    fun `update without a parent change writes in exactly one unit`(): Unit =
        runBlocking {
            val r = root("R")
            val a = child(r, "A")
            val spy = SpyRepository(plainRepo, db.unitOfWork())

            val outcome =
                db.assertNoOutsideUnitWrites {
                    WorkItemPlacementService(spy).update(spy.units, a, r.id, parentChanged = false) { depth, rootId ->
                        a.update { it.copy(title = "A2", depth = depth, rootId = rootId) }
                    }
                }

            assertIs<PlacedWriteOutcome.Written>(outcome)
            assertEquals("A2", load(a.id).title)
            assertEquals(1, spy.transactionCount, "every write runs in exactly one unit (P5b D2)")
        }

    @Test
    fun `update reparent across roots at the same depth restamps descendant rootIds`(): Unit =
        runBlocking {
            val r = root("R")
            val a = child(r, "A")
            val b = child(a, "B")
            val r2 = root("R2")
            val spy = SpyRepository(plainRepo, db.unitOfWork())

            val outcome =
                db.assertNoOutsideUnitWrites {
                    WorkItemPlacementService(spy).update(spy.units, a, r2.id, parentChanged = true) { depth, rootId ->
                        a.update { it.copy(parentId = r2.id, depth = depth, rootId = rootId) }
                    }
                }

            assertIs<PlacedWriteOutcome.Written>(outcome)
            assertEquals(1, spy.transactionCount)
            assertEquals(1, load(a.id).depth)
            assertEquals(r2.id, load(a.id).rootId)
            assertEquals(2, load(b.id).depth)
            assertEquals(r2.id, load(b.id).rootId)
        }

    @Test
    fun `update move to root stamps depth 0 self rootId and cascades`(): Unit =
        runBlocking {
            val r = root("R")
            val a = child(r, "A")
            val b = child(a, "B")

            val outcome =
                db.assertNoOutsideUnitWrites {
                    WorkItemPlacementService(plainRepo).update(db.unitOfWork(), a, null, parentChanged = true) { depth, rootId ->
                        a.update { it.copy(parentId = null, depth = depth, rootId = rootId) }
                    }
                }

            assertIs<PlacedWriteOutcome.Written>(outcome)
            assertNull(load(a.id).parentId)
            assertEquals(0, load(a.id).depth)
            assertEquals(a.id, load(a.id).rootId)
            assertEquals(1, load(b.id).depth)
            assertEquals(a.id, load(b.id).rootId)
        }

    @Test
    fun `update reports CascadeFailed and rolls back the item write and descendants`(): Unit =
        runBlocking {
            val r = root("R")
            val p = child(r, "P")
            val x = root("X")
            val c1 = child(x, "C1")
            val c2 = child(x, "C2")
            val spy = SpyRepository(plainRepo, db.unitOfWork()).also { it.failUpdateFor = c2.id }

            val outcome =
                db.assertNoOutsideUnitWrites {
                    WorkItemPlacementService(spy).update(spy.units, x, p.id, parentChanged = true) { depth, rootId ->
                        x.update { it.copy(parentId = p.id, depth = depth, rootId = rootId) }
                    }
                }

            assertIs<PlacedWriteOutcome.CascadeFailed>(outcome)
            assertNull(load(x.id).parentId, "item write must roll back with the failed cascade")
            assertEquals(0, load(x.id).depth)
            assertEquals(1, load(c1.id).depth, "descendant written before the failure must roll back")
            assertEquals(x.id, load(c1.id).rootId)
        }

    @Test
    fun `update reports WriteFailed with ConflictError on a stale version`(): Unit =
        runBlocking {
            val r = root("R")
            val a = child(r, "A")
            // Another writer bumps the version after [a] was read.
            plainRepo.update(a.update { it.copy(title = "other writer") })

            val outcome =
                db.assertNoOutsideUnitWrites {
                    WorkItemPlacementService(plainRepo).update(db.unitOfWork(), a, r.id, parentChanged = false) { depth, rootId ->
                        a.update { it.copy(title = "stale write", depth = depth, rootId = rootId) }
                    }
                }

            assertIs<PlacedWriteOutcome.WriteFailed>(outcome)
            assertEquals(ErrorCode.VERSION_CONFLICT, outcome.error.code)
        }

    @Test
    fun `update reports BuildFailed and writes nothing when build throws`(): Unit =
        runBlocking {
            val r = root("R")
            val a = child(r, "A")
            val r2 = root("R2")

            val outcome =
                db.assertNoOutsideUnitWrites {
                    WorkItemPlacementService(plainRepo).update(db.unitOfWork(), a, r2.id, parentChanged = true) { _, _ ->
                        throw IllegalArgumentException("bad")
                    }
                }

            assertEquals(PlacedWriteOutcome.BuildFailed("bad"), outcome)
            assertEquals(r.id, load(a.id).parentId)
        }
}

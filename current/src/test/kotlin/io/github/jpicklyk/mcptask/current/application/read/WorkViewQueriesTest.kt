package io.github.jpicklyk.mcptask.current.application.read

import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.port.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.domain.graph.BlockingEdge
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.WorkItemsTable
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteLimits
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Q14 test-plan S8-S9 and S11-S14 (NEW-SURFACE): the [WorkViewQueries] views (`next`, `blocked`, `overview`,
 * `context`) and the batched store primitives they are built on. S10 (constant statement count) lives in
 * [ViewQueryCountTest]; S1-S7 (recommender equivalence and the AR-63 regression) live in [NextEligibilityTest].
 *
 * Substrate: a real SQLite database bound to a [SettableClock] parked at [t]. Expected values are hand counts and
 * hand-ordered lists written from the oracles below, never read back from a view.
 *
 * Oracle sources (frozen test-plan and task-scope, not the code under test):
 *  - S8/S9: `next` pages are disjoint, stable and concatenate to the full ordered eligible list; `total` is the exact
 *    eligible count; the ListPage invariants hold; includeClaimed turns the claim predicates off but keeps dependency
 *    blocking; null complexity sorts last on every path (task-scope D2); ties break by id ascending.
 *  - S11: blocked = role BLOCKED (explicit) plus QUEUE/WORK/REVIEW items with at least one unsatisfied blocker
 *    (dependency); TERMINAL never; no 500 cap, `total` exact (AR-64); each entry lists its blockers with role and
 *    satisfied flag.
 *  - S12: childCounts are direct-child role counts; anchored roll-ups are descendants minus self; claimSummary is over
 *    direct children and its parts sum to the child count; excludeTerminal keeps a terminal child that still has an
 *    open descendant and does so identically in global, scoped and anchored mode (18fd99a7, AR-65).
 *  - S13: active (WORK and REVIEW) and BLOCKED lists have exact totals past 200; the claim summary counts every item in
 *    scope; ancestorId scopes to a subtree, inclusive of its root (get_context `ancestorId` parameter schema).
 *  - S14: every batched primitive maps an empty id set to an empty result; large id sets work under SQLite's historic
 *    999 bind-variable limit (the precedent is WorkItemDeletionBulkSubtreeTest, which lowers the same limit).
 */
class WorkViewQueriesTest {
    private val clock = SettableClock()
    private val t: Instant = clock.now()

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val provider get() = db.repositoryProvider()
    private val repo get() = provider.workItemRepository()
    private val deps get() = provider.dependencyRepository()
    private val notes get() = provider.noteRepository()
    private val queries get() = WorkViewQueries(db.unitOfWork())

    private suspend fun make(
        title: String,
        role: Role = Role.QUEUE,
        priority: Priority = Priority.MEDIUM,
        complexity: Int? = null,
        parent: WorkItem? = null,
        claimedBy: String? = null,
        claimExpiresAt: Instant? = null,
        tags: String? = null
    ): WorkItem {
        val claimedAt = claimExpiresAt?.minusSeconds(60)
        return repo.create(
            WorkItem(
                title = title,
                role = role,
                priority = priority,
                complexity = complexity,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                tags = tags,
                claimedBy = claimedBy,
                claimedAt = claimedAt,
                claimExpiresAt = claimExpiresAt,
                originalClaimedAt = claimedAt
            )
        )
    }

    private suspend fun link(
        from: WorkItem,
        to: WorkItem,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ) {
        deps.create(Dependency(fromItemId = from.id, toItemId = to.id, type = type, unblockAt = unblockAt))
    }

    private fun live(seconds: Long = 300): Instant = t.plusSeconds(seconds)

    private fun ids(list: List<WorkItem>) = list.map { it.id }

    private fun Map<Role, Int>.of(role: Role): Int = this[role] ?: 0

    private fun Map<Role, Int>.assertCounts(
        queue: Int = 0,
        work: Int = 0,
        review: Int = 0,
        blocked: Int = 0,
        terminal: Int = 0,
        label: String
    ) {
        assertEquals(
            listOf(queue, work, review, blocked, terminal),
            listOf(of(Role.QUEUE), of(Role.WORK), of(Role.REVIEW), of(Role.BLOCKED), of(Role.TERMINAL)),
            "$label role counts (queue, work, review, blocked, terminal)"
        )
    }

    // -----------------------------------------------------------------------------------------------------------
    // S8 (NEW-SURFACE, happy): next pages
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S8 next pages of 2 over 5 eligible are disjoint, ordered, and report the exact total`(): Unit =
        runBlocking {
            lateinit var e: List<WorkItem>
            db.unitOfWork().inUnit {
                e =
                    listOf(
                        make("E1", priority = Priority.HIGH, complexity = 1),
                        make("E2", priority = Priority.HIGH, complexity = 2),
                        make("E3", priority = Priority.MEDIUM, complexity = 1),
                        make("E4", priority = Priority.MEDIUM, complexity = 5),
                        make("E5", priority = Priority.LOW, complexity = 3)
                    )
                make("claimed", priority = Priority.HIGH, complexity = 1, claimedBy = "X", claimExpiresAt = live())
                val blocker = make("open blocker", Role.WORK)
                link(blocker, make("blocked", priority = Priority.HIGH, complexity = 1))
                make("working", Role.WORK, Priority.HIGH, 1)
                make("done", Role.TERMINAL, Priority.HIGH, 1)
            }
            val criteria = NextItemRecommender.Criteria()

            val p1 = queries.next(criteria, PageRequest(limit = 2, offset = 0))
            val p2 = queries.next(criteria, PageRequest(limit = 2, offset = 2))
            val p3 = queries.next(criteria, PageRequest(limit = 2, offset = 4))

            assertEquals(ids(e).take(2), ids(p1.items))
            assertEquals(ids(e).slice(2..3), ids(p2.items))
            assertEquals(ids(e).drop(4), ids(p3.items))
            assertEquals(listOf(5, 5, 5), listOf(p1.total, p2.total, p3.total))
            // Envelope amendment U22: truncated means items exist after this page, so only the last page is complete
            assertEquals(listOf(true, true, false), listOf(p1.truncated, p2.truncated, p3.truncated))
            assertNotNull(p1.next)
            assertNotNull(p2.next)
            assertNull(p3.next)
            val all = ids(p1.items) + ids(p2.items) + ids(p3.items)
            assertEquals(all.size, all.toSet().size, "pages are disjoint")
            assertEquals(ids(e), all, "concatenated pages equal the full ordered eligible list")
        }

    @Test
    fun `S8 probe one page covering everything is not truncated, and a repeated page request returns the same items`(): Unit =
        runBlocking {
            db.unitOfWork().inUnit { repeat(5) { i -> make("E$i", priority = Priority.MEDIUM, complexity = i + 1) } }
            val criteria = NextItemRecommender.Criteria()

            val whole = queries.next(criteria, PageRequest(limit = 5))
            val firstAgain = queries.next(criteria, PageRequest(limit = 2))
            val firstOnceMore = queries.next(criteria, PageRequest(limit = 2))

            assertEquals(5, whole.items.size)
            assertEquals(5, whole.total)
            assertTrue(!whole.truncated)
            assertNull(whole.next)
            assertEquals(ids(firstAgain.items), ids(firstOnceMore.items), "paging is stable across repeated calls")
        }

    @Test
    fun `S8 probe items with identical sort keys page disjointly in id order and the total stays exact`(): Unit =
        runBlocking {
            val tied = mutableSetOf<UUID>()
            db.unitOfWork().inUnit {
                repeat(7) { i -> tied += make("tied $i", priority = Priority.MEDIUM, complexity = 4).id }
            }
            val dbIdOrder =
                transaction(db = db.database) {
                    WorkItemsTable
                        .select(WorkItemsTable.id)
                        .orderBy(WorkItemsTable.id, SortOrder.ASC)
                        .map { it[WorkItemsTable.id].value }
                        .filter { it in tied }
                }
            val criteria = NextItemRecommender.Criteria()

            val pages = listOf(0, 3, 6).map { queries.next(criteria, PageRequest(limit = 3, offset = it)) }

            assertEquals(listOf(3, 3, 1), pages.map { it.items.size })
            assertTrue(pages.all { it.total == 7 })
            assertEquals(dbIdOrder, pages.flatMap { ids(it.items) }, "ties by id ascending, so pages tile the list exactly")
        }

    // -----------------------------------------------------------------------------------------------------------
    // S9 (NEW-SURFACE, happy): includeClaimed
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S9 includeClaimed keeps claimed and shielded items, keeps dependency blocking, null complexity last`(): Unit =
        runBlocking {
            lateinit var a: WorkItem
            lateinit var b: WorkItem
            lateinit var c: WorkItem
            lateinit var shielded: WorkItem
            db.unitOfWork().inUnit {
                a = make("A claimed", complexity = 3, claimedBy = "X", claimExpiresAt = live())
                b = make("B no complexity", complexity = null)
                c = make("C", complexity = 7)
                make("D blocked", complexity = 1).also { link(make("open blocker", Role.WORK), it) }
                val heldParent = make("held parent", Role.WORK, claimedBy = "Y", claimExpiresAt = live())
                shielded = make("E under claimed parent", complexity = 9, parent = heldParent)
            }
            val criteria = NextItemRecommender.Criteria()

            val withClaimed = queries.next(criteria, PageRequest(), includeClaimed = true)
            val withoutClaimed = queries.next(criteria, PageRequest())

            assertEquals(
                listOf(a.id, c.id, shielded.id, b.id),
                ids(withClaimed.items),
                "claimed first by complexity 3, then 7, 9, null last"
            )
            assertEquals(4, withClaimed.total)
            assertEquals(listOf(c.id, b.id), ids(withoutClaimed.items), "default path: claimed and shielded items drop out")
            assertEquals(2, withoutClaimed.total)
        }

    @Test
    fun `S9 includeClaimed returns the top-ranked item first even behind 230 claimed ones, and pages tile all 231`(): Unit =
        runBlocking {
            lateinit var top: WorkItem
            db.unitOfWork().inUnit {
                repeat(230) { i -> make("claimed $i", claimedBy = "agent-$i", claimExpiresAt = live()) }
                top = make("top", priority = Priority.HIGH, complexity = 1, claimedBy = "agent-top", claimExpiresAt = live())
            }
            val criteria = NextItemRecommender.Criteria()

            val first = queries.next(criteria, PageRequest(limit = 5), includeClaimed = true)
            val big1 = queries.next(criteria, PageRequest(limit = 200, offset = 0), includeClaimed = true)
            val big2 = queries.next(criteria, PageRequest(limit = 200, offset = 200), includeClaimed = true)

            assertEquals(top.id, first.items.first().id, "ordering is by rank, not by storage order")
            assertEquals(231, first.total)
            assertEquals(200, big1.items.size)
            assertEquals(31, big2.items.size)
            val union = ids(big1.items) + ids(big2.items)
            assertEquals(231, union.toSet().size, "the two pages are disjoint and cover every item")
        }

    // -----------------------------------------------------------------------------------------------------------
    // S11 (NEW-SURFACE): blocked view
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S11 blocked lists explicit BLOCKED items and dependency-blocked active items with their blockers`(): Unit =
        runBlocking {
            lateinit var explicit1: WorkItem
            lateinit var explicit2: WorkItem
            lateinit var queueBlocked: WorkItem
            lateinit var workBlocked: WorkItem
            lateinit var reviewBlocked: WorkItem
            lateinit var partlyBlocked: WorkItem
            lateinit var satisfiedOnly: WorkItem
            lateinit var terminalWithOpenBlocker: WorkItem
            lateinit var relatesOnly: WorkItem
            lateinit var workBlocker: WorkItem
            lateinit var queueBlocker: WorkItem
            lateinit var doneBlocker: WorkItem
            db.unitOfWork().inUnit {
                explicit1 = make("explicit 1", Role.BLOCKED)
                explicit2 = make("explicit 2", Role.BLOCKED)
                workBlocker = make("work blocker", Role.WORK)
                queueBlocker = make("queue blocker", Role.QUEUE)
                doneBlocker = make("done blocker", Role.TERMINAL)
                queueBlocked = make("queue blocked")
                workBlocked = make("work blocked", Role.WORK)
                reviewBlocked = make("review blocked", Role.REVIEW)
                partlyBlocked = make("partly blocked")
                satisfiedOnly = make("satisfied only")
                terminalWithOpenBlocker = make("terminal with open blocker", Role.TERMINAL)
                relatesOnly = make("relates only")
                link(workBlocker, queueBlocked)
                link(queueBlocker, workBlocked)
                link(workBlocker, reviewBlocked, unblockAt = "review")
                link(doneBlocker, partlyBlocked)
                link(workBlocker, partlyBlocked, unblockAt = "terminal")
                link(doneBlocker, satisfiedOnly)
                link(workBlocker, satisfiedOnly, unblockAt = "work")
                link(workBlocker, terminalWithOpenBlocker)
                link(workBlocker, relatesOnly, DependencyType.RELATES_TO)
            }

            val page = queries.blocked(BlockedScope(), PageRequest(limit = 100))

            val byId = page.items.associateBy { it.item.id }
            assertEquals(
                setOf(explicit1.id, explicit2.id, queueBlocked.id, workBlocked.id, reviewBlocked.id, partlyBlocked.id),
                byId.keys,
                "terminal items, satisfied dependencies and RELATES_TO never block"
            )
            assertEquals(6, page.total)
            assertEquals(BlockType.EXPLICIT, byId.getValue(explicit1.id).blockType)
            assertEquals(BlockType.EXPLICIT, byId.getValue(explicit2.id).blockType)
            for (id in listOf(queueBlocked.id, workBlocked.id, reviewBlocked.id, partlyBlocked.id)) {
                assertEquals(BlockType.DEPENDENCY, byId.getValue(id).blockType)
            }
            val q = byId.getValue(queueBlocked.id)
            assertEquals(1, q.blockers.size)
            val qb = q.blockers.single()
            assertEquals(workBlocker.id, qb.blockerId)
            assertEquals("work blocker", qb.title)
            assertEquals(Role.WORK, qb.role)
            assertEquals(Role.TERMINAL, qb.threshold, "default threshold is terminal")
            assertEquals(false, qb.satisfied)
            assertEquals(1, q.unsatisfiedCount)
            val w = byId.getValue(workBlocked.id).blockers.single()
            assertEquals(queueBlocker.id, w.blockerId)
            assertEquals(Role.QUEUE, w.role)
            val r = byId.getValue(reviewBlocked.id).blockers.single()
            assertEquals(Role.REVIEW, r.threshold, "an explicit unblockAt is surfaced as the threshold")
            assertEquals(false, r.satisfied, "a WORK blocker has not reached review")
            val p = byId.getValue(partlyBlocked.id)
            assertEquals(setOf(doneBlocker.id, workBlocker.id), p.blockers.map { it.blockerId }.toSet())
            assertEquals(true, p.blockers.single { it.blockerId == doneBlocker.id }.satisfied)
            assertEquals(false, p.blockers.single { it.blockerId == workBlocker.id }.satisfied)
            assertEquals(1, p.unsatisfiedCount, "only the unsatisfied blocker counts")
            // the store-level primitive agrees on the same set
            val slice = provider.itemStore().findBlocked(parentId = null, ancestorId = null, limit = 100, offset = 0)
            assertEquals(byId.keys, ids(slice.items).toSet())
            assertEquals(6, slice.total)
        }

    @Test
    fun `S11 an IS_BLOCKED_BY dependency and a BLOCKED blocker are reported like their normalized BLOCKS form`(): Unit =
        runBlocking {
            lateinit var viaAlias: WorkItem
            lateinit var heldBlocker: WorkItem
            lateinit var behindHeld: WorkItem
            db.unitOfWork().inUnit {
                val open = make("open", Role.WORK)
                viaAlias = make("via alias")
                link(viaAlias, open, DependencyType.IS_BLOCKED_BY)
                heldBlocker = make("held blocker", Role.BLOCKED)
                behindHeld = make("behind held", Role.WORK)
                link(heldBlocker, behindHeld, unblockAt = "queue")
            }

            val byId = queries.blocked(BlockedScope(), PageRequest(limit = 100)).items.associateBy { it.item.id }

            assertEquals(BlockType.DEPENDENCY, byId.getValue(viaAlias.id).blockType)
            assertEquals(
                "open",
                byId
                    .getValue(viaAlias.id)
                    .blockers
                    .single()
                    .title
            )
            val held = byId.getValue(behindHeld.id)
            assertEquals(BlockType.DEPENDENCY, held.blockType)
            assertEquals(false, held.blockers.single().satisfied, "a BLOCKED blocker never satisfies, even a queue threshold")
            assertEquals(BlockType.EXPLICIT, byId.getValue(heldBlocker.id).blockType)
        }

    @Test
    fun `S11 600 blocked items are reported with total 600 and pages of 200 tile them`(): Unit =
        runBlocking {
            db.unitOfWork().inUnit {
                val blocker = make("open blocker", Role.WORK)
                repeat(600) { i -> link(blocker, make("blocked $i")) }
            }

            val pages = listOf(0, 200, 400).map { queries.blocked(BlockedScope(), PageRequest(limit = 200, offset = it)) }

            assertEquals(listOf(200, 200, 200), pages.map { it.items.size })
            assertEquals(listOf(600, 600, 600), pages.map { it.total })
            // Envelope amendment U22: truncated means items exist after this page
            assertEquals(listOf(true, true, false), pages.map { it.truncated }, "U22: only the last page is complete")
            val union = pages.flatMap { page -> page.items.map { it.item.id } }
            assertEquals(600, union.toSet().size, "pages are disjoint and cover every blocked item")
        }

    @Test
    fun `S11 scope by parent selects direct children, scope by ancestor selects the whole subtree`(): Unit =
        runBlocking {
            lateinit var parent: WorkItem
            lateinit var childHeld: WorkItem
            lateinit var childDep: WorkItem
            lateinit var grandchildHeld: WorkItem
            lateinit var outsideHeld: WorkItem
            db.unitOfWork().inUnit {
                parent = make("parent")
                childHeld = make("child held", Role.BLOCKED, parent = parent)
                childDep = make("child dep", parent = parent)
                link(make("open blocker", Role.WORK), childDep)
                val middle = make("middle", parent = parent)
                grandchildHeld = make("grandchild held", Role.BLOCKED, parent = middle)
                outsideHeld = make("outside held", Role.BLOCKED)
            }

            val byParent = queries.blocked(BlockedScope(parentId = parent.id), PageRequest())
            val byAncestor = queries.blocked(BlockedScope(ancestorId = parent.id), PageRequest())
            val unscoped = queries.blocked(BlockedScope(), PageRequest())

            assertEquals(setOf(childHeld.id, childDep.id), byParent.items.map { it.item.id }.toSet())
            assertEquals(2, byParent.total)
            assertEquals(setOf(childHeld.id, childDep.id, grandchildHeld.id), byAncestor.items.map { it.item.id }.toSet())
            assertEquals(3, byAncestor.total)
            assertTrue(outsideHeld.id in unscoped.items.map { it.item.id })
            assertEquals(4, unscoped.total)
        }

    // -----------------------------------------------------------------------------------------------------------
    // S12 (NEW-SURFACE): overview
    // -----------------------------------------------------------------------------------------------------------

    private class OverviewTree(
        val r1: WorkItem,
        val r2: WorkItem,
        val r3: WorkItem,
        val cq: WorkItem,
        val cw: WorkItem,
        val cr: WorkItem,
        val cb: WorkItem,
        val ct1: WorkItem,
        val ct2: WorkItem,
        val ct3: WorkItem,
        val c2: WorkItem
    ) {
        val r1Children get() = listOf(cq, cw, cr, cb, ct1, ct2, ct3)
    }

    /**
     * R1 (QUEUE): cq QUEUE (live claim), cw WORK (expired claim) > g2 WORK > gg QUEUE, cr REVIEW, cb BLOCKED, ct1 TERMINAL,
     * ct2 TERMINAL > g1 QUEUE, ct3 TERMINAL > h TERMINAL. R2 (TERMINAL) > c2 QUEUE. R3 (TERMINAL), childless.
     */
    private suspend fun seedOverview(): OverviewTree {
        lateinit var tree: OverviewTree
        db.unitOfWork().inUnit {
            val r1 = make("R1")
            val cq = make("cq", Role.QUEUE, parent = r1, claimedBy = "a", claimExpiresAt = live())
            val cw = make("cw", Role.WORK, parent = r1, claimedBy = "b", claimExpiresAt = t.minusSeconds(1))
            val g2 = make("g2", Role.WORK, parent = cw)
            make("gg", Role.QUEUE, parent = g2)
            val cr = make("cr", Role.REVIEW, parent = r1)
            val cb = make("cb", Role.BLOCKED, parent = r1)
            val ct1 = make("ct1", Role.TERMINAL, parent = r1)
            val ct2 = make("ct2", Role.TERMINAL, parent = r1)
            make("g1", Role.QUEUE, parent = ct2)
            val ct3 = make("ct3", Role.TERMINAL, parent = r1)
            make("h", Role.TERMINAL, parent = ct3)
            val r2 = make("R2", Role.TERMINAL)
            val c2 = make("c2", Role.QUEUE, parent = r2)
            val r3 = make("R3", Role.TERMINAL)
            tree = OverviewTree(r1, r2, r3, cq, cw, cr, cb, ct1, ct2, ct3, c2)
        }
        return tree
    }

    @Test
    fun `S12 global overview gives direct childCounts and a claimSummary whose parts sum to the children`(): Unit =
        runBlocking {
            val tree = seedOverview()

            val view = queries.overview(OverviewRequest(page = PageRequest(limit = 100)))

            val global = assertIs<OverviewView.Global>(view)
            assertEquals(
                setOf(tree.r1.id, tree.r2.id, tree.r3.id),
                global.roots.items
                    .map { it.item.id }
                    .toSet()
            )
            assertEquals(3, global.roots.total)
            val e1 = global.roots.items.single { it.item.id == tree.r1.id }
            e1.childCounts.assertCounts(queue = 1, work = 1, review = 1, blocked = 1, terminal = 3, label = "R1 direct")
            global.roots.items
                .single { it.item.id == tree.r2.id }
                .childCounts
                .assertCounts(queue = 1, label = "R2 direct")
            global.roots.items
                .single { it.item.id == tree.r3.id }
                .childCounts
                .assertCounts(label = "R3 direct")
            val claims = assertNotNull(e1.claimSummary, "R1's claim summary")
            assertEquals(ClaimStatusCounts(active = 1, expired = 1, unclaimed = 5), claims)
            assertEquals(7, claims.active + claims.expired + claims.unclaimed, "parts sum to the direct children")
            assertNull(e1.children, "children are only returned when includeChildren is set")
        }

    @Test
    fun `S12 global overview with includeChildren attaches each root's direct children`(): Unit =
        runBlocking {
            val tree = seedOverview()

            val global =
                assertIs<OverviewView.Global>(queries.overview(OverviewRequest(page = PageRequest(limit = 100), includeChildren = true)))

            val e1 = global.roots.items.single { it.item.id == tree.r1.id }
            assertEquals(
                tree.r1Children.map { it.id }.toSet(),
                e1.children
                    .orEmpty()
                    .map { it.item.id }
                    .toSet()
            )
            assertEquals(
                listOf(tree.c2.id),
                global.roots.items
                    .single { it.item.id == tree.r2.id }
                    .children
                    .orEmpty()
                    .map { it.item.id }
            )
            assertTrue(
                global.roots.items
                    .single { it.item.id == tree.r3.id }
                    .children
                    .orEmpty()
                    .isEmpty()
            )
        }

    @Test
    fun `S12 global overview pages its roots`(): Unit =
        runBlocking {
            val tree = seedOverview()

            val first = assertIs<OverviewView.Global>(queries.overview(OverviewRequest(page = PageRequest(limit = 2, offset = 0))))
            val second = assertIs<OverviewView.Global>(queries.overview(OverviewRequest(page = PageRequest(limit = 2, offset = 2))))

            assertEquals(listOf(2, 1), listOf(first.roots.items.size, second.roots.items.size))
            assertEquals(listOf(3, 3), listOf(first.roots.total, second.roots.total))
            // Envelope amendment U22: truncated means items exist after this page
            assertTrue(first.roots.truncated && !second.roots.truncated, "U22: only the last page is complete")
            assertEquals(
                setOf(tree.r1.id, tree.r2.id, tree.r3.id),
                (first.roots.items + second.roots.items).map { it.item.id }.toSet()
            )
        }

    @Test
    fun `S12 scoped overview lists the item's children with their own direct childCounts`(): Unit =
        runBlocking {
            val tree = seedOverview()

            val scoped =
                assertIs<OverviewView.Scoped>(queries.overview(OverviewRequest(itemId = tree.r1.id, page = PageRequest(limit = 100))))

            assertEquals(tree.r1.id, scoped.item.id)
            scoped.childCounts.assertCounts(queue = 1, work = 1, review = 1, blocked = 1, terminal = 3, label = "R1 scoped")
            assertEquals(
                tree.r1Children.map { it.id }.toSet(),
                scoped.children.items
                    .map { it.item.id }
                    .toSet()
            )
            assertEquals(7, scoped.children.total)
            val byId = scoped.children.items.associateBy { it.item.id }
            byId.getValue(tree.cw.id).childCounts.assertCounts(work = 1, label = "cw direct only (g2), not gg")
            byId.getValue(tree.ct2.id).childCounts.assertCounts(queue = 1, label = "ct2")
            byId.getValue(tree.ct3.id).childCounts.assertCounts(terminal = 1, label = "ct3")
            byId.getValue(tree.cq.id).childCounts.assertCounts(label = "cq")
        }

    @Test
    fun `S12 anchored overview rolls up descendants minus self for each direct child of the anchor`(): Unit =
        runBlocking {
            val tree = seedOverview()

            val anchored =
                assertIs<OverviewView.Anchored>(queries.overview(OverviewRequest(anchorId = tree.r1.id, page = PageRequest(limit = 100))))

            assertEquals(tree.r1.id, anchored.anchor.id)
            assertEquals(
                tree.r1Children.map { it.id }.toSet(),
                anchored.items.items
                    .map { it.item.id }
                    .toSet()
            )
            assertEquals(7, anchored.items.total)
            val byId = anchored.items.items.associateBy { it.item.id }
            byId.getValue(tree.cw.id).childCounts.assertCounts(queue = 1, work = 1, label = "cw roll-up: g2 and gg, not cw itself")
            byId.getValue(tree.ct2.id).childCounts.assertCounts(queue = 1, label = "ct2 roll-up")
            byId.getValue(tree.ct3.id).childCounts.assertCounts(terminal = 1, label = "ct3 roll-up")
            byId.getValue(tree.cq.id).childCounts.assertCounts(label = "cq roll-up")
            byId.getValue(tree.cb.id).childCounts.assertCounts(label = "cb roll-up")
        }

    @Test
    fun `S12 excludeTerminal keeps a terminal item with an open descendant in global, scoped and anchored mode`(): Unit =
        runBlocking {
            val tree = seedOverview()
            val page = PageRequest(limit = 100)
            val expectedChildren = setOf(tree.cq.id, tree.cw.id, tree.cr.id, tree.cb.id, tree.ct2.id)

            val global = assertIs<OverviewView.Global>(queries.overview(OverviewRequest(page = page, excludeTerminal = true)))
            val scoped =
                assertIs<OverviewView.Scoped>(queries.overview(OverviewRequest(itemId = tree.r1.id, page = page, excludeTerminal = true)))
            val anchored =
                assertIs<OverviewView.Anchored>(
                    queries.overview(OverviewRequest(anchorId = tree.r1.id, page = page, excludeTerminal = true))
                )

            assertEquals(
                setOf(tree.r1.id, tree.r2.id),
                global.roots.items
                    .map { it.item.id }
                    .toSet(),
                "R2 is terminal but c2 is open; R3 drops"
            )
            assertEquals(2, global.roots.total)
            assertEquals(
                expectedChildren,
                scoped.children.items
                    .map { it.item.id }
                    .toSet(),
                "ct2 stays (g1 open); ct1 and ct3 drop"
            )
            assertEquals(5, scoped.children.total)
            assertEquals(
                expectedChildren,
                anchored.items.items
                    .map { it.item.id }
                    .toSet()
            )
            assertEquals(5, anchored.items.total)
            scoped.childCounts.assertCounts(
                queue = 1,
                work = 1,
                review = 1,
                blocked = 1,
                terminal = 3,
                label = "childCounts stay unfiltered"
            )
        }

    @Test
    fun `S12 a scoped overview of a terminal item still returns that item, with or without open children`(): Unit =
        runBlocking {
            val tree = seedOverview()
            val page = PageRequest(limit = 100)

            val withOpenChild =
                assertIs<OverviewView.Scoped>(queries.overview(OverviewRequest(itemId = tree.r2.id, page = page, excludeTerminal = true)))
            val childless =
                assertIs<OverviewView.Scoped>(queries.overview(OverviewRequest(itemId = tree.r3.id, page = page, excludeTerminal = true)))

            assertEquals(tree.r2.id, withOpenChild.item.id)
            assertEquals(listOf(tree.c2.id), withOpenChild.children.items.map { it.item.id })
            assertEquals(tree.r3.id, childless.item.id)
            assertTrue(childless.children.items.isEmpty())
            assertEquals(0, childless.children.total)
        }

    @Test
    fun `S12 unknown ids give NotFound and itemId together with anchorId is rejected`(): Unit =
        runBlocking {
            seedOverview()
            val unknown = UUID.randomUUID()

            val missingItem = queries.overview(OverviewRequest(itemId = unknown))
            val missingAnchor = queries.overview(OverviewRequest(anchorId = unknown))

            assertEquals(OverviewView.NotFound(unknown), missingItem)
            assertEquals(OverviewView.NotFound(unknown), missingAnchor)
            val ex = assertFailsWith<IllegalArgumentException> { OverviewRequest(itemId = unknown, anchorId = UUID.randomUUID()) }
            assertEquals("itemId and anchorId are mutually exclusive", ex.message)
        }

    // -----------------------------------------------------------------------------------------------------------
    // S13 (NEW-SURFACE): context
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S13 context reports exact active and blocked totals past 200 and pages both lists`(): Unit =
        runBlocking {
            db.unitOfWork().inUnit {
                repeat(230) { i -> make("working $i", Role.WORK) }
                repeat(5) { i -> make("review $i", Role.REVIEW) }
                repeat(210) { i -> make("held $i", Role.BLOCKED) }
                repeat(20) { i -> make("queued $i") }
                repeat(20) { i -> make("done $i", Role.TERMINAL) }
            }

            val whole = queries.context(ContextScope(), PageRequest(limit = 200))
            val small = queries.context(ContextScope(), PageRequest(limit = 10, offset = 0))
            val next = queries.context(ContextScope(), PageRequest(limit = 10, offset = 10))

            assertEquals(235, whole.active.total)
            assertEquals(210, whole.blocked.total)
            assertEquals(200, whole.active.items.size)
            assertEquals(200, whole.blocked.items.size)
            assertEquals(listOf(10, 10), listOf(small.active.items.size, small.blocked.items.size))
            assertEquals(listOf(235, 210), listOf(small.active.total, small.blocked.total))
            assertTrue(small.active.items.all { it.role == Role.WORK || it.role == Role.REVIEW })
            assertTrue(small.blocked.items.all { it.role == Role.BLOCKED })
            assertTrue(ids(small.active.items).intersect(ids(next.active.items).toSet()).isEmpty(), "active pages are disjoint")
            assertTrue(ids(small.blocked.items).intersect(ids(next.blocked.items).toSet()).isEmpty(), "blocked pages are disjoint")
        }

    @Test
    fun `S13 context returns the notes of the active items in the page and none for other items`(): Unit =
        runBlocking {
            lateinit var withTwo: WorkItem
            lateinit var withOne: WorkItem
            lateinit var withNone: WorkItem
            lateinit var queuedWithNote: WorkItem
            db.unitOfWork().inUnit {
                withTwo = make("active, two notes", Role.WORK)
                withOne = make("active, one note", Role.REVIEW)
                withNone = make("active, no notes", Role.WORK)
                queuedWithNote = make("queued, one note")
                notes.upsert(Note(itemId = withTwo.id, key = "implementation-notes", role = "work", body = "one"))
                notes.upsert(Note(itemId = withTwo.id, key = "session-tracking", role = "work", body = "two"))
                notes.upsert(Note(itemId = withOne.id, key = "review-checklist", role = "review", body = "three"))
                notes.upsert(Note(itemId = queuedWithNote.id, key = "task-scope", role = "queue", body = "four"))
            }

            val view = queries.context(ContextScope(), PageRequest())

            assertEquals(setOf(withTwo.id, withOne.id, withNone.id), ids(view.active.items).toSet())
            assertEquals(
                setOf("implementation-notes", "session-tracking"),
                view.notes[withTwo.id]
                    .orEmpty()
                    .map { it.key }
                    .toSet()
            )
            assertEquals(listOf("review-checklist"), view.notes[withOne.id].orEmpty().map { it.key })
            assertTrue(view.notes[withNone.id].orEmpty().isEmpty())
            assertTrue(
                view.notes[queuedWithNote.id].orEmpty().isEmpty(),
                "a queued item is not an active item, so its notes are not part of the context"
            )
        }

    @Test
    fun `S13 context scope by ancestor limits lists and claim summary to the subtree, root included`(): Unit =
        runBlocking {
            lateinit var rootA: WorkItem
            lateinit var activeUnderA: List<WorkItem>
            db.unitOfWork().inUnit {
                rootA = make("A root, working", Role.WORK)
                val a1 = make("A working 1", Role.WORK, parent = rootA, claimedBy = "x", claimExpiresAt = live())
                val a2 = make("A working 2", Role.WORK, parent = rootA, claimedBy = "y", claimExpiresAt = t.minusSeconds(1))
                val a3 = make("A review", Role.REVIEW, parent = rootA)
                make("A held 1", Role.BLOCKED, parent = rootA)
                make("A held 2", Role.BLOCKED, parent = rootA)
                make("A queued", Role.QUEUE, parent = rootA)
                activeUnderA = listOf(rootA, a1, a2, a3)
                val rootB = make("B root")
                repeat(4) { i ->
                    make(
                        "B working $i",
                        Role.WORK,
                        parent = rootB,
                        claimedBy =
                            if (i ==
                                0
                            ) {
                                "z"
                            } else {
                                null
                            },
                        claimExpiresAt = if (i == 0) live() else null
                    )
                }
                make("B held", Role.BLOCKED, parent = rootB)
            }

            val scoped = queries.context(ContextScope(ancestorId = rootA.id), PageRequest())
            val unscoped = queries.context(ContextScope(), PageRequest())

            assertEquals(activeUnderA.map { it.id }.toSet(), ids(scoped.active.items).toSet(), "the scoped root is inclusive")
            assertEquals(4, scoped.active.total)
            assertEquals(2, scoped.blocked.total)
            assertEquals(ClaimStatusCounts(active = 1, expired = 1, unclaimed = 5), scoped.claimSummary, "7 items in A's subtree")
            assertEquals(8, unscoped.active.total)
            assertEquals(3, unscoped.blocked.total)
            assertEquals(ClaimStatusCounts(active = 2, expired = 1, unclaimed = 10), unscoped.claimSummary, "13 items in the database")
        }

    // -----------------------------------------------------------------------------------------------------------
    // S14 (edge, NEW-SURFACE): primitives
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S14 every batched primitive maps an empty id set to an empty result`(): Unit =
        runBlocking {
            db.unitOfWork().inUnit { make("something exists") }
            val itemStore = provider.itemStore()
            val claimStore = provider.claimStore()

            assertEquals(emptyMap(), itemStore.rolesOf(emptySet()))
            assertEquals(emptyMap(), itemStore.countByParentAndRole(emptySet()))
            assertEquals(emptyMap(), itemStore.rollupByTopChild(emptySet()))
            assertEquals(emptyMap(), itemStore.findByParents(emptySet()))
            assertEquals(emptyMap(), claimStore.claimSummaryByParent(emptySet()))
            assertEquals(emptyList(), deps.blockingEdgesFor(emptySet()))
        }

    @Test
    fun `S14 rolesOf maps known ids to their roles and omits unknown ids`(): Unit =
        runBlocking {
            lateinit var q: WorkItem
            lateinit var w: WorkItem
            lateinit var blocked: WorkItem
            db.unitOfWork().inUnit {
                q = make("q")
                w = make("w", Role.WORK)
                blocked = make("b", Role.BLOCKED)
                make("done", Role.TERMINAL)
            }
            val unknown = UUID.randomUUID()

            val roles = provider.itemStore().rolesOf(setOf(q.id, w.id, blocked.id, unknown))

            assertEquals(mapOf(q.id to Role.QUEUE, w.id to Role.WORK, blocked.id to Role.BLOCKED), roles)
        }

    @Test
    fun `S14 countByParentAndRole and rollupByTopChild count direct children and descendants minus self`(): Unit =
        runBlocking {
            lateinit var parent: WorkItem
            lateinit var cw: WorkItem
            lateinit var ct: WorkItem
            lateinit var leaf: WorkItem
            db.unitOfWork().inUnit {
                parent = make("parent")
                make("child q", parent = parent)
                cw = make("child w", Role.WORK, parent = parent)
                make("child w2", Role.WORK, parent = parent)
                ct = make("child t", Role.TERMINAL, parent = parent)
                val g = make("grand w", Role.WORK, parent = cw)
                make("great q", Role.QUEUE, parent = g)
                make("great review", Role.REVIEW, parent = g)
                leaf = make("childless root")
            }
            val itemStore = provider.itemStore()

            val counts = itemStore.countByParentAndRole(setOf(parent.id, cw.id, leaf.id))
            val rollups = itemStore.rollupByTopChild(setOf(cw.id, ct.id, leaf.id))

            counts.getValue(parent.id).assertCounts(queue = 1, work = 2, terminal = 1, label = "parent direct")
            counts.getValue(cw.id).assertCounts(work = 1, label = "cw direct")
            counts[leaf.id].orEmpty().assertCounts(label = "childless")
            rollups.getValue(cw.id).assertCounts(queue = 1, work = 1, review = 1, label = "cw roll-up: grand, great q, great review")
            rollups[ct.id].orEmpty().assertCounts(label = "childless top child")
            rollups[leaf.id].orEmpty().assertCounts(label = "childless top")
        }

    @Test
    fun `S14 findByParents returns the direct children of each requested parent`(): Unit =
        runBlocking {
            lateinit var p1: WorkItem
            lateinit var p2: WorkItem
            lateinit var empty: WorkItem
            lateinit var p1Children: List<WorkItem>
            lateinit var p2Children: List<WorkItem>
            db.unitOfWork().inUnit {
                p1 = make("p1")
                p2 = make("p2")
                empty = make("empty")
                p1Children = listOf(make("p1a", parent = p1), make("p1b", Role.WORK, parent = p1))
                p2Children = listOf(make("p2a", parent = p2))
                make("grandchild", parent = p1Children[0])
            }

            val byParent = provider.itemStore().findByParents(setOf(p1.id, p2.id, empty.id))

            assertEquals(ids(p1Children).toSet(), ids(byParent.getValue(p1.id)).toSet())
            assertEquals(ids(p2Children).toSet(), ids(byParent.getValue(p2.id)).toSet())
            assertTrue(byParent[empty.id].orEmpty().isEmpty())
        }

    @Test
    fun `S14 findChildrenPage pages direct children and excludeTerminal keeps a terminal child with an open descendant`(): Unit =
        runBlocking {
            lateinit var parent: WorkItem
            lateinit var q: WorkItem
            lateinit var w: WorkItem
            lateinit var tOpen: WorkItem
            lateinit var tDone: WorkItem
            lateinit var tDone2: WorkItem
            db.unitOfWork().inUnit {
                parent = make("parent")
                q = make("q", parent = parent)
                w = make("w", Role.WORK, parent = parent)
                tOpen = make("terminal with open descendant", Role.TERMINAL, parent = parent)
                make("open descendant", parent = tOpen)
                tDone = make("terminal", Role.TERMINAL, parent = parent)
                tDone2 = make("terminal with terminal descendant", Role.TERMINAL, parent = parent)
                make("done descendant", Role.TERMINAL, parent = tDone2)
            }
            val itemStore = provider.itemStore()

            val all = itemStore.findChildrenPage(parentId = parent.id, excludeTerminal = false, limit = 100, offset = 0)
            val open = itemStore.findChildrenPage(parentId = parent.id, excludeTerminal = true, limit = 100, offset = 0)
            val pages =
                listOf(
                    0,
                    2,
                    4
                ).map { itemStore.findChildrenPage(parentId = parent.id, excludeTerminal = false, limit = 2, offset = it) }
            val roots = itemStore.findChildrenPage(parentId = null, excludeTerminal = false, limit = 100, offset = 0)

            assertEquals(setOf(q.id, w.id, tOpen.id, tDone.id, tDone2.id), ids(all.items).toSet())
            assertEquals(5, all.total)
            assertEquals(setOf(q.id, w.id, tOpen.id), ids(open.items).toSet())
            assertEquals(3, open.total)
            assertEquals(listOf(2, 2, 1), pages.map { it.items.size })
            assertEquals(listOf(5, 5, 5), pages.map { it.total })
            assertEquals(5, pages.flatMap { ids(it.items) }.toSet().size, "pages are disjoint")
            assertEquals(listOf(parent.id), ids(roots.items), "parentId null lists the roots")
        }

    @Test
    fun `S14 findByRoles filters by role and subtree with exact totals`(): Unit =
        runBlocking {
            lateinit var rootA: WorkItem
            lateinit var inA: List<WorkItem>
            db.unitOfWork().inUnit {
                rootA = make("A root", Role.WORK)
                inA = listOf(rootA, make("A review", Role.REVIEW, parent = rootA))
                make("A queued", parent = rootA)
                make("A done", Role.TERMINAL, parent = rootA)
                make("B working", Role.WORK)
                make("B review", Role.REVIEW)
            }
            val itemStore = provider.itemStore()

            val everywhere = itemStore.findByRoles(setOf(Role.WORK, Role.REVIEW), rootIds = null, limit = 100, offset = 0)
            val underA = itemStore.findByRoles(setOf(Role.WORK, Role.REVIEW), rootIds = setOf(rootA.id), limit = 100, offset = 0)
            val paged = itemStore.findByRoles(setOf(Role.WORK, Role.REVIEW), rootIds = null, limit = 2, offset = 0)
            val none = itemStore.findByRoles(setOf(Role.WORK, Role.REVIEW), rootIds = emptySet(), limit = 100, offset = 0)

            assertEquals(4, everywhere.total)
            assertEquals(inA.map { it.id }.toSet(), ids(underA.items).toSet(), "the subtree root is inclusive")
            assertEquals(2, underA.total)
            assertEquals(2, paged.items.size)
            assertEquals(4, paged.total)
            assertTrue(none.items.isEmpty() && none.total == 0, "an empty subtree scope matches nothing")
        }

    @Test
    fun `S14 blockingEdgesFor returns the normalized edges into the requested items only`(): Unit =
        runBlocking {
            lateinit var a: WorkItem
            lateinit var b: WorkItem
            lateinit var c: WorkItem
            lateinit var d: WorkItem
            lateinit var e: WorkItem
            lateinit var r: WorkItem
            db.unitOfWork().inUnit {
                a = make("a")
                b = make("b", Role.WORK)
                c = make("c", Role.WORK)
                d = make("d", Role.WORK)
                e = make("e")
                r = make("r", Role.WORK)
                link(b, a)
                link(c, a, unblockAt = "review")
                link(a, d, DependencyType.IS_BLOCKED_BY)
                link(a, e)
                link(r, a, DependencyType.RELATES_TO)
            }

            val intoA = deps.blockingEdgesFor(setOf(a.id))
            val intoE = deps.blockingEdgesFor(setOf(e.id))
            val intoBoth = deps.blockingEdgesFor(setOf(a.id, e.id))

            assertEquals(
                setOf(
                    BlockingEdge(blocker = b.id, blocked = a.id, unblockAt = Role.TERMINAL),
                    BlockingEdge(blocker = c.id, blocked = a.id, unblockAt = Role.REVIEW),
                    BlockingEdge(blocker = d.id, blocked = a.id, unblockAt = Role.TERMINAL)
                ),
                intoA.toSet(),
                "BLOCKS and the normalized IS_BLOCKED_BY (d blocks a); a's outgoing edge to e and the RELATES_TO edge are not edges into a"
            )
            assertEquals(3, intoA.size, "no duplicates")
            assertEquals(setOf(BlockingEdge(blocker = a.id, blocked = e.id, unblockAt = Role.TERMINAL)), intoE.toSet())
            assertEquals(intoA.toSet() + intoE.toSet(), intoBoth.toSet())
        }

    @Test
    fun `S14 claimSummary and claimSummaryByParent count active, expired and unclaimed`(): Unit =
        runBlocking {
            lateinit var p1: WorkItem
            lateinit var p2: WorkItem
            lateinit var empty: WorkItem
            db.unitOfWork().inUnit {
                p1 = make("p1")
                p2 = make("p2")
                empty = make("empty")
                make("p1 live", parent = p1, claimedBy = "x", claimExpiresAt = live())
                make("p1 expired", parent = p1, claimedBy = "y", claimExpiresAt = t.minusSeconds(1))
                make("p1 expires now", parent = p1, claimedBy = "w", claimExpiresAt = t)
                make("p1 free", parent = p1)
                make("p2 live", parent = p2, claimedBy = "z", claimExpiresAt = live())
            }
            val claimStore = provider.claimStore()

            val byParent = claimStore.claimSummaryByParent(setOf(p1.id, p2.id, empty.id))
            val subtree = claimStore.claimSummary(setOf(p1.id))
            val global = claimStore.claimSummary(null)

            assertEquals(
                ClaimStatusCounts(active = 1, expired = 2, unclaimed = 1),
                byParent.getValue(p1.id),
                "an expiry equal to now is expired"
            )
            assertEquals(ClaimStatusCounts(active = 1, expired = 0, unclaimed = 0), byParent.getValue(p2.id))
            val emptyCounts = byParent[empty.id] ?: ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0)
            assertEquals(ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0), emptyCounts)
            assertEquals(ClaimStatusCounts(active = 1, expired = 2, unclaimed = 2), subtree, "p1 and its four children")
            assertEquals(ClaimStatusCounts(active = 2, expired = 2, unclaimed = 4), global, "all 8 items")
        }

    @Test
    fun `S14 probe primitives stay correct with more ids than the historic 999 bind-variable limit`(): Unit =
        runBlocking {
            val parents = mutableListOf<WorkItem>()
            val children = mutableListOf<WorkItem>()
            db.unitOfWork().inUnit {
                repeat(1100) { i ->
                    val p = make("p$i")
                    parents += p
                    val childRole = if (i % 2 == 0) Role.QUEUE else Role.WORK
                    val claimed = i % 4 == 0
                    val child =
                        make(
                            "c$i",
                            childRole,
                            parent = p,
                            claimedBy = if (claimed) "agent" else null,
                            claimExpiresAt = if (claimed) live() else null
                        )
                    children += child
                    link(p, child)
                }
            }
            val parentIds = parents.map { it.id }.toSet()
            val childIds = children.map { it.id }.toSet()

            db.unitOfWork().read {
                val connection = TransactionManager.current().connection.connection as Connection
                connection.unwrap(SQLiteConnection::class.java).setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, 999)
                val itemStore = stores.itemStore()

                val roles = itemStore.rolesOf(parentIds + childIds)
                assertEquals(2200, roles.size)
                assertTrue(children.all { roles[it.id] == it.role })

                val counts = itemStore.countByParentAndRole(parentIds)
                assertEquals(1100, parents.count { counts[it.id].orEmpty().values.sum() == 1 })
                assertTrue(parents.withIndex().all { (i, p) -> counts.getValue(p.id).of(children[i].role!!) == 1 })

                val rollups = itemStore.rollupByTopChild(parentIds)
                assertTrue(parents.withIndex().all { (i, p) -> rollups.getValue(p.id).of(children[i].role!!) == 1 })

                val byParent = itemStore.findByParents(parentIds)
                assertEquals(1100, byParent.values.count { it.size == 1 })

                val claims = stores.claimStore().claimSummaryByParent(parentIds)
                assertEquals(275, claims.values.count { it.active == 1 })
                assertEquals(825, claims.values.count { it.unclaimed == 1 })

                val edges = stores.dependencyRepository().blockingEdgesFor(childIds)
                assertEquals(1100, edges.size)
                assertEquals(childIds, edges.map { it.blocked }.toSet())
            }
        }
}

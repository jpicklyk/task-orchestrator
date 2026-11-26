package io.github.jpicklyk.mcptask.current.item

import io.github.jpicklyk.mcptask.current.claim.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.model.item.Priority
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.port.PageRequest
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Q14 test-plan S12 (NEW-SURFACE): the [OverviewQueries] `overview` view. Substrate: a real SQLite database bound to a
 * [SettableClock] parked at [t]. Expected values are hand counts written from the oracle, never read back from a view.
 *
 * Oracle (frozen test-plan and task-scope, not the code under test): childCounts are direct-child role counts; anchored
 * roll-ups are descendants minus self; claimSummary is over direct children and its parts sum to the child count;
 * excludeTerminal keeps a terminal child that still has an open descendant and does so identically in global, scoped
 * and anchored mode (18fd99a7, AR-65).
 */
class OverviewQueriesTest {
    private val clock = SettableClock()
    private val t: Instant = clock.now()

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val provider get() = db.repositoryProvider()
    private val repo get() = provider.workItemRepository()
    private val overviewQueries get() = OverviewQueries(db.unitOfWork())

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

    private fun live(seconds: Long = 300): Instant = t.plusSeconds(seconds)

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

            val view = overviewQueries.overview(OverviewRequest(page = PageRequest(limit = 100)))

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
                assertIs<OverviewView.Global>(
                    overviewQueries.overview(OverviewRequest(page = PageRequest(limit = 100), includeChildren = true))
                )

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

            val first = assertIs<OverviewView.Global>(overviewQueries.overview(OverviewRequest(page = PageRequest(limit = 2, offset = 0))))
            val second = assertIs<OverviewView.Global>(overviewQueries.overview(OverviewRequest(page = PageRequest(limit = 2, offset = 2))))

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
                assertIs<OverviewView.Scoped>(
                    overviewQueries.overview(OverviewRequest(itemId = tree.r1.id, page = PageRequest(limit = 100)))
                )

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
                assertIs<OverviewView.Anchored>(
                    overviewQueries.overview(OverviewRequest(anchorId = tree.r1.id, page = PageRequest(limit = 100)))
                )

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

            val global = assertIs<OverviewView.Global>(overviewQueries.overview(OverviewRequest(page = page, excludeTerminal = true)))
            val scoped =
                assertIs<OverviewView.Scoped>(
                    overviewQueries.overview(OverviewRequest(itemId = tree.r1.id, page = page, excludeTerminal = true))
                )
            val anchored =
                assertIs<OverviewView.Anchored>(
                    overviewQueries.overview(OverviewRequest(anchorId = tree.r1.id, page = page, excludeTerminal = true))
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
                assertIs<OverviewView.Scoped>(
                    overviewQueries.overview(OverviewRequest(itemId = tree.r2.id, page = page, excludeTerminal = true))
                )
            val childless =
                assertIs<OverviewView.Scoped>(
                    overviewQueries.overview(OverviewRequest(itemId = tree.r3.id, page = page, excludeTerminal = true))
                )

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

            val missingItem = overviewQueries.overview(OverviewRequest(itemId = unknown))
            val missingAnchor = overviewQueries.overview(OverviewRequest(anchorId = unknown))

            assertEquals(OverviewView.NotFound(unknown), missingItem)
            assertEquals(OverviewView.NotFound(unknown), missingAnchor)
            val ex = assertFailsWith<IllegalArgumentException> { OverviewRequest(itemId = unknown, anchorId = UUID.randomUUID()) }
            assertEquals("itemId and anchorId are mutually exclusive", ex.message)
        }
}

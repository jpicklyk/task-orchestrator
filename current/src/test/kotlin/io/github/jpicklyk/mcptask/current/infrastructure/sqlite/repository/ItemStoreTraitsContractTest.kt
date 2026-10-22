package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.EligibilityQuery
import io.github.jpicklyk.mcptask.current.application.port.ItemFilter
import io.github.jpicklyk.mcptask.current.application.port.ItemPage
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests for the typed-traits store contract (item 22088359, Q15): `item_traits` persistence on create, update
 * and delete, `ItemStore.traitsOf`, hydration of `WorkItem.traits` on every store read, and `ItemFilter.traitsAny` on
 * `find` / `count`. Scenarios S1, S6 (find/count half), S7, S14, S16 and S17 of the frozen test-plan, against the real
 * SQLite store.
 *
 * Oracles (frozen task-scope B1, B3, B8, AC3, AC5, AC6 and the V26 DDL in migration-assessment section 1, none read from
 * the implementation):
 *  - B1/B3: a create writes one `item_traits` row per name at positions 0..n-1 in the given order; an update replaces the
 *    list wholesale (no stale position, reordering is legal although (item, name) is unique); deleting an item removes its
 *    rows (the application connection runs `foreign_keys=ON`, `ON DELETE CASCADE`). The rows are read back with raw SQL
 *    against the DDL, never through the store under test.
 *  - AC6: `traitsOf` answers any non-empty id set with ONE statement, names in position order, an id with no traits (or no
 *    row at all) is absent, an empty set yields an empty map without touching the database.
 *  - AC3: every read that returns a `WorkItem` returns it WITH its traits, with a statement count that does not grow with
 *    the number of rows returned (compared at one and at forty rows over the same shape of data; the counter is an Exposed
 *    interceptor registered on an outer read unit, as `ViewQueryCountTest` does).
 *  - AC5/B8: `traitsAny` selects the items carrying AT LEAST ONE listed name (exact, case-sensitive), elements are trimmed
 *    and empty elements dropped, null / `[]` / `[""]` add no condition, the condition is AND-ed with every other filter
 *    field, `count` equals the size of the unpaged `find`, and an item matching several listed names appears once.
 *    The expected sets are computed by a plain Kotlin predicate over the seeded items.
 *
 * Fixture invariants (WorkItem domain rules) are satisfied by construction: a child's depth is its parent's depth + 1 and
 * its rootId is the parent's root; no claim is set by hand (claims go through the claim API); trait names are non-blank,
 * trimmed, comma-free, at most 100 characters and unique within an item.
 */
class ItemStoreTraitsContractTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val provider get() = sqliteDb.repositoryProvider()
    private val items get() = provider.itemStore()
    private val hierarchy get() = provider.hierarchyStore()
    private val repo get() = provider.workItemRepository()
    private val jdbc get() = sqliteDb.jdbcUrl

    private suspend fun mk(
        title: String,
        traits: List<String> = emptyList(),
        parent: WorkItem? = null,
        role: Role = Role.QUEUE,
        priority: Priority = Priority.MEDIUM,
        tags: String? = null,
        id: UUID = UUID.randomUUID(),
    ): WorkItem =
        items.create(
            WorkItem(
                title = title,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                id = id,
                rootId = parent?.let { it.rootId ?: it.id } ?: id,
                role = role,
                priority = priority,
                tags = tags,
                traits = traits,
            ),
        )

    private fun rawRows(id: UUID): List<Pair<Int, String>> =
        P7Raw.query(jdbc, "SELECT position, name FROM item_traits WHERE item_id = ? ORDER BY position", id) {
            it.getInt(1) to it.getString(2)
        }

    private fun rawCount(sql: String): Int = P7Raw.query(jdbc, sql) { it.getInt(1) }.single()

    private fun ordered(vararg names: String): List<Pair<Int, String>> = names.mapIndexed { i, n -> i to n }

    // ============================================================================ S1: create, update, delete

    @Test
    fun `S1 create writes one row per name at positions 0 to n-1 in the given order and reads return the same order`(): Unit =
        runBlocking {
            val created = mk("ordered", listOf("zeta", "alpha", "mid"))

            assertEquals(ordered("zeta", "alpha", "mid"), rawRows(created.id), "positions follow the given order, not alphabetical order")
            assertEquals(listOf("zeta", "alpha", "mid"), created.traits, "the returned item carries its traits")
            assertEquals(listOf("zeta", "alpha", "mid"), assertNotNull(items.getById(created.id)).traits, "a read returns the same order")
        }

    @Test
    fun `S1 an item without traits has no rows and reads back an empty list`(): Unit =
        runBlocking {
            val created = mk("bare")

            assertEquals(emptyList(), rawRows(created.id))
            assertEquals(emptyList(), assertNotNull(items.getById(created.id)).traits)
        }

    @Test
    fun `S1 names that differ only by case are distinct and both stored`(): Unit =
        runBlocking {
            val created = mk("cases", listOf("A", "a"))

            assertEquals(ordered("A", "a"), rawRows(created.id))
            assertEquals(listOf("A", "a"), assertNotNull(items.getById(created.id)).traits)
        }

    @Test
    fun `S1 a name of exactly 100 characters is stored and read back`(): Unit =
        runBlocking {
            val name = "n".repeat(100)
            val created = mk("long", listOf(name))

            assertEquals(ordered(name), rawRows(created.id))
            assertEquals(listOf(name), assertNotNull(items.getById(created.id)).traits)
        }

    @Test
    fun `S1 an update replaces the list wholesale and leaves no stale positions`(): Unit =
        runBlocking {
            var item = mk("evolving", listOf("a", "b", "c"))
            val sibling = mk("sibling", listOf("keep-1", "keep-2"))

            item = assertNotNull(items.update(item.copy(traits = listOf("c"))))
            assertEquals(ordered("c"), rawRows(item.id), "shrinking 3 to 1 leaves exactly one row")
            assertEquals(listOf("c"), assertNotNull(items.getById(item.id)).traits)

            item = assertNotNull(items.update(item.copy(traits = listOf("b", "a"))))
            assertEquals(ordered("b", "a"), rawRows(item.id), "a list sharing no position-name pair with the old one is still written")

            item = assertNotNull(items.update(item.copy(traits = listOf("a", "b"))))
            assertEquals(ordered("a", "b"), rawRows(item.id), "swapping two names must not trip the (item, name) uniqueness")

            item = assertNotNull(items.update(item.copy(title = "renamed")))
            assertEquals(ordered("a", "b"), rawRows(item.id), "an update that does not touch the list keeps the rows")
            assertEquals(listOf("a", "b"), assertNotNull(items.getById(item.id)).traits)

            item = assertNotNull(items.update(item.copy(traits = listOf("a", "b", "c", "d"))))
            assertEquals(ordered("a", "b", "c", "d"), rawRows(item.id), "growing rewrites positions 0..3")

            item = assertNotNull(items.update(item.copy(traits = emptyList())))
            assertEquals(emptyList(), rawRows(item.id), "clearing removes every row")
            assertEquals(emptyList(), assertNotNull(items.getById(item.id)).traits)

            assertEquals(ordered("keep-1", "keep-2"), rawRows(sibling.id), "another item's rows are never touched")
        }

    // ============================================================================ S16: delete cascades

    @Test
    fun `S16 deleting items removes their trait rows and only theirs`(): Unit =
        runBlocking {
            val a = mk("doomed-a", listOf("a1", "a2"))
            val b = mk("doomed-b", listOf("b1"))
            val survivor = mk("survivor", listOf("s1", "s2", "s3"))
            assertEquals(6, rawCount("SELECT count(*) FROM item_traits"), "control: all six rows exist before the delete")

            assertEquals(1, items.deleteAll(setOf(a.id)))
            assertEquals(emptyList(), rawRows(a.id), "cascade removes the deleted item's rows")
            assertEquals(ordered("b1"), rawRows(b.id))
            assertEquals(3, rawCount("SELECT count(*) FROM item_traits WHERE item_id = x'${hex(survivor.id)}'"))

            assertEquals(2, items.deleteAll(setOf(b.id, survivor.id)))
            assertEquals(0, rawCount("SELECT count(*) FROM item_traits"), "no orphan row remains")
        }

    private fun hex(id: UUID): String = P7Raw.bytes(id).joinToString("") { "%02x".format(it) }

    // ============================================================================ S7: traitsOf

    @Test
    fun `S7 traitsOf returns names in position order and omits ids with no traits or no row`(): Unit =
        runBlocking {
            val a = mk("a", listOf("zeta", "alpha", "mid"))
            val b = mk("b", listOf("only"))
            val c = mk("c")
            val unknown = UUID.randomUUID()

            val result = items.traitsOf(setOf(a.id, b.id, c.id, unknown))

            assertEquals(mapOf(a.id to listOf("zeta", "alpha", "mid"), b.id to listOf("only")), result)
        }

    @Test
    fun `S7 traitsOf of an empty set is an empty map and issues no statement`(): Unit =
        runBlocking {
            mk("present", listOf("t"))

            var result: Map<UUID, List<String>>? = null
            val statements = countStatements { result = items.traitsOf(emptySet()) }

            assertEquals(emptyMap(), result)
            assertEquals(0, statements, "an empty input must not touch the database")
        }

    @Test
    fun `S7 traitsOf answers any non-empty id set with exactly one statement`(): Unit =
        runBlocking {
            val many = (0 until 40).map { mk("m$it", listOf("t$it", "common")) }
            val one = many.first()
            val absent = UUID.randomUUID()

            val countedOne = countStatements { items.traitsOf(setOf(one.id)) }
            val countedMany = countStatements { items.traitsOf(many.map { it.id }.toSet() + absent) }
            var result: Map<UUID, List<String>>? = null
            countStatements { result = items.traitsOf(many.map { it.id }.toSet()) }

            assertEquals(1, countedOne, "one id: one statement")
            assertEquals(1, countedMany, "forty-one ids: still one statement")
            assertEquals(40, result!!.size)
            for ((index, item) in many.withIndex()) {
                assertEquals(listOf("t$index", "common"), result[item.id], "traits of m$index")
            }
        }

    // ============================================================================ S14: every read path carries traits

    private class Tree(
        val root: WorkItem,
        val children: List<WorkItem>,
        val leaf: WorkItem,
    ) {
        val all: List<WorkItem> get() = listOf(root) + children + leaf
    }

    private suspend fun buildTree(childCount: Int): Tree {
        val root = mk("root", listOf("root-a", "root-b"), role = Role.WORK)
        val children =
            (0 until childCount).map {
                mk(
                    "child-$it",
                    listOf("c$it-first", "shared", "c$it-last"),
                    parent = root,
                    role =
                        if (it % 2 ==
                            0
                        ) {
                            Role.QUEUE
                        } else {
                            Role.WORK
                        }
                )
            }
        val leaf = mk("leaf", listOf("leaf-only"), parent = children.first())
        return Tree(root, children, leaf)
    }

    private fun expectedTraits(tree: Tree): Map<UUID, List<String>> = tree.all.associate { it.id to it.traits }

    private fun assertTraits(
        label: String,
        returned: Collection<WorkItem>,
        expected: Map<UUID, List<String>>,
    ) {
        assertTrue(returned.isNotEmpty(), "$label: control, the read returned rows")
        for (item in returned) {
            assertEquals(expected.getValue(item.id), item.traits, "$label: traits of ${item.title}")
        }
    }

    @Test
    fun `S14 every store read that returns work items returns them with their traits`(): Unit =
        runBlocking {
            val tree = buildTree(4)
            val expected = expectedTraits(tree)
            val ids = expected.keys

            assertTraits("getById", listOfNotNull(items.getById(tree.children[1].id)), expected)
            assertTraits("findByIdSet", items.findByIdSet(ids), expected)
            assertTraits("findByIds", items.findByIds(ids), expected)
            assertTraits("find", items.find(ItemFilter.ALL, page = ItemPage(limit = 100)), expected)
            assertTraits("findChildren", items.findChildren(tree.root.id), expected)
            assertTraits("findByParent", items.findByParent(tree.root.id), expected)
            assertTraits("findByDepth", items.findByDepth(1), expected)
            assertTraits(
                "findChildrenPage",
                items.findChildrenPage(parentId = tree.root.id, excludeTerminal = false, limit = 100, offset = 0).items,
                expected
            )
            assertTraits("findDescendants", hierarchy.findDescendants(tree.root.id), expected)
            assertTraits("findRootItems", repo.findRootItems().items, expected)
            assertTraits("findByRole", repo.findByRole(Role.QUEUE, limit = 50), expected)
            assertTraits("findEligible", repo.findEligible(EligibilityQuery(role = Role.QUEUE), limit = 1000).items, expected)
            val chains = hierarchy.findAncestorChains(setOf(tree.leaf.id))
            assertTraits("findAncestorChains", chains.getValue(tree.leaf.id), expected)
            assertEquals(listOf("root-a", "root-b"), chains.getValue(tree.leaf.id).first { it.id == tree.root.id }.traits)
        }

    @Test
    fun `S14 a claim result and an update result carry the item's traits`(): Unit =
        runBlocking {
            val item = mk("claimable", listOf("t1", "t2"))

            val claimed = assertIs<ClaimResult.Success>(repo.claim(item.id, "agent-a", ttlSeconds = 60))
            assertEquals(listOf("t1", "t2"), claimed.item.traits, "the claimed item is returned with its traits")
            assertEquals(listOf("t1", "t2"), assertNotNull(items.getById(item.id)).traits, "the claim did not disturb the list")

            val updated = assertNotNull(items.update(assertNotNull(items.getById(item.id)).copy(title = "renamed")))
            assertEquals(listOf("t1", "t2"), updated.traits, "the updated item is returned with its traits")
            assertEquals(ordered("t1", "t2"), rawRows(item.id))
        }

    // ---- statement counting (the same interceptor method as ViewQueryCountTest) ----

    private class StatementCounter : StatementInterceptor {
        var count = 0

        override fun beforeExecution(
            transaction: Transaction,
            context: StatementContext,
        ) {
            count++
        }
    }

    private suspend fun countStatements(body: suspend () -> Unit): Int =
        sqliteDb.unitOfWork().read {
            val tx = TransactionManager.current()
            val counter = StatementCounter()
            tx.registerInterceptor(counter)
            try {
                body()
            } finally {
                tx.unregisterInterceptor(counter)
            }
            counter.count
        }

    @Test
    fun `S14 the statement count of each read is the same at one child and at forty children`(): Unit =
        runBlocking {
            val tree1 = buildTree(1)
            val reads = linkedMapOf<String, suspend () -> Unit>()
            reads["getById"] = { items.getById(tree1.root.id) }
            reads["findByIdSet"] = { items.findByIdSet(setOf(tree1.root.id, tree1.leaf.id)) }
            reads["find"] = { items.find(ItemFilter.ALL, page = ItemPage(limit = 200)) }
            reads["findChildren"] = { items.findChildren(tree1.root.id) }
            reads["findChildrenPage"] =
                { items.findChildrenPage(parentId = tree1.root.id, excludeTerminal = false, limit = 200, offset = 0) }
            reads["findDescendants"] = { hierarchy.findDescendants(tree1.root.id) }
            reads["findByRole"] = { repo.findByRole(Role.QUEUE, limit = 200) }
            reads["findEligible"] = { repo.findEligible(EligibilityQuery(role = Role.QUEUE), limit = 1000).items }

            suspend fun measure(): Map<String, Int> =
                reads.mapValues { (_, read) ->
                    read() // warm-up outside the counted run
                    countStatements { read() }
                }

            val small = measure()
            small.forEach { (name, count) -> assertTrue(count > 0, "$name: the interceptor saw the read") }

            // thirty-nine more children, each with its own traits (forty children in all), then the same reads again
            val extra = (0 until 39).map { mk("extra-$it", listOf("e$it", "shared"), parent = tree1.root, role = Role.QUEUE) }
            assertEquals(39, extra.size)
            val large = measure()

            for (name in reads.keys) {
                assertEquals(small.getValue(name), large.getValue(name), "$name: statement count must not grow with the number of rows")
            }
            val ids = (listOf(tree1.root.id, tree1.leaf.id) + extra.map { it.id }).toSet()
            val many = countStatements { items.findByIdSet(ids) }
            val one = countStatements { items.findByIdSet(setOf(tree1.root.id)) }
            assertEquals(one, many, "findByIdSet: one id and forty-one ids cost the same")
        }

    // ============================================================================ S6 / S17: traitsAny on find and count

    private class Seed(
        val title: String,
        val traits: List<String>,
        val role: Role,
        val priority: Priority,
        val tags: String?,
    )

    private val seeds =
        listOf(
            Seed("i0", listOf("x"), Role.QUEUE, Priority.HIGH, "alpha"),
            Seed("i1", listOf("a", "x"), Role.WORK, Priority.LOW, "alpha"),
            Seed("i2", listOf("y"), Role.QUEUE, Priority.MEDIUM, "beta"),
            Seed("i3", listOf("x", "y"), Role.REVIEW, Priority.HIGH, null),
            Seed("i4", emptyList(), Role.QUEUE, Priority.LOW, "alpha"),
            Seed("i5", listOf("X"), Role.WORK, Priority.MEDIUM, null),
            Seed("i6", listOf("pct%"), Role.TERMINAL, Priority.LOW, null),
            Seed("i7", listOf("a", "b", "c", "d", "e", "f", "g", "h", "x"), Role.WORK, Priority.HIGH, "beta"),
            Seed("i8", listOf("y", "z"), Role.QUEUE, Priority.HIGH, "alpha"),
            Seed("i9", listOf("x"), Role.TERMINAL, Priority.MEDIUM, "alpha"),
        )

    private suspend fun seedAll(): List<WorkItem> =
        seeds.map {
            mk(it.title, it.traits, role = it.role, priority = it.priority, tags = it.tags)
        }

    /** Oracle: the documented normalisation (trim, drop empty), null or empty meaning no condition, exact membership. */
    private fun matches(
        traits: List<String>,
        wanted: List<String>?,
    ): Boolean {
        val names = wanted?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return names.isEmpty() || traits.any { it in names }
    }

    private fun find(filter: ItemFilter): List<WorkItem> = runBlocking { items.find(filter, page = ItemPage(limit = 100)) }

    private fun count(filter: ItemFilter): Int = runBlocking { items.count(filter) }

    private fun assertSelects(
        all: List<WorkItem>,
        wanted: List<String>?,
        base: ItemFilter = ItemFilter.ALL,
        extra: (WorkItem) -> Boolean = { true },
    ) {
        val filter = base.copy(traitsAny = wanted)
        val expected = all.filter { matches(it.traits, wanted) && extra(it) }.map { it.id }.toSet()
        val found = find(filter)
        assertEquals(expected, found.map { it.id }.toSet(), "traitsAny=$wanted: selected items")
        assertEquals(found.size, found.map { it.id }.toSet().size, "traitsAny=$wanted: an item matching several names appears once")
        assertEquals(expected.size, count(filter), "traitsAny=$wanted: count equals the size of the unpaged find")
        for (item in found) {
            assertEquals(
                all
                    .single {
                        it.id == item.id
                    }.traits,
                item.traits,
                "traitsAny=$wanted: ${item.title} keeps its full list, not only the matched names"
            )
        }
    }

    @Test
    fun `S6 traitsAny selects items carrying at least one listed name at any position`(): Unit =
        runBlocking {
            val all = seedAll()

            assertSelects(all, listOf("x"))
            assertSelects(all, listOf("y"))
            assertSelects(all, listOf("x", "y"))
            assertSelects(all, listOf("z"))
            assertSelects(all, listOf("nobody-has-this"))
            // control: x is carried in the first, a middle and the last (ninth) position
            val xIds = find(ItemFilter(traitsAny = listOf("x"))).map { it.title }.toSet()
            assertEquals(setOf("i0", "i1", "i3", "i7", "i9"), xIds)
        }

    @Test
    fun `S17 null, an empty list and blank elements add no condition while a mixed list keeps only its real names`(): Unit =
        runBlocking {
            val all = seedAll()

            assertSelects(all, null)
            assertSelects(all, emptyList())
            assertSelects(all, listOf(""))
            assertSelects(all, listOf(" "))
            assertSelects(all, listOf("", "  "))
            assertEquals(all.size, find(ItemFilter(traitsAny = listOf(""))).size, "[''] is not a condition")
            // a blank element next to a real one is dropped, it does not turn the filter into match-all
            assertSelects(all, listOf("x", ""))
            assertEquals(5, find(ItemFilter(traitsAny = listOf("x", ""))).size)
        }

    @Test
    fun `S17 matching is exact and case-sensitive, trims elements and treats listed duplicates as one`(): Unit =
        runBlocking {
            val all = seedAll()

            assertSelects(all, listOf("X"))
            assertEquals(setOf("i5"), find(ItemFilter(traitsAny = listOf("X"))).map { it.title }.toSet(), "X is not x")
            assertEquals(setOf("i0", "i1", "i3", "i7", "i9"), find(ItemFilter(traitsAny = listOf("x"))).map { it.title }.toSet())
            assertSelects(all, listOf(" x "))
            assertEquals(5, find(ItemFilter(traitsAny = listOf(" x "))).size, "elements are trimmed")
            assertSelects(all, listOf("x", "x", "x"))
            assertEquals(5, find(ItemFilter(traitsAny = listOf("x", "x", "x"))).size)
            assertSelects(all, listOf("x", "X"))
            assertEquals(6, find(ItemFilter(traitsAny = listOf("x", "X"))).size)
        }

    @Test
    fun `S17 special characters in a listed name are literal and cannot widen the match`(): Unit =
        runBlocking {
            val all = seedAll()

            assertSelects(all, listOf("pct%"))
            assertEquals(setOf("i6"), find(ItemFilter(traitsAny = listOf("pct%"))).map { it.title }.toSet())
            for (probe in listOf("%", "pct_", "p%", "_", "a,b", "x') OR 1=1 --", "x\" OR \"1\"=\"1", "x'; DROP TABLE item_traits; --")) {
                assertEquals(emptyList(), find(ItemFilter(traitsAny = listOf(probe))).map { it.title }, "'$probe' matches nothing")
                assertEquals(0, count(ItemFilter(traitsAny = listOf(probe))), "'$probe': count is 0")
            }
            assertEquals(
                9,
                rawCount("SELECT count(DISTINCT item_id) FROM item_traits"),
                "control: the table survived (nine items carry traits)"
            )
        }

    @Test
    fun `S6 traitsAny is AND-ed with every other filter field`(): Unit =
        runBlocking {
            val all = seedAll()

            assertSelects(all, listOf("x"), ItemFilter(role = Role.WORK)) { it.role == Role.WORK }
            assertSelects(all, listOf("x", "y"), ItemFilter(role = Role.QUEUE)) { it.role == Role.QUEUE }
            assertSelects(all, listOf("x", "y"), ItemFilter(priority = Priority.HIGH)) { it.priority == Priority.HIGH }
            assertSelects(all, listOf("x"), ItemFilter(tagsAny = listOf("alpha"))) { it.tags?.split(",")?.contains("alpha") == true }
            assertSelects(all, listOf("x", "y"), ItemFilter(role = Role.TERMINAL)) { it.role == Role.TERMINAL }
            // a role with a trait-carrying item but no listed trait: the intersection is empty, not the role's items
            assertEquals(emptyList(), find(ItemFilter(role = Role.REVIEW, traitsAny = listOf("y", "z")).copy(priority = Priority.LOW)))
            assertEquals(1, count(ItemFilter(role = Role.REVIEW, traitsAny = listOf("y"))))
            assertEquals(0, count(ItemFilter(role = Role.REVIEW, traitsAny = listOf("z"))))
        }

    @Test
    fun `S6 count equals the unpaged find and pages of a traitsAny filter tile it exactly`(): Unit =
        runBlocking {
            seedAll()
            val filter = ItemFilter(traitsAny = listOf("x", "y"))

            val unpaged = runBlocking { items.find(filter, page = ItemPage(limit = 100)) }.map { it.id }
            val pages =
                (0 until unpaged.size step 3).flatMap { offset ->
                    runBlocking { items.find(filter, page = ItemPage(3, offset)) }.map { it.id }
                }

            assertEquals(7, unpaged.size, "i0 i1 i2 i3 i7 i8 i9")
            assertEquals(unpaged.size, count(filter), "the total is computed after the trait condition")
            assertEquals(unpaged, pages, "pages of 3 concatenate to the unpaged order, no duplicates and no gaps")
            assertEquals(emptyList(), runBlocking { items.find(filter, page = ItemPage(3, 9)) }, "an offset past the total is empty")
            assertEquals(unpaged.size, count(filter), "count is unchanged by paging")
        }
}

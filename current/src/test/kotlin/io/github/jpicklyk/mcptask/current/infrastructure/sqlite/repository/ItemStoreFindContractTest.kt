package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.ItemFilter
import io.github.jpicklyk.mcptask.current.application.port.ItemPage
import io.github.jpicklyk.mcptask.current.application.port.ItemSort
import io.github.jpicklyk.mcptask.current.application.port.ItemSortField
import io.github.jpicklyk.mcptask.current.application.port.SortDirection
import io.github.jpicklyk.mcptask.current.domain.model.ClaimStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent test-author coverage for item `67ba3514` ([Q5]) -- `ItemStore.find` / `ItemStore.count` driven by an
 * [ItemFilter], an [AccessScope], an [ItemPage] and an [ItemSort], against the real SQLite store. Scenarios S9, S10,
 * S13, S14 and S16 of the frozen `test-plan` note (all NEW-SURFACE: the two members and the three value objects are the
 * fix's own declarations).
 *
 * Oracles (each derived independently of the store, by a plain Kotlin predicate or comparator over the seeded rows):
 *  - S9: the pre-existing KDoc of the replaced `findByFilters` -- all non-null filters AND-ed, tags OR-ed within the list;
 *    `tagsAny` is a whole-tag match over the comma-separated tags (the existing `SQLiteWorkItemRepositoryFilterTest`
 *    pins "no false positives on tag substring"); `claimStatus` per the same KDoc (CLAIMED = active claim, UNCLAIMED = no
 *    `claimed_by`, EXPIRED = a `claimed_by` whose claim is not active). `count` equals the size of the unpaged `find`.
 *    Date bounds in the fixture never equal an item's instant, so strict-vs-inclusive is not asserted.
 *  - S10: the KDoc of the replaced members -- `priority` sorts by rank (high first on desc), `complexity` sorts NULLs last
 *    regardless of direction, every sort applies a secondary `id ASC` tiebreak for stable pagination. The id order is the
 *    unsigned byte order of the BLOB key = lexicographic order of the canonical lower-case string.
 *  - S13: amended by item 754ae3dc (scope on root_id, roots only) -- an item is visible when its stamped `root_id` is one of
 *    `rootIds`; a listed id that is not a root admits nothing; an empty set admits nothing; `task-scope` item 5 -- `filter.ancestorId`
 *    and `scope.rootIds` are AND-ed subtree conditions.
 *  - S14: `task-scope` item 5 -- `scope.tagsInclude` follows the rule of `allowsItemTags`: elements trimmed, case-sensitive,
 *    empty elements never match; the page and the count are computed after the tag condition, so totals are exact.
 *  - S16: an offset past the total yields an empty page while `count` is unchanged.
 *
 * Fixture invariants (WorkItem domain rules) are satisfied by construction: a child's depth is its parent's depth + 1 and
 * its parentId is a created row; every claim has `originalClaimedAt == claimedAt < claimExpiresAt`; complexity stays in
 * 1..10; no TERMINAL row carries a claim.
 *
 * BLINDNESS: authored from the frozen notes and the orchestrator-supplied declarations only. No `src/main` file,
 * implementer diff or implementer note was opened.
 */
class ItemStoreFindContractTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private lateinit var repository: SQLiteWorkItemRepository

    @BeforeEach
    fun setUp() {
        repository = SQLiteWorkItemRepository(sqliteDb.databaseManager)
    }

    // ----------------------------------------------------------------------------------- fixture

    private val t0: Instant = Instant.parse("2025-01-01T00:00:00Z")

    private fun day(n: Int): Instant = t0.plus(n.toLong(), ChronoUnit.DAYS)

    private fun mid(n: Int): Instant = day(n).plus(12, ChronoUnit.HOURS)

    private class Fixture(
        val items: List<WorkItem>,
        val a: WorkItem,
        val b: WorkItem,
        val c: WorkItem,
        val childrenOfA: List<WorkItem>,
        val childrenOfB: List<WorkItem>,
    ) {
        fun subtree(root: UUID): Set<UUID> {
            val out = mutableSetOf(root)
            var frontier = listOf(root)
            while (frontier.isNotEmpty()) {
                frontier = items.filter { it.parentId in frontier }.map { it.id }
                out += frontier
            }
            return out
        }
    }

    private fun buildFixture(): Fixture {
        val rnd = Random(20261010)
        val names =
            listOf(
                "mango",
                "apple",
                "zebra",
                "kiwi",
                "banana",
                "cherry",
                "yak",
                "lemon",
                "fig",
                "grape",
                "nectarine",
                "olive",
                "papaya",
                "quince",
                "raspberry",
                "tangerine",
                "ugli",
            )
        val roles = listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.TERMINAL, Role.QUEUE, Role.WORK, Role.QUEUE)
        val priorities =
            listOf(
                Priority.HIGH,
                Priority.LOW,
                Priority.MEDIUM,
                Priority.MEDIUM,
                Priority.HIGH,
                Priority.LOW,
                Priority.LOW,
                Priority.HIGH,
                Priority.MEDIUM,
            )
        val types = listOf("feature-task", "bug-fix", null)
        val tagSets = listOf("alpha", "beta,alpha", "gamma", null, "alpha,gamma", "delta", "bug,beta")
        val now = Instant.now()
        val items = mutableListOf<WorkItem>()

        fun add(parent: WorkItem?): WorkItem {
            val i = items.size
            val role = roles[i % roles.size]
            val claimKind = if (role == Role.TERMINAL) -1 else i % 5
            val claimedAt =
                when (claimKind) {
                    1 -> now.minus(1, ChronoUnit.HOURS)
                    3 -> now.minus(2, ChronoUnit.DAYS)
                    else -> null
                }
            val claimExpiresAt =
                when (claimKind) {
                    1 -> now.plus(1, ChronoUnit.DAYS)
                    3 -> now.minus(1, ChronoUnit.DAYS)
                    else -> null
                }
            val id = UUID(rnd.nextLong(), rnd.nextLong())
            val item =
                WorkItem(
                    id = id,
                    parentId = parent?.id,
                    depth = (parent?.depth ?: -1) + 1,
                    // roots only (item 754ae3dc): a root is stamped with its own id, a child with its parent's root
                    rootId = parent?.let { it.rootId ?: it.id } ?: id,
                    title = names[i],
                    role = role,
                    priority = priorities[i % priorities.size],
                    complexity = if (i % 4 == 0) null else ((i * 3) % 10) + 1,
                    type = types[i % types.size],
                    tags = tagSets[i % tagSets.size],
                    createdAt = day((i % 6) * 10),
                    modifiedAt = day(((i * 5) % 7) * 10).plus(1, ChronoUnit.HOURS),
                    roleChangedAt = day(((i * 3) % 8) * 10).plus(2, ChronoUnit.HOURS),
                    claimedBy = if (claimedAt != null) "agent-$i" else null,
                    claimedAt = claimedAt,
                    claimExpiresAt = claimExpiresAt,
                    originalClaimedAt = claimedAt,
                )
            runBlocking { repository.create(item) }
            items += item
            return item
        }

        val a = add(null)
        val childrenOfA = (1..6).map { add(a) }
        add(childrenOfA[0])
        add(childrenOfA[0])
        add(childrenOfA[1])
        val b = add(null)
        val childrenOfB = (1..4).map { add(b) }
        add(childrenOfB[0])
        val c = add(null)
        check(items.size == names.size) { "fixture must use every name once" }
        return Fixture(items, a, b, c, childrenOfA, childrenOfB)
    }

    private fun idOrder(): Comparator<WorkItem> = compareBy { it.id.toString() }

    private fun rank(priority: Priority): Int =
        when (priority) {
            Priority.HIGH -> 3
            Priority.MEDIUM -> 2
            Priority.LOW -> 1
        }

    private fun comparator(sort: ItemSort): Comparator<WorkItem> {
        val desc = sort.direction == SortDirection.DESC

        fun <T : Comparable<T>> ordered(key: (WorkItem) -> T): Comparator<WorkItem> = if (desc) compareByDescending(key) else compareBy(key)
        val primary: Comparator<WorkItem> =
            when (sort.field) {
                ItemSortField.TITLE -> ordered { it.title }
                ItemSortField.PRIORITY -> ordered { rank(it.priority) }
                ItemSortField.CREATED_AT -> ordered { it.createdAt }
                ItemSortField.MODIFIED_AT -> ordered { it.modifiedAt }
                ItemSortField.COMPLEXITY ->
                    Comparator { x, y ->
                        val cx = x.complexity
                        val cy = y.complexity
                        when {
                            cx == null && cy == null -> 0
                            cx == null -> 1
                            cy == null -> -1
                            desc -> cy.compareTo(cx)
                            else -> cx.compareTo(cy)
                        }
                    }
            }
        return primary.thenComparing(idOrder())
    }

    private fun claimStatusOf(
        item: WorkItem,
        now: Instant,
    ): ClaimStatus {
        val expires = item.claimExpiresAt
        return when {
            item.claimedBy == null -> ClaimStatus.UNCLAIMED
            expires != null && expires.isAfter(now) -> ClaimStatus.CLAIMED
            else -> ClaimStatus.EXPIRED
        }
    }

    /** The independent oracle for an [ItemFilter] (everything but the scope). */
    private fun Fixture.matches(
        filter: ItemFilter,
        item: WorkItem,
        now: Instant,
    ): Boolean =
        (filter.parentId?.let { item.parentId == it } ?: true) &&
            (filter.ancestorId?.let { item.id in subtree(it) } ?: true) &&
            (filter.depth?.let { item.depth == it } ?: true) &&
            (filter.role?.let { item.role == it } ?: true) &&
            (filter.priority?.let { item.priority == it } ?: true) &&
            (filter.tagsAny?.let { wanted -> (item.tags?.split(',') ?: emptyList()).any { it in wanted } } ?: true) &&
            (filter.type?.let { item.type == it } ?: true) &&
            (filter.createdAfter?.let { item.createdAt.isAfter(it) } ?: true) &&
            (filter.createdBefore?.let { item.createdAt.isBefore(it) } ?: true) &&
            (filter.modifiedAfter?.let { item.modifiedAt.isAfter(it) } ?: true) &&
            (filter.modifiedBefore?.let { item.modifiedAt.isBefore(it) } ?: true) &&
            (filter.roleChangedAfter?.let { item.roleChangedAt.isAfter(it) } ?: true) &&
            (filter.roleChangedBefore?.let { item.roleChangedAt.isBefore(it) } ?: true) &&
            (filter.claimStatus?.let { claimStatusOf(item, now) == it } ?: true)

    private fun Fixture.expected(
        filter: ItemFilter,
        visible: Set<UUID>? = null,
        sort: ItemSort = ItemSort.DEFAULT,
    ): List<WorkItem> {
        val now = Instant.now()
        return items
            .filter { visible == null || it.id in visible }
            .filter { matches(filter, it, now) }
            .sortedWith(comparator(sort))
    }

    private val everything = ItemPage(limit = 1000)

    private fun find(
        filter: ItemFilter = ItemFilter.ALL,
        scope: AccessScope = AccessScope.unrestricted(),
        page: ItemPage = everything,
        sort: ItemSort = ItemSort.DEFAULT,
    ): List<WorkItem> = runBlocking { repository.find(filter, scope, page, sort) }

    private fun count(
        filter: ItemFilter = ItemFilter.ALL,
        scope: AccessScope = AccessScope.unrestricted(),
    ): Int = runBlocking { repository.count(filter, scope) }

    private fun ids(rows: List<WorkItem>): List<UUID> = rows.map { it.id }

    // ============================================================================ fixture sanity

    @Test
    fun `the fixture round-trips through the store with the attributes the oracle relies on`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val rows = find().associateBy { it.id }
            assertEquals(17, rows.size)
            for (item in fx.items) {
                val row = rows.getValue(item.id)
                assertEquals(item.parentId, row.parentId, "parent of ${item.title}")
                assertEquals(item.depth, row.depth, "depth of ${item.title}")
                assertEquals(item.role, row.role, "role of ${item.title}")
                assertEquals(item.priority, row.priority, "priority of ${item.title}")
                assertEquals(item.complexity, row.complexity, "complexity of ${item.title}")
                assertEquals(item.type, row.type, "type of ${item.title}")
                assertEquals(item.tags, row.tags, "tags of ${item.title}")
                assertEquals(item.createdAt, row.createdAt, "createdAt of ${item.title}")
                assertEquals(item.modifiedAt, row.modifiedAt, "modifiedAt of ${item.title}")
                assertEquals(item.roleChangedAt, row.roleChangedAt, "roleChangedAt of ${item.title}")
                assertEquals(item.claimedBy, row.claimedBy, "claimedBy of ${item.title}")
            }
        }

    // ============================================================================ S9 -- filters

    private fun singleFilters(fx: Fixture): Map<String, ItemFilter> =
        linkedMapOf(
            "parentId root" to ItemFilter(parentId = fx.a.id),
            "parentId child" to ItemFilter(parentId = fx.childrenOfA[0].id),
            "ancestorId root" to ItemFilter(ancestorId = fx.a.id),
            "ancestorId child" to ItemFilter(ancestorId = fx.childrenOfA[0].id),
            "depth 0" to ItemFilter(depth = 0),
            "depth 1" to ItemFilter(depth = 1),
            "depth 2" to ItemFilter(depth = 2),
            "role WORK" to ItemFilter(role = Role.WORK),
            "role TERMINAL" to ItemFilter(role = Role.TERMINAL),
            "role REVIEW" to ItemFilter(role = Role.REVIEW),
            "priority HIGH" to ItemFilter(priority = Priority.HIGH),
            "priority LOW" to ItemFilter(priority = Priority.LOW),
            "tagsAny alpha" to ItemFilter(tagsAny = listOf("alpha")),
            "tagsAny gamma,delta" to ItemFilter(tagsAny = listOf("gamma", "delta")),
            "tagsAny bug" to ItemFilter(tagsAny = listOf("bug")),
            "type feature-task" to ItemFilter(type = "feature-task"),
            "type bug-fix" to ItemFilter(type = "bug-fix"),
            "createdAfter" to ItemFilter(createdAfter = mid(15)),
            "createdBefore" to ItemFilter(createdBefore = mid(35)),
            "modifiedAfter" to ItemFilter(modifiedAfter = mid(25)),
            "modifiedBefore" to ItemFilter(modifiedBefore = mid(25)),
            "roleChangedAfter" to ItemFilter(roleChangedAfter = mid(35)),
            "roleChangedBefore" to ItemFilter(roleChangedBefore = mid(35)),
            "claim CLAIMED" to ItemFilter(claimStatus = ClaimStatus.CLAIMED),
            "claim UNCLAIMED" to ItemFilter(claimStatus = ClaimStatus.UNCLAIMED),
            "claim EXPIRED" to ItemFilter(claimStatus = ClaimStatus.EXPIRED),
        )

    private fun combinedFilters(fx: Fixture): Map<String, ItemFilter> =
        linkedMapOf(
            "ancestor A + tagsAny" to ItemFilter(ancestorId = fx.a.id, tagsAny = listOf("alpha", "beta", "gamma")),
            "depth 1 + created window" to ItemFilter(depth = 1, createdAfter = mid(5), createdBefore = mid(55)),
            "type + unclaimed + modifiedBefore" to
                ItemFilter(type = "feature-task", claimStatus = ClaimStatus.UNCLAIMED, modifiedBefore = mid(55)),
            "role + priority" to ItemFilter(role = Role.QUEUE, priority = Priority.HIGH),
            "parent + role" to ItemFilter(parentId = fx.a.id, role = Role.QUEUE),
        )

    @Test
    fun `S9 each single filter returns exactly the rows of the independent predicate, with a matching count`(): Unit =
        runBlocking {
            val fx = buildFixture()
            for ((name, filter) in singleFilters(fx)) {
                val expected = fx.expected(filter)
                assertTrue(
                    expected.isNotEmpty() && expected.size < fx.items.size,
                    "fixture sanity: '$name' must discriminate (${expected.size}/${fx.items.size})",
                )
                assertEquals(ids(expected), ids(find(filter)), "find '$name'")
                assertEquals(expected.size, count(filter), "count '$name'")
            }
        }

    @Test
    fun `S9 combined filters are AND-ed`(): Unit =
        runBlocking {
            val fx = buildFixture()
            for ((name, filter) in combinedFilters(fx)) {
                val expected = fx.expected(filter)
                assertTrue(
                    expected.isNotEmpty() && expected.size < fx.items.size,
                    "fixture sanity: '$name' must discriminate (${expected.size}/${fx.items.size})",
                )
                assertEquals(ids(expected), ids(find(filter)), "find '$name'")
                assertEquals(expected.size, count(filter), "count '$name'")
            }
        }

    @Test
    fun `S9 no filter returns every row and count equals the total`(): Unit =
        runBlocking {
            val fx = buildFixture()
            assertEquals(ids(fx.expected(ItemFilter.ALL)), ids(find()))
            assertEquals(17, count())
        }

    @Test
    fun `S9 a filter that matches nothing returns an empty page and zero`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val none = ItemFilter(type = "no-such-type")
            assertEquals(emptyList(), find(none))
            assertEquals(0, count(none))
            val noChild = ItemFilter(parentId = fx.c.id)
            assertEquals(emptyList(), find(noChild))
            assertEquals(0, count(noChild))
            val unknownParent = ItemFilter(parentId = UUID.fromString("00000000-0000-4000-8000-00000000dead"))
            assertEquals(emptyList(), find(unknownParent))
            assertEquals(0, count(unknownParent))
        }

    @Test
    fun `S9 ancestorId includes the ancestor itself and every depth below it`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val sub = fx.subtree(fx.a.id)
            assertEquals(10, sub.size)
            assertEquals(sub, ids(find(ItemFilter(ancestorId = fx.a.id))).toSet())
            assertEquals(10, count(ItemFilter(ancestorId = fx.a.id)))
            // A leaf's subtree is the leaf alone.
            assertEquals(listOf(fx.c.id), ids(find(ItemFilter(ancestorId = fx.c.id))))
        }

    @Test
    fun `S9 tagsAny matches whole tags only - never a substring`(): Unit =
        runBlocking {
            repository.create(WorkItem(id = UUID(1, 1), title = "plain", tags = "bug"))
            repository.create(WorkItem(id = UUID(1, 2), title = "longer", tags = "bugfix"))
            repository.create(WorkItem(id = UUID(1, 3), title = "other", tags = "x,y"))
            repository.create(WorkItem(id = UUID(1, 4), title = "hyphen", tags = "bug-1"))

            fun hit(vararg tags: String) = ids(find(ItemFilter(tagsAny = tags.toList()))).toSet()

            assertEquals(setOf(UUID(1, 1)), hit("bug"))
            assertEquals(emptySet(), hit("bu"))
            assertEquals(emptySet(), hit("ug"))
            assertEquals(setOf(UUID(1, 1), UUID(1, 3)), hit("bug", "y"))
            assertEquals(setOf(UUID(1, 4)), hit("bug-1"))
            assertEquals(2, count(ItemFilter(tagsAny = listOf("bug", "y"))))
        }

    /**
     * PROBE (adversarial: alternate encoding of the same logical input). The WorkItem domain only admits lowercase
     * alphanumeric-and-hyphen tags, so a pattern character or a quote can only appear in the FILTER, where it must be an
     * inert literal: whole-tag matching means no stored tag can equal it. Oracle: the whole-tag rule pinned by the
     * existing `SQLiteWorkItemRepositoryFilterTest` ("no false positives on tag substring") and the same exact-match rule
     * `task-scope` item 5 states for `tagsInclude`.
     */
    @Test
    fun `S9 probe - pattern characters and quotes in a tagsAny value are inert literals`(): Unit =
        runBlocking {
            repository.create(WorkItem(id = UUID(1, 1), title = "plain", tags = "bug"))
            repository.create(WorkItem(id = UUID(1, 2), title = "longer", tags = "bugfix"))
            repository.create(WorkItem(id = UUID(1, 3), title = "other", tags = "x,y"))
            repository.create(WorkItem(id = UUID(1, 4), title = "hyphen", tags = "bug-1"))

            val values = listOf("%", "b%", "%g", "%,%", "_ug", "b_g", "_", "x' OR '1'='1", "bug'--")
            val leaks =
                values
                    .associateWith { ids(find(ItemFilter(tagsAny = listOf(it)))).toSet() }
                    .filterValues { it.isNotEmpty() }
            assertEquals(emptyMap(), leaks, "a tagsAny value that is not a stored tag must match nothing")
            for (value in values) {
                assertEquals(0, count(ItemFilter(tagsAny = listOf(value))), "count for '$value'")
            }
        }
    // ============================================================================ S10 -- sort

    @Test
    fun `S10 every sort field and direction orders by its rule with an id ascending tiebreak`(): Unit =
        runBlocking {
            val fx = buildFixture()
            for (field in ItemSortField.entries) {
                for (direction in SortDirection.entries) {
                    val sort = ItemSort(field, direction)
                    val expected = fx.items.sortedWith(comparator(sort))
                    assertEquals(ids(expected), ids(find(sort = sort)), "sort $field $direction")
                }
            }
        }

    @Test
    fun `S10 the fixture really has ties and nulls, so the tiebreak and null rules are exercised`(): Unit =
        runBlocking {
            val fx = buildFixture()

            fun hasTies(key: (WorkItem) -> Any?) =
                fx.items
                    .groupBy(key)
                    .values
                    .any { it.size > 1 }
            assertTrue(hasTies { it.priority })
            assertTrue(hasTies { it.complexity })
            assertTrue(hasTies { it.createdAt })
            assertTrue(hasTies { it.modifiedAt })
            assertTrue(fx.items.any { it.complexity == null } && fx.items.any { it.complexity != null })
        }

    @Test
    fun `S10 priority desc lists high before medium before low and asc reverses the groups`(): Unit =
        runBlocking {
            buildFixture()
            val desc = find(sort = ItemSort(ItemSortField.PRIORITY, SortDirection.DESC)).map { it.priority }
            assertEquals(listOf(Priority.HIGH, Priority.MEDIUM, Priority.LOW), desc.distinct())
            val asc = find(sort = ItemSort(ItemSortField.PRIORITY, SortDirection.ASC)).map { it.priority }
            assertEquals(listOf(Priority.LOW, Priority.MEDIUM, Priority.HIGH), asc.distinct())
        }

    @Test
    fun `S10 complexity puts rows without a complexity last in both directions`(): Unit =
        runBlocking {
            buildFixture()
            for (direction in SortDirection.entries) {
                val rows = find(sort = ItemSort(ItemSortField.COMPLEXITY, direction))
                val firstNull = rows.indexOfFirst { it.complexity == null }
                assertTrue(firstNull > 0, "$direction: nulls present and not first")
                assertTrue(rows.drop(firstNull).all { it.complexity == null }, "$direction: nothing non-null after the first null")
            }
        }

    @Test
    fun `S10 the default sort is createdAt descending`(): Unit =
        runBlocking {
            val fx = buildFixture()
            assertEquals(ids(fx.items.sortedWith(comparator(ItemSort.DEFAULT))), ids(find()))
            assertEquals(ids(find(sort = ItemSort(ItemSortField.CREATED_AT, SortDirection.DESC))), ids(find()))
        }

    @Test
    fun `S10 sort composes with a filter`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val filter = ItemFilter(ancestorId = fx.a.id)
            val sort = ItemSort(ItemSortField.TITLE, SortDirection.ASC)
            assertEquals(ids(fx.expected(filter, sort = sort)), ids(find(filter, sort = sort)))
        }

    // ============================================================================ S16 -- pages

    @Test
    fun `S16 consecutive pages partition the sorted result with no overlap and no gap`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val sort = ItemSort(ItemSortField.PRIORITY, SortDirection.DESC)
            val whole = ids(fx.items.sortedWith(comparator(sort)))
            val pages = (0 until 17 step 5).map { offset -> ids(find(page = ItemPage(5, offset), sort = sort)) }
            assertEquals(listOf(5, 5, 5, 2), pages.map { it.size })
            assertEquals(whole, pages.flatten())
        }

    @Test
    fun `S16 an offset past the total is an empty page and the count is unchanged`(): Unit =
        runBlocking {
            buildFixture()
            assertEquals(emptyList(), find(page = ItemPage(10, 17)))
            assertEquals(emptyList(), find(page = ItemPage(10, 500)))
            assertEquals(17, count())
            assertEquals(1, find(page = ItemPage(10, 16)).size)
            assertEquals(17, find(page = ItemPage(1000, 0)).size)
        }

    @Test
    fun `S16 a limit of one walks the whole result one row at a time`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val whole = ids(fx.items.sortedWith(comparator(ItemSort.DEFAULT)))
            val walked = (0 until 17).map { offset -> ids(find(page = ItemPage(1, offset))).single() }
            assertEquals(whole, walked)
        }

    @Test
    fun `S16 the page size is not capped at 200 at the store`(): Unit =
        runBlocking {
            repeat(205) { repository.create(WorkItem(id = UUID(2, it.toLong()), title = "n$it", createdAt = t0.plusSeconds(it.toLong()))) }
            assertEquals(205, find(page = ItemPage(500)).size)
            assertEquals(205, count())
        }

    // ============================================================================ S13 -- root scope

    @Test
    fun `S13 a root scope returns the roots and every descendant, and the count agrees`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val scopeA = AccessScope.principal(setOf(fx.a.id), emptySet())
            assertEquals(ids(fx.expected(ItemFilter.ALL, visible = fx.subtree(fx.a.id))), ids(find(scope = scopeA)))
            assertEquals(10, count(scope = scopeA))

            val scopeAB = AccessScope.principal(setOf(fx.a.id, fx.b.id), emptySet())
            assertEquals(16, count(scope = scopeAB))
            assertEquals(
                ids(fx.expected(ItemFilter.ALL, visible = fx.subtree(fx.a.id) + fx.subtree(fx.b.id))),
                ids(find(scope = scopeAB)),
            )
        }

    @Test
    fun `S13 a scope id that is not a root admits nothing, not even its own subtree`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val nested = fx.childrenOfA[0]
            val scope = AccessScope.principal(setOf(nested.id), emptySet())
            assertEquals(3, fx.subtree(nested.id).size, "fixture sanity: the nested item has a subtree to (not) admit")
            assertEquals(emptyList(), find(scope = scope))
            assertEquals(0, count(scope = scope))
            // control: the real root admits the nested item and its subtree
            val rootScope = AccessScope.principal(setOf(fx.a.id), emptySet())
            assertTrue(fx.subtree(nested.id).all { it in ids(find(scope = rootScope)) })
        }

    @Test
    fun `S13 ancestorId inside the scope narrows to that subtree`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val scope = AccessScope.principal(setOf(fx.a.id), emptySet())
            val filter = ItemFilter(ancestorId = fx.childrenOfA[0].id)
            assertEquals(fx.subtree(fx.childrenOfA[0].id), ids(find(filter, scope)).toSet())
            assertEquals(3, count(filter, scope))
        }

    @Test
    fun `S13 ancestorId outside the scope never yields rows outside the scope`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val scope = AccessScope.principal(setOf(fx.a.id), emptySet())
            for (outside in listOf(fx.b.id, fx.childrenOfB[0].id, fx.c.id)) {
                val filter = ItemFilter(ancestorId = outside)
                assertEquals(emptyList(), find(filter, scope), "ancestorId outside the scope")
                assertEquals(0, count(filter, scope), "count of ancestorId outside the scope")
            }
        }

    @Test
    fun `S13 a parentId outside the scope yields nothing and other filters stay AND-ed with the scope`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val scope = AccessScope.principal(setOf(fx.a.id), emptySet())
            assertEquals(emptyList(), find(ItemFilter(parentId = fx.b.id), scope))
            assertEquals(0, count(ItemFilter(parentId = fx.b.id), scope))

            val filter = ItemFilter(role = Role.QUEUE)
            val expected = fx.expected(filter, visible = fx.subtree(fx.a.id))
            assertTrue(expected.isNotEmpty() && expected.size < 10, "fixture sanity")
            assertEquals(ids(expected), ids(find(filter, scope)))
            assertEquals(expected.size, count(filter, scope))
        }

    @Test
    fun `S13 an empty root set returns nothing and counts zero, with or without other filters`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val none = AccessScope.principal(emptySet(), emptySet())
            assertEquals(emptyList(), find(scope = none))
            assertEquals(0, count(scope = none))
            assertEquals(emptyList(), find(ItemFilter(ancestorId = fx.a.id), none))
            assertEquals(0, count(ItemFilter(ancestorId = fx.a.id), none))
            assertEquals(emptyList(), find(ItemFilter(role = Role.QUEUE), none))
        }

    @Test
    fun `S13 a scope root that is not in the store, or no root restriction at all, behave as stated`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val ghost = AccessScope.principal(setOf(UUID.fromString("00000000-0000-4000-8000-00000000beef")), emptySet())
            assertEquals(emptyList(), find(scope = ghost))
            assertEquals(0, count(scope = ghost))

            // null roots and an empty tag set restrict nothing.
            val open = AccessScope.principal(null, emptySet())
            assertEquals(17, count(scope = open))
            assertEquals(ids(fx.expected(ItemFilter.ALL)), ids(find(scope = open)))
            assertEquals(17, count(scope = AccessScope.unrestricted()))
        }

    @Test
    fun `S13 paging applies after the scope`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val scope = AccessScope.principal(setOf(fx.a.id), emptySet())
            val whole = ids(fx.expected(ItemFilter.ALL, visible = fx.subtree(fx.a.id)))
            assertEquals(whole.drop(4).take(4), ids(find(scope = scope, page = ItemPage(4, 4))))
            assertEquals(whole.drop(8), ids(find(scope = scope, page = ItemPage(4, 8))))
            assertEquals(emptyList(), find(scope = scope, page = ItemPage(4, 10)))
            assertEquals(10, count(scope = scope))
        }

    @Test
    fun `S13 stamped trees scope the same way as unstamped ones`(): Unit =
        runBlocking {
            suspend fun stampedTree(prefix: Long): Pair<WorkItem, List<WorkItem>> {
                val root0 = repository.create(WorkItem(id = UUID(prefix, 0), title = "root-$prefix"))
                val root = repository.update(root0.copy(rootId = root0.id))!!
                val kids =
                    (1..3).map { n ->
                        repository.create(
                            WorkItem(
                                id = UUID(prefix, n.toLong()),
                                title = "kid-$prefix-$n",
                                parentId = root.id,
                                depth = 1,
                                rootId = root.id
                            ),
                        )
                    }
                val grand =
                    repository.create(
                        WorkItem(id = UUID(prefix, 9), title = "grand-$prefix", parentId = kids[0].id, depth = 2, rootId = root.id),
                    )
                return root to (kids + grand)
            }
            val (rootX, restX) = stampedTree(10)
            val (rootY, restY) = stampedTree(20)
            val inX = (listOf(rootX) + restX).map { it.id }.toSet()
            val inY = (listOf(rootY) + restY).map { it.id }.toSet()

            val scopeX = AccessScope.principal(setOf(rootX.id), emptySet())
            assertEquals(inX, ids(find(scope = scopeX)).toSet())
            assertEquals(5, count(scope = scopeX))
            // ancestorId in the other stamped tree, and a nested ancestorId in the same one.
            assertEquals(emptyList(), find(ItemFilter(ancestorId = rootY.id), scopeX))
            assertEquals(emptyList(), find(ItemFilter(ancestorId = restY[1].id), scopeX))
            assertEquals(0, count(ItemFilter(ancestorId = rootY.id), scopeX))
            assertEquals(setOf(restX[0].id, restX[3].id), ids(find(ItemFilter(ancestorId = restX[0].id), scopeX)).toSet())
            assertEquals(inX + inY, ids(find(scope = AccessScope.principal(setOf(rootX.id, rootY.id), emptySet()))).toSet())
        }

    @Test
    fun `B1 anchor drift - an ancestorId equal to a drifted scope anchor never widens a root scope`(): Unit =
        runBlocking {
            val rootR0 = repository.create(WorkItem(id = UUID(40, 0), title = "drift-R"))
            val r = repository.update(rootR0.copy(rootId = rootR0.id))!!
            val rKid = repository.create(WorkItem(id = UUID(40, 1), title = "drift-R-kid", parentId = r.id, depth = 1, rootId = r.id))
            val rGrand =
                repository.create(WorkItem(id = UUID(40, 2), title = "drift-R-grand", parentId = rKid.id, depth = 2, rootId = r.id))
            val q0 = repository.create(WorkItem(id = UUID(41, 0), title = "drift-Q"))
            val q = repository.update(q0.copy(rootId = q0.id))!!
            val qKid = repository.create(WorkItem(id = UUID(41, 1), title = "drift-Q-kid", parentId = q.id, depth = 1, rootId = q.id))

            val scopeR = AccessScope.principal(setOf(r.id), emptySet())
            val filterR = ItemFilter(ancestorId = r.id)
            val rTree = setOf(r.id, rKid.id, rGrand.id)

            // control (before the drift): ancestorId = the listed anchor returns the anchor's subtree.
            assertEquals(rTree, ids(find(filterR, scopeR)).toSet(), "control: pre-drift")
            assertEquals(3, count(filterR, scopeR), "control: pre-drift count")

            // drift: admin reparents R under Q; production restamps the subtree with Q's root_id (depth delta +1).
            assertNotNull(repository.update(r.update { item -> item.copy(parentId = q.id, depth = 1, rootId = q.id) }))
            assertEquals(2, repository.restampSubtree(r.id, 1, q.id), "the restamp must reach R's kid and grandchild")
            assertEquals(
                setOf(q.id, qKid.id, r.id, rKid.id, rGrand.id),
                ids(find(scope = AccessScope.principal(setOf(q.id), emptySet()))).toSet(),
                "fixture sanity: the drifted rows are now Q-stamped"
            )

            // The principal condition (stamped root_id in {R}) must still apply: no row is stamped R any more, so nothing is
            // visible, even though ancestorId = R names the anchor and R's old subtree is intact by parent_id.
            assertEquals(emptyList(), find(filterR, scopeR), "ancestorId = drifted anchor")
            assertEquals(0, count(filterR, scopeR), "count, ancestorId = drifted anchor")
            assertEquals(emptyList(), find(scope = scopeR), "no ancestorId")
            assertEquals(0, count(scope = scopeR))
            // and nothing of Q leaks through an ancestorId naming Q or one of its rows.
            for (anchor in listOf(q.id, qKid.id, rKid.id)) {
                assertEquals(emptyList(), find(ItemFilter(ancestorId = anchor), scopeR), "ancestorId $anchor")
                assertEquals(0, count(ItemFilter(ancestorId = anchor), scopeR), "count, ancestorId $anchor")
            }
        }

    // ============================================================================ S14 -- tag scope

    private fun admits(
        allow: Set<String>,
        tags: String?,
    ): Boolean = tags != null && tags.split(',').map { it.trim() }.any { it.isNotEmpty() && it in allow }

    // Only tags the WorkItem domain accepts (lowercase alphanumeric and hyphen per element; blank and padded elements pass).
    private val tagCases: List<String?> =
        listOf(
            "bug",
            "x, bug",
            "a,bug,b",
            "bug ,c",
            " bug",
            "x,,bug",
            "bug,bug",
            "bug,",
            ",bug",
            "bugfix",
            "debug",
            "bug-1",
            "",
            ",,",
            " , ",
            null,
            "feature",
            "x,feature",
        )

    private fun seedTagCases(): List<WorkItem> {
        val out = mutableListOf<WorkItem>()
        tagCases.forEachIndexed { i, tags ->
            val item = WorkItem(id = UUID(3, i.toLong()), title = "tag-case-$i", tags = tags, createdAt = t0.plusSeconds(i.toLong()))
            runBlocking { repository.create(item) }
            out += item
        }
        return out
    }

    @Test
    fun `S14 tagsInclude admits an item with an exactly matching trimmed tag and rejects near misses`(): Unit =
        runBlocking {
            val items = seedTagCases()
            val allow = setOf("bug")
            val scope = AccessScope.principal(null, allow)
            val expected = items.filter { admits(allow, it.tags) }.sortedWith(comparator(ItemSort.DEFAULT))
            assertEquals(9, expected.size, "fixture sanity: the nine rows that carry a 'bug' element")

            val got = find(scope = scope)
            assertEquals(ids(expected), ids(got))
            assertEquals(got.size, got.map { it.id }.distinct().size, "a row with the tag twice must appear once")
            assertEquals(expected.size, count(scope = scope))

            fun idOfCase(tags: String?): UUID = items[tagCases.indexOf(tags)].id
            val admitted = ids(got).toSet()
            assertTrue(idOfCase("x, bug") in admitted, "elements are trimmed: 'x, bug' is admitted")
            assertTrue(idOfCase(" bug") in admitted, "leading whitespace is trimmed")
            assertTrue(idOfCase("bug ,c") in admitted, "trailing whitespace is trimmed")
            assertTrue(idOfCase("x,,bug") in admitted, "an empty element beside the tag does not hide it")
            assertTrue(idOfCase("bug,bug") in admitted, "a duplicated tag still admits the row")
            for (reject in listOf("bugfix", "debug", "bug-1", "", ",,", " , ", null, "feature", "x,feature")) {
                assertTrue(idOfCase(reject) !in admitted, "'$reject' must be rejected")
            }
        }

    @Test
    fun `S14 the allowlist comparison is case-sensitive`(): Unit =
        runBlocking {
            seedTagCases()
            for (allow in listOf(setOf("Bug"), setOf("BUG"), setOf("bUg"), setOf("Feature"))) {
                assertEquals(emptyList(), find(scope = AccessScope.principal(null, allow)), "allow $allow")
                assertEquals(0, count(scope = AccessScope.principal(null, allow)), "count allow $allow")
            }
        }

    @Test
    fun `S14 a multi-tag allowlist admits an item carrying any of them`(): Unit =
        runBlocking {
            val items = seedTagCases()
            val allow = setOf("bug", "feature")
            val expected = items.filter { admits(allow, it.tags) }.sortedWith(comparator(ItemSort.DEFAULT))
            assertTrue(expected.size > items.count { admits(setOf("bug"), it.tags) }, "fixture sanity: the second tag adds rows")
            assertEquals(ids(expected), ids(find(scope = AccessScope.principal(null, allow))))
            assertEquals(expected.size, count(scope = AccessScope.principal(null, allow)))
        }

    @Test
    fun `S14 an allowlist element that is empty or a pattern character admits nothing`(): Unit =
        runBlocking {
            seedTagCases()

            // Items cannot carry these characters (domain rule), so each allowlist below must admit nothing at all.
            for (allow in listOf(setOf(""), setOf("%"), setOf("b%"), setOf("b_g"), setOf("bu"), setOf("x' OR '1'='1"), setOf("bug,x"))) {
                assertEquals(emptyList(), find(scope = AccessScope.principal(null, allow)), "allow $allow")
                assertEquals(0, count(scope = AccessScope.principal(null, allow)), "count allow $allow")
            }
        }

    @Test
    fun `S14 an empty tag set means no tag restriction while a non-empty one with no match means nothing`(): Unit =
        runBlocking {
            val items = seedTagCases()
            assertEquals(items.size, count(scope = AccessScope.principal(null, emptySet())))
            assertEquals(0, count(scope = AccessScope.principal(null, setOf("never-used"))))
        }

    @Test
    fun `S14 an empty-string allowlist element never matches an empty element of an item`(): Unit =
        runBlocking {
            val items = seedTagCases()
            val withEmptyElement = items.filter { (it.tags ?: "").split(',').any { part -> part.trim().isEmpty() } }
            assertTrue(withEmptyElement.size >= 5, "fixture sanity: rows with empty elements exist (${withEmptyElement.size})")
            // The allowlist {"", "feature"} must admit exactly the 'feature' rows, never a row for its empty element.
            val scope = AccessScope.principal(null, setOf("", "feature"))
            val expected = items.filter { admits(setOf("feature"), it.tags) }.sortedWith(comparator(ItemSort.DEFAULT))
            assertEquals(ids(expected), ids(find(scope = scope)))
            assertEquals(expected.size, count(scope = scope))
        }

    @Test
    fun `S14 the tag scope is AND-ed with the root scope and with the tagsAny filter`(): Unit =
        runBlocking {
            val root = repository.create(WorkItem(id = UUID(5, 0), title = "root", tags = "bug", rootId = UUID(5, 0)))
            val inside =
                repository.create(
                    WorkItem(id = UUID(5, 1), title = "inside", tags = "bug,alpha", parentId = root.id, depth = 1, rootId = root.id)
                )
            repository.create(
                WorkItem(id = UUID(5, 2), title = "inside-other-tag", tags = "other", parentId = root.id, depth = 1, rootId = root.id)
            )
            repository.create(WorkItem(id = UUID(5, 3), title = "elsewhere", tags = "bug,alpha", rootId = UUID(5, 3)))

            val both = AccessScope.principal(setOf(root.id), setOf("bug"))
            assertEquals(setOf(root.id, inside.id), ids(find(scope = both)).toSet())
            assertEquals(2, count(scope = both))

            val withFilter = ItemFilter(tagsAny = listOf("alpha"))
            assertEquals(listOf(inside.id), ids(find(withFilter, both)))
            assertEquals(1, count(withFilter, both))
        }

    @Test
    fun `S14 with more than a thousand visible rows the count is exact and the second page is not empty`(): Unit =
        runBlocking {
            val visible = 1005
            val noise = listOf("bugs", "bugfix", "", null, "bug-2", "x,y")
            repeat(visible) {
                repository.create(
                    WorkItem(
                        id = UUID(6, it.toLong()),
                        title = "v$it",
                        tags = if (it % 2 == 0) "bug" else "a, bug",
                        createdAt = t0.plusSeconds(it.toLong()),
                    ),
                )
            }
            // The newest rows are the non-visible ones, so a window taken before the tag filter loses visible rows.
            noise.forEachIndexed { i, tags ->
                repository.create(
                    WorkItem(
                        id = UUID(7, i.toLong()),
                        title = "n$i",
                        tags = tags,
                        createdAt = t0.plusSeconds((visible + 100 + i).toLong())
                    ),
                )
            }
            val scope = AccessScope.principal(null, setOf("bug"))

            assertEquals(visible, count(scope = scope))
            val first = find(scope = scope, page = ItemPage(1000, 0))
            val second = find(scope = scope, page = ItemPage(1000, 1000))
            assertEquals(1000, first.size)
            assertEquals(5, second.size, "page 2 must hold the remaining 5 visible rows")
            val all = ids(first) + ids(second)
            assertEquals(visible, all.toSet().size, "no overlap, no gap")
            assertTrue(all.all { it.mostSignificantBits == 6L }, "only visible rows")
            // Newest-first: the first visible row is the one created last among the visible ones.
            assertEquals(UUID(6, (visible - 1).toLong()), first.first().id)
            assertEquals(UUID(6, 0), second.last().id)
        }

    // ============================================================================ S16 -- replace parity

    @Test
    fun `S16 find and count agree for every filter under an unrestricted scope and an all-roots scope`(): Unit =
        runBlocking {
            val fx = buildFixture()
            val allRoots = AccessScope.principal(setOf(fx.a.id, fx.b.id, fx.c.id), emptySet())
            for ((name, filter) in singleFilters(fx) + combinedFilters(fx)) {
                assertEquals(ids(find(filter)), ids(find(filter, allRoots)), "scope parity '$name'")
                assertEquals(count(filter), count(filter, allRoots), "count scope parity '$name'")
                assertEquals(find(filter).size, count(filter), "count == unpaged find '$name'")
            }
        }
}

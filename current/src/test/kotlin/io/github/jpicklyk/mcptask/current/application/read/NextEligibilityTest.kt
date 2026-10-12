package io.github.jpicklyk.mcptask.current.application.read

import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.Random
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q14 test-plan S1-S7 (item 671857b4): which items [NextItemRecommender] returns, in what order, and with what counts.
 * Every scenario here is EXISTING-SURFACE: it binds only to API that predates Q14 (`recommend`, `recommendCounted`,
 * `explainEmpty`, `Criteria`, `ExclusionCounts`), so the file also compiles and runs against the pre-Q14 commit.
 *
 * Substrate: a real SQLite database whose stores are bound to a [SettableClock] parked at [t].
 *
 * Oracle sources (frozen test-plan, not the code under test):
 *  - Reference model R (below): `Criteria` filters as in the `ClaimStore.findClaimable` contract; an item claim is active
 *    iff `claimExpiresAt > now` (an expiry equal to now is expired); an ancestor claim shields a descendant when it is
 *    active and held by a disqualifying agent (any agent when `requestingAgentId` is null, otherwise only other
 *    agents); a BLOCKS edge is satisfied iff the blocker's role is at or beyond `unblockAt` (default terminal) on
 *    QUEUE < WORK < REVIEW < TERMINAL, and BLOCKED never satisfies; RELATES_TO never blocks; IS_BLOCKED_BY is the
 *    write alias of the reversed BLOCKS edge. Order: priority rank then complexity ascending with null complexity
 *    last, or createdAt ascending, or createdAt descending; ties by id ascending.
 *  - AR-63 (architecture-review-2026-09-23): eligibility must not be decided inside a 200-row over-fetch window.
 *  - Plan 3.3: `candidateCount` is the exact eligible count.
 *  - `ExclusionCounts` contract: aggregate counts only; `total` is the sum of the three parts and equals the matched
 *    count when nothing is eligible.
 */
class NextEligibilityTest {
    private val clock = SettableClock()
    private val t: Instant = clock.now()

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val provider get() = db.repositoryProvider()
    private val items get() = provider.workItemRepository()
    private val deps get() = provider.dependencyRepository()
    private val recommender get() = NextItemRecommender(items)

    // -----------------------------------------------------------------------------------------------------------
    // Fixture + reference model R
    // -----------------------------------------------------------------------------------------------------------

    private data class ModelEdge(
        val blocker: UUID,
        val blocked: UUID,
        val threshold: Role
    )

    /** Records every item and edge it creates so the reference model can be evaluated over exactly the stored data. */
    private inner class Fixture(
        private val target: SqliteTestDatabase = db.db
    ) {
        val created = linkedMapOf<UUID, WorkItem>()
        val edges = mutableListOf<ModelEdge>()
        private val repo get() = target.repositoryProvider().workItemRepository()
        private val depStore get() = target.repositoryProvider().dependencyRepository()

        suspend fun item(
            title: String,
            role: Role = Role.QUEUE,
            priority: Priority = Priority.MEDIUM,
            complexity: Int? = null,
            parent: WorkItem? = null,
            tags: String? = null,
            type: String? = null,
            createdAt: Instant = t,
            claimedBy: String? = null,
            claimExpiresAt: Instant? = null
        ): WorkItem {
            val claimedAt = claimExpiresAt?.minusSeconds(60)
            val saved =
                repo.create(
                    WorkItem(
                        title = title,
                        role = role,
                        priority = priority,
                        complexity = complexity,
                        parentId = parent?.id,
                        depth = (parent?.depth ?: -1) + 1,
                        tags = tags,
                        type = type,
                        createdAt = createdAt,
                        claimedBy = claimedBy,
                        claimedAt = claimedAt,
                        claimExpiresAt = claimExpiresAt,
                        originalClaimedAt = claimedAt
                    )
                )
            created[saved.id] = saved
            return saved
        }

        /** `from` BLOCKS `to` (or the IS_BLOCKED_BY write alias / RELATES_TO); `unblockAt` is the lower-case threshold or null. */
        suspend fun link(
            from: WorkItem,
            to: WorkItem,
            type: DependencyType = DependencyType.BLOCKS,
            unblockAt: String? = null
        ) {
            depStore.create(Dependency(fromItemId = from.id, toItemId = to.id, type = type, unblockAt = unblockAt))
            val threshold = if (unblockAt == null) Role.TERMINAL else Role.valueOf(unblockAt.uppercase())
            when (type) {
                DependencyType.BLOCKS -> edges += ModelEdge(blocker = from.id, blocked = to.id, threshold = threshold)
                DependencyType.IS_BLOCKED_BY -> edges += ModelEdge(blocker = to.id, blocked = from.id, threshold = threshold)
                DependencyType.RELATES_TO -> Unit
            }
        }

        /** The stored form of every created item (so R is evaluated over stored values, not constructor input). */
        suspend fun refresh() {
            for (id in created.keys.toList()) created[id] = checkNotNull(repo.getById(id)) { "item $id vanished" }
        }

        private fun satisfied(edge: ModelEdge): Boolean {
            val role = created.getValue(edge.blocker).role
            if (role == Role.BLOCKED) return false
            val order = listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.TERMINAL)
            return order.indexOf(role) >= order.indexOf(edge.threshold)
        }

        private fun activeClaim(item: WorkItem): Boolean {
            val expires = item.claimExpiresAt
            return item.claimedBy != null && expires != null && expires.isAfter(t)
        }

        private fun ancestorsOf(item: WorkItem): List<WorkItem> {
            val out = mutableListOf<WorkItem>()
            var cur = item.parentId?.let { created[it] }
            while (cur != null) {
                out += cur
                cur = cur.parentId?.let { created[it] }
            }
            return out
        }

        private fun tagTokens(item: WorkItem): Set<String> = item.tagList().map { it.trim().lowercase() }.toSet()

        private fun rank(p: Priority): Int =
            when (p) {
                Priority.HIGH -> 0
                Priority.MEDIUM -> 1
                Priority.LOW -> 2
            }

        /** R: the eligible items for [c], in order. */
        fun reference(c: NextItemRecommender.Criteria): List<WorkItem> {
            val parentId = c.parentId
            val priority = c.priority
            val type = c.type
            val complexityMax = c.complexityMax
            val createdAfter = c.createdAfter
            val createdBefore = c.createdBefore
            val wantedTags = c.tags
            val scope = c.ancestorIds
            val agent = c.requestingAgentId
            val matched =
                created.values.filter { item ->
                    val complexity = item.complexity
                    item.role == c.role &&
                        (parentId == null || item.parentId == parentId) &&
                        (priority == null || item.priority == priority) &&
                        (type == null || item.type == type) &&
                        (complexityMax == null || (complexity != null && complexity <= complexityMax)) &&
                        (createdAfter == null || !item.createdAt.isBefore(createdAfter)) &&
                        (createdBefore == null || !item.createdAt.isAfter(createdBefore)) &&
                        (wantedTags == null || wantedTags.any { it.trim().lowercase() in tagTokens(item) }) &&
                        (scope == null || (listOf(item) + ancestorsOf(item)).any { it.id in scope })
                }
            val eligible =
                matched.filter { item ->
                    val notClaimed = !activeClaim(item)
                    val notShielded = ancestorsOf(item).none { a -> activeClaim(a) && (agent == null || a.claimedBy != agent) }
                    val notBlocked = edges.filter { it.blocked == item.id }.all { satisfied(it) }
                    notClaimed && notShielded && notBlocked
                }
            val comparator: Comparator<WorkItem> =
                when (c.orderBy) {
                    NextItemOrder.PRIORITY_THEN_COMPLEXITY ->
                        compareBy<WorkItem> { rank(it.priority) }.thenBy { it.complexity ?: Int.MAX_VALUE }
                    NextItemOrder.OLDEST_FIRST -> compareBy { it.createdAt }
                    NextItemOrder.NEWEST_FIRST -> compareByDescending { it.createdAt }
                }
            return eligible.sortedWith(comparator)
        }
    }

    private fun ids(list: List<WorkItem>) = list.map { it.id }

    private fun live(seconds: Long = 300): Instant = t.plusSeconds(seconds)

    // -----------------------------------------------------------------------------------------------------------
    // S1 (EXISTING-SURFACE, happy): equivalence to reference model R on seeded-random fixtures
    // -----------------------------------------------------------------------------------------------------------

    private val criteriaVariants: List<NextItemRecommender.Criteria> =
        listOf(
            NextItemRecommender.Criteria(),
            NextItemRecommender.Criteria(priority = Priority.HIGH),
            NextItemRecommender.Criteria(tags = listOf("a")),
            NextItemRecommender.Criteria(tags = listOf("a", "b")),
            NextItemRecommender.Criteria(complexityMax = 5),
            NextItemRecommender.Criteria(type = "t1"),
            NextItemRecommender.Criteria(role = Role.WORK),
            NextItemRecommender.Criteria(role = Role.REVIEW),
            NextItemRecommender.Criteria(createdAfter = t.minusSeconds(900), createdBefore = t.minusSeconds(500))
        )

    /**
     * 66 items: the 33 QUEUE items get the 33 distinct (priority, complexity) pairs, and the 33 non-queue items reuse
     * the same pairs one-to-one, so within any one role there are no sort-key ties. createdAt is distinct per item.
     * Dependency edges only point from an earlier item to a later one, so the graph is acyclic.
     */
    private suspend fun seedRandom(
        f: Fixture,
        seed: Long
    ) {
        val rnd = Random(seed)
        val combos =
            listOf(Priority.HIGH, Priority.MEDIUM, Priority.LOW)
                .flatMap { p -> (listOf<Int?>(null) + (1..10)).map { c -> p to c } }
                .shuffled(rnd)
        val nonQueueRoles = listOf(Role.WORK, Role.REVIEW, Role.TERMINAL, Role.BLOCKED)
        val all = mutableListOf<WorkItem>()
        val pairs = mutableSetOf<Pair<UUID, UUID>>()
        for (i in 0 until 66) {
            val role = if (i % 2 == 0) Role.QUEUE else nonQueueRoles[rnd.nextInt(nonQueueRoles.size)]
            val (priority, complexity) = combos[i / 2]
            val tagList = listOf("a", "b", "c").filter { rnd.nextInt(3) == 0 }
            val parent = if (all.isNotEmpty() && rnd.nextInt(3) == 0) all[rnd.nextInt(all.size)].takeIf { it.depth < 6 } else null
            val claimKind = rnd.nextInt(8)
            val agent = if (rnd.nextBoolean()) "X" else "Z"
            val expiry =
                when (claimKind) {
                    0 -> live()
                    1 -> t.minusSeconds(1)
                    2 -> t
                    else -> null
                }
            val saved =
                f.item(
                    title = "r$i",
                    role = role,
                    priority = priority,
                    complexity = complexity,
                    parent = parent,
                    tags = tagList.takeIf { it.isNotEmpty() }?.joinToString(","),
                    type = if (rnd.nextBoolean()) "t1" else "t2",
                    createdAt = t.minusSeconds(1000).plusSeconds(i * 7L),
                    claimedBy = if (expiry != null) agent else null,
                    claimExpiresAt = expiry
                )
            all += saved
            if (i > 2) {
                repeat(rnd.nextInt(3)) {
                    val blocker = all[rnd.nextInt(i)]
                    if (pairs.add(blocker.id to saved.id)) {
                        val kind = rnd.nextInt(10)
                        val unblockAt = listOf(null, "queue", "work", "review", "terminal")[rnd.nextInt(5)]
                        when {
                            kind < 6 -> f.link(blocker, saved, DependencyType.BLOCKS, unblockAt)
                            kind < 9 -> f.link(saved, blocker, DependencyType.IS_BLOCKED_BY, unblockAt)
                            else -> f.link(blocker, saved, DependencyType.RELATES_TO, null)
                        }
                    }
                }
            }
        }
    }

    private suspend fun assertEquivalent(
        f: Fixture,
        rec: NextItemRecommender,
        label: String
    ): Int {
        var nonEmpty = 0
        for (base in criteriaVariants) {
            for (order in NextItemOrder.entries) {
                for (agent in listOf(null, "X", "Y")) {
                    val c = base.copy(orderBy = order, requestingAgentId = agent)
                    val expected = ids(f.reference(c))
                    if (expected.isNotEmpty()) nonEmpty++
                    assertEquals(expected.take(20), ids(rec.recommend(c, 20)), "$label limit 20 for $c")
                    assertEquals(expected.take(1), ids(rec.recommend(c, 1)), "$label limit 1 for $c")
                }
            }
        }
        return nonEmpty
    }

    @Test
    fun `S1 recommend equals the reference model for a seeded random fixture across criteria, order and agent`(): Unit =
        runBlocking {
            val f = Fixture()
            db.unitOfWork().inUnit { seedRandom(f, seed = 20261010L) }
            f.refresh()

            val nonEmpty = assertEquivalent(f, recommender, "seed 20261010")

            assertTrue(nonEmpty >= 40, "the fixture must exercise real results, not only empty ones (non-empty cases: $nonEmpty)")
        }

    @Test
    fun `S1 recommend equals the reference model for several more random seeds`(): Unit =
        runBlocking {
            for (seed in listOf(1L, 7L, 42L, 99L)) {
                SqliteTestDatabase.open(clock = clock).use { other ->
                    val f = Fixture(other)
                    other.unitOfWork().inUnit { seedRandom(f, seed) }
                    f.refresh()
                    val rec =
                        NextItemRecommender(other.repositoryProvider().workItemRepository())
                    assertEquivalent(f, rec, "seed $seed")
                }
            }
        }

    @Test
    fun `S1 probe tag filter is whole-token, case-insensitive and trimmed`(): Unit =
        runBlocking {
            val f = Fixture()
            val withTag = f.item("tagged", tags = "backend,api")
            f.item("other", tags = "backend-v2")
            f.item("untagged")

            val upper = recommender.recommend(NextItemRecommender.Criteria(tags = listOf("  BACKEND ")), 10)
            val partial = recommender.recommend(NextItemRecommender.Criteria(tags = listOf("back")), 10)
            val either = recommender.recommend(NextItemRecommender.Criteria(tags = listOf("zzz", "api")), 10)

            assertEquals(listOf(withTag.id), ids(upper), "upper-case, padded tag matches the whole token 'backend' only")
            assertTrue(partial.isEmpty(), "a prefix of a token is not a match: ${partial.map { it.title }}")
            assertEquals(listOf(withTag.id), ids(either), "any-of semantics: one listed tag is enough")
        }

    @Test
    fun `S1 probe createdAfter and createdBefore bounds are inclusive`(): Unit =
        runBlocking {
            val f = Fixture()
            val early = f.item("early", createdAt = t.minusSeconds(100), complexity = 1)
            val mid = f.item("mid", createdAt = t.minusSeconds(50), complexity = 2)
            val late = f.item("late", createdAt = t, complexity = 3)

            val both =
                recommender.recommend(
                    NextItemRecommender.Criteria(createdAfter = early.createdAt, createdBefore = mid.createdAt),
                    10
                )
            val lateOnly = recommender.recommend(NextItemRecommender.Criteria(createdAfter = late.createdAt), 10)

            assertEquals(listOf(early.id, mid.id), ids(both))
            assertEquals(listOf(late.id), ids(lateOnly))
        }

    @Test
    fun `S1 probe empty ancestorIds selects nothing, null selects everything, and parentId composes by AND`(): Unit =
        runBlocking {
            val f = Fixture()
            val scopeRoot = f.item("scope root", role = Role.WORK)
            val inside = f.item("inside", parent = scopeRoot)
            val outsideRoot = f.item("outside root", role = Role.WORK)
            val outside = f.item("outside", parent = outsideRoot)

            val unscoped = recommender.recommend(NextItemRecommender.Criteria(), 10).map { it.id }.toSet()
            val none = recommender.recommend(NextItemRecommender.Criteria(ancestorIds = emptySet()), 10)
            val scoped = recommender.recommend(NextItemRecommender.Criteria(ancestorIds = setOf(scopeRoot.id)), 10)
            val composedInside =
                recommender.recommend(NextItemRecommender.Criteria(ancestorIds = setOf(scopeRoot.id), parentId = scopeRoot.id), 10)
            val composedOutside =
                recommender.recommend(NextItemRecommender.Criteria(ancestorIds = setOf(scopeRoot.id), parentId = outsideRoot.id), 10)

            assertEquals(setOf(inside.id, outside.id), unscoped)
            assertTrue(none.isEmpty(), "an empty (non-null) subtree scope matches nothing: ${none.map { it.title }}")
            assertEquals(listOf(inside.id), ids(scoped))
            assertEquals(listOf(inside.id), ids(composedInside))
            assertTrue(composedOutside.isEmpty(), "parentId outside the scoped subtree is excluded: ${composedOutside.map { it.title }}")
        }

    @Test
    fun `S1 probe items with identical sort keys come back by id ascending and repeatably, for every order`(): Unit =
        runBlocking {
            val tied = mutableSetOf<UUID>()
            db.unitOfWork().inUnit {
                val f = Fixture()
                repeat(6) { i -> tied += f.item("tied $i", priority = Priority.MEDIUM, complexity = 4, createdAt = t.minusSeconds(10)).id }
            }
            val dbIdOrder =
                transaction(db = db.database) {
                    WorkItemsTable
                        .select(WorkItemsTable.id)
                        .orderBy(WorkItemsTable.id, SortOrder.ASC)
                        .map { it[WorkItemsTable.id].value }
                        .filter { it in tied }
                }

            assertEquals(6, dbIdOrder.size)
            for (order in NextItemOrder.entries) {
                val first = ids(recommender.recommend(NextItemRecommender.Criteria(orderBy = order), 6))
                val second = ids(recommender.recommend(NextItemRecommender.Criteria(orderBy = order), 6))
                assertEquals(dbIdOrder, first, "ties by id ascending for $order")
                assertEquals(first, second, "repeatable for $order")
            }
        }

    // -----------------------------------------------------------------------------------------------------------
    // S2 (EXISTING-SURFACE, failure, AR-63): dependency-blocked items that outrank the eligible one past 200
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S2 an eligible item ranked after 201 dependency-blocked ones is returned`(): Unit =
        runBlocking {
            lateinit var low: WorkItem
            db.unitOfWork().inUnit {
                val f = Fixture()
                low = f.item("low eligible", priority = Priority.LOW, complexity = 1)
                repeat(201) { i ->
                    val blocker = f.item("blocker $i", priority = Priority.LOW, complexity = 9)
                    val blocked = f.item("high blocked $i", priority = Priority.HIGH, complexity = 1)
                    f.link(blocker, blocked)
                }
            }

            val result = recommender.recommend(NextItemRecommender.Criteria(), 1)

            assertEquals(listOf(low.id), ids(result), "the 201 higher-ranked items are all blocked by an unfinished QUEUE blocker")
        }

    @Test
    fun `S2 probe 199 and 200 blocked items ahead of the eligible one`(): Unit =
        runBlocking {
            for (blockedAhead in listOf(199, 200)) {
                SqliteTestDatabase.open(clock = clock).use { other ->
                    val f = Fixture(other)
                    lateinit var low: WorkItem
                    other.unitOfWork().inUnit {
                        low = f.item("low", priority = Priority.LOW, complexity = 1)
                        val blocker = f.item("shared blocker", role = Role.WORK)
                        repeat(blockedAhead) { i -> f.link(blocker, f.item("blocked $i", priority = Priority.HIGH, complexity = 1)) }
                    }
                    val rec =
                        NextItemRecommender(other.repositoryProvider().workItemRepository())

                    assertEquals(listOf(low.id), ids(rec.recommend(NextItemRecommender.Criteria(), 1)), "$blockedAhead blocked items ahead")
                }
            }
        }

    // -----------------------------------------------------------------------------------------------------------
    // S3 (EXISTING-SURFACE, failure, AR-63): ancestor-claim-shielded items that outrank the eligible one past 200
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S3 an eligible item ranked after 201 children of a claimed parent is returned for another agent and for null`(): Unit =
        runBlocking {
            lateinit var low: WorkItem
            lateinit var parent: WorkItem
            db.unitOfWork().inUnit {
                val f = Fixture()
                parent = f.item("claimed parent", role = Role.WORK, claimedBy = "X", claimExpiresAt = live())
                low = f.item("low elsewhere", priority = Priority.LOW, complexity = 1)
                repeat(201) { i -> f.item("high child $i", priority = Priority.HIGH, complexity = 1, parent = parent) }
            }

            val forY = recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = "Y"), 1)
            val forNull = recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = null), 1)
            val forX = recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = "X"), 1)

            assertEquals(listOf(low.id), ids(forY))
            assertEquals(listOf(low.id), ids(forNull))
            assertEquals(1, forX.size)
            assertEquals(parent.id, forX.single().parentId, "the holder's own subtree is not shielded from the holder")
            assertEquals(Priority.HIGH, forX.single().priority)
        }

    // -----------------------------------------------------------------------------------------------------------
    // S4 (EXISTING-SURFACE, edge, AR-63): explainEmpty counts exactly, no 200-row window
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S4 explainEmpty over 250 dependency-blocked and 3 claimed items counts every one`(): Unit =
        runBlocking {
            db.unitOfWork().inUnit {
                val f = Fixture()
                val blocker = f.item("unfinished blocker", role = Role.WORK)
                repeat(250) { i -> f.link(blocker, f.item("blocked $i")) }
                repeat(3) { i -> f.item("claimed $i", claimedBy = "agent-$i", claimExpiresAt = live()) }
            }

            val counts = recommender.explainEmpty(NextItemRecommender.Criteria())

            assertEquals(3, counts.claimed)
            assertEquals(0, counts.ancestorClaimed)
            assertEquals(250, counts.dependencyBlocked)
            assertEquals(253, counts.total, "the parts sum to the matched count when nothing is eligible")
            assertTrue(recommender.recommend(NextItemRecommender.Criteria(), 10).isEmpty(), "control: nothing is eligible")
        }

    @Test
    fun `S4 explainEmpty classifies ancestor-claimed children separately from own-claimed and dependency-blocked`(): Unit =
        runBlocking {
            db.unitOfWork().inUnit {
                val f = Fixture()
                val parent = f.item("claimed parent", role = Role.WORK, claimedBy = "X", claimExpiresAt = live())
                repeat(4) { i -> f.item("shielded child $i", parent = parent) }
                val blocker = f.item("unfinished blocker", role = Role.WORK)
                repeat(2) { i -> f.link(blocker, f.item("blocked $i")) }
                f.item("own claimed", claimedBy = "Q", claimExpiresAt = live())
            }

            val counts = recommender.explainEmpty(NextItemRecommender.Criteria(requestingAgentId = "Y"))

            assertEquals(1, counts.claimed)
            assertEquals(4, counts.ancestorClaimed)
            assertEquals(2, counts.dependencyBlocked)
            assertEquals(7, counts.total)
        }

    // -----------------------------------------------------------------------------------------------------------
    // S5 (EXISTING-SURFACE, edge): candidateCount is the exact eligible count
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S5 recommendCounted reports 250 eligible with limit 1 and with limit 20, ignoring ineligible rows`(): Unit =
        runBlocking {
            db.unitOfWork().inUnit {
                val f = Fixture()
                repeat(250) { i -> f.item("eligible $i", priority = Priority.LOW) }
                val blocker = f.item("unfinished blocker", role = Role.WORK)
                repeat(10) { i -> f.link(blocker, f.item("blocked $i", priority = Priority.HIGH)) }
                repeat(5) { i -> f.item("claimed $i", priority = Priority.HIGH, claimedBy = "agent-$i", claimExpiresAt = live()) }
            }

            val one = recommender.recommendCounted(NextItemRecommender.Criteria(), 1)
            val twenty = recommender.recommendCounted(NextItemRecommender.Criteria(), 20)

            assertEquals(1, one.items.size)
            assertEquals(250, one.candidateCount)
            assertEquals(20, twenty.items.size)
            assertEquals(250, twenty.candidateCount)
        }

    // -----------------------------------------------------------------------------------------------------------
    // S6 (EXISTING-SURFACE, edge): unblockAt x blocker role matrix
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S6 a subject is eligible exactly when its blocker is at or beyond unblockAt, for every threshold and blocker role`(): Unit =
        runBlocking {
            val thresholds = listOf<String?>(null, "queue", "work", "review", "terminal")
            val blockerRoles = listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.TERMINAL, Role.BLOCKED)
            val progression = listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.TERMINAL)
            val expectedEligible = mutableSetOf<UUID>()
            val allSubjects = mutableSetOf<UUID>()
            db.unitOfWork().inUnit {
                val f = Fixture()
                for (threshold in thresholds) {
                    for (role in blockerRoles) {
                        val blocker = f.item("blocker ${role}_$threshold", role = role, tags = "blocker")
                        val subject = f.item("subject ${role}_$threshold", tags = "subject")
                        f.link(blocker, subject, DependencyType.BLOCKS, threshold)
                        allSubjects += subject.id
                        val effective = threshold?.let { Role.valueOf(it.uppercase()) } ?: Role.TERMINAL
                        if (role != Role.BLOCKED &&
                            progression.indexOf(role) >= progression.indexOf(effective)
                        ) {
                            expectedEligible += subject.id
                        }
                    }
                }
            }

            val result = recommender.recommend(NextItemRecommender.Criteria(tags = listOf("subject")), 100)

            assertEquals(25, allSubjects.size)
            assertTrue(expectedEligible.size in 1 until allSubjects.size, "control: the matrix has both satisfied and unsatisfied cells")
            assertEquals(expectedEligible, ids(result).toSet())
            assertEquals(expectedEligible.size, result.size, "no duplicates")
        }

    @Test
    fun `S6 probe two blockers need both satisfied, and the IS_BLOCKED_BY alias and RELATES_TO behave as documented`(): Unit =
        runBlocking {
            lateinit var bothDone: WorkItem
            lateinit var oneOpen: WorkItem
            lateinit var viaAlias: WorkItem
            lateinit var relatedOnly: WorkItem
            db.unitOfWork().inUnit {
                val f = Fixture()
                val done1 = f.item("done 1", role = Role.TERMINAL, tags = "blocker")
                val done2 = f.item("done 2", role = Role.TERMINAL, tags = "blocker")
                val open = f.item("open", role = Role.WORK, tags = "blocker")
                bothDone = f.item("both done", tags = "subject")
                oneOpen = f.item("one open", tags = "subject")
                viaAlias = f.item("via alias", tags = "subject")
                relatedOnly = f.item("related only", tags = "subject")
                f.link(done1, bothDone)
                f.link(done2, bothDone)
                f.link(done1, oneOpen)
                f.link(open, oneOpen)
                f.link(viaAlias, open, DependencyType.IS_BLOCKED_BY)
                f.link(open, relatedOnly, DependencyType.RELATES_TO)
            }

            val result = recommender.recommend(NextItemRecommender.Criteria(tags = listOf("subject")), 10)

            assertEquals(setOf(bothDone.id, relatedOnly.id), ids(result).toSet())
            assertTrue(oneOpen.id !in ids(result) && viaAlias.id !in ids(result))
        }

    // -----------------------------------------------------------------------------------------------------------
    // S7 (EXISTING-SURFACE, edge): expiry boundary and ancestor depth
    // -----------------------------------------------------------------------------------------------------------

    @Test
    fun `S7 an item whose own claim expires exactly now is eligible, one millisecond later it is not`(): Unit =
        runBlocking {
            lateinit var atNow: WorkItem
            lateinit var justAfter: WorkItem
            db.unitOfWork().inUnit {
                val f = Fixture()
                atNow = f.item("expires at now", claimedBy = "X", claimExpiresAt = t, tags = "probe")
                justAfter = f.item("expires 1ms after now", claimedBy = "X", claimExpiresAt = t.plusMillis(1), tags = "probe")
            }

            val result = recommender.recommend(NextItemRecommender.Criteria(tags = listOf("probe")), 10)

            assertEquals(listOf(atNow.id), ids(result))
            assertTrue(justAfter.id !in ids(result))
        }

    @Test
    fun `S7 an ancestor claim expiring exactly now does not shield, one millisecond later it does`(): Unit =
        runBlocking {
            lateinit var underExpired: WorkItem
            db.unitOfWork().inUnit {
                val f = Fixture()
                val expiredParent = f.item("parent expiring at now", role = Role.WORK, claimedBy = "X", claimExpiresAt = t)
                val liveParent = f.item("parent expiring 1ms later", role = Role.WORK, claimedBy = "X", claimExpiresAt = t.plusMillis(1))
                underExpired = f.item("under expired", parent = expiredParent)
                f.item("under live", parent = liveParent)
            }

            val forY = recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = "Y"), 10)

            assertEquals(listOf(underExpired.id), ids(forY))
        }

    @Test
    fun `S7 a live claim six levels up shields the leaf for another agent and for null but not for the holder`(): Unit =
        runBlocking {
            lateinit var leaf: WorkItem
            db.unitOfWork().inUnit {
                val f = Fixture()
                var cur = f.item("depth 0 claimed", role = Role.WORK, claimedBy = "X", claimExpiresAt = live())
                for (d in 1..5) cur = f.item("depth $d", role = Role.WORK, parent = cur)
                leaf = f.item("depth 6 leaf", parent = cur)
            }

            assertEquals(6, leaf.depth)
            assertTrue(recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = "Y"), 10).isEmpty())
            assertTrue(recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = null), 10).isEmpty())
            assertEquals(listOf(leaf.id), ids(recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = "X"), 10)))
        }

    @Test
    fun `S7 probe a live claim on a middle ancestor shields even when a higher ancestor claim has expired`(): Unit =
        runBlocking {
            lateinit var leaf: WorkItem
            db.unitOfWork().inUnit {
                val f = Fixture()
                val top = f.item("top, claim expired", role = Role.WORK, claimedBy = "X", claimExpiresAt = t.minusSeconds(5))
                val mid = f.item("mid, claim live", role = Role.WORK, parent = top, claimedBy = "X", claimExpiresAt = live())
                leaf = f.item("leaf", parent = mid)
            }

            assertTrue(recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = "Y"), 10).isEmpty())
            assertEquals(listOf(leaf.id), ids(recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = "X"), 10)))
        }
}

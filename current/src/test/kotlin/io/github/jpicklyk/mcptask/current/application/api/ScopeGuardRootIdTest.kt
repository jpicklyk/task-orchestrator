package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.ItemFilter
import io.github.jpicklyk.mcptask.current.application.port.ItemPage
import io.github.jpicklyk.mcptask.current.application.port.ItemSort
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.port.TextQuery
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.blockIds
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ids
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.irApp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.obj
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.short
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 754ae3dc, run r-202610132225-754ae3dc) for scoping on the stamped `root_id`:
 * scenarios S1 to S7 of the frozen test-plan. Roots only: an item is in a root scope exactly when its stamped `root_id` is a
 * listed id; there is no ancestor walk.
 *
 * Oracles (task-scope items 1 to 3 and decisions D1 and D3, never read from a run): `ScopeGuard.check` admits an item iff its
 * stamped `rootId` is a listed root (and the tag half, when listed, matches); a missing item, a null `rootId` and a lookup fault
 * are `forbidden` (fail closed, never `not_found`); `required` is the sorted listed root ids joined by `,` (empty under an empty
 * root set); `admitted(scope, ids)` equals the ids `check` admits one by one and reads items with one `findByIds`; an unrestricted
 * scope is Ok with no repository read. A listed id that is NOT a root admits nothing (the old any-ancestor rule is gone); the same
 * holds for the store's find and count and for the search index. A request `ancestorId` filter still returns that item's whole
 * subtree (api-reference.md, "Only this item's subtree, itself included"), including a depth-1 item, with or without a principal
 * root scope, on list, blocked, next and context.
 *
 * Fixture invariants (task-scope "Fixture invariant"): every directly created item stamps `rootId` (root: its own id; child: its
 * parent's root); an `orphan` is a depth-0 row whose `root_id` stays NULL, created on purpose for the null-rootId scenarios.
 *
 * Negative assertions are paired with a control built from the same fixture: a scope listing the real root admits the same rows.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ScopeGuardRootIdTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val rootA = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val childA = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
    private val grandA = UUID.fromString("00000000-0000-4000-8000-0000000000a3")
    private val rootB = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
    private val childB = UUID.fromString("00000000-0000-4000-8000-0000000000b2")
    private val orphan = UUID.fromString("00000000-0000-4000-8000-0000000000c1")
    private val ghost = UUID.fromString("00000000-0000-4000-8000-0000000000ee")

    private val repo: WorkItemRepository get() = db.repositoryProvider().workItemRepository()

    private suspend fun seed(
        id: UUID,
        parent: UUID? = null,
        tags: String? = null,
        stamped: Boolean = true,
        role: Role = Role.QUEUE
    ) {
        val p = parent?.let { checkNotNull(repo.getById(it)) }
        repo.create(
            WorkItem(
                id = id,
                title = "rootidprobe item-${id.toString().takeLast(2)}",
                parentId = parent,
                depth = (p?.depth ?: -1) + 1,
                rootId = if (!stamped) null else (p?.let { it.rootId ?: it.id } ?: id),
                tags = tags,
                role = role
            )
        )
    }

    /** A: root (alpha) > child > grandchild (" alpha , beta "); B: root (gamma) > child; an unstamped depth-0 orphan. */
    private suspend fun seedTree() {
        seed(rootA, tags = "alpha")
        seed(childA, parent = rootA)
        seed(grandA, parent = childA, tags = " alpha , beta ")
        seed(rootB, tags = "gamma")
        seed(childB, parent = rootB)
        seed(orphan, stamped = false)
    }

    private fun roots(vararg ids: UUID) = AccessScope.principal(ids.toSet(), emptySet())

    private suspend fun check(
        scope: AccessScope,
        id: UUID
    ): Outcome<Unit> = ScopeGuard.check(scope, id, repo)

    private fun Outcome<Unit>.assertOk() = assertTrue(this is Outcome.Ok, "expected Ok but was $this")

    private fun Outcome<Unit>.forbidden(
        scope: ForbiddenScope,
        required: String
    ) {
        assertTrue(this is Outcome.Err, "expected an Err but was $this")
        val error: DomainError = (this as Outcome.Err).error
        assertEquals(ErrorCode.FORBIDDEN, error.code)
        val detail = error.detail as ErrorDetail.Forbidden
        assertEquals(scope, detail.scope)
        assertEquals(required, detail.required)
    }

    // ------------------------------------------------------------------ S1 happy

    @Test
    fun `S1 a listed root admits itself, its child and its grandchild and refuses another root's child`(): Unit =
        runBlocking {
            seedTree()
            val scope = roots(rootA)
            check(scope, rootA).assertOk()
            check(scope, childA).assertOk()
            check(scope, grandA).assertOk()
            check(scope, childB).forbidden(ForbiddenScope.ROOT, rootA.toString())
            check(scope, rootB).forbidden(ForbiddenScope.ROOT, rootA.toString())
            // control: the other root's own scope admits that child
            check(roots(rootB), childB).assertOk()
        }

    @Test
    fun `S1 several listed roots admit the union and required is the sorted listed ids`(): Unit =
        runBlocking {
            seedTree()
            val both = AccessScope.principal(linkedSetOf(rootB, rootA), emptySet())
            check(both, childA).assertOk()
            check(both, childB).assertOk()
            check(both, orphan).forbidden(ForbiddenScope.ROOT, "$rootA,$rootB")
        }

    // ------------------------------------------------------------------ S2 failure: a non-root anchor admits nothing

    @Test
    fun `S2 a listed depth-1 item admits neither itself nor its descendants, the root half is the stamped root_id`(): Unit =
        runBlocking {
            seedTree()
            val scope = roots(childA)
            check(scope, childA).forbidden(ForbiddenScope.ROOT, childA.toString())
            check(scope, grandA).forbidden(ForbiddenScope.ROOT, childA.toString())
            check(scope, rootA).forbidden(ForbiddenScope.ROOT, childA.toString())
            // control: listing the real root admits exactly the rows the non-root anchor was meant to
            check(roots(rootA), childA).assertOk()
            check(roots(rootA), grandA).assertOk()
            // a depth-2 anchor is no better
            check(roots(grandA), grandA).forbidden(ForbiddenScope.ROOT, grandA.toString())
            // a non-root listed next to the real root adds nothing and removes nothing
            check(roots(rootA, childA), grandA).assertOk()
            check(
                roots(rootB, childA),
                grandA
            ).forbidden(ForbiddenScope.ROOT, listOf(rootB, childA).map { it.toString() }.sorted().joinToString(","))
        }

    // ------------------------------------------------------------------ S3 edge

    @Test
    fun `S3 a listed depth-0 row with a null root_id is forbidden`(): Unit =
        runBlocking {
            seedTree()
            check(roots(orphan), orphan).forbidden(ForbiddenScope.ROOT, orphan.toString())
            // control: the stamped root listed the same way is admitted
            check(roots(rootA), rootA).assertOk()
        }

    @Test
    fun `S3 a missing item is forbidden never not_found, and an unrestricted scope is Ok without any repository read`(): Unit =
        runBlocking {
            seedTree()
            val outcome = check(roots(rootA), ghost)
            outcome.forbidden(ForbiddenScope.ROOT, rootA.toString())
            assertNotEquals(ErrorCode.NOT_FOUND, (outcome as Outcome.Err).error.code)
            check(AccessScope.principal(null, setOf("alpha")), ghost).forbidden(ForbiddenScope.TAG, "alpha")

            val strict = mockk<WorkItemRepository>()
            ScopeGuard.check(AccessScope.unrestricted(), ghost, strict).assertOk()
            ScopeGuard.check(AccessScope.principal(null, emptySet()), ghost, strict).assertOk()
        }

    @Test
    fun `S3 a repository fault under a restricted scope fails closed and an empty root set requires the empty string`(): Unit =
        runBlocking {
            seedTree()
            val strict = mockk<WorkItemRepository>()
            ScopeGuard.check(roots(rootA), childA, strict).forbidden(ForbiddenScope.ROOT, rootA.toString())
            check(AccessScope.principal(emptySet(), emptySet()), rootA).forbidden(ForbiddenScope.ROOT, "")
            check(roots(rootA), rootA).assertOk()
        }

    // ------------------------------------------------------------------ S4 admitted == check, with findByIds only

    private fun row(
        n: Int,
        root: UUID?,
        tags: String? = null
    ) = WorkItem(
        id = UUID.fromString("00000000-0000-4000-8000-%012x".format(0x100 + n)),
        title = "row-$n",
        parentId = rootA,
        depth = 1,
        rootId = root,
        tags = tags
    )

    @Test
    fun `S4 admitted reads items with findByIds only and applies the root and tag halves to the same rows`(): Unit =
        runBlocking {
            val inA = row(1, rootA, "alpha")
            val inAPlain = row(2, rootA)
            val inB = row(3, rootB, "alpha")
            val unstamped = row(4, null, "alpha")
            val all = listOf(inA, inAPlain, inB, unstamped)
            val strict = mockk<WorkItemRepository>()
            coEvery { strict.findByIds(any()) } returns all
            val ids = all.map { it.id }.toSet()

            assertEquals(setOf(inA.id, inAPlain.id), ScopeGuard.admitted(roots(rootA), ids, strict), "root half: stamped root_id only")
            assertEquals(
                setOf(inA.id),
                ScopeGuard.admitted(AccessScope.principal(setOf(rootA), setOf("alpha")), ids, strict),
                "root and tag halves both apply to the same rows"
            )
            assertEquals(
                setOf(inA.id, inB.id, unstamped.id),
                ScopeGuard.admitted(AccessScope.principal(null, setOf("alpha")), ids, strict),
                "the tag half does not look at root_id"
            )
            assertEquals(emptySet(), ScopeGuard.admitted(roots(childA), ids, strict), "a non-root anchor admits nothing")
        }

    @Test
    fun `S4 check reads a single item with getById only`(): Unit =
        runBlocking {
            val inA = row(1, rootA)
            val inB = row(2, rootB)
            val strict = mockk<WorkItemRepository>()
            coEvery { strict.getById(inA.id) } returns inA
            coEvery { strict.getById(inB.id) } returns inB
            ScopeGuard.check(roots(rootA), inA.id, strict).assertOk()
            ScopeGuard.check(roots(rootA), inB.id, strict).forbidden(ForbiddenScope.ROOT, rootA.toString())
        }

    @Test
    fun `S4 admitted equals the ids check admits one by one for root, tag and both`(): Unit =
        runBlocking {
            seedTree()
            val ids = setOf(rootA, childA, grandA, rootB, childB, orphan, ghost)
            val scopes =
                listOf(
                    roots(rootA),
                    roots(childA),
                    roots(rootA, rootB),
                    roots(orphan),
                    AccessScope.principal(null, setOf("alpha")),
                    AccessScope.principal(null, setOf("beta", "gamma")),
                    AccessScope.principal(setOf(rootA), setOf("alpha")),
                    AccessScope.principal(setOf(childA), setOf("alpha"))
                )
            for (scope in scopes) {
                val perItem = ids.filter { check(scope, it) is Outcome.Ok }.toSet()
                assertEquals(perItem, ScopeGuard.admitted(scope, ids, repo), "scope $scope")
            }
            // the comparison is not vacuous: the root scope admits a real subset
            assertEquals(setOf(rootA, childA, grandA), ScopeGuard.admitted(roots(rootA), ids, repo))
            assertEquals(emptySet(), ScopeGuard.admitted(roots(childA), ids, repo))
        }

    // ------------------------------------------------------------------ S5 store find / count

    private val store get() = SQLiteWorkItemRepository(db.databaseManager)

    private suspend fun findIds(
        scope: AccessScope,
        filter: ItemFilter = ItemFilter.ALL
    ): Set<UUID> =
        store
            .find(filter, scope, ItemPage(limit = 1000), ItemSort.DEFAULT)
            .map { it.id }
            .toSet()

    @Test
    fun `S5 find and count under a root scope return exactly the stamped subtree`(): Unit =
        runBlocking {
            seedTree()
            assertEquals(setOf(rootA, childA, grandA), findIds(roots(rootA)))
            assertEquals(3, store.count(ItemFilter.ALL, roots(rootA)))
            assertEquals(setOf(rootA, childA, grandA, rootB, childB), findIds(roots(rootA, rootB)))
            assertEquals(5, store.count(ItemFilter.ALL, roots(rootA, rootB)))
            assertEquals(
                6,
                store.count(ItemFilter.ALL, AccessScope.unrestricted()),
                "control: the orphan exists and is visible unrestricted"
            )
        }

    @Test
    fun `S5 a listed non-root, an unstamped depth-0 row and an unknown id admit no rows and count zero`(): Unit =
        runBlocking {
            seedTree()
            for (anchor in listOf(childA, grandA, orphan, ghost)) {
                assertEquals(emptySet(), findIds(roots(anchor)), "anchor $anchor")
                assertEquals(0, store.count(ItemFilter.ALL, roots(anchor)), "count for anchor $anchor")
            }
            // a non-root next to a real root adds nothing
            assertEquals(setOf(rootA, childA, grandA), findIds(roots(rootA, childA, orphan, ghost)))
        }

    @Test
    fun `S6 a request ancestorId filter still returns that item's whole subtree, with and without a root scope`(): Unit =
        runBlocking {
            seedTree()
            val filter = ItemFilter(ancestorId = childA)
            assertEquals(setOf(childA, grandA), findIds(AccessScope.unrestricted(), filter))
            assertEquals(2, store.count(filter, AccessScope.unrestricted()))
            assertEquals(setOf(childA, grandA), findIds(roots(rootA), filter), "inside the principal root scope")
            assertEquals(emptySet(), findIds(roots(rootB), filter), "outside the principal root scope")
        }

    @Test
    fun `S6 a principal scope on a non-root anchor admits nothing when the ancestorId filter names a different item`(): Unit =
        runBlocking {
            seedTree()
            // Principal scope {childA} is a non-root: it admits nothing (roots only). The request ancestor filter is AND-ed on top
            // and the ancestor (grandA) is not itself a listed scope id, so the scope condition is NOT dropped (supp. declarations B).
            val filter = ItemFilter(ancestorId = grandA)
            assertEquals(emptySet(), findIds(roots(childA), filter), "find: a non-root anchor admits nothing")
            assertEquals(0, store.count(filter, roots(childA)), "count: the same")
            // control: the same request filter under the real root returns the subtree
            assertEquals(setOf(grandA), findIds(roots(rootA), filter))
            assertEquals(1, store.count(filter, roots(rootA)))
        }

    // ------------------------------------------------------------------ S6 ancestorId on the read views

    private fun uid(n: Int): UUID = UUID.fromString("%08x-0000-4000-8000-%012x".format(n, n))

    @Test
    fun `S6 ancestorId on a depth-1 item returns its subtree on list, blocked, next and context, for both tokens`() {
        val r = uid(0x101)
        val c = uid(0x102)
        val c1 = uid(0x103)
        val cb = uid(0x104)
        val s = uid(0x105)
        val sb = uid(0x106)
        val r2 = uid(0x107)
        val x = uid(0x108)
        val xb = uid(0x109)
        irApp(db.db, dir, scopeRootIds = setOf(r)) { _ ->
            seed(r, role = Role.WORK)
            seed(c, parent = r, role = Role.WORK)
            seed(c1, parent = c)
            seed(cb, parent = c, role = Role.BLOCKED)
            seed(s, parent = r)
            seed(sb, parent = r, role = Role.BLOCKED)
            seed(r2, role = Role.WORK)
            seed(x, parent = r2)
            seed(xb, parent = r2, role = Role.BLOCKED)

            for (token in listOf(io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ADMIN_TOKEN, TEST_TOKEN)) {
                val list = select("""{"select":"list","ancestorId":"$c"}""", token)
                assertEquals(200, list.status, list.raw)
                assertEquals(setOf(short(c), short(c1), short(cb)), list.ids(), "list, token $token")

                val blocked = select("""{"select":"blocked","ancestorId":"$c"}""", token)
                assertEquals(200, blocked.status, blocked.raw)
                assertEquals(setOf(short(cb)), blocked.ids(), "blocked, token $token")

                val next = select("""{"select":"next","limit":200,"ancestorId":"$c"}""", token)
                assertEquals(200, next.status, next.raw)
                assertEquals(setOf(short(c1)), next.ids(), "next, token $token")

                val context = select("""{"select":"context","ancestorId":"$c"}""", token)
                assertEquals(200, context.status, context.raw)
                assertEquals(listOf(short(c)), context.data.obj("active").blockIds(), "context active, token $token")
                assertEquals(listOf(short(cb)), context.data.obj("blocked").blockIds(), "context blocked, token $token")
            }

            // a subtree outside the principal scope yields nothing for the scoped token; the unrestricted token still sees it
            val admin = io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ADMIN_TOKEN
            assertEquals(setOf(short(r2), short(x), short(xb)), select("""{"select":"list","ancestorId":"$r2"}""", admin).ids())
            assertEquals(emptySet(), select("""{"select":"list","ancestorId":"$r2"}""", TEST_TOKEN).ids())
            assertEquals(emptySet(), select("""{"select":"blocked","ancestorId":"$r2"}""", TEST_TOKEN).ids())
            assertEquals(emptySet(), select("""{"select":"next","limit":200,"ancestorId":"$r2"}""", TEST_TOKEN).ids())
            val ctx = select("""{"select":"context","ancestorId":"$r2"}""", TEST_TOKEN)
            assertEquals(emptyList(), ctx.data.obj("active").blockIds())
            assertEquals(emptyList(), ctx.data.obj("blocked").blockIds())
        }
    }

    // ------------------------------------------------------------------ S7 search

    private suspend fun owners(
        analyzer: Analyzer,
        filter: ScopeFilter
    ): Set<UUID> =
        db
            .repositoryProvider()
            .searchIndex()
            .candidates(Corpus.ITEM, TextQuery(listOf("rootidprobe")), analyzer, filter, 200)
            .map { it.ownerItemId }
            .toSet()

    @Test
    fun `S7 search rootIds admit the stamped tree only, a non-root and an unstamped row admit nothing`(): Unit =
        runBlocking {
            seedTree()
            for (a in Analyzer.entries) {
                assertEquals(
                    setOf(rootA, childA, grandA),
                    owners(a, ScopeFilter(rootIds = setOf(rootA))),
                    "$a: a root scopes its whole tree"
                )
                assertEquals(emptySet(), owners(a, ScopeFilter(rootIds = setOf(childA))), "$a: a non-root anchor admits nothing")
                assertEquals(emptySet(), owners(a, ScopeFilter(rootIds = setOf(orphan))), "$a: an unstamped row is never admitted")
                assertEquals(
                    setOf(rootA, childA, grandA),
                    owners(a, ScopeFilter(rootIds = setOf(rootA, childA, orphan, ghost))),
                    "$a: non-roots next to a real root add nothing"
                )
                assertEquals(
                    setOf(rootA, childA, grandA, rootB, childB),
                    owners(a, ScopeFilter(rootIds = setOf(rootA, rootB))),
                    "$a: several roots are a union"
                )
            }
            assertEquals(
                setOf(rootA, childA, grandA, rootB, childB, orphan),
                owners(Analyzer.SUBSTRING, ScopeFilter(rootIds = null)),
                "control: without a root constraint the orphan is searchable, so its absence above is an exclusion"
            )
        }

    @Test
    fun `S7 search rootIds are ANDed with the ancestor filter`(): Unit =
        runBlocking {
            seedTree()
            val inA = setOf(rootA)
            assertEquals(setOf(childA, grandA), owners(Analyzer.SUBSTRING, ScopeFilter(rootIds = inA, ancestorId = childA)))
            assertEquals(emptySet(), owners(Analyzer.SUBSTRING, ScopeFilter(rootIds = setOf(rootB), ancestorId = childA)))
            assertEquals(emptySet(), owners(Analyzer.SUBSTRING, ScopeFilter(rootIds = setOf(childA), ancestorId = childA)))
            assertEquals(setOf(grandA), owners(Analyzer.SUBSTRING, ScopeFilter(rootIds = inA, itemId = grandA)))
            assertEquals(emptySet(), owners(Analyzer.SUBSTRING, ScopeFilter(rootIds = inA, itemId = childB)))
        }
}

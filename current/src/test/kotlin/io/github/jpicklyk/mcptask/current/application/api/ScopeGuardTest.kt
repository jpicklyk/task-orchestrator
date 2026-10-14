package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f67e1762) for [ScopeGuard], scenarios S5 and S12 of the frozen test-plan
 * (NEW-SURFACE).
 *
 * Oracles: task-scope item 6 and decision D2, amended by item 754ae3dc (scope on root_id, roots only). `check` is the
 * transport-neutral twin of the REST scope check: an unrestricted scope is Ok with no repository read; a root-restricted scope
 * admits an item whose stamped `rootId` is a listed root (a listed non-root admits nothing, itself included),
 * else `forbidden{scope: root}`; a tag-restricted scope admits an item whose CSV `tags` (entries trimmed, matched exactly,
 * case-sensitive) contain a listed tag, else `forbidden{scope: tag}`; a missing item or a lookup fault under a restricted scope
 * is `forbidden` too (fail closed, no existence oracle, never `not_found`). `required` is the sorted listed root ids or tags
 * joined by `,`. The envelope wire spelling of the scope is the lowercase enum name.
 *
 * Harness: real SQLite items with a three-level chain for the ancestor walk; a strict MockK repository (any call throws) for
 * the no-read and fault scenarios. Every "forbidden" below is paired with an in-scope control built from the same fixture.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ScopeGuardTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val rootA = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val childA = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
    private val grandA = UUID.fromString("00000000-0000-4000-8000-0000000000a3")
    private val rootB = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
    private val childB = UUID.fromString("00000000-0000-4000-8000-0000000000b2")
    private val ghost = UUID.fromString("00000000-0000-4000-8000-0000000000ee")

    private suspend fun seed(
        id: UUID,
        parent: UUID? = null,
        tags: String? = null
    ) {
        val repo = db.repositoryProvider().workItemRepository()
        val p = parent?.let { checkNotNull(repo.getById(it)) }
        repo.create(
            WorkItem(
                id = id,
                title = "item-${id.toString().takeLast(2)}",
                parentId = parent,
                depth = (p?.depth ?: -1) + 1,
                // a root is stamped with its own id, a child with its parent's root (the one-writer invariant)
                rootId = p?.let { it.rootId ?: it.id } ?: id,
                tags = tags
            )
        )
    }

    /** A: root > child > grandchild (grandchild tagged " alpha , beta "); B: root > child. */
    private suspend fun seedTree() {
        seed(rootA, tags = "alpha")
        seed(childA, parent = rootA)
        seed(grandA, parent = childA, tags = " alpha , beta ")
        seed(rootB, tags = "gamma")
        seed(childB, parent = rootB)
    }

    private val repo: WorkItemRepository get() = db.repositoryProvider().workItemRepository()

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

    // ------------------------------------------------------------------ S5 Ok

    @Test
    fun `S5 an unrestricted scope is Ok without reading the repository, even for an unknown item`(): Unit =
        runBlocking {
            val strict = mockk<WorkItemRepository>()
            ScopeGuard.check(AccessScope.unrestricted(), ghost, strict).assertOk()
            ScopeGuard.check(AccessScope.principal(rootIds = null, tagsInclude = emptySet()), ghost, strict).assertOk()
        }

    @Test
    fun `S5 a listed root admits itself, its child and its grandchild`(): Unit =
        runBlocking {
            seedTree()
            val scope = AccessScope.principal(setOf(rootA), emptySet())
            check(scope, rootA).assertOk()
            check(scope, childA).assertOk()
            check(scope, grandA).assertOk()
        }

    @Test
    fun `S5 a listed non-root admits neither itself nor its descendants nor its parent`(): Unit =
        runBlocking {
            seedTree()
            val scope = AccessScope.principal(setOf(childA), emptySet())
            check(scope, childA).forbidden(ForbiddenScope.ROOT, childA.toString())
            check(scope, grandA).forbidden(ForbiddenScope.ROOT, childA.toString())
            check(scope, rootA).forbidden(ForbiddenScope.ROOT, childA.toString())
            // control: the real root admits the same rows
            check(AccessScope.principal(setOf(rootA), emptySet()), childA).assertOk()
            check(AccessScope.principal(setOf(rootA), emptySet()), grandA).assertOk()
        }

    @Test
    fun `S5 a tag entry is trimmed and matched exactly, any listed tag admits`(): Unit =
        runBlocking {
            seedTree()
            check(AccessScope.principal(null, setOf("beta")), grandA).assertOk()
            check(AccessScope.principal(null, setOf("alpha")), grandA).assertOk()
            check(AccessScope.principal(null, setOf("zzz", "beta")), grandA).assertOk()
        }

    @Test
    fun `S5 root and tag restrictions both apply`(): Unit =
        runBlocking {
            seedTree()
            check(AccessScope.principal(setOf(rootA), setOf("beta")), grandA).assertOk()
            check(AccessScope.principal(setOf(rootA), setOf("gamma")), grandA).forbidden(ForbiddenScope.TAG, "gamma")
            check(AccessScope.principal(setOf(rootB), setOf("beta")), grandA).forbidden(ForbiddenScope.ROOT, rootB.toString())
        }

    // ------------------------------------------------------------------ S12 forbidden

    @Test
    fun `S12 an item outside every listed root is forbidden root with the sorted listed ids as required`(): Unit =
        runBlocking {
            seedTree()
            check(AccessScope.principal(setOf(rootA), emptySet()), childB).forbidden(ForbiddenScope.ROOT, rootA.toString())
            check(AccessScope.principal(setOf(rootA), emptySet()), childA).assertOk()
            // listed in reverse order on purpose: required is sorted, joined by a comma
            val two = AccessScope.principal(linkedSetOf(rootB, rootA), emptySet())
            check(two, ghost).forbidden(ForbiddenScope.ROOT, "$rootA,$rootB")
            check(two, childA).assertOk()
            check(two, childB).assertOk()
        }

    @Test
    fun `S12 an item without a listed tag is forbidden tag with the sorted listed tags as required`(): Unit =
        runBlocking {
            seedTree()
            check(AccessScope.principal(null, setOf("gamma")), grandA).forbidden(ForbiddenScope.TAG, "gamma")
            check(AccessScope.principal(null, linkedSetOf("b", "a")), grandA).forbidden(ForbiddenScope.TAG, "a,b")
            // an item with no tags at all carries no listed tag
            check(AccessScope.principal(null, setOf("alpha")), childA).forbidden(ForbiddenScope.TAG, "alpha")
            check(AccessScope.principal(null, setOf("alpha")), rootA).assertOk()
        }

    @Test
    fun `S12 a missing item under a restricted scope is forbidden, never not_found`(): Unit =
        runBlocking {
            seedTree()
            val rooted = check(AccessScope.principal(setOf(rootA), emptySet()), ghost)
            rooted.forbidden(ForbiddenScope.ROOT, rootA.toString())
            check(AccessScope.principal(null, setOf("alpha")), ghost).forbidden(ForbiddenScope.TAG, "alpha")
            assertNotEquals(ErrorCode.NOT_FOUND, (rooted as Outcome.Err).error.code)
            // control: the same scope admits an existing in-scope item, so the refusal above is about the missing id
            check(AccessScope.principal(setOf(rootA), emptySet()), childA).assertOk()
        }

    @Test
    fun `S12 a repository fault under a restricted scope fails closed as forbidden`(): Unit =
        runBlocking {
            val strict = mockk<WorkItemRepository>()
            val rooted = ScopeGuard.check(AccessScope.principal(setOf(rootA), emptySet()), childA, strict)
            rooted.forbidden(ForbiddenScope.ROOT, rootA.toString())
            val tagged = ScopeGuard.check(AccessScope.principal(null, setOf("alpha")), childA, strict)
            tagged.forbidden(ForbiddenScope.TAG, "alpha")
            // control: the same strict repository is never touched by an unrestricted scope
            ScopeGuard.check(AccessScope.unrestricted(), childA, strict).assertOk()
        }

    @Test
    fun `S12 an empty root set admits nothing`(): Unit =
        runBlocking {
            seedTree()
            val none = AccessScope.principal(emptySet(), emptySet())
            check(none, rootA).forbidden(ForbiddenScope.ROOT, "")
            check(AccessScope.principal(setOf(rootA), emptySet()), rootA).assertOk()
        }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe tag prefix and case do not match`(): Unit =
        runBlocking {
            seed(rootA, tags = "api,feature")
            check(AccessScope.principal(null, setOf("api")), rootA).assertOk()
            check(AccessScope.principal(null, setOf("ap")), rootA).forbidden(ForbiddenScope.TAG, "ap")
            check(AccessScope.principal(null, setOf("apis")), rootA).forbidden(ForbiddenScope.TAG, "apis")
            check(AccessScope.principal(null, setOf("API")), rootA).forbidden(ForbiddenScope.TAG, "API")
            check(AccessScope.principal(null, setOf("feat")), rootA).forbidden(ForbiddenScope.TAG, "feat")
        }

    @Test
    fun `probe a listed root that does not exist admits nothing`(): Unit =
        runBlocking {
            seedTree()
            check(AccessScope.principal(setOf(ghost), emptySet()), grandA).forbidden(ForbiddenScope.ROOT, ghost.toString())
            check(AccessScope.principal(setOf(ghost, rootA), emptySet()), grandA).assertOk()
        }

    // ------------------------------------------------------------------ constructors

    @Test
    fun `S12 the forbidden constructors sort and join a listed collection and keep a given required string`() {
        val listed = ScopeGuard.forbidden(ForbiddenScope.TAG, listOf("b", "a"))
        assertEquals(ErrorCode.FORBIDDEN, listed.code)
        val listedDetail = listed.detail as ErrorDetail.Forbidden
        assertEquals(ForbiddenScope.TAG, listedDetail.scope)
        assertEquals("a,b", listedDetail.required)

        val capability = ScopeGuard.forbidden(ForbiddenScope.CAPABILITY, "write-items")
        assertEquals(ErrorCode.FORBIDDEN, capability.code)
        val capabilityDetail = capability.detail as ErrorDetail.Forbidden
        assertEquals(ForbiddenScope.CAPABILITY, capabilityDetail.scope)
        assertEquals("write-items", capabilityDetail.required)
    }
}

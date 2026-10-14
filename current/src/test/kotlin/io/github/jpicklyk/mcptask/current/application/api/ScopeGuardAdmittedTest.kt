package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
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
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 54f3a1ce, Q12) for `ScopeGuard.admitted`, scenario S27 of the frozen test-plan.
 *
 * Oracle: the plan's S27 and the declared `ScopeGuard` KDoc, amended by item 754ae3dc (scope on root_id, roots only).
 * `admitted(scope, ids, repo)` is the batch twin of `check`: an
 * unrestricted scope returns exactly the input without reading the repository; a root or tag restriction returns exactly the ids
 * for which `check` is Ok (root: the item's stamped `rootId` is a listed root, a listed non-root admits nothing; tag: the trimmed CSV element equals a
 * listed tag exactly, no ancestor walk; a missing id is never admitted); a lookup fault returns the empty set (fail closed).
 * The literal expected sets are written out from those rules, and every restricted case is also compared with `check` per id.
 *
 * Harness: real SQLite items with a three-level chain; a strict MockK repository (any call throws) for the no-read and fault
 * scenarios.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ScopeGuardAdmittedTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val rootA = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val childA = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
    private val grandA = UUID.fromString("00000000-0000-4000-8000-0000000000a3")
    private val rootB = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
    private val childB = UUID.fromString("00000000-0000-4000-8000-0000000000b2")
    private val ghost = UUID.fromString("00000000-0000-4000-8000-0000000000ee")

    private val all = setOf(rootA, childA, grandA, rootB, childB, ghost)

    private val repo: WorkItemRepository get() = db.repositoryProvider().workItemRepository()

    private suspend fun seed(
        id: UUID,
        parent: UUID? = null,
        tags: String? = null
    ) {
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

    /** A: root (alpha) > child (no tags) > grandchild (" alpha , beta "); B: root (gamma) > child (no tags). */
    private suspend fun seedTree() {
        seed(rootA, tags = "alpha")
        seed(childA, parent = rootA)
        seed(grandA, parent = childA, tags = " alpha , beta ")
        seed(rootB, tags = "gamma")
        seed(childB, parent = rootB)
    }

    private suspend fun admitted(
        scope: AccessScope,
        ids: Set<UUID> = all
    ): Set<UUID> = ScopeGuard.admitted(scope, ids, repo)

    /** The batch result must be exactly the ids `check` admits one by one. */
    private suspend fun assertAgreesWithCheck(
        scope: AccessScope,
        ids: Set<UUID> = all
    ) {
        val perItem = ids.filter { ScopeGuard.check(scope, it, repo) is Outcome.Ok }.toSet()
        assertEquals(perItem, admitted(scope, ids), "admitted must equal the ids check() admits one by one")
    }

    @Test
    fun `S27 an unrestricted scope returns the input unchanged without reading the repository`(): Unit =
        runBlocking {
            val strict = mockk<WorkItemRepository>()
            assertEquals(all, ScopeGuard.admitted(AccessScope.unrestricted(), all, strict), "even unknown ids are returned as given")
            assertEquals(emptySet(), ScopeGuard.admitted(AccessScope.unrestricted(), emptySet(), strict))
            val noRestriction = AccessScope.principal(rootIds = null, tagsInclude = emptySet())
            assertEquals(setOf(ghost), ScopeGuard.admitted(noRestriction, setOf(ghost), strict))
        }

    @Test
    fun `S27 a root restriction admits the listed root and its descendants only`(): Unit =
        runBlocking {
            seedTree()
            val scope = AccessScope.principal(setOf(rootA), emptySet())
            assertEquals(setOf(rootA, childA, grandA), admitted(scope))
            assertAgreesWithCheck(scope)
        }

    @Test
    fun `S27 a listed non-root admits nothing, neither itself nor its descendants nor its parent`(): Unit =
        runBlocking {
            seedTree()
            val scope = AccessScope.principal(setOf(childA), emptySet())
            assertEquals(emptySet(), admitted(scope))
            assertAgreesWithCheck(scope)
            // control: the real root admits the same rows
            assertEquals(setOf(rootA, childA, grandA), admitted(AccessScope.principal(setOf(rootA), emptySet())))
        }

    @Test
    fun `S27 several listed roots admit the union`(): Unit =
        runBlocking {
            seedTree()
            val scope = AccessScope.principal(setOf(rootA, rootB), emptySet())
            assertEquals(setOf(rootA, childA, grandA, rootB, childB), admitted(scope))
            assertAgreesWithCheck(scope)
        }

    @Test
    fun `S27 a tag restriction matches the trimmed CSV elements exactly and does not walk ancestors`(): Unit =
        runBlocking {
            seedTree()
            val beta = AccessScope.principal(null, setOf("beta"))
            assertEquals(setOf(grandA), admitted(beta))
            assertAgreesWithCheck(beta)

            val alpha = AccessScope.principal(null, setOf("alpha"))
            assertEquals(setOf(rootA, grandA), admitted(alpha), "childA has no tags of its own and inherits none from rootA")
            assertAgreesWithCheck(alpha)

            val either = AccessScope.principal(null, setOf("beta", "gamma"))
            assertEquals(setOf(grandA, rootB), admitted(either), "any listed tag admits")
            assertAgreesWithCheck(either)
        }

    @Test
    fun `S27 root and tag restrictions both apply`(): Unit =
        runBlocking {
            seedTree()
            val both = AccessScope.principal(setOf(rootA), setOf("alpha"))
            assertEquals(setOf(rootA, grandA), admitted(both))
            assertAgreesWithCheck(both)

            val disjoint = AccessScope.principal(setOf(rootA), setOf("gamma"))
            assertEquals(emptySet(), admitted(disjoint), "gamma is only on rootB, which is outside rootA")
            assertAgreesWithCheck(disjoint)
        }

    @Test
    fun `S27 an unknown id is never admitted under a restricted scope, whatever the other ids are`(): Unit =
        runBlocking {
            seedTree()
            val scope = AccessScope.principal(setOf(rootA), emptySet())
            assertEquals(setOf(rootA), admitted(scope, setOf(rootA, ghost)))
            assertEquals(emptySet(), admitted(scope, setOf(ghost)))
            val tags = AccessScope.principal(null, setOf("alpha"))
            assertEquals(emptySet(), admitted(tags, setOf(ghost)))
        }

    @Test
    fun `S27 an empty root set admits nothing and an empty input yields an empty set`(): Unit =
        runBlocking {
            seedTree()
            assertEquals(emptySet(), admitted(AccessScope.principal(emptySet(), emptySet())))
            assertEquals(emptySet(), admitted(AccessScope.principal(setOf(rootA), emptySet()), emptySet()))
        }

    @Test
    fun `S27 the result never contains an id that was not asked about`(): Unit =
        runBlocking {
            seedTree()
            val scope = AccessScope.principal(setOf(rootA), emptySet())
            val asked = setOf(childA, rootB)
            val result = admitted(scope, asked)
            assertTrue(asked.containsAll(result), "admitted is a subset of the input: $result")
            assertEquals(setOf(childA), result)
        }

    @Test
    fun `S27 a repository fault under a restricted scope fails closed as the empty set`(): Unit =
        runBlocking {
            val strict = mockk<WorkItemRepository>()
            assertEquals(emptySet(), ScopeGuard.admitted(AccessScope.principal(setOf(rootA), emptySet()), setOf(childA, rootA), strict))
            assertEquals(emptySet(), ScopeGuard.admitted(AccessScope.principal(null, setOf("alpha")), setOf(childA, rootA), strict))
            // control: the same strict repository is never touched by an unrestricted scope, so the two empty sets above are the fault
            assertEquals(setOf(childA, rootA), ScopeGuard.admitted(AccessScope.unrestricted(), setOf(childA, rootA), strict))
        }
}

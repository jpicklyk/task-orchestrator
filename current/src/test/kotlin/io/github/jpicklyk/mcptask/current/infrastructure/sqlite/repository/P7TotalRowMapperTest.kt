package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7 (item beeef6f7) S8: the work-item row mapper is total. A stored row that violates a domain rule is returned
 * with diagnostics instead of being silently dropped, structural walks still reach it, and it cannot be written
 * back while it is invalid.
 *
 * Oracles (frozen test-plan): plan 3.11 l.344-345 and AR-45 (rehydration must not drop rows; the persisted shape is
 * the source of truth), task-scope section 6 (diagnostics carry the violations; writes still validate; the
 * `skipped` count of a fetch result is always 0 and the REST `skipped` field is omitted).
 *
 * Fixture: the invalid rows are inserted with raw SQL (the domain type refuses to build them without diagnostics, and
 * the committed schema snapshot shows work_items has no CHECK on title length or root depth). Each fixture row
 * carries canonical timestamps and, for children, the parent_id/root_id/depth structural columns a valid child has.
 */
class P7TotalRowMapperTest {
    private val t: Instant = Instant.parse("2001-02-03T04:05:06.789Z")

    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val provider get() = sqliteDb.repositoryProvider()
    private val items get() = provider.itemStore()
    private val hierarchy get() = provider.hierarchyStore()
    private val jdbc get() = sqliteDb.jdbcUrl

    private fun insertRawItem(
        id: UUID,
        parentId: UUID?,
        rootId: UUID?,
        title: String,
        depth: Int
    ) {
        val ts = P7Raw.canon(t)
        P7Raw.exec(
            jdbc,
            "INSERT INTO work_items (id, parent_id, root_id, title, depth, created_at, modified_at, role_changed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id,
            parentId,
            rootId,
            title,
            depth,
            ts,
            ts,
            ts
        )
    }

    private suspend fun rootWithInvalidChild(): Triple<WorkItem, UUID, String> {
        val root = items.create(WorkItem(title = "Root"))
        val childId = UUID.randomUUID()
        val longTitle = "T".repeat(600)
        insertRawItem(childId, root.id, root.rootId ?: root.id, longTitle, depth = 1)
        return Triple(root, childId, longTitle)
    }

    @Test
    fun `S8 an invalid stored child is returned by every read path with diagnostics and nothing is skipped`(): Unit =
        runBlocking {
            val (root, childId, longTitle) = rootWithInvalidChild()

            val byId = assertNotNull(items.getById(childId), "getById must return the invalid row")
            assertEquals(longTitle, byId.title)
            val diagnostics = assertNotNull(byId.diagnostics, "an invalid row must carry diagnostics")
            assertTrue(diagnostics.isNotEmpty())
            assertTrue(diagnostics.any { it.contains("500") }, "diagnostics must mention the 500-character rule, got $diagnostics")

            assertEquals(listOf(childId), items.findChildren(root.id).map { it.id })
            assertEquals(listOf(childId), items.findByParent(root.id).map { it.id })
            assertEquals(listOf(childId), items.findByIds(setOf(childId)).map { it.id })
            assertEquals(listOf(childId), items.findByDepth(1).map { it.id })

            val filtered = items.findByFilters(parentId = root.id)
            assertEquals(listOf(childId), filtered.items.map { it.id })
            assertEquals(0, filtered.skipped, "nothing is skipped any more")
            assertEquals(1, items.countByFilters(parentId = root.id))
            assertEquals(mapOf(Role.QUEUE to 1), items.countChildrenByRole(root.id).filterValues { it > 0 })

            val scoped = items.findInScope(rootIds = setOf(root.rootId ?: root.id), parentId = root.id)
            assertEquals(listOf(childId), scoped.map { it.id })
        }

    @Test
    fun `S8 structural walks reach an invalid descendant`(): Unit =
        runBlocking {
            val (root, childId, _) = rootWithInvalidChild()

            assertTrue(hierarchy.findDescendants(root.id).any { it.id == childId }, "findDescendants must include the invalid child")
            assertTrue(childId in hierarchy.descendantIds(root.id), "descendantIds must include the invalid child")
            val chains = hierarchy.findAncestorChains(setOf(childId))
            val chain = assertNotNull(chains[childId], "an ancestor chain must exist for the invalid child")
            assertTrue(chain.any { it.id == root.id }, "the chain must reach the root, got ${chain.map { it.id }}")
        }

    @Test
    fun `S8 an invalid row cannot be written back while it is still invalid`(): Unit =
        runBlocking {
            val (_, childId, _) = rootWithInvalidChild()
            val invalid = assertNotNull(items.getById(childId))

            assertFailsWith<ValidationException> { items.update(invalid.copy(summary = "changed")) }

            assertEquals("", P7Raw.text(jdbc, "SELECT summary FROM work_items WHERE id = ?", childId), "the refused write must not persist")
        }

    @Test
    fun `S8 repairing the invalid field makes the row a clean valid item again`(): Unit =
        runBlocking {
            val (_, childId, _) = rootWithInvalidChild()
            val invalid = assertNotNull(items.getById(childId))

            val updated = items.update(invalid.copy(title = "Short title", diagnostics = null))

            assertNotNull(updated)
            val reread = assertNotNull(items.getById(childId))
            assertEquals("Short title", reread.title)
            assertNull(reread.diagnostics, "a valid row carries no diagnostics")
        }

    @Test
    fun `S8 a subtree containing an invalid row can be deleted`(): Unit =
        runBlocking {
            val (root, childId, _) = rootWithInvalidChild()

            val ids = hierarchy.descendantIds(root.id) + root.id
            val deleted = items.deleteAll(ids)

            assertEquals(2, deleted)
            assertNull(items.getById(childId))
            assertNull(items.getById(root.id))
        }

    @Test
    fun `S8 probe an invalid root with a non-zero depth is returned by the root listing`(): Unit =
        runBlocking {
            val badRootId = UUID.randomUUID()
            insertRawItem(badRootId, parentId = null, rootId = badRootId, title = "Bad root", depth = 3)

            val roots = items.findRootItems()

            val bad = assertNotNull(roots.items.singleOrNull { it.id == badRootId }, "an invalid root must be listed, not skipped")
            assertTrue(assertNotNull(bad.diagnostics).isNotEmpty())
            assertEquals(0, roots.skipped)
        }

    @Test
    fun `S8 probe a valid row has no diagnostics on read`(): Unit =
        runBlocking {
            val root = items.create(WorkItem(title = "Fine"))

            assertNull(assertNotNull(items.getById(root.id)).diagnostics)
        }
}

package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test authorship for item a19fcf0a (P12, needs-test-author): readers that moved onto the normalized
 * blocking graph. Scenarios S6 (get_next_item) and S12 (NextItemRecommender.isBlocked is fail-closed), plus probes.
 *
 * Oracles (frozen plan 3.4 and task-scope item 5a): `a IS_BLOCKED_BY b` means b blocks a, so a stays out of the
 * recommendations while b is below the edge's unblock threshold (terminal by default) and comes back once b reaches
 * it; the recommender is FAIL-CLOSED: a blocker that cannot be read counts as blocking (it was skipped, fail-open,
 * before the item).
 *
 * Response-shape evidence (public): `GetNextItemToolTest` (data.recommendations[].itemId), `NextItemRecommenderTest`
 * (the fail-closed expectation with a mocked getById returning null).
 *
 * EXISTING-SURFACE: S6 goes through GetNextItemTool.execute with real SQLite; S12 calls the existing
 * `isBlocked(item)` on the real repositories with only `getById` of the blocker overridden to return null.
 */
class DependencyNormalizationReadersTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var context: ToolExecutionContext
    private lateinit var nextTool: GetNextItemTool
    private lateinit var manageDeps: ManageDependenciesTool

    @BeforeEach
    fun setUp() {
        context = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
        nextTool = GetNextItemTool()
        manageDeps = ManageDependenciesTool()
    }

    private suspend fun item(
        title: String,
        role: Role = Role.QUEUE
    ): WorkItem = context.workItemRepository().create(WorkItem(title = title, role = role))

    private suspend fun link(
        from: UUID,
        to: UUID,
        type: String,
        unblockAt: String? = null
    ) {
        val spec =
            buildJsonObject {
                put("fromItemId", JsonPrimitive(from.toString()))
                put("toItemId", JsonPrimitive(to.toString()))
                put("type", JsonPrimitive(type))
                if (unblockAt != null) put("unblockAt", JsonPrimitive(unblockAt))
            }
        val result =
            manageDeps.execute(
                JsonObject(mapOf("operation" to JsonPrimitive("create"), "dependencies" to JsonArray(listOf(spec)))),
                context
            ) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "setup failed: $result")
    }

    private suspend fun recommendedIds(): Set<UUID> {
        val result = nextTool.execute(JsonObject(mapOf("limit" to JsonPrimitive(50))), context) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected success, got: $result")
        return (result["data"] as JsonObject)["recommendations"]!!
            .jsonArray
            .map { UUID.fromString(it.jsonObject["itemId"]!!.jsonPrimitive.content) }
            .toSet()
    }

    // ------------------------------------------------------------------
    // S6: get_next_item honors the direction of an IS_BLOCKED_BY input edge
    // ------------------------------------------------------------------

    @Test
    fun `S6 get_next_item excludes an item blocked via IS_BLOCKED_BY while the blocker is queued and keeps the blocker`(): Unit =
        runBlocking {
            val blocked = item("Blocked")
            val blocker = item("Blocker")
            link(blocked.id, blocker.id, "IS_BLOCKED_BY")

            val ids = recommendedIds()

            assertTrue(blocker.id in ids, "the blocker has nothing in its way: $ids")
            assertFalse(blocked.id in ids, "an item whose blocker is unfinished is not recommended: $ids")
        }

    @Test
    fun `S6 get_next_item recommends the blocked item once its blocker is terminal`(): Unit =
        runBlocking {
            val blocker = item("Done Blocker", role = Role.TERMINAL)
            val blocked = item("Blocked")
            link(blocked.id, blocker.id, "IS_BLOCKED_BY")

            val ids = recommendedIds()

            assertTrue(blocked.id in ids, "the blocker reached the default (terminal) threshold: $ids")
        }

    @Test
    fun `S6 probe an IS_BLOCKED_BY edge with unblockAt work is satisfied by a blocker in work but not one in queue`(): Unit =
        runBlocking {
            val activeBlocker = item("Active Blocker", role = Role.WORK)
            val blockedByActive = item("Blocked By Active")
            link(blockedByActive.id, activeBlocker.id, "IS_BLOCKED_BY", unblockAt = "work")

            val queuedBlocker = item("Queued Blocker")
            val blockedByQueued = item("Blocked By Queued")
            link(blockedByQueued.id, queuedBlocker.id, "IS_BLOCKED_BY", unblockAt = "work")

            val ids = recommendedIds()

            assertTrue(blockedByActive.id in ids, "work satisfies a work threshold: $ids")
            assertFalse(blockedByQueued.id in ids, "queue is below a work threshold: $ids")
        }

    @Test
    fun `S6 probe the blocker of an IS_BLOCKED_BY edge is not itself held back by the edge`(): Unit =
        runBlocking {
            val blocker = item("Blocker")
            val blocked = item("Blocked")
            link(blocked.id, blocker.id, "IS_BLOCKED_BY")
            // the same relationship written as BLOCKS must read identically
            val blocker2 = item("Blocker 2")
            val blocked2 = item("Blocked 2")
            link(blocker2.id, blocked2.id, "BLOCKS")

            val ids = recommendedIds()

            assertEquals(setOf(blocker.id, blocker2.id), ids.intersect(setOf(blocker.id, blocked.id, blocker2.id, blocked2.id)))
        }

    // ------------------------------------------------------------------
    // S12: NextItemRecommender.isBlocked is fail-closed
    // ------------------------------------------------------------------

    /** Delegates to the real repository but reports one item as unreadable. */
    private class UnreadableItemRepository(
        private val delegate: WorkItemRepository,
        private val unreadable: UUID
    ) : WorkItemRepository by delegate {
        override suspend fun getById(id: UUID): WorkItem? = if (id == unreadable) null else delegate.getById(id)
    }

    @Test
    fun `S12 isBlocked is true when the BLOCKS blocker cannot be read, and false when it is readable and terminal`(): Unit =
        runBlocking {
            val blocker = item("Terminal Blocker", role = Role.TERMINAL)
            val blocked = item("Blocked")
            link(blocker.id, blocked.id, "BLOCKS")
            val readable = NextItemRecommender(context.workItemRepository(), context.dependencyRepository())
            val unreadable =
                NextItemRecommender(UnreadableItemRepository(context.workItemRepository(), blocker.id), context.dependencyRepository())

            assertFalse(readable.isBlocked(blocked), "control: a terminal blocker satisfies the default threshold")
            assertTrue(unreadable.isBlocked(blocked), "an unreadable blocker counts as blocking (fail-closed)")
        }

    @Test
    fun `S12 isBlocked is true when the blocker of an IS_BLOCKED_BY input cannot be read`(): Unit =
        runBlocking {
            val blocker = item("Terminal Blocker", role = Role.TERMINAL)
            val blocked = item("Blocked")
            link(blocked.id, blocker.id, "IS_BLOCKED_BY")
            val readable = NextItemRecommender(context.workItemRepository(), context.dependencyRepository())
            val unreadable =
                NextItemRecommender(UnreadableItemRepository(context.workItemRepository(), blocker.id), context.dependencyRepository())

            assertFalse(readable.isBlocked(blocked), "control: a terminal blocker satisfies the default threshold")
            assertTrue(unreadable.isBlocked(blocked), "an unreadable blocker counts as blocking (fail-closed)")
        }

    @Test
    fun `S12 probe an unreadable item that is only the blocked side does not block anything`(): Unit =
        runBlocking {
            val blocker = item("Terminal Blocker", role = Role.TERMINAL)
            val blocked = item("Blocked")
            link(blocker.id, blocked.id, "BLOCKS")
            val recommender =
                NextItemRecommender(UnreadableItemRepository(context.workItemRepository(), blocked.id), context.dependencyRepository())

            assertFalse(
                recommender.isBlocked(blocked),
                "only an unreadable BLOCKER is fail-closed; the blocked item itself is the argument"
            )
        }

    @Test
    fun `S12 probe an item with only a RELATES_TO edge to an unreadable item is not blocked`(): Unit =
        runBlocking {
            val related = item("Related", role = Role.TERMINAL)
            val subject = item("Subject")
            val edge =
                buildJsonObject {
                    put("fromItemId", JsonPrimitive(related.id.toString()))
                    put("toItemId", JsonPrimitive(subject.id.toString()))
                    put("type", JsonPrimitive("RELATES_TO"))
                }
            manageDeps.execute(
                JsonObject(mapOf("operation" to JsonPrimitive("create"), "dependencies" to JsonArray(listOf(edge)))),
                context
            )
            val recommender =
                NextItemRecommender(UnreadableItemRepository(context.workItemRepository(), related.id), context.dependencyRepository())

            assertFalse(recommender.isBlocked(subject), "RELATES_TO carries no blocking semantics")
        }
}

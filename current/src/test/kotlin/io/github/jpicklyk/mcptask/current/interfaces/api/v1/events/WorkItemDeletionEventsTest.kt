package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.tools.items.WorkItemDeleteOutcome
import io.github.jpicklyk.mcptask.current.application.tools.items.WorkItemDeletion
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-016 T6: level-batched recursive delete still publishes one ITEM_DELETED per row, on commit. */
class WorkItemDeletionEventsTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val repositoryProvider get() = db.repositoryProvider()

    @Test
    fun `T6 recursive delete publishes one item deleted per row including the root`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(repositoryProvider, bus)
            val repo = provider.workItemRepository()
            val root = repo.create(WorkItem(title = "r", depth = 0))
            val a = repo.create(WorkItem(parentId = root.id, title = "a", depth = 1))
            val b = repo.create(WorkItem(parentId = root.id, title = "b", depth = 1))
            val c = repo.create(WorkItem(parentId = a.id, title = "c", depth = 2))

            val flow = bus.subscribe("t6", emptySet(), lastEventId = null)
            val outcome = WorkItemDeletion(provider, db.unitOfWork()).delete(root.id, recursive = true)
            assertEquals(WorkItemDeleteOutcome.Deleted(root.id, 3), outcome)

            val events = bus.drainDelivered("t6", flow).filter { it.event == ApiEventType.ITEM_DELETED }
            assertEquals(4, events.size, "expected one ITEM_DELETED per deleted row, got: $events")
            assertEquals(listOf(root, a, b, c).map { it.id.toString() }.toSet(), events.map { it.itemId }.toSet())
        }

    @Test
    fun `T6 a recursive delete of a missing root publishes nothing`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(repositoryProvider, bus)
            val flow = bus.subscribe("t6b", emptySet(), lastEventId = null)

            assertTrue(
                WorkItemDeletion(provider, db.unitOfWork()).delete(UUID.randomUUID(), recursive = true) is WorkItemDeleteOutcome.NotFound
            )

            assertEquals(0, bus.drainDelivered("t6b", flow).size)
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.OutsideUnitPolicy
import io.github.jpicklyk.mcptask.current.infrastructure.database.OutsideUnitWriteException
import io.github.jpicklyk.mcptask.current.infrastructure.database.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent P5b tests (item c01d2e90): S10 (no `item.created` event for a unit that rolls back; the control commits
 * and publishes) and probe P1 (a decorated write outside a unit under OutsideUnitPolicy.FAIL throws and publishes
 * nothing).
 *
 * Oracle: api-rest.md event contract as quoted by the existing EventPublishingTransactionRollbackTest (a data event
 * denotes a persisted change) plus task-scope section 6 (FAIL throws before any work). The decorator's events are
 * observed through ApiEventBus.ringBufferSnapshot() against a baseline.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class P5bEventsOnRollbackTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val managers = mutableListOf<DatabaseManager>()

    @AfterEach
    fun tearDown() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    private fun rawExec(sql: String) {
        DriverManager.getConnection(db.jdbcUrl).use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun rawCount(table: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM $table").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun createdEventsAfter(
        bus: ApiEventBus,
        baseline: Int
    ) = bus.ringBufferSnapshot().drop(baseline).filter { it.event == ApiEventType.ITEM_CREATED }

    private fun unitOver(provider: EventPublishingRepositoryProvider) =
        SqliteUnitOfWork(db.databaseManager, provider, Clock { Instant.now() })

    @Test
    fun `S10 a unit that creates through the decorated provider and returns Err publishes no item_created and leaves no row`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(db.repositoryProvider(), bus)
            val baseline = bus.ringBufferSnapshot().size

            val result =
                unitOver(provider).write<Unit>("S10.err") {
                    repositories.workItemRepository().create(WorkItem(title = "S10 rolled back", depth = 0))
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "domain failure after the create"))
                }

            assertIs<Outcome.Err>(result)
            assertEquals(0, rawCount("work_items"), "the create must have been rolled back")
            assertEquals(emptyList(), createdEventsAfter(bus, baseline), "a rolled-back create must not publish")
        }

    @Test
    fun `S10 a unit whose later write faults publishes no item_created and leaves no row`(): Unit =
        runBlocking {
            rawExec(
                "CREATE TRIGGER s10_fault BEFORE INSERT ON work_items WHEN NEW.title = 'S10 boom' BEGIN SELECT RAISE(ABORT, 'inj'); END"
            )
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(db.repositoryProvider(), bus)
            val baseline = bus.ringBufferSnapshot().size

            val result =
                unitOver(provider).write<Unit>("S10.fault") {
                    repositories.workItemRepository().create(WorkItem(title = "S10 first", depth = 0))
                    repositories.workItemRepository().create(WorkItem(title = "S10 boom", depth = 0))
                    Outcome.Ok(Unit)
                }

            val err = assertIs<Outcome.Err>(result, "the fault must fail the unit: $result")
            assertTrue("inj" in err.error.message, "innermost SQL text kept: ${err.error.message}")
            assertEquals(0, rawCount("work_items"))
            assertEquals(emptyList(), createdEventsAfter(bus, baseline))
        }

    @Test
    fun `S10 control - the same unit returning Ok commits the row and publishes exactly one item_created`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(db.repositoryProvider(), bus)
            val baseline = bus.ringBufferSnapshot().size

            val result =
                unitOver(provider).write("S10.ok") {
                    val created = repositories.workItemRepository().create(WorkItem(title = "S10 committed", depth = 0))
                    Outcome.Ok(created.id)
                }

            val id = assertIs<Outcome.Ok<java.util.UUID>>(result).value
            assertEquals(1, rawCount("work_items"))
            val events = createdEventsAfter(bus, baseline)
            assertEquals(1, events.size, "exactly one item.created after commit: $events")
            assertEquals(id.toString(), events[0].itemId)
        }

    @Test
    fun `P1 a decorated write outside a unit under FAIL throws, writes nothing and publishes nothing`(): Unit =
        runBlocking {
            val failing =
                DatabaseManager(appConfig = AppConfig.fromEnv { null }, outsideUnitPolicy = OutsideUnitPolicy.FAIL).also {
                    assertTrue(it.initialize(db.jdbcUrl))
                    managers += it
                }
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(DefaultRepositoryProvider(failing), bus)
            val baseline = bus.ringBufferSnapshot().size

            assertFailsWith<OutsideUnitWriteException> {
                provider.workItemRepository().create(WorkItem(title = "P1 outside", depth = 0))
            }

            assertEquals(0, rawCount("work_items"))
            assertEquals(emptyList(), bus.ringBufferSnapshot().drop(baseline), "no event of any kind may be published")
        }

    @Test
    fun `P1 control - the same decorated write inside a unit under FAIL commits and publishes`(): Unit =
        runBlocking {
            val failing =
                DatabaseManager(appConfig = AppConfig.fromEnv { null }, outsideUnitPolicy = OutsideUnitPolicy.FAIL).also {
                    assertTrue(it.initialize(db.jdbcUrl))
                    managers += it
                }
            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(DefaultRepositoryProvider(failing), bus)
            val baseline = bus.ringBufferSnapshot().size

            SqliteUnitOfWork(failing, provider, Clock { Instant.now() }).write("P1.control") {
                repositories.workItemRepository().create(WorkItem(title = "P1 inside", depth = 0))
                Outcome.Ok(Unit)
            }

            assertEquals(1, rawCount("work_items"))
            assertEquals(1, createdEventsAfter(bus, baseline).size)
        }
}

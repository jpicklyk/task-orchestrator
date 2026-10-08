package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.OutsideUnitPolicy
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Independent P8 test S14 (item ea2b9b63): a row appended through a SECOND database manager over the same file (the
 * stand-in for another server process) reaches a live SSE subscriber of the first process through the tail poll.
 *
 * Oracle: plan section 3.7 -- another process's write streams to subscribers via a poll of about one second -- and the
 * test-plan bound for S14 (reaches a live subscriber within about 2 s). This test is wall-clock dependent, so it is
 * tagged serial and uses a generous 8 s ceiling for the assertion and a 20 s hang guard; it asserts delivery, the seq
 * as id, and that the poll interval is not absurd, never an upper bound tighter than 8 s.
 */
@Tag("serial")
class EventCrossProcessSseTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun otherProcess(): DatabaseManager =
        DatabaseManager(appConfig = AppConfig.fromEnv { null }, outsideUnitPolicy = OutsideUnitPolicy.IMPLICIT).also {
            check(it.initialize(db.jdbcUrl)) { "second manager failed to open ${db.jdbcUrl}" }
        }

    private fun foreignRecord(
        rootId: UUID,
        itemId: UUID,
    ) = EventRecord(
        id = UUID.randomUUID(),
        occurredAt = Instant.now(),
        rootId = rootId,
        entityKind = DomainEvent.KIND_ITEM,
        entityId = itemId,
        type = DomainEvent.ITEM_UPDATED,
        data = """{"changedFields":["title"]}""",
    )

    @Test
    fun `S14 a row appended by another database manager reaches a live subscriber via the tail poll`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 1000, source = db.repositoryProvider().eventStore())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val other = otherProcess()
            try {
                bus.startTailer(scope)
                val rootId = UUID.randomUUID()
                val itemId = UUID.randomUUID()
                val flow = bus.subscribe("s14", emptySet(), lastEventId = null, resumeRequested = false)
                val received = async(Dispatchers.IO) { withTimeout(20.seconds) { flow.first() } }
                delay(300)

                val startedAt = System.nanoTime()
                val appended =
                    DefaultRepositoryProvider(other, Clock.SYSTEM)
                        .eventStore()
                        .append(listOf(foreignRecord(rootId, itemId)))
                        .single()
                val event = received.await()
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

                assertEquals(appended.seq, event.id, "the SSE id is the row's seq")
                assertEquals(ApiEventType.ITEM_UPDATED, event.event)
                assertEquals(itemId.toString(), event.itemId)
                assertEquals(rootId.toString(), event.rootId)
                assertTrue(elapsedMs < 8_000, "a foreign write must stream within the poll bound, took $elapsedMs ms")
            } finally {
                bus.unsubscribe("s14")
                scope.cancel()
                other.shutdown()
            }
        }

    @Test
    fun `S14 foreign rows reach only subscribers of their root and arrive once and in order`(): Unit =
        runBlocking {
            val bus = ApiEventBus(bufferSize = 1000, source = db.repositoryProvider().eventStore())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val other = otherProcess()
            try {
                bus.startTailer(scope)
                val mine = UUID.randomUUID()
                val theirs = UUID.randomUUID()
                val flow = bus.subscribe("s14b", setOf(mine), lastEventId = null, resumeRequested = false)
                val received =
                    async(Dispatchers.IO) {
                        withTimeout(20.seconds) {
                            val out = mutableListOf<ApiEvent>()
                            flow.collect {
                                out += it
                            }
                            out
                        }
                    }
                delay(300)
                val store = DefaultRepositoryProvider(other, Clock.SYSTEM).eventStore()
                val wanted1 = store.append(listOf(foreignRecord(mine, UUID.randomUUID()))).single()
                store.append(listOf(foreignRecord(theirs, UUID.randomUUID())))
                val wanted2 = store.append(listOf(foreignRecord(mine, UUID.randomUUID()))).single()

                delay(4_000)
                bus.unsubscribe("s14b")
                val got = received.await()

                assertEquals(listOf(wanted1.seq, wanted2.seq), got.map { it.id }, "only the subscriber's root, once each, ascending: $got")
            } finally {
                scope.cancel()
                other.shutdown()
            }
        }
}

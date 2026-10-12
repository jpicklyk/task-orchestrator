package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Independent tests (test-author seat, item a7fa21a6, Q28b) for scenario S4 of the frozen test-plan: rows the V29 backfill
 * copies into `events` sit BELOW [EventStore.SEQ_FLOOR] and must never reach the stream, and a table holding only such rows
 * must not make every resume answer `sync.lost`.
 *
 * Oracles (frozen task-scope section 1 "Floor", AC3, D3, D11, migration-assessment s7; none read from an implementation):
 * with a backfill-only table `maxSeq()` is the floor, so an SSE resume carrying `Last-Event-ID` equal to the floor is a
 * cursor at the live high-water mark: no `sync.lost` frame, and the next live event is delivered; a connection without a
 * cursor never replays a backfilled row; every live event's id is above the floor. The bus is built over the REAL SQLite
 * store (a [SqliteTestDatabase] copy of the migrated template) AFTER the backfilled rows were inserted, because the
 * high-water mark is read from the table.
 *
 * Fixture condition for the negative assertions: without the floor `maxSeq()` would be 3 (the largest backfilled seq), so a
 * resume at the floor would be a cursor beyond the high-water mark and the first frame would be `sync.lost`; and `readAfter`
 * from a cursor below the floor would stream seqs 1..3. Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EventSeqFloorTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val floor = EventStore.SEQ_FLOOR
    private val token = "seq-floor-test-token-q28b"
    private val replayJson = Json { ignoreUnknownKeys = true }

    private fun idBytes(id: UUID): ByteArray =
        ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    /** Three backfilled `item.transitioned` rows at seq 1..3, exactly the shape the migration writes (below the floor). */
    private fun backfill() {
        val root = UUID.randomUUID()
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c
                .prepareStatement(
                    "INSERT INTO events (seq, id, occurred_at, root_id, entity_kind, entity_id, type, data) VALUES (?,?,?,?,?,?,?,?)"
                ).use { ps ->
                    for (seq in 1L..3L) {
                        ps.setLong(1, seq)
                        ps.setBytes(2, idBytes(UUID.randomUUID()))
                        ps.setString(3, "2026-03-01 10:15:3$seq.123")
                        ps.setBytes(4, idBytes(root))
                        ps.setString(5, DomainEvent.KIND_ITEM)
                        ps.setBytes(6, idBytes(UUID.randomUUID()))
                        ps.setString(7, DomainEvent.ITEM_TRANSITIONED)
                        ps.setString(8, "{\"backfilled\":true}")
                        ps.executeUpdate()
                    }
                }
        }
    }

    @Test
    fun `S4 a resume at the floor over a backfill-only table gets no sync_lost and receives the next live event`(): Unit =
        runBlocking {
            backfill()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val live = UUID.randomUUID()

            val flow = bus.subscribe("floor-resume", emptySet(), lastEventId = floor)
            val collected = async { withTimeout(5.seconds) { flow.take(1).toList() } }
            delay(150)
            bus.emit(ApiEventType.ITEM_CREATED, itemId = live)

            val first = collected.await().single()
            assertNotEquals(ApiEventType.SYNC_LOST, first.event, "a cursor at the floor is not beyond the high-water mark: $first")
            assertEquals(live.toString(), first.itemId, "the first frame is the live event")
            assertTrue(first.id > floor, "a live event id is above the floor: ${first.id}")
            bus.unsubscribe("floor-resume")
        }

    @Test
    fun `S4 a connection without a cursor never replays a backfilled row and still receives live events`(): Unit =
        runBlocking {
            backfill()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val live = UUID.randomUUID()

            val flow = bus.subscribe("floor-fresh", emptySet(), lastEventId = null)
            val collected = mutableListOf<ApiEvent>()
            val job = launch { withTimeoutOrNull(1_500) { flow.collect { collected += it } } }
            delay(200)
            bus.emit(ApiEventType.ITEM_CREATED, itemId = live)
            job.join()

            assertEquals(listOf(live.toString()), collected.map { it.itemId }, "only the live event arrives: $collected")
            assertTrue(collected.none { it.id in 1L..3L }, "no backfilled seq is streamed: ${collected.map { it.id }}")
            bus.unsubscribe("floor-fresh")
        }

    @Test
    fun `S4 over HTTP a Last-Event-ID equal to the floor on a backfill-only table gets no sync_lost and then the live event`(): Unit =
        testApplication {
            backfill()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    eventRoutes(
                        bus,
                        tokenEntries(),
                        allowQueryToken = false,
                        authCheckIntervalSeconds = 60,
                        workItemRepository = null,
                        redactAttribution = true
                    )
                }
            }
            val sseClient = createClient { install(ClientSSE) }
            val live = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(300)
                        bus.emit(ApiEventType.ITEM_CREATED, itemId = live)
                    }
                    sseClient.sse(
                        urlString = "/events",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header("Last-Event-ID", floor.toString())
                        }
                    ) {
                        collected.addAll(
                            incoming.take(1).toList().map { replayJson.decodeFromString(ApiEvent.serializer(), it.data.orEmpty()) }
                        )
                    }
                }
            }

            val first = collected.single()
            assertNotEquals(ApiEventType.SYNC_LOST, first.event, "the first frame must not be sync.lost: $first")
            assertEquals(live.toString(), first.itemId)
            assertTrue(first.id > floor)
        }

    private fun tokenEntries(): Map<HashBytes, BearerTokenStore.TokenEntry> {
        val principal =
            ApiPrincipal(
                tokenId = "seq-floor-test-principal",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER
            )
        return mapOf(HashBytes(sha256(token)) to BearerTokenStore.TokenEntry(principal, expiresAt = null))
    }
}

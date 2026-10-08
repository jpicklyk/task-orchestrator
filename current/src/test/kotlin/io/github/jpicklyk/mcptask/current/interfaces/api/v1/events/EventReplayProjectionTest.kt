package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.service.EventRecorder
import io.github.jpicklyk.mcptask.current.domain.event.DeleteCause
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.ReparentSide
import io.github.jpicklyk.mcptask.current.domain.event.TransitionOrigin
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
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Independent P8 replay and projection tests (item ea2b9b63) over the real SQLite event store: S11 (the
 * Last-Event-ID gap rules), S13 (replay then live, no duplicate and no gap), the F7 projection table and the SSE
 * header probes.
 *
 * Oracles: carry-in F2 as refined (a cursor EQUAL to the floor is valid; unparsable, above maxSeq, or STRICTLY below
 * the floor is `unknown_event_id`), F3 (`maxSeq - cursor > window` is `buffer_evicted`; the sentinel id is
 * `max(floor, maxSeq - window)`; replay then continues strictly after it), F7 (only the 3.x names are projected;
 * rejected, lease, config and plan-document rows stay table-only), plan section 3.7 (SSE `id` is the seq; replay equals
 * live) and the documented `ApiEvent` shape. No expected value is read from the implementation.
 */
class EventReplayProjectionTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val store: EventStore get() = db.repositoryProvider().eventStore()

    private val floor = EventStore.SEQ_FLOOR

    private fun busWith(window: Int) = ApiEventBus(bufferSize = window, source = store)

    /** Records [n] rows through the production projection wiring; returns their projections (ids are the seqs). */
    private suspend fun seed(
        bus: ApiEventBus,
        n: Int,
    ): List<ApiEvent> = List(n) { bus.emit(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID()) }

    /** Collects every frame that arrives within [ms]; replay frames are available at once, so a short window is enough. */
    private suspend fun collectFor(
        flow: Flow<ApiEvent>,
        ms: Long = 600,
    ): List<ApiEvent> {
        val out = CopyOnWriteArrayList<ApiEvent>()
        coroutineScope {
            val job = launch { flow.collect { out.add(it) } }
            delay(ms)
            job.cancel()
        }
        return out.toList()
    }

    private suspend fun subscribeAndCollect(
        bus: ApiEventBus,
        lastEventId: Long?,
        resume: Boolean = lastEventId != null,
        id: String = "sub-${UUID.randomUUID()}",
    ): List<ApiEvent> {
        val flow = bus.subscribe(id, emptySet(), lastEventId, resume)
        return try {
            collectFor(flow)
        } finally {
            bus.unsubscribe(id)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // S11 -- gap rules
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 an id equal to the floor is valid and replays the whole log without a sentinel`(): Unit =
        runBlocking {
            val bus = busWith(100)
            val rows = seed(bus, 5)

            val got = subscribeAndCollect(bus, floor)

            assertEquals(rows.map { it.id }, got.map { it.id }, "the floor cursor replays every row: $got")
            assertTrue(got.none { it.event == ApiEventType.SYNC_LOST })
            assertTrue(got.first().id > floor, "ids are seqs above the floor")
        }

    @Test
    fun `S11 an id strictly below the floor yields unknown_event_id with the floor sentinel then the replay`(): Unit =
        runBlocking {
            val bus = busWith(100)
            val rows = seed(bus, 5)

            for (stale in listOf(floor - 1, 57L, 0L)) {
                val got = subscribeAndCollect(bus, stale)
                assertEquals(ApiEventType.SYNC_LOST, got.first().event, "cursor $stale: sentinel first: $got")
                assertEquals(SyncLostReason.UNKNOWN_EVENT_ID, got.first().reason, "cursor $stale")
                assertNull(got.first().itemId)
                assertEquals(floor, got.first().id, "sentinel id = max(floor, maxSeq - window) = floor here (cursor $stale)")
                assertEquals(rows.map { it.id }, got.drop(1).map { it.id }, "cursor $stale: replay continues after the sentinel")
            }
        }

    @Test
    fun `S11 an id above the newest seq or an unparsable resume yields unknown_event_id`(): Unit =
        runBlocking {
            val bus = busWith(100)
            val rows = seed(bus, 3)
            val maxSeq = rows.last().id

            for (ahead in listOf(maxSeq + 1, Long.MAX_VALUE)) {
                val got = subscribeAndCollect(bus, ahead)
                assertEquals(ApiEventType.SYNC_LOST, got.first().event, "cursor $ahead: $got")
                assertEquals(SyncLostReason.UNKNOWN_EVENT_ID, got.first().reason, "cursor $ahead")
            }
            val unparsable = subscribeAndCollect(bus, lastEventId = null, resume = true)
            assertEquals(SyncLostReason.UNKNOWN_EVENT_ID, unparsable.first().reason, "a resume request without a usable id: $unparsable")

            // Control: the newest seq itself is valid, replays nothing, and does not produce a sentinel.
            val atNewest = subscribeAndCollect(bus, maxSeq)
            assertEquals(emptyList(), atNewest, "cursor == maxSeq is valid and has nothing to replay: $atNewest")
        }

    @Test
    fun `S11 the replay window boundary - behind by exactly the window is valid, one more is buffer_evicted`(): Unit =
        runBlocking {
            val bus = busWith(4)
            val rows = seed(bus, 10)
            val maxSeq = rows.last().id

            val atEdge = subscribeAndCollect(bus, maxSeq - 4)
            assertEquals(rows.takeLast(4).map { it.id }, atEdge.map { it.id }, "behind by exactly the window: replay, no sentinel: $atEdge")
            assertTrue(atEdge.none { it.event == ApiEventType.SYNC_LOST })

            val beyond = subscribeAndCollect(bus, maxSeq - 5)
            assertEquals(ApiEventType.SYNC_LOST, beyond.first().event, "$beyond")
            assertEquals(SyncLostReason.BUFFER_EVICTED, beyond.first().reason)
            assertEquals(maxSeq - 4, beyond.first().id, "sentinel id = max(floor, maxSeq - window)")
            assertEquals(rows.takeLast(4).map { it.id }, beyond.drop(1).map { it.id }, "replay continues strictly after the sentinel")
            assertTrue(beyond.drop(1).all { it.id > beyond.first().id })
        }

    @Test
    fun `S11 the floor cursor on a log longer than the window is buffer_evicted with a sentinel above the floor`(): Unit =
        runBlocking {
            val bus = busWith(4)
            val rows = seed(bus, 10)

            val got = subscribeAndCollect(bus, floor)

            assertEquals(SyncLostReason.BUFFER_EVICTED, got.first().reason, "$got")
            assertEquals(rows.last().id - 4, got.first().id)
            assertTrue(got.first().id > floor)
        }

    @Test
    fun `S11 a window of zero keeps no replay at all`(): Unit =
        runBlocking {
            val bus = busWith(0)
            val rows = seed(bus, 3)
            val maxSeq = rows.last().id

            val behind = subscribeAndCollect(bus, maxSeq - 1)
            assertEquals(SyncLostReason.BUFFER_EVICTED, behind.first().reason, "$behind")
            assertEquals(maxSeq, behind.first().id, "sentinel id = max(floor, maxSeq - 0) = maxSeq")
            assertEquals(1, behind.size, "nothing replays past the sentinel: $behind")

            assertEquals(emptyList(), subscribeAndCollect(bus, maxSeq), "cursor == maxSeq has nothing to replay and is valid")
        }

    @Test
    fun `S11 an empty log - the floor is valid and anything below it is unknown_event_id with the floor sentinel`(): Unit =
        runBlocking {
            val bus = busWith(100)

            assertEquals(emptyList(), subscribeAndCollect(bus, floor))
            val below = subscribeAndCollect(bus, floor - 1)
            assertEquals(SyncLostReason.UNKNOWN_EVENT_ID, below.single().reason, "$below")
            assertEquals(floor, below.single().id)
        }

    @Test
    fun `S11 without a resume request there is no replay and no sentinel and only live events arrive`(): Unit =
        runBlocking {
            val bus = busWith(100)
            seed(bus, 3)
            val flow = bus.subscribe("live-only", emptySet(), lastEventId = null, resumeRequested = false)
            val live = CopyOnWriteArrayList<ApiEvent>()
            coroutineScope {
                val job = launch { flow.collect { live.add(it) } }
                delay(200)
                val fresh = bus.emit(ApiEventType.ITEM_UPDATED, itemId = UUID.randomUUID())
                delay(400)
                job.cancel()
                assertEquals(listOf(fresh.id), live.map { it.id }, "only the event emitted after subscribing: $live")
            }
            bus.unsubscribe("live-only")
        }

    // ---------------------------------------------------------------------------------------------
    // S13 -- replay then live: id == seq, no duplicate, no gap
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S13 reconnecting at a cursor delivers exactly the later rows with id equal to seq across replay and live`(): Unit =
        runBlocking {
            val bus = busWith(1000)
            val first = seed(bus, 5)
            val cursor = first[1].id
            val flow = bus.subscribe("s13", emptySet(), lastEventId = cursor, resumeRequested = true)

            val live = mutableListOf<ApiEvent>()
            val got =
                withTimeout(20.seconds) {
                    coroutineScope {
                        launch {
                            delay(150)
                            repeat(3) { live += bus.emit(ApiEventType.ITEM_UPDATED, itemId = UUID.randomUUID()) }
                        }
                        flow.take(6).toList()
                    }
                }
            bus.unsubscribe("s13")

            assertEquals(
                first.drop(2).map { it.id } + live.map { it.id },
                got.map { it.id },
                "replay (3) then live (3), in order, no duplicate"
            )
            val tableSeqs = store.readAfter(cursor, null, 100).map { it.seq }
            assertEquals(tableSeqs, got.map { it.id }, "the id of every delivered frame is the seq of its row")
        }

    @Test
    fun `S13 live rows written while an old log is being replayed arrive exactly once and in order`(): Unit =
        runBlocking {
            val bus = busWith(10_000)
            val initial = seed(bus, 60)
            val flow = bus.subscribe("s13b", emptySet(), lastEventId = floor, resumeRequested = true)

            val live = mutableListOf<ApiEvent>()
            val got =
                withTimeout(30.seconds) {
                    coroutineScope {
                        launch { repeat(40) { live += bus.emit(ApiEventType.ITEM_UPDATED, itemId = UUID.randomUUID()) } }
                        flow.take(100).toList()
                    }
                }
            bus.unsubscribe("s13b")

            val ids = got.map { it.id }
            assertEquals(100, ids.size)
            assertEquals(ids.sorted(), ids, "ascending")
            assertEquals(ids.toSet().size, ids.size, "no duplicate across the replay to live boundary")
            assertEquals((initial.map { it.id } + live.map { it.id }), ids, "no gap either: every row of the log, once")
        }

    @Test
    fun `S13 a replay larger than one page is delivered completely and in order`(): Unit =
        runBlocking {
            val bus = busWith(5000)
            val root = UUID.randomUUID()
            val now = Instant.now()
            val appended =
                store.append(
                    List(1100) {
                        val entity = UUID.randomUUID()
                        EventRecord(
                            id = UUID.randomUUID(),
                            occurredAt = now,
                            rootId = root,
                            entityKind = DomainEvent.KIND_ITEM,
                            entityId = entity,
                            type = DomainEvent.ITEM_UPDATED,
                            data = """{"changedFields":["title"]}""",
                        )
                    },
                )
            assertEquals(1100, appended.size)
            val flow = bus.subscribe("paged", emptySet(), lastEventId = floor, resumeRequested = true)

            val got = withTimeout(30.seconds) { flow.take(1100).toList() }
            bus.unsubscribe("paged")

            assertEquals(appended.map { it.seq }, got.map { it.id }, "every row, once, ascending, across the page boundary")
        }

    @Test
    fun `S13 a root-scoped subscriber replays only its roots rows`(): Unit =
        runBlocking {
            val bus = busWith(100)
            val rootA = UUID.randomUUID()
            val rootB = UUID.randomUUID()
            val a1 = bus.emit(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), rootId = rootA)
            bus.emit(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), rootId = rootB)
            val a2 = bus.emit(ApiEventType.ITEM_UPDATED, itemId = UUID.randomUUID(), rootId = rootA)
            val flow = bus.subscribe("scoped", setOf(rootA), lastEventId = floor, resumeRequested = true)

            val got = collectFor(flow)
            bus.unsubscribe("scoped")

            assertEquals(listOf(a1.id, a2.id), got.map { it.id })
            assertTrue(got.all { it.rootId == rootA.toString() })
        }

    // ---------------------------------------------------------------------------------------------
    // F7 -- the projection table
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `F7 only the 3x names are projected and every other row type stays table-only`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val itemId = UUID.randomUUID()
            val other = UUID.randomUUID()
            val noteId = UUID.randomUUID()
            val depId = UUID.randomUUID()
            val recorder = EventRecorder(store)

            fun projectedOf(event: DomainEvent): ApiEvent? = ApiEventBus.project(runBlocking { recorder.record(listOf(event)) }.single())

            val created = projectedOf(DomainEvent.ItemCreated(itemId, rootId, null))!!
            assertEquals(ApiEventType.ITEM_CREATED, created.event)
            assertEquals(itemId.toString(), created.itemId)
            assertEquals(rootId.toString(), created.rootId)

            assertEquals(ApiEventType.ITEM_UPDATED, projectedOf(DomainEvent.ItemUpdated(itemId, rootId, listOf("title")))!!.event)
            assertEquals(ApiEventType.ITEM_DELETED, projectedOf(DomainEvent.ItemDeleted(itemId, rootId))!!.event)

            val advanced =
                projectedOf(DomainEvent.ItemTransitioned(itemId, rootId, "start", "queue", "work", null, null, TransitionOrigin.USER))!!
            assertEquals(ApiEventType.ITEM_ADVANCED, advanced.event)
            assertEquals("work", advanced.newRole, "newRole is the toRole")
            assertEquals(itemId.toString(), advanced.itemId)

            assertEquals(
                ApiEventType.SCOPE_LEFT,
                projectedOf(DomainEvent.ItemReparented(itemId, rootId, ReparentSide.LEFT, rootId, other))!!.event
            )
            assertEquals(
                ApiEventType.SCOPE_ENTERED,
                projectedOf(DomainEvent.ItemReparented(itemId, other, ReparentSide.ENTERED, rootId, other))!!.event
            )

            val noteUp = projectedOf(DomainEvent.NoteUpserted(noteId, rootId, itemId, "k", "work", 3))!!
            assertEquals(ApiEventType.NOTE_UPSERTED, noteUp.event)
            assertEquals(itemId.toString(), noteUp.itemId, "a note event is about its item, not the note id")
            val noteDel = projectedOf(DomainEvent.NoteDeleted(noteId, rootId, itemId, "k", "work", DeleteCause.EXPLICIT))!!
            assertEquals(ApiEventType.NOTE_DELETED, noteDel.event)
            assertEquals(itemId.toString(), noteDel.itemId)

            val depAdd = projectedOf(DomainEvent.DependencyAdded(depId, rootId, itemId, other, "blocks", null))!!
            assertEquals(ApiEventType.DEPENDENCY_ADDED, depAdd.event)
            assertEquals(itemId.toString(), depAdd.itemId, "a dependency event is about its from item")
            assertEquals(
                ApiEventType.DEPENDENCY_REMOVED,
                projectedOf(DomainEvent.DependencyRemoved(depId, rootId, itemId, other, "blocks", null, DeleteCause.CASCADE))!!.event,
            )

            assertEquals(ApiEventType.ITEM_UPDATED, projectedOf(DomainEvent.ClaimAcquired(itemId, rootId, "agent", 60))!!.event)
            assertEquals(
                ApiEventType.ITEM_UPDATED,
                projectedOf(
                    DomainEvent.ClaimReleased(itemId, rootId, io.github.jpicklyk.mcptask.current.domain.event.ClaimReleaseReason.CLEARED)
                )!!.event,
            )

            val tableOnly =
                listOf(
                    DomainEvent.TransitionRejected(itemId, rootId, "start", "gate_blocked"),
                    DomainEvent.ClaimRejected(itemId, rootId, 10L),
                    DomainEvent.ClaimExpired(itemId, rootId),
                    DomainEvent.LeaseAcquired(itemId, rootId, "k", 60L),
                    DomainEvent.LeaseReleased(itemId, rootId, "k", 1, false),
                    DomainEvent.LeaseRejected(itemId, rootId, listOf("k"), 10L),
                    DomainEvent.LeaseExpired(itemId, rootId, "k"),
                    DomainEvent.ProjectConfigUpserted(rootId, "fp"),
                    DomainEvent.ProjectConfigDeleted(rootId),
                    DomainEvent.PlanDocumentStashed(noteId, rootId, "slug"),
                    DomainEvent.PlanDocumentAdopted(noteId, rootId, "slug", itemId),
                )
            for (event in tableOnly) {
                assertNull(projectedOf(event), "${event.type} stays table-only until W2/W6 (F7)")
            }
            assertTrue(store.maxSeq() > floor, "control: all of the above were really appended")
        }

    @Test
    fun `projection carries the principal columns as the actor and the unit instant as modifiedAt`(): Unit =
        runBlocking {
            val at = Instant.parse("2026-01-02T03:04:05.678Z")
            val feed = EventFeed(store)
            val actor =
                io.github.jpicklyk.mcptask.current.domain.model.ActorClaim(
                    id = "agent-x",
                    kind = io.github.jpicklyk.mcptask.current.domain.model.ActorKind.SUBAGENT,
                    parent = "orch-1",
                )

            val event = feed.emit(ApiEventType.NOTE_UPSERTED, actor = actor, at = at)

            assertEquals("agent-x", event.actor?.id)
            assertEquals("subagent", event.actor?.kind)
            assertEquals("orch-1", event.actor?.parent)
            assertEquals(at, Instant.parse(event.modifiedAt!!), "modifiedAt is the row's occurred_at")
        }

    // ---------------------------------------------------------------------------------------------
    // Last-Event-ID header probes through the real SSE route
    // ---------------------------------------------------------------------------------------------

    private fun tokenEntries(): Map<HashBytes, BearerTokenStore.TokenEntry> {
        val principal =
            ApiPrincipal(
                tokenId = "p8-replay-principal",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER,
            )
        return mapOf(HashBytes(sha256(TOKEN)) to BearerTokenStore.TokenEntry(principal, expiresAt = null))
    }

    private fun Application.wireEventsRoute(bus: ApiEventBus) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            eventRoutes(bus, tokenEntries(), allowQueryToken = false, authCheckIntervalSeconds = 60, workItemRepository = null)
        }
    }

    @Test
    fun `the Last-Event-ID header - unusable forms are unknown_event_id and the floor header replays from the start`(): Unit =
        testApplication {
            val bus = busWith(100)
            val rows = runBlocking { seed(bus, 3) }
            application { wireEventsRoute(bus) }
            val sseClient = createClient { install(ClientSSE) }
            val decoder = Json { ignoreUnknownKeys = true }

            suspend fun firstFrame(headerValue: String): ApiEvent {
                var frame: ApiEvent? = null
                withTimeout(10.seconds) {
                    sseClient.sse(
                        urlString = "/events",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $TOKEN")
                            header("Last-Event-ID", headerValue)
                        },
                    ) {
                        frame =
                            decoder.decodeFromString(
                                ApiEvent.serializer(),
                                incoming
                                    .take(1)
                                    .toList()
                                    .single()
                                    .data
                                    .orEmpty()
                            )
                    }
                }
                return frame!!
            }

            for (bad in listOf("abc", " 12 ", "-1", "0", Long.MAX_VALUE.toString(), "12.5", "0x10")) {
                val frame = firstFrame(bad)
                assertEquals(ApiEventType.SYNC_LOST, frame.event, "header '$bad': $frame")
                assertEquals(SyncLostReason.UNKNOWN_EVENT_ID, frame.reason, "header '$bad'")
            }
            val fromStart = firstFrame(FROM_START)
            assertEquals(rows.first().id, fromStart.id, "the floor header replays from the first row, not a sentinel: $fromStart")
            assertEquals(ApiEventType.ITEM_CREATED, fromStart.event)
        }

    companion object {
        private const val TOKEN = "p8-replay-test-token-abc123"
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
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
import io.ktor.sse.ServerSentEvent
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `a3ebd108` -- SSE `Last-Event-ID` replay after
 * ring-buffer eviction gets a documented `sync.lost` sentinel. HTTP-level scenarios S6-S9; bus-level
 * scenarios (S1-S5, S10-S12) live in [SyncLostReplayTest].
 *
 * Written from the frozen `test-plan` note (`a2ab9e58`) per the test-author protocol (Wave C,
 * `plans/bugwave3-2026-09.md`): blind to the implementer's diff, the implementer's own tests, and
 * `implementation-notes` / `session-tracking` / `diagnosis`. Harness mirrors
 * [TagScopeSseEventsTest]'s `wireEventsRoute` + `collectWithin` (a fixed collection window, never
 * `take(n)` under a timeout, for any scenario that must assert "nothing extra arrived").
 *
 * Oracles: same sentinel contract as [SyncLostReplayTest] (`event=sync.lost`, `itemId=null`,
 * `id=oldestRetained.id-1` or `idCounter.get()` when empty, never buffered, first emission before
 * replay), plus `ApiEventType.CONTROL_EVENTS` (`sync.lost`, `auth.expired` bypass both the `types=`
 * filter and the tag-scope filter) and `api-rest.md` §3/§21 (`tags_include` exact membership;
 * replay applies the same filter as live fan-out).
 *
 * S-ids are stable identifiers frozen in `test-plan` -- do not renumber.
 */
class SyncLostSseDeliveryTest {
    companion object {
        private const val TOKEN = "sync-lost-sse-test-token-xyz789"

        private val replayJson = Json { ignoreUnknownKeys = true }

        /** Time given to a launched fixture-mutation/publish job to run before the SSE window opens. */
        private const val SETTLE_DELAY_MS = 150L

        /** Fixed window a connection listens for frames -- bounds every "assert nothing extra
         * arrived" scenario so it cannot hang; mirrors [TagScopeSseEventsTest]'s `collectWithin`. */
        private const val COLLECT_WINDOW_MS = 900L

        private fun decode(data: String?): ApiEvent = replayJson.decodeFromString(ApiEvent.serializer(), data.orEmpty())

        private fun tokenEntries(
            token: String,
            tagsInclude: Set<String> = emptySet(),
            rootIds: Set<UUID>? = null,
        ): Map<HashBytes, BearerTokenStore.TokenEntry> {
            val principal =
                ApiPrincipal(
                    tokenId = "sync-lost-sse-test-principal",
                    scope = ApiScope(rootIds = rootIds, tagsInclude = tagsInclude),
                    capabilities = setOf(ApiCapability.READ),
                    authMode = ApiAuthMode.BEARER,
                )
            return mapOf(HashBytes(sha256(token)) to BearerTokenStore.TokenEntry(principal, expiresAt = null))
        }

        private suspend fun createItem(
            provider: DefaultRepositoryProvider,
            tags: String?,
        ): WorkItem {
            val item = WorkItem(id = UUID.randomUUID(), parentId = null, title = "sync-lost-fixture", depth = 0, tags = tags)
            val result = provider.workItemRepository().create(item)
            check(result is Result.Success) { "fixture item create failed: $result" }
            return item
        }

        /** Mirrors [TagScopeSseEventsTest]: collects every frame delivered within a fixed window,
         * then cancels -- never discards partial results the way `take(n)` under a timeout would. */
        private suspend fun collectWithin(incoming: Flow<ServerSentEvent>): List<ApiEvent> {
            val collected = mutableListOf<ApiEvent>()
            coroutineScope {
                val job = launch { incoming.collect { sse -> collected.add(decode(sse.data)) } }
                delay(COLLECT_WINDOW_MS)
                job.cancel()
            }
            return collected
        }
    }

    /** Mirrors the harness in [EventRoutesTest] / [TagScopeSseEventsTest] -- direct `eventRoutes(...)`
     * wiring, no full app. */
    private fun Application.wireEventsRoute(
        bus: ApiEventBus,
        tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
        workItemRepository: WorkItemRepository?,
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            eventRoutes(
                bus,
                tokenEntries,
                allowQueryToken = false,
                authCheckIntervalSeconds = 60,
                workItemRepository = workItemRepository,
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // S6 -- HTTP edge: malformed vs. absent Last-Event-ID header
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S6a - a malformed Last-Event-ID header yields sync_lost unknown_event_id then live delivery`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            application { wireEventsRoute(bus, tokenEntries(TOKEN), workItemRepository = null) }
            val sseClient = createClient { install(ClientSSE) }
            val liveId = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = liveId, modifiedAt = Instant.now()), emptySet())
                    }
                    sseClient.sse(
                        urlString = "/events",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $TOKEN")
                            header("Last-Event-ID", "abc")
                        },
                    ) {
                        collected.addAll(incoming.take(2).toList().map { decode(it.data) })
                    }
                }
            }
            assertEquals(2, collected.size, "S6a: expected sentinel + the live event. Got: $collected")
            assertEquals(
                ApiEventType.SYNC_LOST,
                collected[0].event,
                "S6a: an unparseable Last-Event-ID must still be treated as a resume request",
            )
            assertEquals(
                SyncLostReason.UNKNOWN_EVENT_ID,
                collected[0].reason,
                "S6a: a malformed header value cannot be resolved to any known cursor -> unknown_event_id. Got: ${collected[0]}",
            )
            assertNull(collected[0].itemId)
            assertEquals(liveId.toString(), collected[1].itemId, "S6a: the live event must still be delivered after the sentinel")
            assertNotEquals(ApiEventType.SYNC_LOST, collected[1].event)
        }

    @Test
    fun `S6b - no Last-Event-ID header at all is absent, not a resume request, and yields no sentinel`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            application { wireEventsRoute(bus, tokenEntries(TOKEN), workItemRepository = null) }
            val sseClient = createClient { install(ClientSSE) }
            val liveId = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = liveId, modifiedAt = Instant.now()), emptySet())
                    }
                    sseClient.sse(
                        urlString = "/events",
                        request = { header(HttpHeaders.Authorization, "Bearer $TOKEN") },
                    ) {
                        collected.addAll(collectWithin(incoming))
                    }
                }
            }
            assertEquals(1, collected.size, "S6b: absent Last-Event-ID must deliver only the live event, no sentinel. Got: $collected")
            assertEquals(liveId.toString(), collected[0].itemId)
            assertNotEquals(ApiEventType.SYNC_LOST, collected[0].event)
        }

    // -------------------------------------------------------------------------------------------
    // S7 -- HTTP: control events bypass the types= filter, both on replay and on live overflow
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S7a - types filter drops non-matching replay events but the sentinel still arrives first`(): Unit =
        testApplication {
            val bus = ApiEventBus(bufferSize = 3)
            val published = mutableListOf<ApiEvent>()
            val eventTypes =
                listOf(
                    ApiEventType.ITEM_CREATED,
                    ApiEventType.NOTE_UPSERTED,
                    ApiEventType.ITEM_CREATED,
                    ApiEventType.NOTE_UPSERTED,
                    ApiEventType.ITEM_CREATED,
                )
            eventTypes.forEach { t ->
                val e = bus.buildEvent(t, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            application { wireEventsRoute(bus, tokenEntries(TOKEN), workItemRepository = null) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/events?types=${ApiEventType.ITEM_CREATED}",
                    request = {
                        header(HttpHeaders.Authorization, "Bearer $TOKEN")
                        header("Last-Event-ID", published[0].id.toString()) // evicted -- only ids 3,4,5 retained
                    },
                ) {
                    collected.addAll(incoming.take(3).toList().map { decode(it.data) })
                }
            }
            assertEquals(3, collected.size, "S7a: expected sentinel + the two retained item.created events. Got: $collected")
            assertEquals(
                ApiEventType.SYNC_LOST,
                collected[0].event,
                "S7a: a control event must bypass the types= filter and arrive first",
            )
            assertTrue(
                collected.drop(1).all { it.event == ApiEventType.ITEM_CREATED },
                "S7a: note.upserted must be dropped by types=item.created. Got: $collected",
            )
            assertEquals(
                listOf(published[2].id, published[4].id),
                collected.drop(1).map { it.id },
                "S7a: exactly the two retained item.created events (e3, e5) must replay, in order",
            )
        }

    @Test
    fun `S7b - a live queue-overflow sentinel also bypasses the types filter`(): Unit =
        testApplication {
            val bus = ApiEventBus(bufferSize = 1000, connectionQueueSize = 4)
            application { wireEventsRoute(bus, tokenEntries(TOKEN), workItemRepository = null) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        repeat(10) {
                            bus.publish(
                                bus.buildEvent(ApiEventType.NOTE_UPSERTED, itemId = UUID.randomUUID(), modifiedAt = Instant.now()),
                                emptySet(),
                            )
                        }
                    }
                    sseClient.sse(
                        urlString = "/events?types=${ApiEventType.ITEM_CREATED}",
                        request = { header(HttpHeaders.Authorization, "Bearer $TOKEN") },
                    ) {
                        collected.addAll(collectWithin(incoming))
                    }
                }
            }
            val overflow = collected.firstOrNull { it.event == ApiEventType.SYNC_LOST }
            assertNotNull(
                overflow,
                "S7b: expected a queue-overflow sentinel despite types=item.created excluding note.upserted. Got: $collected"
            )
            assertEquals(SyncLostReason.QUEUE_OVERFLOW, overflow!!.reason)
            assertTrue(
                collected.none { it.event == ApiEventType.NOTE_UPSERTED },
                "S7b: note.upserted must still be filtered out by types=. Got: $collected",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S8 -- HTTP: tags_include-scoped subscription; sentinel passes (null itemId), replay filtered
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S8 - a tags_include-scoped subscription still receives the sentinel across eviction while dropping out-of-scope replay`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus(bufferSize = 3)
            val itemAlpha = createItem(provider, tags = "alpha")
            val itemBeta1 = createItem(provider, tags = "beta")
            val itemBeta2 = createItem(provider, tags = "beta")
            val itemBeta3 = createItem(provider, tags = "beta")
            val published = mutableListOf<ApiEvent>()
            val sequence = listOf(itemBeta1.id, itemAlpha.id, itemBeta2.id, itemAlpha.id, itemBeta3.id)
            sequence.forEach { id ->
                val e = bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = id, modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/events",
                    request = {
                        header(HttpHeaders.Authorization, "Bearer $TOKEN")
                        header("Last-Event-ID", published[0].id.toString()) // evicted -- only ids 3,4,5 retained
                    },
                ) {
                    collected.addAll(incoming.take(2).toList().map { decode(it.data) })
                }
            }
            assertEquals(2, collected.size, "S8: expected sentinel + the single in-scope retained event (alpha). Got: $collected")
            assertEquals(
                ApiEventType.SYNC_LOST,
                collected[0].event,
                "S8: a null-itemId sentinel must pass a tags_include-scoped subscription",
            )
            assertNull(collected[0].itemId)
            assertEquals(
                itemAlpha.id.toString(),
                collected[1].itemId,
                "S8: the out-of-scope (beta) retained events must be dropped; only the alpha one replays",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S9 -- HTTP: root-scoped subscriber, only OTHER roots' events evicted -> sentinel still fires
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S9 - a root-scoped subscriber still receives the sentinel when only other roots' events were evicted, with no leak`(): Unit =
        testApplication {
            val bus = ApiEventBus(bufferSize = 3)
            val rootA = UUID.randomUUID()
            val rootB = UUID.randomUUID()
            val published = mutableListOf<ApiEvent>()
            repeat(5) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, setOf(rootB)) // every buffered event belongs to rootB only
            }
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = setOf(rootA)), workItemRepository = null) }
            val sseClient = createClient { install(ClientSSE) }
            val liveId = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = liveId, modifiedAt = Instant.now()), setOf(rootA))
                    }
                    sseClient.sse(
                        urlString = "/events",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $TOKEN")
                            header("Last-Event-ID", published[0].id.toString()) // evicted globally
                        },
                    ) {
                        collected.addAll(incoming.take(2).toList().map { decode(it.data) })
                    }
                }
            }
            assertEquals(2, collected.size, "S9: expected sentinel + the live in-root event. Got: $collected")
            assertEquals(
                ApiEventType.SYNC_LOST,
                collected[0].event,
                "S9: a root-scoped subscriber must still see the sentinel from a global-buffer eviction it never had access to (documented false positive)",
            )
            assertNull(collected[0].itemId)
            assertEquals(
                liveId.toString(),
                collected[1].itemId,
                "S9: no rootB replay must leak through; only the live rootA event follows the sentinel",
            )
        }

    // -------------------------------------------------------------------------------------------
    // Adversarial probes (recorded in test-manifest regardless of outcome)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `probe - a mixed-case Last-Event-ID header still triggers replay - HTTP headers are case-insensitive`(): Unit =
        testApplication {
            val bus = ApiEventBus(bufferSize = 3)
            val published = mutableListOf<ApiEvent>()
            repeat(5) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, emptySet())
            }
            application { wireEventsRoute(bus, tokenEntries(TOKEN), workItemRepository = null) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/events",
                    request = {
                        header(HttpHeaders.Authorization, "Bearer $TOKEN")
                        // Deliberately lower-case header name -- RFC 7230 §3.2 header names are
                        // case-insensitive; the SSE spec's own reconnect mechanism relies on this.
                        header("last-event-id", published[0].id.toString())
                    },
                ) {
                    collected.addAll(incoming.take(4).toList().map { decode(it.data) })
                }
            }
            assertEquals(
                4,
                collected.size,
                "probe: expected sentinel + the 3 retained events via a lower-case header name. Got: $collected"
            )
            assertEquals(
                ApiEventType.SYNC_LOST,
                collected[0].event,
                "probe: a lower-case 'last-event-id' header must be recognized the same as 'Last-Event-ID'"
            )
        }
}

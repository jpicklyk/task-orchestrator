package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `ffce70f6` -- SSE root scoping must fail closed: an event
 * whose roots could not be resolved must not reach root-scoped subscribers (live or replay), and
 * `GET /api/v1/events` rejects an empty root intersection / a malformed `?root=` as a normal HTTP
 * response, before any stream opens.
 *
 * Written from the frozen `test-plan` note (`99bb686c`) per the test-author protocol
 * (`plans/bugwave4-2026-09.md`, "sse (wave A)"): blind to the implementer's diff, the implementer's
 * own tests, and `implementation-notes` / `session-tracking`. All public declarations were supplied
 * inline in the dispatch contract's "DECLARATIONS for ffce70f6" block -- nothing under `src/main`
 * was opened to write this file.
 *
 * Oracles (from `test-plan`):
 * - O1 `ApiScope` KDoc -- non-null `rootIds` = access ONLY items under those roots => a non-null,
 *   unmatched intersection is a deny, never an implicit allow.
 * - O2 `AuthorizationPlugin` -- an empty candidate set / lookup failure denies, never admits.
 * - O3 `api-rest.md` SS21 -- effective subscription = intersection of `?root=` with the principal's
 *   scope; an empty intersection is an empty subscription, not a universal one.
 * - O4 `AuthorizationPlugin` -- 403, not 404, for an out-of-scope request.
 * - O5 `ApiEventBus` KDoc -- `emptySet` `affectedRoots` (the pre-existing default) is a bus-level
 *   broadcast; this contract is PRESERVED by the fix and must not regress.
 * - O6 `ApiEventType.CONTROL_EVENTS` KDoc -- control events (`sync.lost`, `auth.expired`) are never
 *   suppressed by scope or `types=` filtering.
 *
 * S-ids are stable identifiers frozen in `test-plan` -- do not renumber. S1/S2/S3/S11 are
 * `NEW-SURFACE` (they bind to the new `rootsResolved` parameter); their narrowest-revert recipe is
 * noted at each test. S4/S5/S6/S7/S8/S9/S10/S12 are `EXISTING-SURFACE` regressions/route-contract
 * scenarios reachable by a plain revert.
 */
class SseRootScopeFailClosedTest {
    companion object {
        private const val TOKEN = "sse-root-scope-fail-closed-test-token-abc123"

        private val replayJson = Json { ignoreUnknownKeys = true }
        private val errorJson = Json { ignoreUnknownKeys = true }

        /** Time given to a launched publish/fixture job to run before the SSE window opens. */
        private const val SETTLE_DELAY_MS = 150L

        /** Fixed window a connection listens for frames -- bounds every "assert nothing extra
         * arrived" scenario so it cannot hang. Mirrors [TagScopeSseEventsTest]'s `collectWithin`. */
        private const val COLLECT_WINDOW_MS = 900L

        private fun decode(data: String?): ApiEvent = replayJson.decodeFromString(ApiEvent.serializer(), data.orEmpty())

        private fun tokenEntries(
            token: String,
            rootIds: Set<UUID>?,
        ): Map<HashBytes, BearerTokenStore.TokenEntry> {
            val principal =
                ApiPrincipal(
                    tokenId = "sse-root-scope-fail-closed-test-principal",
                    scope = ApiScope(rootIds = rootIds, tagsInclude = emptySet()),
                    capabilities = setOf(ApiCapability.READ),
                    authMode = ApiAuthMode.BEARER,
                )
            return mapOf(HashBytes(sha256(token)) to BearerTokenStore.TokenEntry(principal, expiresAt = null))
        }

        /** Mirrors [TagScopeSseEventsTest] / [SyncLostSseDeliveryTest]: collects every frame
         * delivered within a fixed window, then cancels -- never discards partial results the way
         * `take(n)` under a timeout would. */
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
     * wiring, no full app. This item's fix is root-scope-only, so no `workItemRepository` is wired. */
    private fun Application.wireEventsRoute(
        bus: ApiEventBus,
        tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            eventRoutes(
                bus,
                tokenEntries,
                allowQueryToken = false,
                authCheckIntervalSeconds = 60,
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // S1 / S2 -- NEW-SURFACE: rootsResolved=false fails closed to root-scoped subscribers, but not
    // to unrestricted ones. Narrowest revert: keep the `rootsResolved` parameter and the
    // RingBufferEntry field, revert only the two fan-out/replay predicates to the pre-fix two-clause
    // form (ApiEventBus.kt fan-out ~:114-119).
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S1 - an unresolved-roots publish delivers nothing to a root-scoped subscriber`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root = UUID.randomUUID()
            val itemId = UUID.randomUUID()

            val flow = bus.subscribe("s1-root-scoped", setOf(root), lastEventId = null)
            val received = async { withTimeoutOrNull(800) { flow.take(1).toList() } }

            delay(50)
            val event = bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = itemId, modifiedAt = Instant.now())
            bus.publish(event, affectedRoots = emptySet(), rootsResolved = false)

            val result = received.await()
            assertNull(
                result,
                "S1: an unresolved-roots publish must fail closed -- a root-scoped subscriber must receive " +
                    "nothing (rootsResolved=false reaches only subscribers with an empty rootIds filter, per O1/O2)",
            )
            bus.unsubscribe("s1-root-scoped")
        }

    @Test
    fun `S2 - the same unresolved-roots publish IS delivered to an unrestricted subscriber`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val itemId = UUID.randomUUID()

            val flow = bus.subscribe("s2-unrestricted", emptySet(), lastEventId = null)
            val collected = async { withTimeout(5.seconds) { flow.take(1).toList() } }

            delay(50)
            val event = bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = itemId, modifiedAt = Instant.now())
            bus.publish(event, affectedRoots = emptySet(), rootsResolved = false)

            val events = collected.await()
            assertEquals(
                1,
                events.size,
                "S2: an unrestricted subscriber is entitled to everything -- an unresolved event must still arrive"
            )
            assertEquals(itemId.toString(), events[0].itemId)
            bus.unsubscribe("s2-unrestricted")
        }

    // -------------------------------------------------------------------------------------------
    // S3 -- NEW-SURFACE: the replay (Last-Event-ID) path applies the identical rootsResolved rule.
    // Narrowest revert: same as S1/S2, on the replay predicate (~:215-224).
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S3 - replay skips an unresolved entry for a root-scoped resume, delivers it unrestricted, idempotently`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root = UUID.randomUUID()
            val itemId = UUID.randomUUID()

            // Buffer the unresolved event BEFORE any subscriber connects.
            bus.publish(
                bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = itemId, modifiedAt = Instant.now()),
                affectedRoots = emptySet(),
                rootsResolved = false,
            )

            val rootScopedFlow = bus.subscribe("s3-root-scoped", setOf(root), lastEventId = 0L)
            val rootScopedResult = withTimeoutOrNull(800) { rootScopedFlow.take(1).toList() }
            assertNull(
                rootScopedResult,
                "S3: replay of an unresolved entry must not reach a root-scoped resume (fail closed, same as live)"
            )
            bus.unsubscribe("s3-root-scoped")

            suspend fun unrestrictedReplay(): List<ApiEvent> {
                val flow = bus.subscribe("s3-unrestricted", emptySet(), lastEventId = 0L)
                val result = withTimeout(5.seconds) { flow.take(1).toList() }
                bus.unsubscribe("s3-unrestricted")
                return result
            }

            val first = unrestrictedReplay()
            val second = unrestrictedReplay()

            assertEquals(1, first.size, "S3: an unrestricted resume must replay the unresolved entry")
            assertEquals(itemId.toString(), first[0].itemId)
            assertEquals(
                first.map { it.id },
                second.map { it.id },
                "S3: reconnecting again with the same Last-Event-ID must yield the same replayed set (idempotent)",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S4 / S5 -- EXISTING-SURFACE regressions: the pre-existing bus-level-broadcast and
    // single-root-scoping contracts (O5) must be unaffected by the additive rootsResolved parameter.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S4 - a default bus-level publish (emptySet roots, rootsResolved default true) still reaches a root-scoped subscriber`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root = UUID.randomUUID()

            val flow = bus.subscribe("s4-root-scoped", setOf(root), lastEventId = null)
            val collected = async { withTimeout(5.seconds) { flow.take(1).toList() } }

            delay(50)
            // No rootsResolved argument at all -- exercises the pre-existing default (true).
            bus.publish(bus.buildEvent(ApiEventType.SYNC_LOST))

            val events = collected.await()
            assertEquals(1, events.size, "S4: emptySet affectedRoots with the default rootsResolved must remain a bus-level broadcast (O5)")
            assertEquals(ApiEventType.SYNC_LOST, events[0].event)
            bus.unsubscribe("s4-root-scoped")
        }

    @Test
    fun `S5 - a publish scoped to one root reaches only that root's subscriber (regression)`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root1 = UUID.randomUUID()
            val root2 = UUID.randomUUID()
            val itemId = UUID.randomUUID()

            val flow1 = bus.subscribe("s5-root1", setOf(root1), lastEventId = null)
            val flow2 = bus.subscribe("s5-root2", setOf(root2), lastEventId = null)
            val received1 = async { withTimeout(5.seconds) { flow1.take(1).toList() } }
            val received2 = async { withTimeoutOrNull(800) { flow2.take(1).toList() } }

            delay(50)
            val event = bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = itemId, modifiedAt = Instant.now())
            bus.publish(event, affectedRoots = setOf(root1))

            val events1 = received1.await()
            val events2 = received2.await()

            assertEquals(1, events1.size, "S5: the root1 subscriber must receive the root1-scoped event")
            assertEquals(itemId.toString(), events1[0].itemId)
            assertNull(events2, "S5: the root2 subscriber must NOT receive a root1-scoped event")
            bus.unsubscribe("s5-root1")
            bus.unsubscribe("s5-root2")
        }

    // -------------------------------------------------------------------------------------------
    // S11 -- NEW-SURFACE: decorator cold-cache path. A dependency write on an item whose root was
    // never cached must publish as unresolved, not as a broadcast. Narrowest revert: keep the
    // rootsResolved parameter, revert only the decorator's argument at the dependency publish sites.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S11 - a dependency create on a never-cached item reaches only the unrestricted subscriber, never a root-scoped one`(): Unit =
        runBlocking {
            val delegate = buildH2RepositoryProvider()
            val uncachedFromId = UUID.randomUUID()
            val uncachedToId = UUID.randomUUID()
            // Pre-create the items directly through the UNDECORATED delegate so the decorator's root
            // cache is never warmed for them (mirrors the pre-existing cold-path fixture pattern in
            // EventPublishingRepositoryProviderTest).
            delegate.workItemRepository().create(WorkItem(id = uncachedFromId, title = "S11 cold from", depth = 0))
            delegate.workItemRepository().create(WorkItem(id = uncachedToId, title = "S11 cold to", depth = 0))

            val bus = ApiEventBus()
            val provider = EventPublishingRepositoryProvider(delegate, bus)

            val rootScopedFlow = bus.subscribe("s11-root-scoped", setOf(UUID.randomUUID()), lastEventId = null)
            val unrestrictedFlow = bus.subscribe("s11-unrestricted", emptySet(), lastEventId = null)
            val rootScopedResult = async { withTimeoutOrNull(800) { rootScopedFlow.take(1).toList() } }
            val unrestrictedResult = async { withTimeout(5.seconds) { unrestrictedFlow.take(1).toList() } }

            delay(50)
            provider.dependencyRepository().create(
                Dependency(fromItemId = uncachedFromId, toItemId = uncachedToId, type = DependencyType.BLOCKS),
            )

            val rootScoped = rootScopedResult.await()
            val unrestricted = unrestrictedResult.await()

            assertNull(
                rootScoped,
                "S11: an unresolved (never-cached) dependency.added must not reach a root-scoped subscriber -- " +
                    "a cold cache must fail closed, not fall back to broadcast",
            )
            assertEquals(1, unrestricted.size, "S11: an unrestricted subscriber must still receive the unresolved event")
            assertEquals(ApiEventType.DEPENDENCY_ADDED, unrestricted[0].event)
            bus.unsubscribe("s11-root-scoped")
            bus.unsubscribe("s11-unrestricted")
        }

    // -------------------------------------------------------------------------------------------
    // S6 -- EXISTING-SURFACE route contract: an out-of-scope ?root= is rejected with 403, before
    // any stream opens (normal HTTP response, not an SSE frame).
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S6 - a root-scoped principal requesting a root outside its scope is rejected with 403 before any stream opens`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val r1 = UUID.randomUUID()
            val r2 = UUID.randomUUID()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = setOf(r1))) }

            val response = client.get("/events?root=$r2") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "S6: a root-scoped principal querying an out-of-scope root must be rejected with 403, not admitted (O1/O3/O4)",
            )
            val body = errorJson.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                "insufficient_scope",
                body["error"]?.jsonPrimitive?.content,
                "S6: 403 body must report insufficient_scope per the declared raw JSON shape. Got: $body",
            )
            assertEquals(
                "Requested roots are outside this token's scope",
                body["error_description"]?.jsonPrimitive?.content,
                "S6: 403 body's error_description must match the declared literal exactly. Got: $body",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S7 / S8 -- EXISTING-SURFACE route contract: a valid, in-scope ?root= narrows the subscription
    // and never widens it back out, for both a multi-root principal and an unrestricted one.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S7 - a two-root principal requesting one of its own roots is narrowed to exactly that root, never widened back to both`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val r1 = UUID.randomUUID()
            val r2 = UUID.randomUUID()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = setOf(r1, r2))) }
            val sseClient = createClient { install(ClientSSE) }
            val idInR1 = UUID.randomUUID()
            val idInR2 = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = idInR1, modifiedAt = Instant.now()), setOf(r1))
                        delay(60L)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = idInR2, modifiedAt = Instant.now()), setOf(r2))
                    }
                    sseClient.sse(
                        urlString = "/events?root=$r2",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $TOKEN")
                            header("Last-Event-ID", "0")
                        },
                    ) {
                        collected.addAll(collectWithin(incoming))
                    }
                }
            }
            assertEquals(1, collected.size, "S7: only the requested root's events should arrive, not both scoped roots. Got: $collected")
            assertEquals(idInR2.toString(), collected[0].itemId, "S7: the r1 event must never leak through the ?root=r2 narrowing")
        }

    @Test
    fun `S8 - an unrestricted principal requesting one root via query param is narrowed to that root (unchanged behavior)`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val r1 = UUID.randomUUID()
            val r2 = UUID.randomUUID()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = null)) }
            val sseClient = createClient { install(ClientSSE) }
            val idInR1 = UUID.randomUUID()
            val idInR2 = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = idInR1, modifiedAt = Instant.now()), setOf(r1))
                        delay(60L)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = idInR2, modifiedAt = Instant.now()), setOf(r2))
                    }
                    sseClient.sse(
                        urlString = "/events?root=$r2",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $TOKEN")
                            header("Last-Event-ID", "0")
                        },
                    ) {
                        collected.addAll(collectWithin(incoming))
                    }
                }
            }
            assertEquals(
                1,
                collected.size,
                "S8: an unrestricted principal's ?root= narrowing must still exclude the other root. Got: $collected"
            )
            assertEquals(idInR2.toString(), collected[0].itemId)
        }

    // -------------------------------------------------------------------------------------------
    // S9 -- EXISTING-SURFACE route contract: a ?root= present but yielding zero valid UUIDs is
    // rejected with 400, never treated as "no filter" / an unnarrowed stream.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S9a - a root query parameter that is not a valid UUID is rejected with 400 before any stream opens`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = null)) }

            val response = client.get("/events?root=not-a-uuid") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }

            assertEquals(HttpStatusCode.BadRequest, response.status, "S9a: a malformed root value must be rejected with 400, not ignored")
            val body = errorJson.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", body["error"]?.jsonPrimitive?.content, "S9a body: $body")
            assertEquals(
                "root query parameter must be a valid UUID",
                body["error_description"]?.jsonPrimitive?.content,
                "S9a body: $body",
            )
        }

    @Test
    fun `S9b - a present but empty root query parameter is also rejected with 400`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = null)) }

            val response = client.get("/events?root=") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }

            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "S9b: a present-but-empty root value yields zero valid UUIDs -> 400, same as garbage input",
            )
            val body = errorJson.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("validation_error", body["error"]?.jsonPrimitive?.content, "S9b body: $body")
        }

    // -------------------------------------------------------------------------------------------
    // S10 -- EXISTING-SURFACE route contract: NO ?root= at all must not be mistaken for an empty
    // intersection -- it is "no narrowing requested," and the principal's own root(s) still stream.
    // Guards S6 against over-firing.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S10 - a root-scoped principal with no root query param at all still streams its own root (guards S6 against over-firing)`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val r1 = UUID.randomUUID()
            val r2 = UUID.randomUUID()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = setOf(r1))) }
            val sseClient = createClient { install(ClientSSE) }
            val idInR1 = UUID.randomUUID()
            val idInR2 = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = idInR2, modifiedAt = Instant.now()), setOf(r2))
                        delay(60L)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = idInR1, modifiedAt = Instant.now()), setOf(r1))
                    }
                    sseClient.sse(
                        urlString = "/events",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $TOKEN")
                            header("Last-Event-ID", "0")
                        },
                    ) {
                        collected.addAll(collectWithin(incoming))
                    }
                }
            }
            assertEquals(1, collected.size, "S10: an absent ?root= must not be treated as an empty-intersection rejection. Got: $collected")
            assertEquals(idInR1.toString(), collected[0].itemId, "S10: must stream exactly the principal's own root, not r2")
        }

    // -------------------------------------------------------------------------------------------
    // S12 -- EXISTING-SURFACE non-regression (wave 3): a root-scoped connection resuming past a
    // ring-buffer eviction still gets sync.lost first, and CONTROL_EVENTS still bypass ?types=.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S12 - a root-scoped resume past a buffer eviction gets sync_lost first, bypassing a types filter (wave-3)`(): Unit =
        testApplication {
            val bus = ApiEventBus(bufferSize = 3)
            val rootA = UUID.randomUUID()
            val rootB = UUID.randomUUID()
            val published = mutableListOf<ApiEvent>()
            repeat(5) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, setOf(rootB)) // every buffered event belongs to rootB only -- irrelevant to rootA
            }
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = setOf(rootA))) }
            val sseClient = createClient { install(ClientSSE) }
            val liveId = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.NOTE_UPSERTED, itemId = liveId, modifiedAt = Instant.now()), setOf(rootA))
                    }
                    sseClient.sse(
                        urlString = "/events?types=${ApiEventType.NOTE_UPSERTED}",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $TOKEN")
                            header("Last-Event-ID", published[0].id.toString()) // evicted globally
                        },
                    ) {
                        collected.addAll(incoming.take(2).toList().map { decode(it.data) })
                    }
                }
            }
            assertEquals(2, collected.size, "S12: expected the sync.lost sentinel + the live in-root note.upserted. Got: $collected")
            assertEquals(
                ApiEventType.SYNC_LOST,
                collected[0].event,
                "S12: a control event must still reach a root-scoped connection and bypass types=note.upserted (CONTROL_EVENTS, O6) " +
                    "-- the ffce70f6 fail-closed fix must not regress this wave-3 guarantee",
            )
            assertNull(collected[0].itemId)
            assertEquals(liveId.toString(), collected[1].itemId, "S12: the live in-root, type-matching event must follow the sentinel")
        }

    // -------------------------------------------------------------------------------------------
    // Adversarial probes (recorded in test-manifest regardless of outcome)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `probe - a duplicate root query parameter narrows to the single deduplicated root, not rejected nor widened`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val r1 = UUID.randomUUID()
            val r2 = UUID.randomUUID()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = setOf(r1, r2))) }
            val sseClient = createClient { install(ClientSSE) }
            val idInR1 = UUID.randomUUID()
            val idInR2 = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = idInR2, modifiedAt = Instant.now()), setOf(r2))
                        delay(60L)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = idInR1, modifiedAt = Instant.now()), setOf(r1))
                    }
                    sseClient.sse(
                        urlString = "/events?root=$r1&root=$r1",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $TOKEN")
                            header("Last-Event-ID", "0")
                        },
                    ) {
                        collected.addAll(collectWithin(incoming))
                    }
                }
            }
            assertEquals(1, collected.size, "probe: a duplicate root param must narrow, same as a single occurrence. Got: $collected")
            assertEquals(idInR1.toString(), collected[0].itemId)
        }

    @Test
    fun `probe - a mixed-case UUID in the root query parameter is still accepted and narrows correctly`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val r1 = UUID.randomUUID()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, rootIds = setOf(r1))) }
            val sseClient = createClient { install(ClientSSE) }
            val idInR1 = UUID.randomUUID()

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = idInR1, modifiedAt = Instant.now()), setOf(r1))
                    }
                    sseClient.sse(
                        urlString = "/events?root=${r1.toString().uppercase()}",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $TOKEN")
                            header("Last-Event-ID", "0")
                        },
                    ) {
                        collected.addAll(collectWithin(incoming))
                    }
                }
            }
            assertEquals(
                1,
                collected.size,
                "probe: an upper-cased UUID string must still parse to the same root and be accepted, not rejected. Got: $collected",
            )
            assertEquals(idInR1.toString(), collected[0].itemId)
        }

    @Test
    fun `probe - interleaved unresolved and resolved publishes to the same root are each filtered independently`(): Unit =
        runBlocking {
            val bus = ApiEventBus()
            val root = UUID.randomUUID()
            val resolvedId = UUID.randomUUID()
            val unresolvedId = UUID.randomUUID()

            val flow = bus.subscribe("probe-interleave-root", setOf(root), lastEventId = null)
            val collected = async { withTimeout(3.seconds) { flow.take(1).toList() } }

            delay(50)
            // Unresolved first -- must be silently dropped for this root-scoped subscriber.
            bus.publish(
                bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = unresolvedId, modifiedAt = Instant.now()),
                affectedRoots = emptySet(),
                rootsResolved = false,
            )
            delay(60L)
            // Resolved second, for the same root -- must arrive, proving the subscriber is still
            // live and the drop above was a real filter, not a dead/broken connection.
            bus.publish(
                bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = resolvedId, modifiedAt = Instant.now()),
                affectedRoots = setOf(root),
            )

            val events = collected.await()
            assertEquals(1, events.size, "probe: exactly the resolved, in-root event should arrive. Got: $events")
            assertEquals(resolvedId.toString(), events[0].itemId, "probe: the unresolved event must never leak through")
            bus.unsubscribe("probe-interleave-root")
        }
}

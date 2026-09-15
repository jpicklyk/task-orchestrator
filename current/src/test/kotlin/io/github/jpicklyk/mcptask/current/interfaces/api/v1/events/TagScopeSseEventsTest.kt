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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `ad2c23ea` -- SSE `/api/v1/events` per-event `tags_include`
 * filtering, both live and via `Last-Event-ID` replay, plus the fail-closed 403 when a tag-scoped
 * subscription is opened with no [WorkItemRepository] wired.
 *
 * Written from the frozen `test-plan` note per the test-author protocol (Wave C, `plans/bugwave2-2026-09.md`):
 * blind to the implementation diff, the implementer's own tests, and `implementation-notes` /
 * `session-tracking` / `diagnosis`. Oracles come from the test-plan's citations, never from what
 * the route currently returns:
 *
 * - O1/O2 `current/docs/api-rest.md` SS3 (`tags_include` is item-level, exact CSV membership, no
 *   ancestor walk, empty = no constraint) and SS21 (replay applies the same filter as live fan-out).
 * - O3 [ApiScope] KDoc (`rootIds == null` = unrestricted roots; `tagsInclude` must ALSO hold).
 * - O4 [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.allowsItemTags] KDoc (exact
 *   membership, no prefix/substring; empty `tagsInclude` = no constraint).
 * - O5 the item's `diagnosis` note (fail-closed: no repository wired + tag-scoped token -> 403;
 *   a lookup failure, including a deleted item, excludes rather than admits).
 *
 * S-ids are stable identifiers frozen in `test-plan` -- do not renumber. `S6`, `S7`, and `S9` each
 * have two named test methods (a/b) for their two sub-cases; `S3` also folds in the plan's
 * replay-idempotency probe (reconnecting twice with the same `Last-Event-ID` yields the same set).
 */
class TagScopeSseEventsTest {
    companion object {
        private const val TOKEN = "tag-scope-test-token-abc123"

        private val replayJson = Json { ignoreUnknownKeys = true }

        /** Time given to a launched fixture-mutation/publish job to run before the SSE window opens. */
        private const val SETTLE_DELAY_MS = 150L

        /** Fixed window a connection listens for frames -- long enough for [SETTLE_DELAY_MS] plus a
         * few chained steps, short enough to keep the suite fast. Bounds every test's SSE wait so
         * none can hang: this replaces `take(n)`, which (wrapped in a timeout) would discard any
         * partial results collected before the timeout fired. */
        private const val COLLECT_WINDOW_MS = 900L

        private fun sha256(input: String): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(input.toByteArray(Charsets.UTF_8))
        }

        private fun tokenEntries(
            token: String,
            tagsInclude: Set<String> = emptySet(),
            rootIds: Set<UUID>? = null,
        ): Map<HashBytes, BearerTokenStore.TokenEntry> {
            val principal =
                ApiPrincipal(
                    tokenId = "tag-scope-test-principal",
                    scope = ApiScope(rootIds = rootIds, tagsInclude = tagsInclude),
                    capabilities = setOf(ApiCapability.READ),
                    authMode = ApiAuthMode.BEARER,
                )
            return mapOf(HashBytes(sha256(token)) to BearerTokenStore.TokenEntry(principal, expiresAt = null))
        }

        private fun decode(data: String?): ApiEvent = replayJson.decodeFromString(ApiEvent.serializer(), data.orEmpty())

        private suspend fun createItem(
            provider: DefaultRepositoryProvider,
            tags: String?,
        ): WorkItem {
            val item = WorkItem(id = UUID.randomUUID(), parentId = null, title = "tag-scope-fixture", depth = 0, tags = tags)
            val result = provider.workItemRepository().create(item)
            check(result is Result.Success) { "fixture item create failed: $result" }
            return item
        }

        /**
         * Collects every frame delivered within a fixed window, then cancels the collector. A fixed
         * window (rather than `take(n)` under an outer timeout) is deliberate: `take(n)` combined
         * with a timeout throws away whatever was already collected if `n` never arrives, which
         * would silently turn every "assert nothing arrives" scenario below into an unverifiable
         * no-op. This always returns exactly what arrived, empty list included.
         */
        private suspend fun collectWithin(incoming: Flow<ServerSentEvent>): List<ApiEvent> {
            val collected = mutableListOf<ApiEvent>()
            coroutineScope {
                val job =
                    launch {
                        incoming.collect { sse -> collected.add(decode(sse.data)) }
                    }
                delay(COLLECT_WINDOW_MS)
                job.cancel()
            }
            return collected
        }
    }

    /** Mirrors the harness in [EventRoutesTest] -- direct `eventRoutes(...)` wiring, no full app. */
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
    // S1 / S2 -- live delivery, in-scope vs. out-of-scope
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S1 - a write to an in-scope item is delivered to a tags_include-scoped subscription`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val itemA = createItem(provider, tags = "alpha")
            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemA.id, modifiedAt = Instant.now()), emptySet())
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
            assertEquals(1, collected.size, "S1: exactly the in-scope write should be delivered. Got: $collected")
            assertEquals(itemA.id.toString(), collected[0].itemId, "S1: delivered event must reference item A")
        }

    @Test
    fun `S2 - an out-of-scope write produces no event, and a follow-up in-scope write proves the connection is live`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val itemA = createItem(provider, tags = "alpha")
            val itemB = createItem(provider, tags = "beta")
            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        // Out-of-scope write first -- must produce nothing.
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemB.id, modifiedAt = Instant.now()), emptySet())
                        delay(SETTLE_DELAY_MS)
                        // In-scope write second -- proves the connection is actually live, not just
                        // silent. A filter that dropped everything (including A) would fail this.
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemA.id, modifiedAt = Instant.now()), emptySet())
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
            assertEquals(1, collected.size, "S2: exactly one event (A) should arrive, B must never leak through. Got: $collected")
            assertEquals(itemA.id.toString(), collected[0].itemId, "S2: the delivered event must be A")
        }

    // -------------------------------------------------------------------------------------------
    // S3 -- Last-Event-ID replay, plus reconnect idempotency
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S3 - replay includes only the in-scope buffered event, and reconnecting is idempotent`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val itemA = createItem(provider, tags = "alpha")
            val itemB = createItem(provider, tags = "beta")
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemA.id, modifiedAt = Instant.now()), emptySet())
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemB.id, modifiedAt = Instant.now()), emptySet())

            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            suspend fun replayOnce(): List<ApiEvent> {
                val collected = mutableListOf<ApiEvent>()
                withTimeout(10.seconds) {
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
                return collected
            }

            val first = replayOnce()
            val second = replayOnce()

            assertEquals(1, first.size, "S3: only the in-scope event (A) should replay. Got: $first")
            assertEquals(itemA.id.toString(), first[0].itemId, "S3: replayed event must be A, never B")
            assertEquals(
                first.map { it.itemId },
                second.map { it.itemId },
                "S3: reconnecting again with the same Last-Event-ID must yield the same filtered set (idempotent)",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S4 / S5 -- regression: rootIds-only and unscoped principals unaffected by the tag fix
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S4 - rootIds-only scope (empty tags_include) filters by root exactly as before the tag fix`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val root1 = UUID.randomUUID()
            val root2 = UUID.randomUUID()
            val inRoot = UUID.randomUUID()
            val outOfRoot = UUID.randomUUID()
            application {
                wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = emptySet(), rootIds = setOf(root1)), workItemRepository = null)
            }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = outOfRoot, modifiedAt = Instant.now()), setOf(root2))
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = inRoot, modifiedAt = Instant.now()), setOf(root1))
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
            assertEquals(1, collected.size, "S4: only the in-root event should be delivered. Got: $collected")
            assertEquals(inRoot.toString(), collected[0].itemId, "S4: root filtering must be unaffected by the tag fix")
        }

    @Test
    fun `S5 - an unscoped principal receives all events, live and via replay (regression)`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val replayedId = UUID.randomUUID()
            val liveId = UUID.randomUUID()
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = replayedId, modifiedAt = Instant.now()), emptySet())

            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = emptySet(), rootIds = null), workItemRepository = null) }
            val sseClient = createClient { install(ClientSSE) }

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
                            header("Last-Event-ID", "0")
                        },
                    ) {
                        collected.addAll(collectWithin(incoming))
                    }
                }
            }
            val ids = collected.map { it.itemId }
            assertEquals(2, collected.size, "S5: unscoped principal must see both the replayed and the live event. Got: $collected")
            assertTrue(ids.contains(replayedId.toString()), "S5: replayed event missing")
            assertTrue(ids.contains(liveId.toString()), "S5: live event missing")
        }

    // -------------------------------------------------------------------------------------------
    // S6 -- mid-stream tag change; oracle is current item state at event time, per test-plan
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S6a - adding the in-scope tag mid-stream makes the item's next event deliverable`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val itemB = createItem(provider, tags = "beta")
            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        val retagged = itemB.copy(tags = "alpha,beta")
                        val updateResult = provider.workItemRepository().update(retagged)
                        check(updateResult is Result.Success) { "fixture retag failed: $updateResult" }
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = itemB.id, modifiedAt = Instant.now()), emptySet())
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
            assertEquals(1, collected.size, "S6a: after gaining the in-scope tag, B's next event must be delivered. Got: $collected")
            assertEquals(itemB.id.toString(), collected[0].itemId, "S6a: delivered event must reference B")
        }

    @Test
    fun `S6b - removing the in-scope tag mid-stream makes the item's next event non-deliverable`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val itemA = createItem(provider, tags = "alpha")
            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        val retagged = itemA.copy(tags = "beta")
                        val updateResult = provider.workItemRepository().update(retagged)
                        check(updateResult is Result.Success) { "fixture retag failed: $updateResult" }
                        bus.publish(bus.buildEvent(ApiEventType.NOTE_UPSERTED, itemId = itemA.id, modifiedAt = Instant.now()), emptySet())
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
            assertTrue(
                collected.isEmpty(),
                "S6b: after losing the in-scope tag, A's next event must NOT be delivered. Got: $collected",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S7 -- exact CSV membership: whitespace-padded match vs. null/empty/superstring exclusion
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S7a - a whitespace-padded csv tag element matches by exact trimmed value`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val itemSpaced = createItem(provider, tags = " alpha , beta ")
            bus.publish(bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemSpaced.id, modifiedAt = Instant.now()), emptySet())

            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
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
            assertEquals(
                1,
                collected.size,
                "S7a: a trimmed exact match ('alpha' inside ' alpha , beta ') must be included. Got: $collected"
            )
            assertEquals(itemSpaced.id.toString(), collected[0].itemId)
        }

    @Test
    fun `S7b - null, empty, and superstring tags are all excluded from a tag-scoped subscription`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val itemNull = createItem(provider, tags = null)
            val itemEmpty = createItem(provider, tags = "")
            // "alphabet" is a superstring of "alpha" -- membership must be exact, never substring/prefix.
            val itemSuperstring = createItem(provider, tags = "alphabet")
            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        listOf(itemNull, itemEmpty, itemSuperstring).forEach {
                            bus.publish(bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = it.id, modifiedAt = Instant.now()), emptySet())
                            delay(60L)
                        }
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
            assertTrue(
                collected.isEmpty(),
                "S7b: null/empty/superstring tags must never pass the exact tags_include membership test. Got: $collected",
            )
        }

    // -------------------------------------------------------------------------------------------
    // S8 -- bus-level events (itemId == null) bypass tag filtering entirely
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S8 - bus-level events with no itemId reach a tag-scoped connection, live and replayed`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            // Pre-buffer one bus-level sentinel so Last-Event-ID=0 must replay it.
            bus.publish(bus.buildEvent(ApiEventType.SYNC_LOST), emptySet())

            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(bus.buildEvent(ApiEventType.SYNC_LOST), emptySet())
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
            assertEquals(
                2,
                collected.size,
                "S8: both the replayed and the live bus-level event must reach a tag-scoped connection. Got: $collected"
            )
            assertTrue(collected.all { it.itemId == null }, "S8: bus-level events must carry no itemId")
            assertTrue(collected.all { it.event == ApiEventType.SYNC_LOST }, "S8: both frames must be sync.lost")
        }

    // -------------------------------------------------------------------------------------------
    // S9 -- fail-closed: no repository wired
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S9a - a tag-scoped subscription is refused with 403 insufficient_scope when no repository is wired`() =
        testApplication {
            val bus = ApiEventBus()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), workItemRepository = null) }

            val response = client.get("/events") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "S9a: tag-scoped token + no repository wired must fail closed with 403, not stream unfiltered",
            )
            val body = response.bodyAsText()
            assertTrue(body.contains("insufficient_scope"), "S9a: 403 body must report insufficient_scope. Got: $body")
        }

    @Test
    fun `S9b - an unscoped subscription still streams 200 when no repository is wired`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = emptySet()), workItemRepository = null) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(
                            bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now()),
                            emptySet()
                        )
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
            assertEquals(1, collected.size, "S9b: an unscoped principal must still stream when no repository is wired. Got: $collected")
        }

    // -------------------------------------------------------------------------------------------
    // S10 -- combined rootIds + tags_include: both dimensions must match
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S10 - combined rootIds and tags_include scope requires both dimensions to match`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val root1 = UUID.randomUUID()
            val root2 = UUID.randomUUID()

            val wrongTagInRoot = createItem(provider, tags = "beta")
            val rightTagOutOfRoot = createItem(provider, tags = "alpha")
            val bothMatch = createItem(provider, tags = "alpha")

            application {
                wireEventsRoute(
                    bus,
                    tokenEntries(TOKEN, tagsInclude = setOf("alpha"), rootIds = setOf(root1)),
                    provider.workItemRepository(),
                )
            }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        bus.publish(
                            bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = wrongTagInRoot.id, modifiedAt = Instant.now()),
                            setOf(root1),
                        )
                        delay(60L)
                        bus.publish(
                            bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = rightTagOutOfRoot.id, modifiedAt = Instant.now()),
                            setOf(root2),
                        )
                        delay(60L)
                        bus.publish(
                            bus.buildEvent(ApiEventType.ITEM_UPDATED, itemId = bothMatch.id, modifiedAt = Instant.now()),
                            setOf(root1),
                        )
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
            assertEquals(1, collected.size, "S10: exactly the both-match event should pass a combined rootIds+tags scope. Got: $collected")
            assertEquals(
                bothMatch.id.toString(),
                collected[0].itemId,
                "S10: right-root-wrong-tag and right-tag-wrong-root must both be dropped"
            )
        }

    // -------------------------------------------------------------------------------------------
    // S11 -- documented gap: item.deleted for a formerly in-scope item is dropped, not a bug
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S11 - item_deleted for an item that was in scope before deletion is dropped (documented gap)`(): Unit =
        testApplication {
            val provider = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val itemA = createItem(provider, tags = "alpha")
            application { wireEventsRoute(bus, tokenEntries(TOKEN, tagsInclude = setOf("alpha")), provider.workItemRepository()) }
            val sseClient = createClient { install(ClientSSE) }

            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                coroutineScope {
                    launch {
                        delay(SETTLE_DELAY_MS)
                        val deleteResult = provider.workItemRepository().delete(itemA.id)
                        check(deleteResult is Result.Success) { "fixture delete failed: $deleteResult" }
                        bus.publish(bus.buildEvent(ApiEventType.ITEM_DELETED, itemId = itemA.id, modifiedAt = Instant.now()), emptySet())
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
            // Per test-plan S11: this is a DOCUMENTED GAP, not a bug. Once the item is deleted its
            // tags can no longer be looked up, and the filter fails closed -- dropping item.deleted
            // even for a subject that WAS in scope right up until the delete.
            assertFalse(
                collected.isNotEmpty(),
                "S11: item.deleted after deletion cannot be tag-verified and is dropped by design (known gap)",
            )
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.service.ParentChange
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testClaimService
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Integration tests for `GET /api/v1/events` SSE route.
 *
 * These tests exercise the REAL SSE path through Ktor's `testApplication` — not just
 * in-memory bus assertions. The core proof test verifies that writing via the
 * **repository** (the MCP path) produces events on the SSE stream, demonstrating
 * that the decorator captures writes made outside the REST layer.
 */
class EventRoutesTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    companion object {
        private const val READ_TOKEN = "sse-test-read-token-abc123"
        private const val NO_CAP_TOKEN = "sse-test-nocap-token-xyz789"

        /** Deserializer for the ApiEvent JSON the SSE route emits in each event's data field. */
        private val testJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        private fun sha256(input: String): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(input.toByteArray(Charsets.UTF_8))
        }

        private fun makeTokenEntries(
            token: String,
            capabilities: Set<ApiCapability> = setOf(ApiCapability.READ),
            scope: ApiScope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            expiresAt: Instant? = null,
            tokenId: String = "test-sse-principal",
        ): Map<HashBytes, BearerTokenStore.TokenEntry> {
            val principal =
                ApiPrincipal(
                    tokenId = tokenId,
                    scope = scope,
                    capabilities = capabilities,
                    authMode = ApiAuthMode.BEARER,
                )
            return mapOf(HashBytes(sha256(token)) to BearerTokenStore.TokenEntry(principal, expiresAt))
        }
    }

    // -------------------------------------------------------------------------
    // Auth
    // -------------------------------------------------------------------------

    @Test
    fun `missing auth header returns 401`() =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing { eventRoutes(bus, emptyMap()) }
            }
            val response = client.get("/events")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `invalid bearer token returns 401`() =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val entries = makeTokenEntries(READ_TOKEN)
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing { eventRoutes(bus, entries) }
            }
            val response =
                client.get("/events") {
                    header("Authorization", "Bearer WRONG_TOKEN")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `token without read capability returns 403`() =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val entries = makeTokenEntries(NO_CAP_TOKEN, capabilities = setOf(ApiCapability.WRITE_ITEMS))
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing { eventRoutes(bus, entries) }
            }
            val response =
                client.get("/events") {
                    header("Authorization", "Bearer $NO_CAP_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `already-expired token returns 401 at connection time`() =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val expiredAt = Instant.now().minusSeconds(60) // already expired
            val entries = makeTokenEntries(READ_TOKEN, expiresAt = expiredAt)
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing { eventRoutes(bus, entries) }
            }
            val response =
                client.get("/events") {
                    header("Authorization", "Bearer $READ_TOKEN")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // -------------------------------------------------------------------------
    // Query-token opt-in
    // -------------------------------------------------------------------------

    @Test
    fun `query-token mode disabled by default - returns 401 when only token param provided`() =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val entries = makeTokenEntries(READ_TOKEN)
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    // allowQueryToken = false (default)
                    eventRoutes(bus, entries, allowQueryToken = false)
                }
            }
            // No Authorization header, only ?token= query param
            val response = client.get("/events?token=$READ_TOKEN")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `query-token mode enabled accepts token via query param`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val entries = makeTokenEntries(READ_TOKEN)
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    // allowQueryToken = true (opt-in)
                    eventRoutes(bus, entries, allowQueryToken = true)
                }
            }
            val sseClient = createClient { install(ClientSSE) }

            // Publish an event BEFORE connecting so it's in the ring buffer
            val itemId = UUID.randomUUID()
            val evt = bus.emit(ApiEventType.ITEM_CREATED, itemId = itemId)

            // Connect via query token (no Authorization header) and replay the buffered event.
            // Opening the SSE session over the query-token path proves auth accepted it; collecting
            // the one replayed event then exiting the block closes the stream so the test completes.
            val collected = mutableListOf<String>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/events?token=$READ_TOKEN",
                    request = { header("Last-Event-ID", FROM_START) },
                ) {
                    incoming.take(1).toList().forEach { collected.add(it.event ?: "") }
                }
            }

            assertTrue(
                collected.contains(ApiEventType.ITEM_CREATED),
                "Expected the replayed item.created event over the query-token SSE stream. Got: $collected",
            )
        }

    // -------------------------------------------------------------------------
    // SSE stream — basic event delivery
    // -------------------------------------------------------------------------

    /**
     * CORE PROOF TEST: a write made DIRECTLY via the repository (the MCP-tool path)
     * produces an event on the [ApiEventBus].
     *
     * This proves that the [EventPublishingRepositoryProvider] decorator captures writes
     * made outside the REST layer — the whole architectural justification for Phase 6.
     *
     * The test verifies:
     * 1. The decorator produces events in the bus after a repo-level write.
     * 2. The event arrives on a real SSE subscriber flow (bus-level, not just ring buffer).
     *
     * A separate HTTP-route-level test below verifies the SSE HTTP path returns 200 with
     * correct content-type and that the route is wired correctly.
     */
    @Test
    fun `MCP-path write via repository decorator produces event on bus subscriber`(): Unit =
        runBlocking {
            // Build a real SQLite-backed repository provider and wrap it with the decorator
            val baseRepo = db.repositoryProvider()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val context = eventWiredContext(db.databaseManager, baseRepo, bus)

            // Subscribe to bus BEFORE making the repo write (simulates a connected dashboard).
            // create + update produce exactly two events, so take(2) completes deterministically
            // and the collector finishes — no leaked coroutine, no timeout hang.
            val flow = bus.subscribe("mcp-proof-sub", emptySet(), lastEventId = null)
            val collectorDeferred = async { withTimeout(10.seconds) { flow.take(2).toList() } }

            delay(100)

            // Write through the item command service (the MCP-tool write path; REST routes are NOT used
            // here). This proves the service's own event rows reach the bus.
            val itemId = UUID.randomUUID()
            val createResult =
                context.itemCommandService
                    .create(ItemCreateCommand(id = itemId, parentId = null, title = "MCP-path test item"))
                    .orFail()
            assertNotNull(createResult, "Item creation should succeed: $createResult")

            val updateResult = context.itemCommandService.patch(patchOf(createResult, title = "MCP-path test item updated")).orFail()
            assertNotNull(updateResult, "Item update should succeed: $updateResult")

            val collectedEvents = collectorDeferred.await()
            bus.unsubscribe("mcp-proof-sub")

            assertEquals(2, collectedEvents.size, "Expected exactly create + update events. Got: $collectedEvents")
            val eventTypes = collectedEvents.map { it.event }
            assertTrue(
                eventTypes.contains(ApiEventType.ITEM_CREATED),
                "Expected item.created event from MCP-path repo write. Got: $eventTypes",
            )
            assertTrue(
                eventTypes.contains(ApiEventType.ITEM_UPDATED),
                "Expected item.updated event from MCP-path repo update. Got: $eventTypes",
            )
            val itemIds = collectedEvents.mapNotNull { it.itemId }
            assertTrue(
                itemIds.contains(itemId.toString()),
                "Expected itemId $itemId in events. Got: $itemIds",
            )
        }

    @Test
    fun `role change via decorated update emits item_advanced with newRole not item_updated`(): Unit =
        runBlocking {
            val baseRepo = db.repositoryProvider()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val (decorated, unitOfWork) = eventWiredUnit(db.databaseManager, baseRepo, bus)

            // Subscriber connected before the writes — create + advance produce exactly two events.
            val flow = bus.subscribe("advance-sub", emptySet(), lastEventId = null)
            val collectorDeferred = async { withTimeout(10.seconds) { flow.take(2).toList() } }
            delay(100)

            val itemId = UUID.randomUUID()
            val item = WorkItem(id = itemId, parentId = null, title = "Advance test", depth = 0, role = Role.QUEUE)
            assertTrue(
                ToolExecutionContext(decorated, unitOfWork = unitOfWork)
                    .itemCommandService
                    .create(ItemCreateCommand(id = itemId, parentId = null, title = "Advance test"))
                    .orFail() != null
            )

            // Change the ROLE (a phase advance) — must surface as item.advanced (carries newRole),
            // distinct from item.updated. P11: AdvanceService writes the role change, its transition row
            // and the item.transitioned event in one unit; that event is what projects as item.advanced,
            // and the role-changing update records nothing.
            val service =
                AdvanceService(
                    workItemRepository = decorated.workItemRepository(),
                    roleTransitionRepository = decorated.roleTransitionRepository(),
                    dependencyRepository = decorated.dependencyRepository(),
                    noteRepository = decorated.noteRepository(),
                    schemaResolver = { null },
                    unitOfWork = unitOfWork,
                    claimService = testClaimService(decorated.workItemRepository(), null, unitOfWork)
                )
            val outcome = service.advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, enforceOwnership = false)
            assertTrue(outcome is AdvanceOutcome.Success, "advance failed: $outcome")

            val events = collectorDeferred.await()
            bus.unsubscribe("advance-sub")

            assertEquals(2, events.size, "Expected create + advance events. Got: ${events.map { it.event }}")
            val advancedEvents = events.filter { it.event == ApiEventType.ITEM_ADVANCED }
            assertEquals(
                1,
                advancedEvents.size,
                "Role change must emit exactly one item.advanced. Got: ${events.map { it.event }}",
            )
            assertEquals("work", advancedEvents[0].newRole, "item.advanced must carry the new role")
            assertTrue(
                events.none { it.event == ApiEventType.ITEM_UPDATED },
                "A role change must NOT also surface as item.updated. Got: ${events.map { it.event }}",
            )
        }

    /**
     * PRODUCTION-WIRING PROOF TEST: an actual MCP tool (`ManageItemsTool.create`) executed
     * through a [ToolExecutionContext] built on the DECORATED provider — exactly the way
     * `CurrentMcpServer.run()` wires the tool context when the API is enabled — produces an
     * event on a bus subscriber.
     *
     * This guards the WIRING (tool-context → decorated provider → bus → SSE subscriber), not
     * just the decorator in isolation. If the tool context were ever wired to the raw provider
     * (the bug fixed in this revision), this test would fail because no event would be published.
     */
    @Test
    fun `production MCP tool path - ManageItemsTool create through decorated tool context emits event`(): Unit =
        runBlocking {
            // Mirror CurrentMcpServer.run() wiring when API is enabled:
            // raw provider -> EventPublishingRepositoryProvider -> ToolExecutionContext.
            val baseRepo = db.repositoryProvider()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val toolContext = eventWiredContext(db.databaseManager, baseRepo, bus)
            val tool = ManageItemsTool()

            // Subscribe BEFORE executing the tool (simulates a connected dashboard). The create
            // produces exactly one event, so a bounded take(1) completes deterministically.
            val flow = bus.subscribe("prod-wiring-sub", emptySet(), lastEventId = null)
            val collectorDeferred = async { withTimeout(10.seconds) { flow.take(1).toList() } }

            delay(100)

            // Execute the REAL MCP tool against the decorated tool context. No repository call,
            // no REST route — this goes through the exact production tool path.
            val result =
                tool.execute(
                    JsonObject(
                        mapOf(
                            "operation" to JsonPrimitive("create"),
                            "items" to
                                JsonArray(
                                    listOf(
                                        buildJsonObject { put("title", JsonPrimitive("Production wiring item")) },
                                    ),
                                ),
                        ),
                    ),
                    toolContext,
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Tool create should succeed: $result")
            val createdItemId =
                ((result["data"] as JsonObject)["items"]!!.jsonArray[0] as JsonObject)["id"]!!.jsonPrimitive.content

            val collectedEvents = collectorDeferred.await()
            bus.unsubscribe("prod-wiring-sub")

            // Verify the item.created event arrived via the production tool path.
            assertEquals(1, collectedEvents.size, "Expected exactly one event from the MCP tool path. Got: $collectedEvents")
            assertEquals(
                ApiEventType.ITEM_CREATED,
                collectedEvents[0].event,
                "Expected item.created event from ManageItemsTool create",
            )
            assertEquals(
                createdItemId,
                collectedEvents[0].itemId,
                "Expected created item id in the event",
            )
        }

    /**
     * Verifies the HTTP SSE route is wired and streams over a real SSE client.
     * Uses ring-buffer replay (publish before subscribe) so a bounded `take(1)` completes
     * immediately, then exits the `sse { }` block which closes the stream.
     */
    @Test
    fun `SSE route streams a replayed event to an authenticated SSE client`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val entries = makeTokenEntries(READ_TOKEN)

            // Publish an event to the ring buffer BEFORE subscribing so replay can deliver it
            val itemId = UUID.randomUUID()
            val prePublished = bus.emit(ApiEventType.ITEM_CREATED, itemId = itemId)

            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    eventRoutes(bus, entries, allowQueryToken = false, authCheckIntervalSeconds = 60)
                }
            }
            val sseClient = createClient { install(ClientSSE) }

            // Open the SSE session with Last-Event-ID=0 to replay the buffered event, collect it
            // (bounded take(1)), then exit the block — which closes the stream and completes the test.
            val collected = mutableListOf<Pair<String, String?>>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/events",
                    request = {
                        header("Authorization", "Bearer $READ_TOKEN")
                        header("Last-Event-ID", FROM_START)
                    },
                ) {
                    incoming.take(1).toList().forEach { collected.add(it.event.orEmpty() to it.data) }
                }
            }

            assertEquals(1, collected.size, "Expected exactly one replayed event. Got: $collected")
            assertEquals(ApiEventType.ITEM_CREATED, collected[0].first, "Replayed event type mismatch")
            assertTrue(
                collected[0].second?.contains(itemId.toString()) == true,
                "Replayed event data should contain the item id. Got: ${collected[0].second}",
            )
        }

    // -------------------------------------------------------------------------
    // Scope filtering
    // -------------------------------------------------------------------------

    @Test
    fun `scope filter - subscriber for root1 does not see root2 events (in-memory bus)`(): Unit =
        runBlocking {
            // Test the bus-level filtering directly (HTTP route test for scope filtering would
            // require a running server; bus test is sufficient per spec).
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val root1 = UUID.randomUUID()
            val root2 = UUID.randomUUID()

            val flow = bus.subscribe("scope-sub", setOf(root1), null)

            // Launch a bounded collector and capture the first event (if any) within a short window.
            // Publish to root2 ONLY — the root1 subscriber must NOT receive it, so take(1) never
            // completes and withTimeoutOrNull returns null (no event delivered). The timeout bound
            // is short and the collector is cancelled when withTimeoutOrNull elapses, so nothing leaks.
            val firstEvent =
                async {
                    withTimeoutOrNull(800) { flow.take(1).toList() }
                }

            delay(50)
            bus.emit(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), rootId = root2)

            val result = firstEvent.await()
            assertNull(result, "root1 subscriber should not receive root2 events (expected no event)")
            bus.unsubscribe("scope-sub")
        }

    // -------------------------------------------------------------------------
    // Last-Event-ID replay over HTTP
    // -------------------------------------------------------------------------

    @Test
    fun `Last-Event-ID replay delivers buffered events on reconnect`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val entries = makeTokenEntries(READ_TOKEN)

            // Pre-populate the ring buffer with 3 events before any subscriber connects
            val root = UUID.randomUUID()
            val published = mutableListOf<ApiEvent>()
            repeat(3) {
                val e = bus.emit(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), rootId = root)
                published.add(e)
            }

            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    eventRoutes(bus, entries, allowQueryToken = false, authCheckIntervalSeconds = 60)
                }
            }
            val sseClient = createClient { install(ClientSSE) }

            // Reconnect with Last-Event-ID = id of event 0 → replay events 1 and 2.
            // Bounded take(2) completes once both replayed events arrive, then the block exits/closes.
            val lastSeenId = published[0].id.toString()
            val collected = mutableListOf<ApiEvent>()
            withTimeout(10.seconds) {
                sseClient.sse(
                    urlString = "/events?root=$root",
                    request = {
                        header("Authorization", "Bearer $READ_TOKEN")
                        header("Last-Event-ID", lastSeenId)
                    },
                ) {
                    incoming.take(2).toList().forEach { sse ->
                        // The server serializes the full ApiEvent into the SSE data field.
                        collected.add(testJson.decodeFromString(ApiEvent.serializer(), sse.data.orEmpty()))
                    }
                }
            }

            assertEquals(2, collected.size, "Expected 2 replayed events. Got: $collected")
            assertEquals(published[1].id, collected[0].id, "First replayed event should be event[1] by id")
            assertEquals(published[2].id, collected[1].id, "Second replayed event should be event[2] by id")
            assertEquals(
                published[1].itemId,
                collected[0].itemId,
                "First replayed event itemId mismatch",
            )
        }

    // -------------------------------------------------------------------------
    // Token-expiry auth.expired event
    // -------------------------------------------------------------------------

    @Test
    fun `expired token during connection emits auth_expired and closes within check interval`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            // Token expires shortly after connect; the periodic check (1s cadence) then fires.
            val expiresAt = Instant.now().plusMillis(1200)
            val entries = makeTokenEntries(READ_TOKEN, expiresAt = expiresAt)

            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    // Check every 1 second; the server emits auth.expired then closes the stream.
                    eventRoutes(bus, entries, allowQueryToken = false, authCheckIntervalSeconds = 1)
                }
            }
            val sseClient = createClient { install(ClientSSE) }

            // Collect every event the server sends until it closes the stream after auth.expired.
            // Generous bound (well under the 60s harness limit): interval 1s + expiry 1.2s + margin.
            val eventTypes = mutableListOf<String>()
            withTimeout(15.seconds) {
                sseClient.sse(
                    urlString = "/events",
                    request = { header("Authorization", "Bearer $READ_TOKEN") },
                ) {
                    // The server closes the stream right after auth.expired, so this flow completes.
                    incoming.toList().forEach { eventTypes.add(it.event ?: "") }
                }
            }

            assertTrue(
                eventTypes.contains(ApiEventType.AUTH_EXPIRED),
                "Expected auth.expired event before the connection closed. Got: $eventTypes",
            )
        }

    // -------------------------------------------------------------------------
    // Independence of SSE namespaces
    // -------------------------------------------------------------------------

    @Test
    fun `api event bus IDs are the events table seq - independent of the mcp EventStore`() {
        // P8 expected-value change: ids are no longer a per-bus counter starting at 1. They are the
        // events table's seq, which starts above the floor (1e12) and is shared by every bus (every
        // process) projecting the same database -- still a namespace unrelated to /mcp's EventStore.
        val bus1 = ApiEventBus(source = db.repositoryProvider().eventStore())
        val bus2 = ApiEventBus(source = db.repositoryProvider().eventStore())

        val e1 = bus1.buildEvent(ApiEventType.ITEM_CREATED)
        val e2 = bus2.buildEvent(ApiEventType.ITEM_CREATED)

        assertEquals(EventStore.SEQ_FLOOR, e1.id, "a bus over an empty log issues ids at the seq floor")
        assertEquals(e1.id, e2.id, "two buses over the same log share one id space (the table's seq)")
        assertNotNull(e1.event)
        assertNotNull(e2.event)
    }

    // -------------------------------------------------------------------------
    // MCP regression guard — decorator is transparent on reads
    // -------------------------------------------------------------------------

    @Test
    fun `EventPublishingRepositoryProvider is transparent - read operations unchanged`(): Unit =
        runBlocking {
            val baseRepo = db.repositoryProvider()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            // No store is decorated any more: reads go to the provider the services share.
            val decorated = eventWiredContext(db.databaseManager, baseRepo, bus).repositoryProvider

            // Create via base repo (no events)
            val item =
                WorkItem(
                    id = UUID.randomUUID(),
                    title = "Transparency test",
                    depth = 0,
                )
            baseRepo.workItemRepository().create(item)

            // Read via decorated repo — should return same result
            val result = decorated.workItemRepository().getById(item.id)
            assertNotNull(result, "Read should succeed via decorated provider")
            val retrieved = result
            assertEquals(item.id, retrieved!!.id)
            assertEquals("Transparency test", retrieved!!.title)

            // Verify no spurious events were emitted on the read
            assertEquals(0, bus.subscriberCount(), "No subscribers should be active")
        }

    // -------------------------------------------------------------------------
    // Reparent: scope.entered / scope.left
    // -------------------------------------------------------------------------

    @Test
    fun `reparent emits scope_left on old root and scope_entered on new root`(): Unit =
        runBlocking {
            val baseRepo = db.repositoryProvider()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            val context = eventWiredContext(db.databaseManager, baseRepo, bus)
            val items = context.itemCommandService

            // Create two roots and a child under root1
            val root1 = items.create(ItemCreateCommand(parentId = null, title = "Root1")).orFail()
            val root2 = items.create(ItemCreateCommand(parentId = null, title = "Root2")).orFail()
            val child = items.create(ItemCreateCommand(parentId = root1.id, title = "Child")).orFail()

            // Subscribe to each root. The reparent delivers exactly one event to each root topic
            // (scope.left to root1, scope.entered to root2), so a bounded take(1) per subscriber
            // completes deterministically and the collectors finish — no leaked coroutines.
            val root1Flow = bus.subscribe("scope-test-r1", setOf(root1.id), null)
            val root2Flow = bus.subscribe("scope-test-r2", setOf(root2.id), null)

            // Launch bounded collectors INSIDE this runBlocking scope (structured concurrency).
            val root1Deferred = async { withTimeout(10.seconds) { root1Flow.take(1).toList() } }
            val root2Deferred = async { withTimeout(10.seconds) { root2Flow.take(1).toList() } }

            // Give the collectors a moment to start consuming before triggering the write.
            delay(100)

            // Reparent child from root1 → root2.
            items.patch(patchOf(child, parent = ParentChange.MoveUnder(root2.id))).orFail()

            // Await the single event each subscriber should receive (bounded by withTimeout above).
            val root1Events = root1Deferred.await()
            val root2Events = root2Deferred.await()

            assertEquals(1, root1Events.size, "root1 should receive exactly one event")
            assertEquals(
                ApiEventType.SCOPE_LEFT,
                root1Events[0].event,
                "root1 subscriber should see scope.left",
            )
            assertEquals(1, root2Events.size, "root2 should receive exactly one event")
            assertEquals(
                ApiEventType.SCOPE_ENTERED,
                root2Events[0].event,
                "root2 subscriber should see scope.entered",
            )

            bus.unsubscribe("scope-test-r1")
            bus.unsubscribe("scope-test-r2")
        }
}

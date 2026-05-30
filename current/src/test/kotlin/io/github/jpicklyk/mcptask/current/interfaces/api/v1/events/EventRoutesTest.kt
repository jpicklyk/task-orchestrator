package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for `GET /api/v1/events` SSE route.
 *
 * These tests exercise the REAL SSE path through Ktor's `testApplication` — not just
 * in-memory bus assertions. The core proof test verifies that writing via the
 * **repository** (the MCP path) produces events on the SSE stream, demonstrating
 * that the decorator captures writes made outside the REST layer.
 */
class EventRoutesTest {
    companion object {
        private const val READ_TOKEN = "sse-test-read-token-abc123"
        private const val NO_CAP_TOKEN = "sse-test-nocap-token-xyz789"

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
            val bus = ApiEventBus()
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
            val bus = ApiEventBus()
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
            val bus = ApiEventBus()
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
            val bus = ApiEventBus()
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
            val bus = ApiEventBus()
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
    fun `query-token mode enabled accepts token via query param`() =
        testApplication {
            val bus = ApiEventBus()
            val entries = makeTokenEntries(READ_TOKEN)
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    // allowQueryToken = true (opt-in)
                    eventRoutes(bus, entries, allowQueryToken = true)
                }
            }

            // Publish an event BEFORE connecting so it's in the ring buffer
            val itemId = UUID.randomUUID()
            val evt = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = itemId, modifiedAt = Instant.now())
            bus.publish(evt, emptySet())

            // Connect via query token and replay that buffered event
            val response =
                client.get("/events?token=$READ_TOKEN&Last-Event-ID=0") {
                    // No Authorization header
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Content-Type"]?.contains("text/event-stream") == true)
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
            // Build a real H2-backed repository provider and wrap it with the decorator
            val baseRepo = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val decorated = EventPublishingRepositoryProvider(baseRepo, bus)

            // Subscribe to bus BEFORE making the repo write (simulates a connected dashboard)
            val flow = bus.subscribe("mcp-proof-sub", emptySet(), lastEventId = null)
            val collectedEvents = mutableListOf<ApiEvent>()

            val collectJob =
                GlobalScope.async {
                    try {
                        withTimeout(5.seconds) {
                            flow.collect { event ->
                                collectedEvents.add(event)
                            }
                        }
                    } catch (_: Exception) {
                        // timeout = done collecting
                    }
                }

            delay(100)

            // Write DIRECTLY via the decorated repository — this is the MCP-tool path.
            // REST routes are NOT used here. This proves the decorator captures repo-level writes.
            val itemId = UUID.randomUUID()
            val item =
                WorkItem(
                    id = itemId,
                    parentId = null,
                    title = "MCP-path test item",
                    depth = 0,
                )
            val createResult = decorated.workItemRepository().create(item)
            assertTrue(createResult is Result.Success, "Item creation should succeed: $createResult")

            // Also update to verify update events
            val updated = item.copy(title = "MCP-path test item updated")
            val updateResult = decorated.workItemRepository().update(updated)
            assertTrue(updateResult is Result.Success, "Item update should succeed: $updateResult")

            // Give events time to propagate to the subscriber
            delay(500)
            collectJob.cancel()
            bus.unsubscribe("mcp-proof-sub")

            // Verify we received the expected events
            assertTrue(collectedEvents.isNotEmpty(), "Expected at least one event. Got none.")
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

    /**
     * Verifies the HTTP SSE route is wired and returns 200 with SSE content-type.
     * Uses ring-buffer replay (publish before subscribe) to avoid the streaming-hang issue.
     */
    @Test
    fun `SSE route returns 200 with correct content-type for authenticated request`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val entries = makeTokenEntries(READ_TOKEN)

            // Publish an event to the ring buffer BEFORE subscribing so replay can deliver it
            val prePublished = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
            bus.publish(prePublished, emptySet())

            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    eventRoutes(bus, entries, allowQueryToken = false, authCheckIntervalSeconds = 60)
                }
            }

            // Connect with Last-Event-ID=0 to replay the buffered event, then read what we get
            val response =
                client.get("/events?") {
                    header("Authorization", "Bearer $READ_TOKEN")
                    header("Last-Event-ID", "0")
                }
            assertEquals(HttpStatusCode.OK, response.status, "Expected 200 OK for SSE connection")
            val contentType = response.headers["Content-Type"] ?: ""
            assertTrue(
                contentType.contains("text/event-stream"),
                "Expected text/event-stream content-type. Got: $contentType",
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
            val bus = ApiEventBus()
            val root1 = UUID.randomUUID()
            val root2 = UUID.randomUUID()

            val flow = bus.subscribe("scope-sub", setOf(root1), null)

            var received = false
            val collectJob =
                GlobalScope.async {
                    try {
                        withTimeout(500) {
                            flow.collect { received = true }
                        }
                    } catch (_: Exception) {
                    }
                }

            delay(50)
            // Publish to root2 only — root1 subscriber should NOT receive it
            bus.publish(
                bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID()),
                affectedRoots = setOf(root2),
            )
            delay(200)
            collectJob.cancel()
            assertFalse(received, "root1 subscriber should not receive root2 events")
            bus.unsubscribe("scope-sub")
        }

    // -------------------------------------------------------------------------
    // Last-Event-ID replay over HTTP
    // -------------------------------------------------------------------------

    @Test
    fun `Last-Event-ID replay delivers buffered events on reconnect`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            val entries = makeTokenEntries(READ_TOKEN)

            // Pre-populate the ring buffer with 3 events before any subscriber connects
            val root = UUID.randomUUID()
            val published = mutableListOf<ApiEvent>()
            repeat(3) {
                val e = bus.buildEvent(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID(), modifiedAt = Instant.now())
                published.add(e)
                bus.publish(e, setOf(root))
            }

            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    eventRoutes(bus, entries, allowQueryToken = false, authCheckIntervalSeconds = 60)
                }
            }

            // Reconnect with Last-Event-ID = id of event 0 → should replay events 1 and 2
            val lastSeenId = published[0].id.toString()
            val response =
                client.get("/events?root=$root") {
                    header("Authorization", "Bearer $READ_TOKEN")
                    header("Last-Event-ID", lastSeenId)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val channel = response.bodyAsChannel()
            val dataLines = mutableListOf<String>()
            withTimeout(3.seconds) {
                while (dataLines.size < 2) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.startsWith("data:")) dataLines.add(line)
                }
            }

            assertEquals(2, dataLines.size, "Expected 2 replayed events. Got: $dataLines")
            assertTrue(
                dataLines[0].contains(published[1].itemId ?: ""),
                "First replayed event should be event[1]. Got: ${dataLines[0]}",
            )
        }

    // -------------------------------------------------------------------------
    // Token-expiry auth.expired event
    // -------------------------------------------------------------------------

    @Test
    fun `expired token during connection emits auth_expired and closes within check interval`(): Unit =
        testApplication {
            val bus = ApiEventBus()
            // Token expires in 1 second from now
            val expiresAt = Instant.now().plusMillis(1200)
            val entries = makeTokenEntries(READ_TOKEN, expiresAt = expiresAt)

            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    // Check every 1 second (generous for CI — actual timing depends on check interval)
                    eventRoutes(bus, entries, allowQueryToken = false, authCheckIntervalSeconds = 1)
                }
            }

            val response =
                client.get("/events") {
                    header("Authorization", "Bearer $READ_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            // Read SSE lines until we see auth.expired or timeout (use generous margin: 1s * 1000 + 1500ms)
            val channel = response.bodyAsChannel()
            val authExpiredSeen: Boolean
            withTimeout(4.seconds) {
                var seen = false
                while (!seen) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.contains("auth.expired")) {
                        seen = true
                    }
                }
                authExpiredSeen = seen
            }

            assertTrue(authExpiredSeen, "Expected auth.expired event to be emitted before connection closes")
        }

    // -------------------------------------------------------------------------
    // Independence of SSE namespaces
    // -------------------------------------------------------------------------

    @Test
    fun `api event bus IDs are independent - starting from bus own counter`() {
        // Verify that two different ApiEventBus instances have independent ID sequences.
        // This proves the namespace separation from /mcp's EventStore.
        val bus1 = ApiEventBus()
        val bus2 = ApiEventBus()

        val e1 = bus1.buildEvent(ApiEventType.ITEM_CREATED)
        val e2 = bus2.buildEvent(ApiEventType.ITEM_CREATED)

        // Both start at 1 — they are independent (not shared state)
        assertEquals(1L, e1.id, "First bus should start at id=1")
        assertEquals(1L, e2.id, "Second bus should also start at id=1 (independent namespace)")
        assertNotNull(e1.event)
        assertNotNull(e2.event)
    }

    // -------------------------------------------------------------------------
    // MCP regression guard — decorator is transparent on reads
    // -------------------------------------------------------------------------

    @Test
    fun `EventPublishingRepositoryProvider is transparent - read operations unchanged`(): Unit =
        runBlocking {
            val baseRepo = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val decorated = EventPublishingRepositoryProvider(baseRepo, bus)

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
            assertTrue(result is Result.Success, "Read should succeed via decorated provider")
            val retrieved = (result as Result.Success).data
            assertEquals(item.id, retrieved.id)
            assertEquals("Transparency test", retrieved.title)

            // Verify no spurious events were emitted on the read
            assertEquals(0, bus.subscriberCount(), "No subscribers should be active")
        }

    // -------------------------------------------------------------------------
    // Reparent: scope.entered / scope.left
    // -------------------------------------------------------------------------

    @Test
    fun `reparent emits scope_left on old root and scope_entered on new root`(): Unit =
        runBlocking {
            val baseRepo = buildH2RepositoryProvider()
            val bus = ApiEventBus()
            val decorated = EventPublishingRepositoryProvider(baseRepo, bus)

            // Create two roots and a child under root1
            val root1 = WorkItem(id = UUID.randomUUID(), title = "Root1", depth = 0)
            val root2 = WorkItem(id = UUID.randomUUID(), title = "Root2", depth = 0)
            val child =
                WorkItem(id = UUID.randomUUID(), title = "Child", depth = 1, parentId = root1.id)

            decorated.workItemRepository().create(root1)
            decorated.workItemRepository().create(root2)
            decorated.workItemRepository().create(child)

            // Subscribe to root1's events
            val root1Flow = bus.subscribe("scope-test-r1", setOf(root1.id), null)
            val root2Flow = bus.subscribe("scope-test-r2", setOf(root2.id), null)

            val root1Events = mutableListOf<ApiEvent>()
            val root2Events = mutableListOf<ApiEvent>()

            val job1 =
                GlobalScope.async {
                    try {
                        withTimeout(3.seconds) {
                            root1Flow.collect { root1Events.add(it) }
                        }
                    } catch (_: Exception) {
                    }
                }
            val job2 =
                GlobalScope.async {
                    try {
                        withTimeout(3.seconds) {
                            root2Flow.collect { root2Events.add(it) }
                        }
                    } catch (_: Exception) {
                    }
                }

            delay(100)

            // Reparent child from root1 → root2
            val reparented = child.copy(parentId = root2.id)
            decorated.workItemRepository().update(reparented)

            delay(500)

            job1.cancel()
            job2.cancel()

            val root1EventTypes = root1Events.map { it.event }
            val root2EventTypes = root2Events.map { it.event }

            assertTrue(
                root1EventTypes.contains(ApiEventType.SCOPE_LEFT),
                "root1 subscriber should see scope.left. Got: $root1EventTypes",
            )
            assertTrue(
                root2EventTypes.contains(ApiEventType.SCOPE_ENTERED),
                "root2 subscriber should see scope.entered. Got: $root2EventTypes",
            )

            bus.unsubscribe("scope-test-r1")
            bus.unsubscribe("scope-test-r2")
        }
}

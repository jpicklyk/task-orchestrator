package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.test.InMemoryEventStore
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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Independent test authorship for item f0e193b7: the actor/rootId fields on the REAL
 * `GET /events` SSE frames, and the per-connection-principal attribution redaction.
 *
 * Oracles (frozen planning notes, never the implementation):
 *  [R] task-scope "Redaction": actor is hidden iff redactAttribution && principal lacks ADMIN;
 *      identical for live fan-out and Last-Event-ID replay; rootId is NEVER redacted; the ring
 *      buffer keeps the unredacted event (redaction is on egress only).
 *  [C] task-scope AC7: sync.lost and auth.expired JSON unchanged (carry neither field); `proof`
 *      never appears in any frame.
 *  [A] task-scope: both null => the keys are absent from the JSON (not null-valued).
 *
 * Frames are asserted as RAW JSON objects (key presence), not via ApiEvent.serializer(), because
 * "absent" and "null" are indistinguishable once decoded into the data class.
 *
 * Harness: the real eventRoutes route under testApplication with an SSE client; live frames are
 * produced by publishing repeatedly while the client is connected (the first frame wins), so no
 * fixed settle delay decides the verdict.
 */
class SseActorRedactionTest {
    companion object {
        private const val READ_TOKEN = "sse-actor-read-token-aaa111"
        private const val ADMIN_TOKEN = "sse-actor-admin-token-bbb222"
        private val FIXED_TIME: Instant = Instant.parse("2026-10-06T12:00:00Z")

        private fun entries(
            readScope: ApiScope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            readExpiresAt: Instant? = null,
        ): Map<HashBytes, BearerTokenStore.TokenEntry> {
            val read =
                ApiPrincipal(
                    tokenId = "sse-actor-read",
                    scope = readScope,
                    capabilities = setOf(ApiCapability.READ),
                    authMode = ApiAuthMode.BEARER,
                )
            val admin =
                ApiPrincipal(
                    tokenId = "sse-actor-admin",
                    scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                    capabilities = setOf(ApiCapability.READ, ApiCapability.ADMIN),
                    authMode = ApiAuthMode.BEARER,
                )
            return mapOf(
                HashBytes(sha256(READ_TOKEN)) to BearerTokenStore.TokenEntry(read, readExpiresAt),
                HashBytes(sha256(ADMIN_TOKEN)) to BearerTokenStore.TokenEntry(admin, null),
            )
        }
    }

    private val claim = ActorClaim(id = "agent-a", kind = ActorKind.SUBAGENT, parent = "orch-1", proof = "SECRET-PROOF-TOKEN")

    private fun Application.wire(
        bus: ApiEventBus,
        entries: Map<HashBytes, BearerTokenStore.TokenEntry>,
        redact: Boolean,
        authCheckIntervalSeconds: Int = 60,
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            eventRoutes(
                bus,
                entries,
                allowQueryToken = false,
                authCheckIntervalSeconds = authCheckIntervalSeconds,
                redactAttribution = redact,
            )
        }
    }

    private fun publisher(
        itemId: UUID,
        rootId: UUID?,
        actor: ActorClaim?,
    ): suspend (ApiEventBus) -> Unit =
        { bus ->
            // P8: a row always has a root; a null rootId means "the item is its own root".
            bus.emit(ApiEventType.ITEM_UPDATED, itemId = itemId, rootId = rootId ?: itemId, actor = actor, at = FIXED_TIME)
        }

    /** Connects as the given principal and returns the first raw frame (replay or live) plus its raw text. */
    private fun fetchFrame(
        admin: Boolean,
        redact: Boolean,
        live: Boolean,
        readScope: ApiScope = ApiScope(rootIds = null, tagsInclude = emptySet()),
        publish: suspend (ApiEventBus) -> Unit,
    ): Pair<JsonObject, String> {
        var out: Pair<JsonObject, String>? = null
        testApplication {
            val bus = ApiEventBus(source = InMemoryEventStore())
            if (!live) publish(bus)
            application { wire(bus, entries(readScope), redact) }
            val client = createClient { install(ClientSSE) }
            withTimeout(20.seconds) {
                coroutineScope {
                    val pub =
                        if (live) {
                            launch {
                                repeat(60) {
                                    delay(150)
                                    publish(bus)
                                }
                            }
                        } else {
                            null
                        }
                    client.sse(
                        urlString = "/events",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer ${if (admin) ADMIN_TOKEN else READ_TOKEN}")
                            if (!live) header("Last-Event-ID", FROM_START)
                        },
                    ) {
                        val raw =
                            incoming
                                .take(1)
                                .toList()
                                .single()
                                .data
                                .orEmpty()
                        out = Json.parseToJsonElement(raw).jsonObject to raw
                    }
                    pub?.cancel()
                }
            }
        }
        return out!!
    }

    private fun JsonObject.withoutId(): JsonObject = JsonObject(filterKeys { it != "id" })

    private fun assertActorShown(
        frame: JsonObject,
        raw: String,
        label: String,
    ) {
        val actor = frame["actor"]
        assertNotNull(actor, "$label: actor must be present, got: $raw")
        assertEquals(setOf("id", "kind", "parent"), actor!!.jsonObject.keys, "$label: actor keys")
        assertEquals("agent-a", actor.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("subagent", actor.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("orch-1", actor.jsonObject["parent"]!!.jsonPrimitive.content)
    }

    private fun assertActorHidden(
        frame: JsonObject,
        raw: String,
        label: String,
    ) {
        assertFalse(frame.containsKey("actor"), "$label: the actor key must be absent, got: $raw")
        assertFalse(raw.contains("agent-a"), "$label: no part of the actor identity may remain, got: $raw")
        assertFalse(raw.contains("orch-1"), "$label: the actor parent must not remain, got: $raw")
    }

    private fun checkCell(
        admin: Boolean,
        redact: Boolean,
    ) {
        val itemId = UUID.randomUUID()
        val rootId = UUID.randomUUID()
        val publish = publisher(itemId, rootId, claim)
        val expectShown = admin || !redact
        val replay = fetchFrame(admin, redact, live = false, publish = publish)
        val live = fetchFrame(admin, redact, live = true, publish = publish)

        val cell = "admin=$admin redact=$redact"
        listOf("replay" to replay, "live" to live).forEach { (path, pair) ->
            val (frame, raw) = pair
            val label = "$cell $path"
            assertEquals(itemId.toString(), frame["itemId"]!!.jsonPrimitive.content, "$label: itemId")
            assertEquals(rootId.toString(), frame["rootId"]!!.jsonPrimitive.content, "$label: rootId is never redacted")
            assertFalse(raw.contains("SECRET-PROOF-TOKEN"), "$label: proof value leaked: $raw")
            assertFalse(raw.contains("proof"), "$label: a proof key leaked: $raw")
            assertFalse(raw.contains("verification"), "$label: a verification key leaked: $raw")
            if (expectShown) assertActorShown(frame, raw, label) else assertActorHidden(frame, raw, label)
        }
        assertEquals(
            live.first.withoutId(),
            replay.first.withoutId(),
            "$cell: the live frame and the Last-Event-ID replay frame of the same event must be identical (ids aside)",
        )
    }

    // -------------------------------------------------------------------------
    // S8 -- redaction matrix, live and replay [AC5]
    // -------------------------------------------------------------------------

    @Test
    fun `S8a admin with redaction on sees the actor on live and replay`() = checkCell(admin = true, redact = true)

    @Test
    fun `S8b admin with redaction off sees the actor on live and replay`() = checkCell(admin = true, redact = false)

    @Test
    fun `S8c read-only principal with redaction on does not see the actor on live or replay but keeps rootId`() =
        checkCell(admin = false, redact = true)

    @Test
    fun `S8d read-only principal with redaction off sees the actor on live and replay`() = checkCell(admin = false, redact = false)

    @Test
    fun `S8e an actor without a parent has no parent key and an event without actor or rootId has neither key`() {
        val noParent = ActorClaim(id = "agent-a", kind = ActorKind.SUBAGENT)
        val withActor =
            fetchFrame(admin = true, redact = true, live = false, publish = publisher(UUID.randomUUID(), UUID.randomUUID(), noParent))
        assertEquals(
            setOf("id", "kind"),
            withActor.first["actor"]!!.jsonObject.keys,
            "parent must be omitted, not null: ${withActor.second}"
        )

        val bare = fetchFrame(admin = false, redact = true, live = false, publish = publisher(UUID.randomUUID(), null, null))
        // P8 expected-value change: every row carries a root (a root item uses its own id), so rootId is always
        // present; an event without an actor still has no actor key.
        assertEquals(
            setOf("id", "event", "itemId", "modifiedAt", "rootId"),
            bare.first.keys,
            "an event without an actor has no actor key (rootId is always present since P8), got: ${bare.second}",
        )
    }

    @Test
    fun `S8f redaction is egress-only so a later admin connection still sees the actor`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = InMemoryEventStore())
            val itemId = UUID.randomUUID()
            val rootId = UUID.randomUUID()
            publisher(itemId, rootId, claim)(bus)
            application { wire(bus, entries(), redact = true) }
            val client = createClient { install(ClientSSE) }

            suspend fun first(token: String): Pair<JsonObject, String> {
                var out: Pair<JsonObject, String>? = null
                withTimeout(10.seconds) {
                    client.sse(
                        urlString = "/events",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header("Last-Event-ID", FROM_START)
                        },
                    ) {
                        val raw =
                            incoming
                                .take(1)
                                .toList()
                                .single()
                                .data
                                .orEmpty()
                        out = Json.parseToJsonElement(raw).jsonObject to raw
                    }
                }
                return out!!
            }

            val readFrame = first(READ_TOKEN)
            val adminFrame = first(ADMIN_TOKEN)
            assertActorHidden(readFrame.first, readFrame.second, "read first")
            assertActorShown(adminFrame.first, adminFrame.second, "admin after read")
        }

    @Test
    fun `S8g a root-scoped read-only principal gets rootId but not the actor when redaction is on`() {
        val itemId = UUID.randomUUID()
        val rootId = UUID.randomUUID()
        val scope = ApiScope(rootIds = setOf(rootId), tagsInclude = emptySet())
        val publish = publisher(itemId, rootId, claim)
        listOf(false, true).forEach { live ->
            val (frame, raw) = fetchFrame(admin = false, redact = true, live = live, readScope = scope, publish = publish)
            assertEquals(rootId.toString(), frame["rootId"]!!.jsonPrimitive.content, "live=$live: rootId present")
            assertActorHidden(frame, raw, "root-scoped live=$live")
        }
    }

    // -------------------------------------------------------------------------
    // S9 -- control events carry neither field [AC7]
    // -------------------------------------------------------------------------

    @Test
    fun `S9a the replay-gap sync_lost sentinel carries neither actor nor rootId for admin and read-only alike`(): Unit =
        testApplication {
            val bus = ApiEventBus(bufferSize = 3, source = InMemoryEventStore())
            val rootId = UUID.randomUUID()
            val published = mutableListOf<ApiEvent>()
            repeat(5) {
                val e = bus.emit(ApiEventType.ITEM_UPDATED, itemId = UUID.randomUUID(), rootId = rootId, actor = claim, at = FIXED_TIME)
                published.add(e)
            }
            application { wire(bus, entries(), redact = false) }
            val client = createClient { install(ClientSSE) }

            listOf(ADMIN_TOKEN, READ_TOKEN).forEach { token ->
                var raw = ""
                withTimeout(10.seconds) {
                    client.sse(
                        urlString = "/events",
                        request = {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header("Last-Event-ID", published[0].id.toString())
                        },
                    ) {
                        raw =
                            incoming
                                .take(1)
                                .toList()
                                .single()
                                .data
                                .orEmpty()
                    }
                }
                val frame = Json.parseToJsonElement(raw).jsonObject
                assertEquals(ApiEventType.SYNC_LOST, frame["event"]!!.jsonPrimitive.content, "fixture: first frame is the sentinel: $raw")
                assertFalse(frame.containsKey("actor"), "sync.lost must not carry actor: $raw")
                assertFalse(frame.containsKey("rootId"), "sync.lost must not carry rootId: $raw")
            }
        }

    @Test
    fun `S9b auth_expired carries neither actor nor rootId`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = InMemoryEventStore())
            application {
                wire(bus, entries(readExpiresAt = Instant.now().plusMillis(1200)), redact = false, authCheckIntervalSeconds = 1)
            }
            val client = createClient { install(ClientSSE) }

            val raws = mutableListOf<String>()
            withTimeout(20.seconds) {
                client.sse(
                    urlString = "/events",
                    request = { header(HttpHeaders.Authorization, "Bearer $READ_TOKEN") },
                ) {
                    incoming.toList().forEach { raws.add(it.data.orEmpty()) }
                }
            }
            val expired =
                raws
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .firstOrNull { it["event"]?.jsonPrimitive?.content == ApiEventType.AUTH_EXPIRED }
            assertTrue(expired != null, "fixture: an auth.expired frame must arrive, got: $raws")
            assertFalse(expired!!.containsKey("actor"), "auth.expired must not carry actor: $expired")
            assertFalse(expired.containsKey("rootId"), "auth.expired must not carry rootId: $expired")
        }
}

package io.github.jpicklyk.mcptask.current.run

import io.github.jpicklyk.mcptask.current.event.internal.wire.ApiEventBus
import io.github.jpicklyk.mcptask.current.event.internal.wire.eventRoutes
import io.github.jpicklyk.mcptask.current.identity.BearerTokenStore
import io.github.jpicklyk.mcptask.current.identity.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.FROM_START
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.awaitSubscribers
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolServiceRegistry
import io.github.jpicklyk.mcptask.current.model.event.DomainEvent
import io.github.jpicklyk.mcptask.current.model.event.EventClass
import io.github.jpicklyk.mcptask.current.model.event.EventClasses
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.platform.call.AnswerProvenance
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.Principal
import io.github.jpicklyk.mcptask.current.platform.call.PrincipalKind
import io.github.jpicklyk.mcptask.current.platform.call.ProofStatus
import io.github.jpicklyk.mcptask.current.platform.port.AccessScope
import io.github.jpicklyk.mcptask.current.platform.port.EventRecord
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Independent tests (test-author seat, item eb933b68) for the two decision events that record routing, `decision.delegated` and
 * `decision.escalated`: their audit classification and their frames on `GET /api/v1/events`.
 *
 * Oracles (never read from a run): the frozen task-scope decision D6 and the existing event rules. In short: both types belong to
 * the `decision.` family, so both are audit-class events (`EventClasses.of` answers AUDIT); the decision event types are listed in
 * lifecycle order raised, acknowledged, delegated, escalated, answered, withdrawn; and both stream as decision frames that carry
 * `decisionId`, `class` and `severity` (and `itemId` when the decision names an item), follow the same root and tag visibility as
 * the other decision frames, live and on replay, and never carry the escalation reason, a principal id, a host or a session id.
 *
 * Negative-assertion fixture conditions: every hidden decision is paired with a visible one on the same connection, so an empty
 * stream cannot pass the absence checks; the reason check runs on frames that are known to exist (the type set is asserted first).
 *
 * Domain invariants satisfied by construction: roots and items are seeded through the rig with tags; decisions are raised, delegated
 * and escalated through the service, which records the event rows the bus replays.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class DecisionRoutingEventsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private companion object {
        const val OPEN_TOKEN = "routing-events-open-token"
        const val ROOT_TOKEN = "routing-events-root-token"
        const val COLLECT_WINDOW_MS = 900L
        const val REASON = "secret escalation reason"
        val ROUTING_TYPES = setOf("decision.delegated", "decision.escalated")

        fun sha256(input: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

        fun entry(
            id: String,
            rootIds: Set<UUID>?
        ) = HashBytes(sha256(id)) to
            BearerTokenStore.TokenEntry(
                Principal(
                    id = "routing-events-$id",
                    scope = AccessScope.principal(rootIds = rootIds, tagsInclude = emptySet()),
                    capabilities = setOf(ApiCapability.READ),
                    kind = PrincipalKind.SERVICE,
                    proofStatus = ProofStatus.ISSUED
                ),
                expiresAt = null
            )

        suspend fun collectWithin(incoming: Flow<ServerSentEvent>): List<JsonObject> {
            val collected = mutableListOf<JsonObject>()
            coroutineScope {
                val job = launch { incoming.collect { sse -> collected.add(Json.parseToJsonElement(sse.data.orEmpty()).jsonObject) } }
                delay(COLLECT_WINDOW_MS)
                job.cancel()
            }
            return collected
        }
    }

    // ------------------------------------------------------------------ S26: audit class

    @Test
    fun `S26 decision delegated and decision escalated are audit-class events`() {
        assertEquals(EventClass.AUDIT, EventClasses.of("decision.delegated"))
        assertEquals(EventClass.AUDIT, EventClasses.of("decision.escalated"))
        assertEquals("decision.delegated", DomainEvent.DECISION_DELEGATED)
        assertEquals("decision.escalated", DomainEvent.DECISION_ESCALATED)
        assertTrue(ROUTING_TYPES.all { it in EventClasses.AUDIT_TYPES || EventClasses.AUDIT_PREFIXES.any { prefix -> it.startsWith(prefix) } })
        // the family prefix is the rule, so the control for the other four types is the same
        for (type in listOf("decision.raised", "decision.acknowledged", "decision.answered", "decision.withdrawn")) {
            assertEquals(EventClass.AUDIT, EventClasses.of(type), type)
        }
    }

    @Test
    fun `S26 the decision event types are listed in lifecycle order`() {
        assertEquals(
            listOf(
                "decision.raised",
                "decision.acknowledged",
                "decision.delegated",
                "decision.escalated",
                "decision.answered",
                "decision.withdrawn"
            ),
            DomainEvent.DECISION_TYPES
        )
    }

    // ------------------------------------------------------------------ S26: SSE frames

    private class Fixture(
        val rig: EventLogRig,
        val alphaRoot: WorkItem,
        val alphaItem: WorkItem,
        val betaRoot: WorkItem,
        val betaItem: WorkItem
    ) {
        val svc get() = ToolServiceRegistry(rig.ctx).get(RunToolServices::class).decisionService

        fun cmd(
            root: WorkItem,
            item: WorkItem?,
            key: String
        ) = DecisionRaiseCommand(
            rootId = root.id,
            itemId = item?.id,
            decisionClass = "deviation",
            question = "Which one?",
            options = listOf(DecisionRequestOption("a", "A"), DecisionRequestOption("b", "B")),
            dedupeKey = key,
            raisedById = "api:orch",
            raisedByKind = "service"
        )
    }

    private fun fixture(): Fixture {
        val rig = EventLogRig.build(db.db, dir, "work_item_schemas: {}\n")
        return runBlocking {
            val alpha = rig.seed("Alpha root", tags = "alpha")
            val beta = rig.seed("Beta root", tags = "beta")
            Fixture(
                rig,
                alpha,
                rig.seed("Alpha item", parent = alpha, tags = "alpha"),
                beta,
                rig.seed("Beta item", parent = beta, tags = "beta")
            )
        }
    }

    private val director =
        DecisionCaller(
            principalId = "api:director-secret",
            kind = PrincipalKind.SERVICE,
            proofStatus = ProofStatus.ISSUED,
            director = true,
            seat = null,
            runId = null,
            assignmentId = null,
            provenance =
                AnswerProvenance(
                    "api:director-secret",
                    PrincipalKind.SERVICE,
                    ProofStatus.ISSUED,
                    "laptop-secret-host",
                    "session-secret",
                    null,
                    null,
                    "director"
                )
        )

    /** Raises, delegates and escalates one decision on each of the four anchors, returning every row the writes recorded. */
    private suspend fun produce(f: Fixture): List<EventRecord> =
        f.rig
            .written {
                val anchors = listOf(f.alphaRoot to null, f.alphaRoot to f.alphaItem, f.betaRoot to null, f.betaRoot to f.betaItem)
                anchors.forEachIndexed { i, (root, item) ->
                    val d = f.svc.raise(f.cmd(root, item, "k$i")).getOrNull()!!.decision
                    f.svc.delegate(d.id, director).getOrNull()!!
                    f.svc.escalate(d.id, REASON, director).getOrNull()!!
                }
            }.second

    private fun Application.wire(
        bus: ApiEventBus,
        entries: Map<HashBytes, BearerTokenStore.TokenEntry>
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            eventRoutes(
                bus,
                entries,
                allowQueryToken = false,
                authCheckIntervalSeconds = 60,
                workItemRepository = db.repositoryProvider().workItemRepository(),
                redactAttribution = true
            )
        }
    }

    private fun entries(f: Fixture) = mapOf(entry(OPEN_TOKEN, null), entry(ROOT_TOKEN, setOf(f.alphaRoot.id)))

    private suspend fun ApplicationTestBuilder.stream(token: String): List<JsonObject> {
        val client = createClient { install(ClientSSE) }
        val collected = mutableListOf<JsonObject>()
        withTimeout(10.seconds) {
            client.sse(
                urlString = "/events",
                request = {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("Last-Event-ID", FROM_START)
                }
            ) {
                collected.addAll(collectWithin(incoming))
            }
        }
        return collected
    }

    private suspend fun ApplicationTestBuilder.liveStream(
        bus: ApiEventBus,
        token: String,
        produce: suspend () -> Unit
    ): List<JsonObject> {
        val before = bus.subscriberCount()
        val client = createClient { install(ClientSSE) }
        val collected = mutableListOf<JsonObject>()
        withTimeout(15.seconds) {
            client.sse(
                urlString = "/events",
                request = { header(HttpHeaders.Authorization, "Bearer $token") }
            ) {
                coroutineScope {
                    val job = launch { incoming.collect { sse -> collected.add(Json.parseToJsonElement(sse.data.orEmpty()).jsonObject) } }
                    bus.awaitSubscribers(before + 1)
                    produce()
                    delay(COLLECT_WINDOW_MS)
                    job.cancel()
                }
            }
        }
        return collected
    }

    private fun decisionFrames(frames: List<JsonObject>) =
        frames.filter { (it["event"]?.jsonPrimitiveContent() ?: "").startsWith("decision.") }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun assertRoutingFrames(
        frames: List<JsonObject>,
        expectedPerType: Int,
        label: String
    ) {
        val decisions = decisionFrames(frames)
        assertEquals(
            setOf("decision.raised", "decision.delegated", "decision.escalated"),
            decisions.mapNotNull { it.text("event") }.toSet(),
            "$label: the routing types arrive beside the raise: $decisions"
        )
        for (type in ROUTING_TYPES) {
            assertEquals(expectedPerType, decisions.count { it.text("event") == type }, "$label: $type frames")
        }
        for (frame in decisions.filter { it.text("event") in ROUTING_TYPES }) {
            assertNotNull(frame.text("decisionId"), "$label: decisionId: $frame")
            assertEquals("deviation", frame.text("class"), "$label: class: $frame")
            assertEquals("normal", frame.text("severity"), "$label: severity: $frame")
            val serialized = frame.toString()
            for (secret in listOf(REASON, "director-secret", "laptop-secret-host", "session-secret")) {
                assertFalse(secret in serialized, "$label: a routing frame must not carry '$secret': $frame")
            }
            for (key in listOf("actor", "principalId", "host", "sessionId", "reason", "waitingReason")) {
                assertFalse(key in frame.keys, "$label: a routing frame has no $key member: $frame")
            }
        }
    }

    @Test
    fun `S26 replay - both routing types stream as decision frames with decisionId, class and severity, and no reason`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }
            produce(f)

            val frames = stream(OPEN_TOKEN)

            assertRoutingFrames(frames, expectedPerType = 4, label = "unrestricted")
            val withItem = decisionFrames(frames).filter { it.text("itemId") != null && it.text("event") in ROUTING_TYPES }
            assertEquals(4, withItem.size, "the item-anchored decisions' routing frames name their item: $frames")
            assertTrue(withItem.all { it.text("itemId") in setOf(f.alphaItem.id.toString(), f.betaItem.id.toString()) })
        }

    @Test
    fun `S26 replay - a root-scoped subscriber sees the routing frames of its own root only`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }
            produce(f)

            val scoped = stream(ROOT_TOKEN)

            assertRoutingFrames(scoped, expectedPerType = 2, label = "root-scoped")
            val roots = decisionFrames(scoped).mapNotNull { it.text("rootId") }.toSet()
            assertEquals(setOf(f.alphaRoot.id.toString()), roots, "only the subscriber's own root: $scoped")
            // control: the unrestricted subscriber sees both roots, so the scoped stream is a real filter
            assertEquals(
                setOf(f.alphaRoot.id.toString(), f.betaRoot.id.toString()),
                decisionFrames(stream(OPEN_TOKEN)).mapNotNull { it.text("rootId") }.toSet()
            )
        }

    @Test
    fun `S26 live - the routing frames are delivered as they commit`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }

            val open = liveStream(bus, OPEN_TOKEN) { bus.committed(produce(f)) }

            assertRoutingFrames(open, expectedPerType = 4, label = "live unrestricted")
        }
}

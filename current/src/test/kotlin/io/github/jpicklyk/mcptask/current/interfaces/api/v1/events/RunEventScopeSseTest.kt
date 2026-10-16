package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
import io.github.jpicklyk.mcptask.current.parity.RunsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.callMcp
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Independent tests (third test-author seat, item 8df44a22, R20 fix round) for review finding N2: the SSE visibility of the
 * item-less `run.*` events on `GET /api/v1/events`, live and on `Last-Event-ID` replay.
 *
 * Oracle (fix declarations): an unrestricted subscriber gets every event; a restricted subscriber still gets the control events
 * (`sync.lost`), gets a `run.*` event only when the run's `rootId` is in its root set and, for a tag-scoped subscriber, when the
 * run's ROOT ITEM carries an admitted tag (exact membership, the rule items use); live and replay apply the same rule.
 *
 * Fixture: root ALPHA (tag alpha) and root BETA (tag beta), each with one run opened and updated through the `runs_write` tool,
 * which records `run.opened` and `run.updated` rows; every subscriber reads the same rows. Negative assertions are paired with a
 * positive assertion on the same connection (the in-scope run events do arrive), so an empty stream cannot pass them.
 */
class RunEventScopeSseTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())

    companion object {
        private const val OPEN_TOKEN = "run-event-scope-open-token"
        private const val ROOT_TOKEN = "run-event-scope-root-token"
        private const val TAG_TOKEN = "run-event-scope-tag-token"
        private const val BOTH_TOKEN = "run-event-scope-both-token"

        private const val COLLECT_WINDOW_MS = 900L

        private val replayJson = Json { ignoreUnknownKeys = true }

        private fun sha256(input: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

        private fun decode(data: String?): ApiEvent = replayJson.decodeFromString(ApiEvent.serializer(), data.orEmpty())

        private fun entry(
            id: String,
            rootIds: Set<UUID>?,
            tagsInclude: Set<String>
        ) = HashBytes(sha256(id)) to
            BearerTokenStore.TokenEntry(
                Principal(
                    id = "run-event-scope-$id",
                    scope = AccessScope.principal(rootIds = rootIds, tagsInclude = tagsInclude),
                    capabilities = setOf(ApiCapability.READ),
                    kind = PrincipalKind.SERVICE,
                    proofStatus = ProofStatus.ISSUED
                ),
                expiresAt = null
            )

        private suspend fun collectWithin(incoming: Flow<ServerSentEvent>): List<ApiEvent> {
            val collected = mutableListOf<ApiEvent>()
            coroutineScope {
                val job = launch { incoming.collect { sse -> collected.add(decode(sse.data)) } }
                delay(COLLECT_WINDOW_MS) // settle-ok: nothing-arrives window (alpha is produced before beta)
                job.cancel()
            }
            return collected
        }
    }

    private class Fixture(
        val rig: EventLogRig,
        val alpha: UUID,
        val beta: UUID
    )

    private fun fixture(): Fixture {
        val rig = EventLogRig.build(db.db, dir, clock = PARITY_CLOCK)
        val (alpha, beta) =
            kotlinx.coroutines.runBlocking {
                rig.seed("Alpha root", tags = "alpha").id to rig.seed("Beta root", tags = "beta").id
            }
        return Fixture(rig, alpha, beta)
    }

    /** Opens and updates one run of [root] through the tool, returning the rows it recorded (`run.opened`, `run.updated`). */
    private suspend fun produce(
        f: Fixture,
        root: UUID
    ): List<EventRecord> =
        f.rig
            .written {
                val opened =
                    callMcp(
                        f.rig.composition.toolContext,
                        registry,
                        null,
                        "runs_write",
                        Json.parseToJsonElement(RunsParityScenarios.ops(RunsParityScenarios.open(root))).jsonObject,
                        ToolProfile.ORCHESTRATION
                    )
                check(!opened.isError) { "open failed: ${opened.structured}" }
                val runId = Regex("\"runId\":\"([0-9a-f-]{36})\"").find(opened.structured.toString())!!.groupValues[1]
                // R22 D3: updating a run is the coordinator TOKEN `open` returned, presented as the top-level seatToken.
                val coordinatorToken =
                    Regex("\"coordinatorToken\":\"([A-Za-z0-9_-]{43})\"").find(opened.structured.toString())!!.groupValues[1]
                val updated =
                    callMcp(
                        f.rig.composition.toolContext,
                        registry,
                        null,
                        "runs_write",
                        Json
                            .parseToJsonElement(
                                RunsParityScenarios.ops(
                                    RunsParityScenarios.update(runId, ""","hints":{"x":"y"}"""),
                                    top = ""","seatToken":"$coordinatorToken""""
                                )
                            ).jsonObject,
                        ToolProfile.ORCHESTRATION
                    )
                check(!updated.isError) { "update failed: ${updated.structured}" }
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

    private fun entries(f: Fixture) =
        mapOf(
            entry(OPEN_TOKEN, null, emptySet()),
            entry(ROOT_TOKEN, setOf(f.alpha), emptySet()),
            entry(TAG_TOKEN, null, setOf("alpha")),
            entry(BOTH_TOKEN, setOf(f.alpha, f.beta), setOf("alpha"))
        )

    private suspend fun ApplicationTestBuilder.stream(
        bus: ApiEventBus,
        token: String,
        lastEventId: String?,
        duringConnection: suspend () -> Unit = {}
    ): List<ApiEvent> {
        val client = createClient { install(ClientSSE) }
        val collected = mutableListOf<ApiEvent>()
        awaitUntil("a previous connection never released its subscriber") { bus.subscriberCount() == 0 }
        withTimeout(10.seconds) {
            coroutineScope {
                launch {
                    bus.awaitSubscribers()
                    duringConnection()
                }
                client.sse(
                    urlString = "/events",
                    request = {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        if (lastEventId != null) header("Last-Event-ID", lastEventId)
                    }
                ) {
                    collected.addAll(collectWithin(incoming))
                }
            }
        }
        return collected
    }

    private fun runEvents(events: List<ApiEvent>) = events.filter { it.event.startsWith("run.") }

    private fun roots(events: List<ApiEvent>) = runEvents(events).map { it.rootId }.toSet()

    // ------------------------------------------------------------------ replay

    @Test
    fun `N2 replay - a root-scoped subscriber gets only the run events of its root, an unrestricted one gets both`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }
            produce(f, f.alpha)
            produce(f, f.beta)

            val open = stream(bus, OPEN_TOKEN, FROM_START)
            assertEquals(setOf(f.alpha.toString(), f.beta.toString()), roots(open), "unrestricted control: $open")

            val scoped = stream(bus, ROOT_TOKEN, FROM_START)
            assertTrue(runEvents(scoped).any { it.event == "run.opened" }, "the in-scope run.opened is replayed: $scoped")
            assertTrue(runEvents(scoped).any { it.event == "run.updated" }, "the in-scope run.updated is replayed: $scoped")
            assertEquals(setOf(f.alpha.toString()), roots(scoped), "only the subscriber's own root: $scoped")
            assertTrue(runEvents(scoped).all { it.itemId == null && it.runId != null }, "run events carry a run id and no item: $scoped")
        }

    @Test
    fun `N2 replay - a tag-scoped subscriber gets run events only when the run's root item carries an admitted tag`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }
            produce(f, f.alpha)
            produce(f, f.beta)

            val scoped = stream(bus, TAG_TOKEN, FROM_START)
            assertTrue(runEvents(scoped).isNotEmpty(), "the run of the alpha-tagged root is replayed: $scoped")
            assertEquals(setOf(f.alpha.toString()), roots(scoped), "the beta-tagged root's run stays hidden: $scoped")
        }

    @Test
    fun `N2 replay - root scope and tag scope both apply`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }
            produce(f, f.alpha)
            produce(f, f.beta)

            val scoped = stream(bus, BOTH_TOKEN, FROM_START)
            assertEquals(setOf(f.alpha.toString()), roots(scoped), "beta is in the root set but not tag-admitted: $scoped")
        }

    // ------------------------------------------------------------------ live

    @Test
    fun `N2 live - a root-scoped subscriber gets only the run events of its root, an unrestricted one gets both`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }

            val open =
                stream(bus, OPEN_TOKEN, null) {
                    bus.committed(produce(f, f.alpha) + produce(f, f.beta))
                }
            assertEquals(setOf(f.alpha.toString(), f.beta.toString()), roots(open), "unrestricted control: $open")

            val scoped =
                stream(bus, ROOT_TOKEN, null) {
                    bus.committed(produce(f, f.alpha) + produce(f, f.beta))
                }
            assertTrue(runEvents(scoped).isNotEmpty(), "the in-scope run events arrive live: $scoped")
            assertEquals(setOf(f.alpha.toString()), roots(scoped), "only the subscriber's own root: $scoped")
        }

    @Test
    fun `N2 live - a tag-scoped subscriber gets run events only when the run's root item carries an admitted tag`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }

            val scoped =
                stream(bus, TAG_TOKEN, null) {
                    bus.committed(produce(f, f.alpha) + produce(f, f.beta))
                }
            assertTrue(runEvents(scoped).isNotEmpty(), "the alpha-tagged root's run events arrive live: $scoped")
            assertEquals(setOf(f.alpha.toString()), roots(scoped), "the beta-tagged root's run stays hidden: $scoped")
        }

    // ------------------------------------------------------------------ control events

    @Test
    fun `N2 sync_lost is still delivered to a restricted subscriber`(): Unit =
        testApplication {
            val f = fixture()
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            application { wire(bus, entries(f)) }

            for (token in listOf(ROOT_TOKEN, TAG_TOKEN, BOTH_TOKEN)) {
                val events = stream(bus, token, "abc")
                assertTrue(
                    events.any { it.event == ApiEventType.SYNC_LOST },
                    "$token: a restricted subscriber still sees sync.lost: $events"
                )
                assertFalse(events.any { it.event.startsWith("run.") }, "$token: nothing else was recorded: $events")
            }
        }
}

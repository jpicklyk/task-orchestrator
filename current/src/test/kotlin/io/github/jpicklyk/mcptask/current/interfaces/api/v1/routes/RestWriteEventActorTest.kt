package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ActorClaimDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEvent
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventBus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.ApiEventType
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventPublishingRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.drainDelivered
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Amendment A1 (item f0e193b7): every domain event emitted by a REST write is attributed to the
 * calling API principal. Oracle: task-scope Amendment A1 and api-rest.md section 21 -- the actor is
 * `api:<tokenId>`, kind `external`, parent absent. The WRITE token's id is [WRITE_TOKEN_ID].
 *
 * Wiring: the REAL write route functions under the production-style bearer plugin, over an
 * [EventPublishingRepositoryProvider]-decorated SQLite provider. Fixtures are seeded through the
 * UNDECORATED provider so the only events on the bus are those the REST write under test produced.
 * A bus subscriber is registered before each write, because the decorator skips root resolution
 * while nobody is subscribed (a rootId assertion needs a subscriber).
 */
class RestWriteEventActorTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val expectedActor = ActorClaimDto(id = "api:$WRITE_TOKEN_ID", kind = "external", parent = null)

    private fun Application.wire(decorated: EventPublishingRepositoryProvider) {
        configureTestApp(makeWriteAuthConfig()) {
            itemWriteRoutes(
                decorated,
                DegradedModePolicy.ACCEPT_CACHED,
                IdempotencyService(db.unitOfWork()),
                ToolExecutionContext(
                    decorated,
                    NoOpNoteSchemaService,
                    statusLabelService = NoOpStatusLabelService,
                    perRootConfigService = PerRootConfigService(decorated.projectConfigRepository()),
                    unitOfWork = db.unitOfWork()
                ).advanceServiceFactory(),
                db.unitOfWork(),
            )
            noteWriteRoutes(decorated, DegradedModePolicy.ACCEPT_CACHED, IdempotencyService(db.unitOfWork()), db.unitOfWork())
            dependencyWriteRoutes(decorated, DegradedModePolicy.ACCEPT_CACHED, IdempotencyService(db.unitOfWork()), db.unitOfWork())
        }
    }

    private fun seed(
        repo: DefaultRepositoryProvider,
        title: String,
        parent: WorkItem? = null,
    ): WorkItem =
        runBlocking {
            repo
                .workItemRepository()
                .create(WorkItem(title = title, parentId = parent?.id, depth = (parent?.depth ?: -1) + 1))!!
        }

    private fun etag(item: WorkItem) = "\"v1-${item.modifiedAt.toEpochMilli()}\""

    private fun idOf(body: String): String = Regex(""""id":"([0-9a-fA-F-]{36})"""").find(body)!!.groupValues[1]

    private fun assertAttributed(
        events: List<ApiEvent>,
        context: String,
    ) {
        assertTrue(events.isNotEmpty(), "$context: expected events but the bus delivered none")
        events.forEach {
            assertEquals(expectedActor, it.actor, "$context: event ${it.event} for ${it.itemId} has wrong actor")
        }
    }

    @Test
    fun `R1 reparent attributes the moved item and every descendant to the api actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val rootA = seed(repo, "Root A")
            val rootB = seed(repo, "Root B")
            val p = seed(repo, "P", rootA)
            val c = seed(repo, "C", p)
            val g = seed(repo, "G", c)
            val bus = ApiEventBus()
            application { wire(EventPublishingRepositoryProvider(repo, bus)) }
            val flow = bus.subscribe("r1", emptySet(), lastEventId = null)

            val response =
                client.patch("/api/v1/items/${p.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag(p))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${rootB.id}"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val events = bus.drainDelivered("r1", flow)

            assertAttributed(events, "R1 all events")
            val updated = events.filter { it.event == ApiEventType.ITEM_UPDATED }
            // The moved item itself may be reported through scope.* events; any event satisfies A1 R1.
            assertTrue(
                events.any { it.itemId == p.id.toString() },
                "R1: no event for P (${p.id}); got ${events.map { it.event to it.itemId }}",
            )
            for (descendant in listOf(c, g)) {
                assertTrue(
                    updated.any { it.itemId == descendant.id.toString() },
                    "R1: no item.updated for ${descendant.title} (${descendant.id}); got ${updated.map { it.itemId }}",
                )
            }
            // Descendants now live under Root B, so their depth-0 ancestor is B.
            for (descendant in listOf(c, g)) {
                updated.filter { it.itemId == descendant.id.toString() }.forEach {
                    assertEquals(rootB.id.toString(), it.rootId, "R1: rootId of ${descendant.title}")
                }
            }
        }

    @Test
    fun `R2 create root and nested child attribute item created events to the api actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val bus = ApiEventBus()
            application { wire(EventPublishingRepositoryProvider(repo, bus)) }
            val flow = bus.subscribe("r2", emptySet(), lastEventId = null)

            val rootResp =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"R2 root"}""")
                }
            assertEquals(HttpStatusCode.Created, rootResp.status)
            val rootId = idOf(rootResp.bodyAsText())
            val childResp =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"R2 child","parentId":"$rootId"}""")
                }
            assertEquals(HttpStatusCode.Created, childResp.status)
            val childId = idOf(childResp.bodyAsText())
            val events = bus.drainDelivered("r2", flow)

            assertAttributed(events, "R2 all events")
            val created = events.filter { it.event == ApiEventType.ITEM_CREATED }
            assertEquals(setOf(rootId, childId), created.mapNotNull { it.itemId }.toSet())
            assertEquals(rootId, created.single { it.itemId == childId }.rootId)
        }

    @Test
    fun `R3 patch without reparent attributes item updated and bus readback keeps the actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item = seed(repo, "R3 item")
            val bus = ApiEventBus()
            application { wire(EventPublishingRepositoryProvider(repo, bus)) }
            val flow = bus.subscribe("r3", emptySet(), lastEventId = null)

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag(item))
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"R3 renamed"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val events = bus.drainDelivered("r3", flow)

            assertAttributed(events, "R3 all events")
            val updated = events.filter { it.event == ApiEventType.ITEM_UPDATED && it.itemId == item.id.toString() }
            assertEquals(1, updated.size, "R3: exactly one item.updated for the item; got $events")
            // Read-back through the bus ring buffer yields the same attribution (replay path).
            val replayed = bus.projectedEvents().filter { it.id == updated.single().id }
            assertEquals(1, replayed.size)
            assertEquals(expectedActor, replayed.single().actor)
        }

    @Test
    fun `R4 delete attributes item deleted to the api actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item = seed(repo, "R4 item")
            val bus = ApiEventBus()
            application { wire(EventPublishingRepositoryProvider(repo, bus)) }
            val flow = bus.subscribe("r4", emptySet(), lastEventId = null)

            val response =
                client.delete("/api/v1/items/${item.id}") { header("Authorization", "Bearer $WRITE_TOKEN") }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val events = bus.drainDelivered("r4", flow)

            assertAttributed(events, "R4 all events")
            assertTrue(
                events.any { it.event == ApiEventType.ITEM_DELETED && it.itemId == item.id.toString() },
                "R4: no item.deleted for ${item.id}; got $events",
            )
        }

    @Test
    fun `R5 note delete attributes note deleted to the api actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item = seed(repo, "R5 item")
            runBlocking {
                repo.noteRepository().upsert(Note(itemId = item.id, key = "r5-note", role = "work", body = "bye"))
            }
            val bus = ApiEventBus()
            application { wire(EventPublishingRepositoryProvider(repo, bus)) }
            val flow = bus.subscribe("r5", emptySet(), lastEventId = null)

            val response =
                client.delete("/api/v1/items/${item.id}/notes/r5-note") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val events = bus.drainDelivered("r5", flow)

            assertAttributed(events, "R5 all events")
            assertTrue(
                events.any { it.event == ApiEventType.NOTE_DELETED },
                "R5: no note.deleted event; got $events",
            )
        }

    @Test
    fun `R6 dependency create attributes dependency added to the api actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val from = seed(repo, "R6 from")
            val to = seed(repo, "R6 to")
            val bus = ApiEventBus()
            application { wire(EventPublishingRepositoryProvider(repo, bus)) }
            val flow = bus.subscribe("r6", emptySet(), lastEventId = null)

            val response =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${from.id}","toItemId":"${to.id}","type":"blocks"}""")
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val events = bus.drainDelivered("r6", flow)

            assertAttributed(events, "R6 all events")
            assertTrue(
                events.any { it.event == ApiEventType.DEPENDENCY_ADDED },
                "R6: no dependency.added event; got $events",
            )
        }

    @Test
    fun `R7 dependency remove attributes dependency removed to the api actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val from = seed(repo, "R7 from")
            val to = seed(repo, "R7 to")
            val dep =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        repo.dependencyRepository().create(
                            Dependency(fromItemId = from.id, toItemId = to.id, type = DependencyType.BLOCKS),
                        )
                    }
                }
            val bus = ApiEventBus()
            application { wire(EventPublishingRepositoryProvider(repo, bus)) }
            val flow = bus.subscribe("r7", emptySet(), lastEventId = null)

            val response =
                client.delete("/api/v1/dependencies/${dep.id}") { header("Authorization", "Bearer $WRITE_TOKEN") }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val events = bus.drainDelivered("r7", flow)

            assertAttributed(events, "R7 all events")
            assertTrue(
                events.any { it.event == ApiEventType.DEPENDENCY_REMOVED },
                "R7: no dependency.removed event; got $events",
            )
        }

    @Test
    fun `read-only token is rejected 403 on a write route and emits no event`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val bus = ApiEventBus()
            application { wire(EventPublishingRepositoryProvider(repo, bus)) }
            val flow = bus.subscribe("ro", emptySet(), lastEventId = null)

            val denied =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"denied"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            // The same request with the write token produces an event (R2), so a zero here is
            // attributable to the 403, not to a fixture that can never emit.
            assertEquals(emptyList(), bus.drainDelivered("ro", flow))
        }
}

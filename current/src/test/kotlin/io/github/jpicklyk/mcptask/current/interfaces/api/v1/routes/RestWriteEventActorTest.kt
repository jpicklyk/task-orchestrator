package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
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
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.drainDelivered
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.eventWiredUnit
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
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
 * Wiring: the REAL write route functions under the production-style bearer plugin, over a SQLite provider whose
 * unit of work records through one recorder feeding the bus. Fixtures are seeded directly through the
 * store so the only events on the bus are those the REST write under test produced.
 * A bus subscriber is registered before each write, because the decorator skips root resolution
 * while nobody is subscribed (a rootId assertion needs a subscriber).
 */
class RestWriteEventActorTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val expectedActor = ActorClaimDto(id = "api:$WRITE_TOKEN_ID", kind = "external", parent = null)

    private fun Application.wire(bus: ApiEventBus) {
        // One recorder feeding the bus, shared by the unit every write service records through (ServerComposition's wiring).
        val (decorated, unitOfWork) = eventWiredUnit(db.databaseManager, db.repositoryProvider(), bus)
        configureTestApp(makeWriteAuthConfig()) {
            val toolContext =
                ToolExecutionContext(
                    decorated,
                    statusLabelService = NoOpStatusLabelService,
                    perRootConfigService = PerRootConfigService(decorated.configStore()),
                    unitOfWork = unitOfWork
                )
            itemWriteRoutes(
                decorated,
                DegradedModePolicy.ACCEPT_CACHED,
                IdempotencyService(unitOfWork),
                toolContext.advanceServiceFactory(),
                unitOfWork,
            )
            noteWriteRoutes(
                decorated,
                DegradedModePolicy.ACCEPT_CACHED,
                IdempotencyService(unitOfWork),
                unitOfWork,
                toolContext.noteCommandService
            )
            dependencyWriteRoutes(decorated, DegradedModePolicy.ACCEPT_CACHED, IdempotencyService(unitOfWork), unitOfWork)
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
    fun `R5 note delete attributes note deleted to the api actor`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item = seed(repo, "R5 item")
            runBlocking {
                repo.noteRepository().upsert(Note(itemId = item.id, key = "r5-note", role = "work", body = "bye"))
            }
            val bus = ApiEventBus()
            application { wire(bus) }
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
            application { wire(bus) }
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
            application { wire(bus) }
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
}

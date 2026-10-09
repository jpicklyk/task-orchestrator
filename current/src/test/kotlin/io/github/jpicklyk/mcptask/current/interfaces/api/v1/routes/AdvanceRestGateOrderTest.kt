package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.rejections
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent P11 tests (item 919d379e), REST surface: S7 (gate order and status codes), S9 (a fault in the advance
 * rolls everything back) and S14 (the cascade trigger is not a user trigger).
 *
 * Oracles: the `POST /items/{id}/advance` response table in `current/docs/api-rest.md` (400 validation_error for an
 * invalid trigger, 409 resource_unavailable with Retry-After >= 1 and `details.contendedResources`, 422 gate_blocked with
 * `details.missingNotes`, 422 transition_blocked with `details.blockers`, 422 transition_failed for a persistence fault
 * anywhere in the advance; claim ownership is not enforced on REST); AC3 (one rejection row per rejected advance).
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceRestGateOrderTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun Application.configureP11App(rig: EventLogRig) {
        val ctx = rig.ctx
        val uow = rig.composition.unitOfWork
        configureTestApp(makeWriteAuthConfig()) {
            itemRoutes(rig.provider)
            itemGateRoutes(rig.provider, ctx.configResolver, ctx.transitionPreview())
            itemWriteRoutes(rig.provider, DegradedModePolicy.ACCEPT_CACHED, IdempotencyService(uow), ctx.advanceServiceFactory(), uow)
        }
    }

    private suspend fun ApplicationTestBuilder.post(
        itemId: UUID,
        trigger: String,
    ): HttpResponse =
        client.post("/api/v1/items/$itemId/advance") {
            header("Authorization", "Bearer $WRITE_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"trigger":"$trigger"}""")
        }

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject

    @Test
    fun `S7 REST start is rejected by dependency, then notes, then lease, then applied, one rejection row each`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val item = d.item("rest ordered", Role.QUEUE, type = "p11-order")
            val blocker = d.item("blocker", Role.QUEUE)
            val holder = d.item("holder", Role.QUEUE)
            d.blocks(blocker, item)
            d.holdLease(holder)
            val mark = d.rig.maxSeq()
            application { configureP11App(d.rig) }

            val dependency = post(item.id, "start")
            val dependencyBody = dependency.bodyAsText().json()
            assertEquals(HttpStatusCode.UnprocessableEntity, dependency.status, "$dependencyBody")
            assertEquals("transition_blocked", dependencyBody.text("error"), "$dependencyBody")
            assertEquals(
                blocker.id.toString(),
                dependencyBody["details"]!!
                    .jsonObject
                    .arr("blockers")[0]
                    .jsonObject
                    .text("fromItemId")
            )

            d.setRole(blocker, Role.TERMINAL)
            val notes = post(item.id, "start")
            val notesBody = notes.bodyAsText().json()
            assertEquals(HttpStatusCode.UnprocessableEntity, notes.status, "$notesBody")
            assertEquals("gate_blocked", notesBody.text("error"), "$notesBody")
            assertEquals(
                "spec",
                notesBody["details"]!!
                    .jsonObject
                    .arr("missingNotes")[0]
                    .jsonObject
                    .text("key")
            )
            assertEquals("Gate check failed: required notes not filled for queue phase: spec", notesBody.text("message"), "$notesBody")

            d.note(item, "spec", "queue")
            val lease = post(item.id, "start")
            val leaseBody = lease.bodyAsText().json()
            assertEquals(HttpStatusCode.Conflict, lease.status, "$leaseBody")
            assertEquals("resource_unavailable", leaseBody.text("error"), "$leaseBody")
            assertEquals(
                listOf(P11_LEASE_KEY),
                leaseBody["details"]!!.jsonObject.arr("contendedResources").map { it.jsonPrimitive.content }
            )
            assertTrue(
                (lease.headers["Retry-After"] ?: "0").toLong() >= 1,
                "Retry-After is whole seconds with a floor of 1: ${lease.headers.entries()}"
            )
            assertEquals(Role.QUEUE, d.role(item), "nothing was applied")
            assertEquals(
                3,
                d.rig
                    .rowsAfter(mark)
                    .rejections()
                    .size,
                "exactly one rejection row per rejected advance"
            )

            d.freeLease(holder)
            val ok = post(item.id, "start")
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertEquals("work", ok.bodyAsText().json().text("newRole"))
            assertEquals(
                3,
                d.rig
                    .rowsAfter(mark)
                    .rejections()
                    .size,
                "an applied advance adds no rejection row"
            )
        }

    @Test
    fun `S7 REST does not enforce claim ownership`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val item = d.item("claimed", Role.QUEUE)
            d.claim(item, "agent-a")
            application { configureP11App(d.rig) }

            val response = post(item.id, "start")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(Role.WORK, d.role(item))
        }

    @Test
    fun `S9 REST a fault in the cascade's transition row returns 422 transition_failed and persists nothing`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val parent = d.item("parent", Role.WORK)
            val child = d.item("child", Role.WORK, parent = parent)
            val mark = d.rig.maxSeq()
            application { configureP11App(d.rig) }
            rawExec(
                d.jdbcUrl,
                "CREATE TRIGGER p11_rest_second_row BEFORE INSERT ON role_transitions " +
                    "WHEN (SELECT count(*) FROM role_transitions) >= 1 BEGIN SELECT RAISE(ABORT, 'inj'); END",
            )

            val response = post(child.id, "complete")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
            assertEquals("transition_failed", response.bodyAsText().json().text("error"), response.bodyAsText())
            assertEquals(Role.WORK, d.role(child), "the child's transition rolled back with the cascade")
            assertEquals(Role.WORK, d.role(parent))
            assertEquals(0, rawCount(d.jdbcUrl, "SELECT count(*) FROM role_transitions"))
            assertEquals(emptyList(), d.rig.rowsAfter(mark), "no event row survives")
        }

    @Test
    fun `S14 REST the cascade trigger is rejected with 400 validation_error and nothing changes`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val item = d.item("cascade attempt", Role.QUEUE)
            application { configureP11App(d.rig) }

            val response = post(item.id, "cascade")

            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals("validation_error", response.bodyAsText().json().text("error"), response.bodyAsText())
            assertEquals(Role.QUEUE, d.role(item))
            assertEquals(0, d.transitions(item).size)
        }
}

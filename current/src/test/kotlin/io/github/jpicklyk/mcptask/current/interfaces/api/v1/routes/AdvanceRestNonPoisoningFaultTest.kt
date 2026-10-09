package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.P11FaultRig
import io.github.jpicklyk.mcptask.current.test.P11FaultSchemaService
import io.github.jpicklyk.mcptask.current.test.P11_FAULT_LEASE_KEY
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent P11 tests (item 919d379e, round r2), gap 2, REST surface: S9 with NON-POISONING faults (plain RuntimeException
 * from a delegating TransitionStore on the 2nd create() -- the cascade's row -- or from a LeaseStore whose releaseAllForItem
 * throws on the WORK exit), through `POST /items/{id}/advance`.
 *
 * Oracle: AC1 / task-scope 1.1 step 7 (a store fault anywhere in the advance rolls back the whole unit: no partial advance)
 * and the `POST /items/{id}/advance` table in `current/docs/api-rest.md` (422 `transition_failed` for a persistence fault
 * anywhere in the advance). Each test ends with a same-fixture control (fault disarmed) that returns 200 and applies, so the
 * "unchanged" assertions would fail if the fault were swallowed.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceRestNonPoisoningFaultTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun Application.configureFaultApp(rig: P11FaultRig) {
        val uow = db.unitOfWork()
        val ctx = ToolExecutionContext(rig.provider, P11FaultSchemaService(), unitOfWork = uow)
        configureTestApp(makeWriteAuthConfig()) {
            itemWriteRoutes(rig.provider, DegradedModePolicy.ACCEPT_CACHED, IdempotencyService(uow), ctx.advanceServiceFactory(), uow)
        }
    }

    private suspend fun ApplicationTestBuilder.complete(item: WorkItem): HttpResponse =
        client.post("/api/v1/items/${item.id}/advance") {
            header("Authorization", "Bearer $WRITE_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"trigger":"complete"}""")
        }

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject

    private suspend fun assertWholeAdvanceRolledBack(
        rig: P11FaultRig,
        before: io.github.jpicklyk.mcptask.current.test.P11FaultSnapshot,
        parent: WorkItem,
        child: WorkItem,
    ) {
        val after = rig.snapshot(parent, child)
        assertEquals(before, after, "role/label/previousRole/roleChangedAt, rows, leases and the event table are all unchanged")
        assertEquals(Role.WORK, after.items.single { it.id == child.id }.role, "the PRIMARY rolled back with the failure")
        assertEquals(Role.WORK, after.items.single { it.id == parent.id }.role)
        assertEquals(0, after.transitionRows[child.id], "no transition row for the primary")
        assertEquals(0, after.transitionRows[parent.id], "no transition row for the cascade")
        assertEquals(0, rig.eventCount("item.transitioned"), "no item.transitioned event survives")
        assertEquals(listOf(P11_FAULT_LEASE_KEY), after.leases[child.id], "the child's lease is unchanged (still held)")
    }

    private suspend fun assertControlApplies(
        rig: P11FaultRig,
        parent: WorkItem,
        child: WorkItem,
    ) {
        val after = rig.snapshot(parent, child)
        assertEquals(
            Role.TERMINAL,
            after.items.single { it.id == child.id }.role,
            "control: the same advance applies once the fault is gone"
        )
        assertEquals(Role.TERMINAL, after.items.single { it.id == parent.id }.role, "control: and cascades the parent")
        assertEquals(1, after.transitionRows[child.id])
        assertEquals(1, after.transitionRows[parent.id])
        assertEquals(2, rig.eventCount("item.transitioned"), "control: one event per applied transition")
        assertEquals(emptyList(), after.leases[child.id], "control: the lease is released on the WORK exit")
    }

    @Test
    fun `S9 REST a plain fault on the cascade's transition row returns 422 transition_failed and changes nothing`(): Unit =
        testApplication {
            val rig = P11FaultRig(db.repositoryProvider())
            val (parent, child) = rig.fixture("tx-rest")
            val before = rig.snapshot(parent, child)
            application { configureFaultApp(rig) }
            rig.transitionStore.arm()

            val response = complete(child)

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
            assertEquals("transition_failed", response.bodyAsText().json().text("error"), response.bodyAsText())
            assertEquals(2, rig.transitionStore.createCalls, "the fault point (the cascade's row, the 2nd create) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.transitionStore.disarm()
            val ok = complete(child)
            assertEquals(HttpStatusCode.OK, ok.status, "control: ${ok.bodyAsText()}")
            assertControlApplies(rig, parent, child)
        }

    @Test
    fun `S9 REST a plain fault releasing the lease on work exit returns 422 transition_failed and changes nothing`(): Unit =
        testApplication {
            val rig = P11FaultRig(db.repositoryProvider())
            val (parent, child) = rig.fixture("lease-rest")
            val before = rig.snapshot(parent, child)
            application { configureFaultApp(rig) }
            rig.leaseStore.arm()

            val response = complete(child)

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
            assertEquals("transition_failed", response.bodyAsText().json().text("error"), response.bodyAsText())
            assertTrue(rig.leaseStore.releaseCalls >= 1, "the fault point (the release on the WORK exit) was reached")
            assertWholeAdvanceRolledBack(rig, before, parent, child)

            rig.leaseStore.disarm()
            val ok = complete(child)
            assertEquals(HttpStatusCode.OK, ok.status, "control: ${ok.bodyAsText()}")
            assertControlApplies(rig, parent, child)
        }
}

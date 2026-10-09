package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11MatrixState
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.cleanup
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.setup
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Independent P11 test (item 919d379e) for S12 on the REST surface: `GET /items/{id}/gate` `gateStatus.canAdvance` and
 * `blockedBy` must equal what `POST /items/{id}/advance` with `start` does over the same generated states as the MCP
 * parity test (every role x dependency x current-phase notes x exclusive-lease combination).
 *
 * Oracles: api-rest.md section on the gate (canAdvance is "POST advance with start would be allowed right now", claim
 * ownership excluded; blockedBy is table | dependency | note | lease, present only when canAdvance is false and the
 * item is not terminal) and AC5; expected values come from [P11MatrixState.expectedBlockedBy], derived from the
 * documented gate order.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PreviewAdvanceParityRestTest {
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

    @Test
    fun `S12 REST gate canAdvance and blockedBy equal what advance start does, across every generated state`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            application { configureP11App(d.rig) }

            for (state in P11MatrixState.all()) {
                val (item, holder) = d.setup(state)
                val label = state.label

                val gateResponse = client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $WRITE_TOKEN") }
                assertEquals(HttpStatusCode.OK, gateResponse.status, "$label: ${gateResponse.bodyAsText()}")
                val gate = Json.parseToJsonElement(gateResponse.bodyAsText()).jsonObject["gateStatus"]!!.jsonObject
                val advance =
                    client.post("/api/v1/items/${item.id}/advance") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody("""{"trigger":"start"}""")
                    }

                assertEquals(state.allowed, gate.flag("canAdvance"), "$label: $gate")
                assertEquals(state.expectedBlockedBy.takeIf { it != "terminal" }, gate.text("blockedBy"), "$label: $gate")
                assertEquals(
                    state.allowed,
                    advance.status == HttpStatusCode.OK,
                    "$label: advance and gate agree: ${advance.status} ${advance.bodyAsText()}"
                )
                if (!state.allowed) {
                    val expectedStatus =
                        mapOf(
                            "terminal" to HttpStatusCode.UnprocessableEntity,
                            "table" to HttpStatusCode.UnprocessableEntity,
                            "dependency" to HttpStatusCode.UnprocessableEntity,
                            "note" to HttpStatusCode.UnprocessableEntity,
                            "lease" to HttpStatusCode.Conflict,
                        )[state.expectedBlockedBy]
                    assertEquals(expectedStatus, advance.status, "$label: ${advance.bodyAsText()}")
                }
                d.cleanup(item, holder)
            }
        }
}

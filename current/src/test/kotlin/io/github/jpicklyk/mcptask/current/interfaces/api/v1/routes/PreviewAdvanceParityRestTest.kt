package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advanceTool
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent P11 test (item 919d379e, re-pointed at the `advance` transition op by item 2cd17c00) for S12 on the REST surface:
 * `GET /items/{id}/gate` `gateStatus.canAdvance` and `blockedBy` must equal what `advance` op `transition` with trigger `start`
 * does over the same generated states as the MCP parity test (every role x dependency x current-phase notes x exclusive-lease
 * combination).
 *
 * Oracles: api-rest.md section on the gate (canAdvance is "a start would be allowed right now", claim ownership excluded; blockedBy
 * is table | dependency | note | lease, present only when canAdvance is false and the item is not terminal) and AC5; the expected
 * `blockedBy` comes from [P11MatrixState.expectedBlockedBy], derived from the documented gate order. The element error code per
 * blocked gate comes from the envelope catalog: a terminal item or a transition-table refusal is `invalid_transition`, a missing
 * note is `gate_blocked`, a held exclusive lease is `resource_unavailable`; an unmet dependency is `dependency_unmet`, with
 * `invalid_transition` also admitted because the catalog names the dependency refusal only through cascade suppression fields
 * (an ambiguity recorded in the test-manifest, resolved with this public evidence).
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PreviewAdvanceParityRestTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val token = ParityToken("preview-advance", setOf(ApiCapability.READ, ApiCapability.ADVANCE))

    @Test
    fun `S12 REST gate canAdvance and blockedBy equal what advance transition start does, across every generated state`(
        @TempDir dir: Path
    ): Unit =
        testApplication {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            application { installParityRestApp(d.rig, parityAuthConfig(token), ToolRegistry(listOf(advanceTool()))) }

            for (state in P11MatrixState.all()) {
                val (item, holder) = d.setup(state)
                val label = state.label

                val gateResponse = client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer ${token.raw}") }
                assertEquals(HttpStatusCode.OK, gateResponse.status, "$label: ${gateResponse.bodyAsText()}")
                val gate = Json.parseToJsonElement(gateResponse.bodyAsText()).jsonObject["gateStatus"]!!.jsonObject
                val advance =
                    client.post("/api/v1/ops/advance") {
                        header("Authorization", "Bearer ${token.raw}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"ops":[{"op":"transition","itemId":"${item.id}","trigger":"start"}]}""")
                    }
                val body = Json.parseToJsonElement(advance.bodyAsText()).jsonObject
                val applied = body["ok"]!!.jsonPrimitive.content.toBoolean()

                assertEquals(state.allowed, gate.flag("canAdvance"), "$label: $gate")
                assertEquals(state.expectedBlockedBy.takeIf { it != "terminal" }, gate.text("blockedBy"), "$label: $gate")
                assertEquals(state.allowed, applied, "$label: advance and gate agree: ${advance.status} ${advance.bodyAsText()}")
                if (state.allowed) {
                    assertEquals(HttpStatusCode.OK, advance.status, label)
                } else {
                    val element =
                        body["data"]!!
                            .jsonObject["results"]!!
                            .jsonArray[0]
                            .jsonObject
                    val code = element["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
                    val admitted =
                        mapOf(
                            "terminal" to setOf("invalid_transition"),
                            "table" to setOf("invalid_transition"),
                            "dependency" to setOf("dependency_unmet", "invalid_transition"),
                            "note" to setOf("gate_blocked"),
                            "lease" to setOf("resource_unavailable")
                        )
                    assertTrue(code in admitted.getValue(state.expectedBlockedBy!!), "$label: ${state.expectedBlockedBy} gave $code")
                    assertEquals(HttpStatusCode.MultiStatus, advance.status, "$label: a single failed element is a 207 partial_failure")
                }
                d.cleanup(item, holder)
            }
        }
}

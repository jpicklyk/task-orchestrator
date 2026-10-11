package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
import io.github.jpicklyk.mcptask.current.parity.ParityHarness
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What a twin call observed: the REST status and the body with `meta.reqId` masked (equal to the MCP body byte for byte). */
internal class TwinResult(
    val status: Int,
    val body: JsonObject
) {
    val isOk: Boolean get() =
        body
            .getValue("ok")
            .jsonPrimitive.content
            .toBoolean()

    val data: JsonObject get() = body.getValue("data").jsonObject

    /** The whole-call error member (absent for an ok body). */
    val error: JsonObject get() = body.getValue("error").jsonObject

    val code: String get() = error.getValue("code").jsonPrimitive.content

    val results: JsonArray get() = data.getValue("results").jsonArray

    fun element(i: Int): JsonObject = results[i].jsonObject

    fun elementError(i: Int): JsonObject = element(i).getValue("error").jsonObject

    fun elementCode(i: Int): String = elementError(i).getValue("code").jsonPrimitive.content

    fun elementDetail(i: Int): JsonObject = elementError(i).getValue("detail").jsonObject

    /** The `field` of the single field violation of element [i]'s `invalid_request` detail. */
    fun elementField(i: Int): String =
        elementDetail(i)
            .getValue("fields")
            .jsonArray
            .single()
            .jsonObject
            .getValue("field")
            .jsonPrimitive.content
}

/**
 * Two identically seeded databases, one driven over `POST /api/v1/ops/advance` and one through an MCP client, for scenarios that
 * need what [ParityHarness] cannot seed (a config, roles, claims, dependencies, notes) or that need several calls in sequence.
 * Every [call] sends the SAME arguments to both legs under the SAME token, asserts the bodies are equal byte for byte once
 * `meta.reqId` is masked, asserts the REST status equals the catalog status of the body's error code, and asserts the two
 * databases still hold equal rows (generated ids and timestamps ignored), exactly as [ParityHarness.run] does for one call.
 *
 * Both legs run under the harness fixed clock, so a claim made at the fixed instant expires at the fixed instant plus its TTL.
 */
internal class AdvanceTwin(
    configYaml: String = "work_item_schemas: {}\n",
    seed: suspend EventLogRig.() -> Unit = {}
) : AutoCloseable {
    private class Leg(
        val db: SqliteTestDatabase,
        val dir: Path,
        val rig: EventLogRig
    ) : AutoCloseable {
        override fun close() {
            runCatching { db.close() }
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    private val registry = ToolRegistry(listOf(advanceTool()))

    private fun open(
        configYaml: String,
        seed: suspend EventLogRig.() -> Unit
    ): Leg {
        val db = SqliteTestDatabase.open(clock = PARITY_CLOCK)
        val dir = Files.createTempDirectory("advance-twin-")
        try {
            val rig = EventLogRig.build(db, dir, configYaml, clock = PARITY_CLOCK)
            runBlocking { rig.seed() }
            return Leg(db, dir, rig)
        } catch (e: Throwable) {
            runCatching { db.close() }
            runCatching { dir.toFile().deleteRecursively() }
            throw e
        }
    }

    private val restLeg: Leg = open(configYaml, seed)
    private val mcpLeg: Leg =
        try {
            open(configYaml, seed)
        } catch (e: Throwable) {
            restLeg.close()
            throw e
        }

    /** The MCP leg's rig, for state assertions after a call (every call asserts the REST leg holds equal rows). */
    val rig: EventLogRig get() = mcpLeg.rig

    override fun close() {
        restLeg.close()
        mcpLeg.close()
    }

    private fun rest(
        token: ParityToken,
        args: String
    ): Pair<Int, String> {
        var status = 0
        var body = ""
        testApplication {
            application { installParityRestApp(restLeg.rig, parityAuthConfig(token), registry) }
            val response =
                client.post("/api/v1/ops/$ADVANCE_TOOL") {
                    header(HttpHeaders.Authorization, "Bearer ${token.raw}")
                    contentType(ContentType.Application.Json)
                    setBody(args)
                }
            status = response.status.value
            body = response.bodyAsText()
        }
        return status to body
    }

    private fun mask(body: String): String {
        val reqId =
            assertNotNull(
                Json
                    .parseToJsonElement(body)
                    .jsonObject["meta"]
                    ?.jsonObject
                    ?.get("reqId")
                    ?.jsonPrimitive
                    ?.content,
                "body carries meta.reqId: $body"
            )
        val marker = """"reqId":"$reqId""""
        assertTrue(marker in body, "body carries its reqId marker: $body")
        return body.replace(marker, """"reqId":"<REQ>"""")
    }

    private fun comparable(snapshot: Map<String, List<Map<String, Any?>>>): Map<String, List<Map<String, Any?>>> =
        snapshot.mapValues { (_, rows) ->
            rows.map { row ->
                // a note event's entity id is the generated note id, so it differs between two seeded databases
                if (row["entity_kind"] == "note") row - "host" - "entity_id" else row - "host"
            }
        }

    /** Sends [args] to both legs under [token] and returns the (equal) outcome. */
    fun call(
        token: ParityToken,
        args: String
    ): TwinResult {
        val (status, restBody) = rest(token, args)
        val mcp =
            runBlocking {
                callMcp(
                    mcpLeg.rig.composition.toolContext,
                    registry,
                    { parityAccess(token) },
                    ADVANCE_TOOL,
                    Json.parseToJsonElement(args).jsonObject
                )
            }
        val restNorm = mask(restBody)
        val mcpNorm = mask(mcp.structured.toString())
        assertEquals(mcpNorm, restNorm, "body parity between MCP and REST for $args")

        val parsed = Json.parseToJsonElement(restNorm).jsonObject
        val ok =
            parsed
                .getValue("ok")
                .jsonPrimitive.content
                .toBoolean()
        assertEquals(!ok, mcp.isError, "MCP isError is the negation of ok")
        val code =
            parsed["error"]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.content
        if (code == null) {
            assertEquals(200, status, "an ok body is 200")
        } else {
            assertEquals(ErrorCode.entries.single { it.wire == code }.httpStatus, status, "REST status equals the catalog status of $code")
        }
        // The event `host` column records the calling client (the REST client's user agent vs the MCP client's name), a
        // surface-dependent value by construction; every other column must agree (generated note ids aside).
        val restSnapshot = comparable(ParityHarness.snapshot(restLeg.db.jdbcUrl))
        val mcpSnapshot = comparable(ParityHarness.snapshot(mcpLeg.db.jdbcUrl))
        for (table in restSnapshot.keys) {
            assertEquals(restSnapshot.getValue(table), mcpSnapshot.getValue(table), "$table rows equal across the two legs after $args")
        }
        return TwinResult(status, parsed)
    }
}

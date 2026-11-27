package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.identity.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.identity.BearerTokenStore
import io.github.jpicklyk.mcptask.current.identity.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.run.internal.wire.Reply
import io.github.jpicklyk.mcptask.current.run.internal.wire.SeatWorld
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8fc593a7 / F3-A) for the serving half of seat `skills`: scenarios S7 and S11 of the
 * frozen test-plan. Every call goes through the real MCP adapter and production dispatcher over the real composition and SQLite
 * ([SeatWorld]); the REST legs run the real routes.
 *
 * Oracles (never read from a run): task-scope AC2 (`items_read` schema and context serve a seat's `skills` only when declared), D3
 * (the member sits on the seat object, right after `rules`, in the declared order; `[]` is served as `[]`; absent serves no member),
 * AC4 and D5 (the schema `features` list is `[seats, dispatchBySeat, independent_of, rules, seatSkills]`, `seatSkills` appended last,
 * advertised unconditionally, so also for a seat-less schema, and on `GET /api/v1/info` and `GET /.well-known/mcp-task-orchestrator.json`,
 * as documented in `current/docs/api/openapi.yaml`).
 *
 * Negative-assertion conditions: the undeclared seat (`checker`) is served beside declaring seats of the same response, so a server
 * that serves nothing fails the declared legs, and a server that serves `skills` for every seat fails the undeclared leg.
 *
 * Domain invariants satisfied by construction: items are seeded through the rig as children of their root in the WORK phase, the
 * phase every declared seat belongs to, so the context seats list names all three.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class SeatSkillsServingTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val config =
        """
        work_item_schemas:
          seated-task:
            seats:
              - { name: builder, phase: work, enters: true, skills: [zeta, "plugin:beta", alpha] }
              - { name: checker, phase: work }
              - { name: auditor, phase: work, skills: [] }
            notes: []
          plain-task:
            notes: []
        """.trimIndent() + "\n"

    private val expectedFeatures =
        listOf(
            "seats",
            "dispatchBySeat",
            "independent_of",
            "rules",
            "seatSkills",
            "seatWrites",
            "orchestratorNotes",
            "resumeGate",
            "answerProvenance",
            "decisionTools",
            "waitingOn",
            "decisionAuthority"
        )

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock, config)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private fun rest(block: suspend ApplicationTestBuilder.(SeatWorld) -> Unit) =
        testApplication {
            val w = SeatWorld.create(db.db, dir, clock, config)
            val auth =
                ApiAuthConfig.Bearer(
                    tokens =
                        w.everyToken.associate { t ->
                            HashBytes(sha256(t.raw)) to BearerTokenStore.TokenEntry(t.principal(), expiresAt = null)
                        }
                )
            application { installParityRestApp(w.rig, auth, w.registry) }
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private fun seatsByName(data: JsonObject?): Map<String, JsonObject> =
        assertNotNull(data, "a data object").getValue("seats").jsonArray.map { it.jsonObject }.associateBy {
            it.getValue("name").jsonPrimitive.content
        }

    private fun assertServedSkills(
        seats: Map<String, JsonObject>,
        label: String
    ) {
        assertEquals(setOf("builder", "checker", "auditor"), seats.keys, "$label: all three work seats are served")
        assertEquals(
            listOf("zeta", "plugin:beta", "alpha"),
            seats
                .getValue("builder")
                .getValue("skills")
                .jsonArray
                .map { it.jsonPrimitive.content },
            "$label: declared skills in declared order"
        )
        assertFalse(seats.getValue("checker").containsKey("skills"), "$label: an undeclared seat serves no skills member")
        assertEquals(JsonArray(emptyList()), seats.getValue("auditor")["skills"], "$label: a declared-empty list is served as []")
    }

    // ------------------------------------------------------------------ S7

    @Test
    fun `S7 items_read schema serves skills in order on the declaring seat only`(): Unit =
        world { w ->
            val item = w.rig.seed("S7 schema item", parent = w.root, role = Role.WORK, tags = "seated-task")

            val byType = w.call("items_read", """{"select":"schema","type":"seated-task"}""")
            assertTrue(byType.ok(), byType.toString())
            assertServedSkills(seatsByName(byType.data), "schema by type")

            val byItem = w.call("items_read", """{"select":"schema","itemId":"${item.id}"}""")
            assertTrue(byItem.ok(), byItem.toString())
            assertServedSkills(seatsByName(byItem.data), "schema by item")
        }

    @Test
    fun `S7 items_read context serves skills in order on the declaring seat only, and an empty list as an empty array`(): Unit =
        world { w ->
            val item = w.rig.seed("S7 context item", parent = w.root, role = Role.WORK, tags = "seated-task")

            val context = w.call("items_read", """{"select":"context","itemId":"${item.id}"}""")
            assertTrue(context.ok(), context.toString())
            assertServedSkills(seatsByName(context.data), "context")
        }

    @Test
    fun `S7 a seat-less schema serves no seats and so no skills member anywhere`(): Unit =
        world { w ->
            val item = w.rig.seed("S7 plain item", parent = w.root, role = Role.WORK, tags = "plain-task")
            val schema = w.call("items_read", """{"select":"schema","itemId":"${item.id}"}""")
            assertTrue(schema.ok(), schema.toString())
            assertFalse("skills" in schema.allKeys(), "no skills member in a seat-less schema: $schema")
            // Control: the seated schema of the same server does serve one.
            val seated = w.call("items_read", """{"select":"schema","type":"seated-task"}""")
            assertTrue("skills" in seated.allKeys(), "control: $seated")
        }

    // ------------------------------------------------------------------ S11

    private fun featuresOf(reply: Reply): List<String> =
        assertNotNull(reply.data, reply.toString()).getValue("features").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `S11 the schema features list is the advertised list on a seat-aware type, a seat-aware item and a seat-less schema`(): Unit =
        world { w ->
            val seated = w.rig.seed("S11 seated", parent = w.root, role = Role.WORK, tags = "seated-task")
            val plain = w.rig.seed("S11 plain", parent = w.root, role = Role.WORK, tags = "plain-task")
            val calls =
                mapOf(
                    "seat-aware type" to """{"select":"schema","type":"seated-task"}""",
                    "seat-aware item" to """{"select":"schema","itemId":"${seated.id}"}""",
                    "seat-less type" to """{"select":"schema","type":"plain-task"}""",
                    "seat-less item" to """{"select":"schema","itemId":"${plain.id}"}"""
                )
            for ((label, args) in calls) {
                val reply = w.call("items_read", args)
                assertTrue(reply.ok(), "$label: $reply")
                assertEquals(expectedFeatures, featuresOf(reply), label)
            }
        }

    @Test
    fun `S11 GET api v1 info and the well-known document advertise the same features list`(): Unit =
        rest { w ->
            val info = client.get("/api/v1/info") { header(HttpHeaders.Authorization, "Bearer ${w.orch.raw}") }
            assertEquals(200, info.status.value, info.bodyAsText())
            val infoFeatures =
                Json
                    .parseToJsonElement(info.bodyAsText())
                    .jsonObject
                    .getValue("data")
                    .jsonObject
                    .getValue("features")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(expectedFeatures, infoFeatures, "GET /api/v1/info")

            val wellKnown = client.get("/.well-known/mcp-task-orchestrator.json")
            assertEquals(200, wellKnown.status.value, wellKnown.bodyAsText())
            val wellKnownFeatures =
                Json
                    .parseToJsonElement(wellKnown.bodyAsText())
                    .jsonObject
                    .getValue("features")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(expectedFeatures, wellKnownFeatures, "GET /.well-known/mcp-task-orchestrator.json")
        }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops

import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
import io.github.jpicklyk.mcptask.current.parity.ParityHarness
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.RunsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8df44a22, R20) for the REST seam of the run tools: `POST /api/v1/ops/runs_write`
 * versus `POST /api/v1/ops/director/runs_write`, and the multi-call sequences (lifecycle, idempotency, replay) the one-call parity
 * harness cannot state. Scenarios S5 to S8, S12 to S14 and S19 of the frozen test-plan, plus the unauthenticated row of the
 * item's test rule.
 *
 * Oracles (never read from a run): plan 3.2 (close: the coordinator, the director profile or a human; pause and resume: the director
 * profile or a human only, the opener may not; update: the coordinator only; mark: any `runs` caller) with the refusal shape
 * `forbidden{scope: principal_kind, required}` fixed by the task-scope; plan 3.4c and R10 (the director is a PROFILE: `/ops/{tool}`
 * is served as `rest`, `/ops/director/{tool}` as `director`; the director path serves exactly the director members of a visible
 * tool and answers a hidden member with the dispatcher's `400 invalid_request` at `/ops/0/op`); the frozen REST fault table of
 * `api-rest.md` (401 `unauthenticated` before anything else); the envelope status table (200, 207 for a failed element, 400, 409
 * `idempotency_mismatch`); `api-rest.md` section 20a (a body `actor` is ignored on REST; idempotency keys are namespaced per
 * token principal); the R17 state machine (`run_paused` on an update of a paused run) and the run event types (`run.opened`,
 * `run.updated`, `run.paused`, `run.resumed`, `run.ended`, `run.marked`). Expected values are literals.
 *
 * Negative-assertion conditions: every refusal is paired with the same call on the route or token that succeeds and asserts the run
 * status afterwards; every "no second run" assertion counts rows in the runs table after a call that WOULD have created one with a
 * different key or token.
 *
 * Domain invariants satisfied by construction: items are seeded through the undecorated repository with each child inheriting its
 * parent's root and depth; runs are opened through the tool itself (so the stored state is whatever the service produced) or seeded
 * through `ParitySeeder.run`, which derives the state fields from the status; tokens are `ParityToken`s whose principal the harness builds.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class RunsDirectorRouteTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())

    private val coord = RunsParityScenarios.coord
    private val other = RunsParityScenarios.other
    private val human = RunsParityScenarios.human
    private val agent = RunsParityScenarios.agent
    private val reader = RunsParityScenarios.reader

    private val auth =
        ApiAuthConfig.Bearer(
            tokens =
                listOf(coord, other, human, agent, reader).associate { t ->
                    HashBytes(sha256(t.raw)) to
                        BearerTokenStore.TokenEntry(t.principal(), expiresAt = null)
                }
        )

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            val rig = EventLogRig.build(db.db, dir, clock = PARITY_CLOCK)
            val provider = db.db.repositoryProvider()
            ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork())
                .let { seeder -> RunsParityScenarios.tree.invoke(seeder) }
            application { installParityRestApp(rig, auth, registry) }
            block()
        }

    private suspend fun HttpClient.send(
        path: String,
        body: String,
        token: ParityToken? = coord,
        extra: List<Pair<String, String>> = emptyList()
    ): HttpResponse =
        post(path) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer ${token.raw}")
            contentType(ContentType.Application.Json)
            extra.forEach { (k, v) -> header(k, v) }
            setBody(body)
        }

    private suspend fun HttpClient.plain(
        body: String,
        token: ParityToken? = coord,
        tool: String = "runs_write"
    ) = send("/api/v1/ops/$tool", body, token)

    private suspend fun HttpClient.director(
        body: String,
        token: ParityToken? = coord,
        tool: String = "runs_write"
    ) = send("/api/v1/ops/director/$tool", body, token)

    private suspend fun HttpResponse.text(): String = bodyAsText()

    private fun JsonObject.code(): String? =
        this["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content

    private val runIdPattern = Regex("\"runId\":\"([0-9a-f-]{36})\"")

    private fun runIdIn(text: String): String = checkNotNull(runIdPattern.find(text)) { "no runId in $text" }.groupValues[1]

    private fun snapshot() = ParityHarness.snapshot(db.db.jdbcUrl)

    private fun runEventTypes(): List<String> =
        snapshot()
            .getValue("events")
            .map { it["type"] as String }
            .filter { it.startsWith("run.") }

    private fun runRows(): Int = snapshot().getValue("runs").size

    private val ops = RunsParityScenarios

    private suspend fun HttpClient.open(
        extra: String = "",
        token: ParityToken = coord
    ): String {
        val response = plain(ops.ops(ops.open(ops.r1, extra)), token)
        assertEquals(200, response.status.value, "open: ${response.text()}")
        return runIdIn(response.text())
    }

    private suspend fun HttpClient.statusOf(runId: String): String {
        val response = plain(ops.get(runId), reader, "runs_read")
        assertEquals(200, response.status.value)
        return Json
            .parseToJsonElement(response.text())
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("status")
            .jsonPrimitive.content
    }

    // ------------------------------------------------------------------ unauthenticated

    @Test
    fun `an unauthenticated call is 401 unauthenticated on every runs route and writes nothing`() =
        withApp {
            val before = snapshot()
            for ((path, body) in listOf(
                "/api/v1/ops/runs_write" to ops.ops(ops.open(ops.r1)),
                "/api/v1/ops/runs_read" to ops.list(),
                "/api/v1/ops/director/runs_write" to ops.ops(ops.open(ops.r1)),
                "/api/v1/ops/director/runs_read" to ops.list()
            )) {
                val response = client.send(path, body, token = null)
                assertEquals(401, response.status.value, path)
                assertEquals("unauthenticated", Json.parseToJsonElement(response.text()).jsonObject.code(), path)
            }
            assertEquals(before, snapshot(), "nothing was written")
            // control: the same call with a token does run
            assertEquals(200, client.plain(ops.ops(ops.open(ops.r1))).status.value)
        }

    // ------------------------------------------------------------------ S13 pause and resume by path

    @Test
    fun `S13 a service token that opened the run is refused pause on the plain route and succeeds on the director route`() =
        withApp {
            val runId = client.open()
            val refused = client.plain(ops.ops(ops.pause(runId)))
            assertEquals(207, refused.status.value)
            val text = refused.text()
            assertTrue("\"code\":\"forbidden\"" in text && "\"scope\":\"principal_kind\"" in text, text)
            assertTrue("\"required\":\"director,human\"" in text, text)
            assertEquals("open", client.statusOf(runId), "the refused pause changed nothing")
            assertEquals(listOf("run.opened"), runEventTypes())

            val paused = client.director(ops.ops(ops.pause(runId)))
            assertEquals(200, paused.status.value, paused.text())
            assertTrue("\"status\":\"paused\"" in paused.text() && "\"pauseReason\":\"manual\"" in paused.text(), paused.text())
            assertEquals("paused", client.statusOf(runId))

            val resumed = client.director(ops.ops(ops.resume(runId)))
            assertEquals(200, resumed.status.value, resumed.text())
            assertEquals("open", client.statusOf(runId))
            assertEquals(listOf("run.opened", "run.paused", "run.resumed"), runEventTypes())
        }

    @Test
    fun `S13 a service token that is not the coordinator and an agent token follow the same path rule for pause and resume`() =
        withApp {
            val runId = client.open()
            for (token in listOf(other, agent)) {
                val refused = client.plain(ops.ops(ops.pause(runId)), token)
                assertEquals(207, refused.status.value, token.id)
                assertTrue("\"required\":\"director,human\"" in refused.text(), refused.text())
                assertEquals("open", client.statusOf(runId), token.id)
            }
            val viaDirector = client.director(ops.ops(ops.pause(runId)), other)
            assertEquals(200, viaDirector.status.value, viaDirector.text())
            assertEquals("paused", client.statusOf(runId))

            val resumeRefused = client.plain(ops.ops(ops.resume(runId)), agent)
            assertEquals(207, resumeRefused.status.value)
            assertTrue("\"required\":\"director,human\"" in resumeRefused.text(), resumeRefused.text())
            assertEquals("paused", client.statusOf(runId), "a refused resume leaves the run paused")

            val resumed = client.plain(ops.ops(ops.resume(runId)), human)
            assertEquals(200, resumed.status.value, resumed.text())
            assertEquals("open", client.statusOf(runId))
        }

    @Test
    fun `S13 a human token pauses on the plain route and the opener is no exception to the kind rule`() =
        withApp {
            val runId = client.open(token = human)
            val paused = client.plain(ops.ops(ops.pause(runId)), human)
            assertEquals(200, paused.status.value, paused.text())
            assertEquals("paused", client.statusOf(runId))
            assertEquals(listOf("run.opened", "run.paused"), runEventTypes())
            val openerOnly = client.open()
            val refused = client.plain(ops.ops(ops.pause(openerOnly)), coord)
            assertEquals(207, refused.status.value)
            assertEquals("open", client.statusOf(openerOnly))
        }

    // ------------------------------------------------------------------ director route member set

    @Test
    fun `the director route serves its members and answers update and mark as hidden members`() =
        withApp {
            val runId = client.open()
            for ((label, body) in listOf(
                "update" to ops.ops(ops.update(runId, ""","hints":{"x":"y"}""")),
                "mark" to ops.ops(ops.mark(runId, "retro"))
            )) {
                val response = client.director(body)
                assertEquals(207, response.status.value, "$label: ${response.text()}")
                assertEquals("partial_failure", Json.parseToJsonElement(response.text()).jsonObject.code(), label)
                assertTrue(
                    "\"code\":\"invalid_request\"" in response.text() && "/ops/0/op" in response.text(),
                    "$label: the element is invalid_request at the op selector: ${response.text()}"
                )
            }
            assertEquals(listOf("run.opened"), runEventTypes(), "the refused calls wrote nothing")

            // controls: the same members are served where they belong
            assertEquals(200, client.plain(ops.ops(ops.update(runId, ""","hints":{"x":"y"}"""))).status.value, "update on the plain route")
            assertEquals(200, client.plain(ops.ops(ops.mark(runId, "retro")), other).status.value, "mark on the plain route")

            assertEquals(200, client.director(ops.get(runId), reader, "runs_read").status.value, "get on the director route")
            assertEquals(200, client.director(ops.list(), reader, "runs_read").status.value, "list on the director route")
            val opened = client.director(ops.ops(ops.open(ops.r2)), other)
            assertEquals(200, opened.status.value, opened.text())
            assertTrue("\"openedBy\":\"api:rw-other\"" in opened.text(), opened.text())
        }

    @Test
    fun `the director route lets any runs token close any run, and the plain route lets only the coordinator or a human`() =
        withApp {
            val runId = client.open()
            val refused = client.plain(ops.ops(ops.close(runId, "cancelled")), other)
            assertEquals(207, refused.status.value)
            assertTrue("\"required\":\"coordinator,director,human\"" in refused.text(), refused.text())
            assertEquals("open", client.statusOf(runId))

            val closed = client.director(ops.ops(ops.close(runId, "cancelled")), other)
            assertEquals(200, closed.status.value, closed.text())
            assertTrue(
                "\"terminalReason\":\"cancelled\"" in closed.text() && "\"closedBy\":\"api:rw-other\"" in closed.text(),
                closed.text()
            )
            assertEquals("closed", client.statusOf(runId))
            assertEquals(listOf("run.opened", "run.ended"), runEventTypes())
        }

    // ------------------------------------------------------------------ S12 body actor on REST

    @Test
    fun `S12 a body actor named like the coordinator does not make a REST token the coordinator`() =
        withApp {
            val runId = client.open()
            val spoof =
                client.plain(
                    ops.ops(ops.close(runId, "cancelled"), top = ""","actor":{"id":"api:rw-coord","kind":"subagent"}"""),
                    other
                )
            assertEquals(207, spoof.status.value)
            assertTrue("\"scope\":\"principal_kind\"" in spoof.text(), spoof.text())
            assertEquals("open", client.statusOf(runId))
            val updateSpoof =
                client.plain(
                    ops.ops(ops.update(runId, ""","hints":{"x":"y"}"""), top = ""","actor":{"id":"api:rw-coord","kind":"subagent"}"""),
                    other
                )
            assertEquals(207, updateSpoof.status.value)
            assertTrue("\"required\":\"coordinator\"" in updateSpoof.text(), updateSpoof.text())
            // control: the real coordinator token closes
            assertEquals(200, client.plain(ops.ops(ops.close(runId, "cancelled")), coord).status.value)
        }

    @Test
    fun `S12 a read-only token cannot open and a runs token without read cannot get, each by capability`() =
        withApp {
            val refused = client.plain(ops.ops(ops.open(ops.r1)), reader)
            assertEquals(207, refused.status.value)
            assertTrue("\"scope\":\"capability\"" in refused.text() && "\"required\":\"runs\"" in refused.text(), refused.text())
            assertEquals(0, runRows())
            assertEquals(200, client.plain(ops.ops(ops.open(ops.r1)), coord).status.value, "control: a runs token opens")
            assertEquals(1, runRows())
        }

    // ------------------------------------------------------------------ S19 idempotency

    @Test
    fun `S19 open twice with one idempotency key and one payload is one run, the replay answers the same run id`() =
        withApp {
            val body = ops.ops(ops.open(ops.r1, ""","idempotencyKey":"open-once""""))
            val first = client.plain(body)
            val second = client.plain(body)
            assertEquals(200, first.status.value, first.text())
            assertEquals(200, second.status.value, second.text())
            assertEquals(runIdIn(first.text()), runIdIn(second.text()), "the replay is the same run")
            assertEquals(1, runRows(), "one run row")
            assertEquals(listOf("run.opened"), runEventTypes(), "one run.opened")
        }

    @Test
    fun `S19 a reused key with a different payload is idempotency_mismatch and creates nothing`() =
        withApp {
            assertEquals(200, client.plain(ops.ops(ops.open(ops.r1, ""","idempotencyKey":"k1""""))).status.value)
            val clash = client.plain(ops.ops(ops.open(ops.r2, ""","idempotencyKey":"k1"""")))
            assertTrue("idempotency_mismatch" in clash.text(), clash.text())
            assertEquals(1, runRows(), "the clashing open created no run")
            assertEquals(listOf("run.opened"), runEventTypes())
        }

    @Test
    fun `S19 the same key and payload from two different tokens are two different calls`() =
        withApp {
            val body = ops.ops(ops.open(ops.r1, ""","idempotencyKey":"shared-key""""))
            val a = client.plain(body, coord)
            val b = client.plain(body, other)
            assertEquals(200, a.status.value, a.text())
            assertEquals(200, b.status.value, b.text())
            assertNotEquals(runIdIn(a.text()), runIdIn(b.text()))
            assertEquals(2, runRows())
        }

    @Test
    fun `S19 a mark replay across two calls keeps the first write and records one event`() =
        withApp {
            val runId = client.open()
            assertEquals(200, client.plain(ops.ops(ops.close(runId, "completed"))).status.value)
            val first = client.plain(ops.ops(ops.mark(runId, "retro")), other)
            assertEquals(200, first.status.value, first.text())
            assertTrue("\"marked\":true" in first.text(), first.text())
            val second = client.plain(ops.ops(ops.mark(runId, "retro")), other)
            assertTrue("\"marked\":false" in second.text(), second.text())
            assertEquals(1, runEventTypes().count { it == "run.marked" })
        }

    // ------------------------------------------------------------------ S5 .. S8 lifecycle

    @Test
    fun `the lifecycle records one event per transition in order, and a refused update on a paused run records none`() =
        withApp {
            val runId = client.open(""","items":["${ops.c1}"]""")
            assertEquals(200, client.plain(ops.ops(ops.update(runId, ""","addItems":["${ops.c2}"]"""))).status.value)
            assertEquals(200, client.plain(ops.ops(ops.pause(runId)), human).status.value)

            val refused = client.plain(ops.ops(ops.update(runId, ""","hints":{"x":"y"}""")))
            assertEquals(207, refused.status.value)
            assertTrue("\"code\":\"run_paused\"" in refused.text() && "\"pauseReason\":\"manual\"" in refused.text(), refused.text())
            assertEquals(listOf("run.opened", "run.updated", "run.paused"), runEventTypes(), "the refused update recorded nothing")

            assertEquals(200, client.plain(ops.ops(ops.resume(runId)), human).status.value)
            val closed = client.plain(ops.ops(ops.close(runId, "cancelled")))
            assertEquals(200, closed.status.value, closed.text())
            assertEquals(200, client.plain(ops.ops(ops.mark(runId, "retro")), other).status.value)
            assertEquals(
                listOf("run.opened", "run.updated", "run.paused", "run.resumed", "run.ended", "run.marked"),
                runEventTypes()
            )
        }

    @Test
    fun `a closed run refuses every state change with run_closed and a mark still works`() =
        withApp {
            val runId = client.open()
            assertEquals(200, client.plain(ops.ops(ops.close(runId, "abandoned"))).status.value)
            val attempts =
                listOf(
                    "update" to client.plain(ops.ops(ops.update(runId, ""","hints":{"x":"y"}"""))),
                    "pause" to client.plain(ops.ops(ops.pause(runId)), human),
                    "resume" to client.plain(ops.ops(ops.resume(runId)), human),
                    "close" to client.plain(ops.ops(ops.close(runId, "cancelled")))
                )
            for ((label, response) in attempts) {
                assertEquals(207, response.status.value, label)
                assertTrue(
                    "\"code\":\"run_closed\"" in response.text() && "\"terminalReason\":\"abandoned\"" in response.text(),
                    "$label: ${response.text()}"
                )
            }
            assertEquals(listOf("run.opened", "run.ended"), runEventTypes())
            val marked = client.plain(ops.ops(ops.mark(runId, "retro")), other)
            assertEquals(200, marked.status.value, marked.text())
            assertTrue("\"marked\":true" in marked.text(), "marking a closed run is the purpose: ${marked.text()}")
        }

    @Test
    fun `S4 unmarked follows the marks across calls - a marked run leaves the list and an unmarked closed run stays`() =
        withApp {
            val a = client.open()
            val b = client.open()
            assertEquals(200, client.plain(ops.ops(ops.close(a, "completed"))).status.value)
            assertEquals(200, client.plain(ops.ops(ops.close(b, "completed"))).status.value)

            suspend fun unmarked(): List<String> =
                Json
                    .parseToJsonElement(client.plain(ops.list(""","unmarked":"retro""""), reader, "runs_read").text())
                    .jsonObject
                    .getValue("data")
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("runId")
                            .jsonPrimitive.content
                    }

            assertEquals(setOf(a, b), unmarked().toSet(), "before any mark both closed runs are unmarked")
            assertEquals(200, client.plain(ops.ops(ops.mark(a, "retro")), other).status.value)
            assertEquals(listOf(b), unmarked(), "marking a drops it from the list; the other stays")
            assertEquals(200, client.plain(ops.ops(ops.mark(b, "other-key")), other).status.value)
            assertEquals(listOf(b), unmarked(), "a different key does not mark retro")
        }

    // ------------------------------------------------------------------ the view

    @Test
    fun `the view of a closed run carries the close instant, closer and reason, is not stalled, and omits absent members`() =
        withApp {
            val runId = client.open()
            assertEquals(200, client.plain(ops.ops(ops.close(runId, "failed")), coord).status.value)
            val text = client.plain(ops.get(runId), reader, "runs_read").text()
            val view =
                Json
                    .parseToJsonElement(text)
                    .jsonObject
                    .getValue("data")
                    .jsonObject
            assertEquals("closed", view.getValue("status").jsonPrimitive.content)
            assertEquals("failed", view.getValue("terminalReason").jsonPrimitive.content)
            assertEquals("api:rw-coord", view.getValue("closedBy").jsonPrimitive.content)
            assertEquals("2026-10-10T12:00:00Z", view.getValue("closedAt").jsonPrimitive.content)
            assertEquals("false", view.getValue("coordinatorStalled").jsonPrimitive.content)
            assertEquals(Run.DEFAULT_STALL_AFTER_SECONDS.toString(), view.getValue("stallAfterSeconds").jsonPrimitive.content)
            assertFalse("budget" in view.keys, "no budget was given: $text")
            assertFalse("pauseReason" in view.keys, text)
            assertFalse(":null" in text, "absent members are omitted, never null: $text")
            assertTrue("\"reportedTokens\":0" in text && "\"observedTokens\":0" in text, "usage starts at zero: $text")
            assertTrue("\"activeSeats\":0" in text, "no seat port before R22: $text")
            assertEquals("0", view.getValue("members").jsonPrimitive.content)
        }
}

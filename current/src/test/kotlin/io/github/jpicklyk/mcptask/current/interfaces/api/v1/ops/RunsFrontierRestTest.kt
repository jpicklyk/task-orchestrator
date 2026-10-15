package io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops

import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
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
 * Independent tests (test-author seat, item fca8ce36, R25) for the REST seam of `runs_read` select `frontier`: scenario S15 (the plain
 * route `/api/v1/ops/runs_read` and the director route `/api/v1/ops/director/runs_read` both serve it, a bad prefix is `not_found`, an
 * ambiguous prefix is `ambiguous_id`) and the scope refusal of S7 on the REST leg.
 *
 * Oracles (never read from a run): the R25 placement clause (the select is served on the orchestration profile and the director profile, so
 * the plain and the director route both answer it), the frozen REST fault table of `api-rest.md` (the error code is the envelope `error.code`),
 * the run-as-visible-as-its-root rule of `api-reference.md` (a run on a root outside the token scope is `forbidden`, never `not_found`), the
 * seeded fixture of `RunsParityScenarios` (run `ra` on root R1 over children C1 and C2, both queue and unclaimed, so both are ready; two runs
 * whose ids share the prefix `ab000000`; the well-formed id `99999999` that no run carries). Item ids on the wire are the first 8 hex digits
 * of the item UUID.
 *
 * Negative-assertion conditions: every refusal is paired with the same call by a token that is allowed (or a prefix that resolves), and
 * asserts that no frontier members come back with the refusal.
 *
 * Domain invariants satisfied by construction: runs are seeded through `ParitySeeder.run`, which derives the state fields from the status;
 * items are seeded through `RunsParityScenarios.tree` as children of their roots.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class RunsFrontierRestTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())

    private val ops = RunsParityScenarios
    private val reader = RunsParityScenarios.reader
    private val rootScoped = RunsParityScenarios.rootScoped
    private val opener = "api:rw-coord"

    private val auth =
        ApiAuthConfig.Bearer(
            tokens =
                listOf(reader, rootScoped, RunsParityScenarios.coord).associate { t ->
                    HashBytes(sha256(t.raw)) to BearerTokenStore.TokenEntry(t.principal(), expiresAt = null)
                }
        )

    private fun withApp(
        seed: suspend ParitySeeder.() -> Unit,
        block: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        val rig = EventLogRig.build(db.db, dir, clock = PARITY_CLOCK)
        val provider = db.db.repositoryProvider()
        val seeder = ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork())
        RunsParityScenarios.tree.invoke(seeder)
        seed.invoke(seeder)
        application { installParityRestApp(rig, auth, registry) }
        block()
    }

    private suspend fun HttpClient.frontier(
        route: String,
        runId: Any,
        token: ParityToken = reader
    ): HttpResponse =
        post("/api/v1/ops/$route") {
            header(HttpHeaders.Authorization, "Bearer ${token.raw}")
            contentType(ContentType.Application.Json)
            setBody("""{"select":"frontier","runId":"$runId"}""")
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.code(): String? =
        this["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content

    private fun short(id: java.util.UUID) = id.toString().substring(0, 8)

    private val seedRuns: suspend ParitySeeder.() -> Unit = {
        run(RunsParityScenarios.ra, RunsParityScenarios.r1, listOf(RunsParityScenarios.c1, RunsParityScenarios.c2), openedBy = opener)
        run(RunsParityScenarios.rb, RunsParityScenarios.r2, listOf(RunsParityScenarios.d1), openedBy = opener)
        run(RunsParityScenarios.am1, RunsParityScenarios.r1, openedBy = opener)
        run(RunsParityScenarios.am2, RunsParityScenarios.r1, openedBy = opener)
    }

    @Test
    fun `S15 the plain route and the director route both serve the frontier with the same data`() =
        withApp(seedRuns) {
            val plain = client.frontier("runs_read", RunsParityScenarios.ra)
            val director = client.frontier("director/runs_read", RunsParityScenarios.ra)
            assertEquals(200, plain.status.value, plain.bodyAsText())
            assertEquals(200, director.status.value, director.bodyAsText())

            val data = plain.json().getValue("data").jsonObject
            assertEquals(RunsParityScenarios.ra.toString(), data.getValue("runId").jsonPrimitive.content)
            assertEquals("open", data.getValue("status").jsonPrimitive.content)
            assertEquals(
                setOf(short(RunsParityScenarios.c1), short(RunsParityScenarios.c2)),
                data
                    .getValue("ready")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    }.toSet()
            )
            assertEquals(
                2,
                data
                    .getValue("counts")
                    .jsonObject
                    .getValue("queue")
                    .jsonPrimitive.content
                    .toInt()
            )
            assertTrue(data.getValue("inFlight").jsonArray.isEmpty() && data.getValue("waiting").jsonArray.isEmpty())
            assertEquals(data, director.json().getValue("data").jsonObject, "the director route serves the same frontier")
        }

    @Test
    fun `S15 an unknown prefix is not_found and an ambiguous one is ambiguous_id on both routes`() =
        withApp(seedRuns) {
            for (route in listOf("runs_read", "director/runs_read")) {
                val unknown = client.frontier(route, "99999999")
                assertEquals("not_found", unknown.json().code(), "$route unknown: ${unknown.bodyAsText()}")
                assertNotEquals(200, unknown.status.value, route)

                val ambiguous = client.frontier(route, "ab000000")
                assertEquals("ambiguous_id", ambiguous.json().code(), "$route ambiguous: ${ambiguous.bodyAsText()}")
                assertNotEquals(200, ambiguous.status.value, route)

                val resolved = client.frontier(route, RunsParityScenarios.am1)
                assertEquals(200, resolved.status.value, "control: the full id of an ambiguous prefix resolves: ${resolved.bodyAsText()}")
            }
        }

    @Test
    fun `S7 a root-scoped token is forbidden the frontier of a run on another root on both routes and reads its own`() =
        withApp(seedRuns) {
            for (route in listOf("runs_read", "director/runs_read")) {
                val refused = client.frontier(route, RunsParityScenarios.rb, rootScoped)
                assertEquals("forbidden", refused.json().code(), "$route: ${refused.bodyAsText()}")
                assertNotEquals(200, refused.status.value, route)
                assertFalse(short(RunsParityScenarios.d1) in refused.bodyAsText(), "$route: the refusal names no member")

                val own = client.frontier(route, RunsParityScenarios.ra, rootScoped)
                assertEquals(200, own.status.value, "control: its own run: ${own.bodyAsText()}")
                val unrestricted = client.frontier(route, RunsParityScenarios.rb, reader)
                assertEquals(
                    200,
                    unrestricted.status.value,
                    "control: an unrestricted reader reads the other root: ${unrestricted.bodyAsText()}"
                )
            }
        }
}

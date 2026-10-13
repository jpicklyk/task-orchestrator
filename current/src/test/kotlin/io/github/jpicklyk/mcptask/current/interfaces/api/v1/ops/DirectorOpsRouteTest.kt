package io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item b9527ef9) for `POST /api/v1/ops/director/{tool}`: scenarios S10 and S13 (REST seam)
 * of the frozen test-plan, with the fault-order rows that must hold on the new path too.
 *
 * Oracles (never read from a run): the R10 plan paragraph and task-scope item 7 and AC4 (the director path serves the director
 * profile through the same dispatcher; a registered tool outside the set is `forbidden{scope: capability, required: "director"}`,
 * not the `/ops/{tool}` `invalid_request`; a hidden select inside a visible tool keeps the dispatcher's `invalid_request`; the
 * envelopes of served members are byte-equal to `/ops/{tool}`; decision D5, the refusal is tool-level only), R5 AC3 as amended by
 * the R9 merge ruling (the director set is exactly `items_read` get, list, overview and blocked, `deps_read` list and graph, and
 * `report` usage, nothing else), the frozen REST
 * fault table of `api-rest.md` (401 unauthenticated; 400 for a non-JSON `Content-Type`, an `Idempotency-Key` header, a body that
 * is not a JSON object, an unknown tool, in that order), test-plan S13 and task-scope item 9 (the provenance of a REST call is
 * `(api:<id>, kind, proofStatus, host, sessionId null, runId, seat, served profile)`; the served profile's wire name is
 * `director` on the director path and `rest` on `/ops/{tool}`; `host` is the lowercased `X-Client-Host` per Q7). Expected values
 * are literals.
 *
 * Negative-assertion conditions: every refusal asserts that the handler never ran (the `seen` list for fixtures, the database for
 * production) and is paired with a control through the same dispatcher that does run; every "byte-equal" assertion also asserts
 * the body carries the seeded data, so two equally empty error bodies cannot satisfy it.
 *
 * Harness: the production REST topology ([installParityRestApp]); part one uses test-only select tools whose profile sets are
 * declared here, part two the production registry.
 *
 * Domain invariants satisfied by construction: items are seeded through the undecorated repository with a child inheriting its
 * parent's root and depth (see `EventLogRig.seed`); the dependency joins two distinct existing items with a fresh id and has no
 * `unblockAt` (a BLOCKS edge needs no threshold).
 */
class DirectorOpsRouteTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    @Serializable
    data class ProbeReq(
        @Doc("What to probe.") val what: String = "x",
    )

    private val seen = CopyOnWriteArrayList<CallContext>()

    private fun probe(
        name: String,
        profiles: Set<ToolProfile>,
    ) = SelectSpec.Single<ProbeReq, String>(
        name = name,
        doc = "Probe.",
        profiles = profiles,
        request = serializer<ProbeReq>(),
        handler = { _, ctx, req ->
            seen += ctx
            Outcome.Ok("probed:$name:${req.what}")
        },
        data = { JsonPrimitive(it) },
    )

    /** `dir_probe` serves `peek` to the director and knowledge profiles and `deep` to knowledge only. */
    private val visibleTool =
        SelectToolSpec(
            "dir_probe",
            "Fixture director-visible tool.",
            listOf(probe("peek", director() + knowledge()), probe("deep", knowledge()))
        )

    private val hiddenTool = SelectToolSpec("dir_hidden", "Fixture knowledge-only tool.", listOf(probe("peek", knowledge())))

    private val orchestrationTool = SelectToolSpec("dir_orch", "Fixture orchestration-only tool.", listOf(probe("peek", orchestration())))

    private val fixtureRegistry get() = ToolRegistry(listOf(visibleTool, hiddenTool, orchestrationTool))

    private fun sha256(input: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    private fun token(
        raw: String,
        id: String,
        capabilities: Set<ApiCapability>,
        kind: PrincipalKind = PrincipalKind.SERVICE,
    ): Pair<HashBytes, BearerTokenStore.TokenEntry> =
        HashBytes(sha256(raw)) to
            BearerTokenStore.TokenEntry(
                Principal(id, kind, ProofStatus.ISSUED, AccessScope.unrestricted(), capabilities),
                expiresAt = null,
            )

    private val auth =
        ApiAuthConfig.Bearer(
            tokens =
                mapOf(
                    token("raw-read", "dir-read", setOf(ApiCapability.READ)),
                    token("raw-agent", "dir-agent", setOf(ApiCapability.READ), PrincipalKind.AGENT),
                    token("raw-admin", "dir-admin", setOf(ApiCapability.ADMIN)),
                ),
        )

    private fun withApp(
        registry: ToolRegistry,
        block: suspend ApplicationTestBuilder.(EventLogRig) -> Unit,
    ) = testApplication {
        val rig = EventLogRig.build(db.db, dir)
        application { installParityRestApp(rig, auth, registry) }
        block(rig)
    }

    private suspend fun HttpClient.send(
        path: String,
        body: String,
        token: String? = "raw-read",
        ct: ContentType? = ContentType.Application.Json,
        extra: List<Pair<String, String>> = emptyList(),
    ): HttpResponse =
        post(path) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (ct != null) contentType(ct)
            extra.forEach { (k, v) -> header(k, v) }
            setBody(body)
        }

    private suspend fun HttpResponse.envelope(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.error(): JsonObject = getValue("error").jsonObject

    private fun JsonObject.code(): String = error().getValue("code").jsonPrimitive.content

    private fun JsonObject.firstField(): JsonObject =
        error()
            .getValue("detail")
            .jsonObject
            .getValue("fields")
            .jsonArray
            .first()
            .jsonObject

    private fun assertDirectorRefusal(
        envelope: JsonObject,
        label: String,
    ) {
        assertEquals("forbidden", envelope.code(), label)
        val detail = envelope.error().getValue("detail").jsonObject
        assertEquals("capability", detail.getValue("scope").jsonPrimitive.content, label)
        assertEquals("director", detail.getValue("required").jsonPrimitive.content, label)
    }

    private val reqIdPattern = Regex("\"reqId\":\"[^\"]*\"")

    private fun normalized(body: String): String = body.replace(reqIdPattern, "\"reqId\":\"<REQ>\"")

    // ------------------------------------------------------------------ part one: fixtures

    @Test
    fun `S10 the director path serves a director-visible member and the same member on ops is the same envelope`() =
        withApp(fixtureRegistry) { _ ->
            val viaDirector = client.send("/api/v1/ops/director/dir_probe", """{"select":"peek","what":"w"}""")
            assertEquals(200, viaDirector.status.value, viaDirector.bodyAsText())
            val viaOps = client.send("/api/v1/ops/dir_probe", """{"select":"peek","what":"w"}""")
            assertEquals(200, viaOps.status.value, viaOps.bodyAsText())

            val directorBody = normalized(viaDirector.bodyAsText())
            assertTrue("probed:peek:w" in directorBody, "the body carries the handler's data: $directorBody")
            assertEquals(normalized(viaOps.bodyAsText()), directorBody, "byte-equal envelopes modulo meta.reqId")
            assertEquals(2, seen.size, "both calls ran the handler")
        }

    @Test
    fun `S10 a registered tool outside the director set is forbidden director and never runs, whatever its other profiles`() =
        withApp(fixtureRegistry) { _ ->
            for (tool in listOf("dir_hidden", "dir_orch")) {
                val response = client.send("/api/v1/ops/director/$tool", """{"select":"peek"}""")
                assertEquals(403, response.status.value, "$tool: ${response.bodyAsText()}")
                assertDirectorRefusal(response.envelope(), tool)
                assertEquals(emptyList(), seen.toList(), "$tool: the handler never ran")
            }
            val admin = client.send("/api/v1/ops/director/dir_hidden", """{"select":"peek"}""", token = "raw-admin")
            assertEquals(403, admin.status.value, "admin does not lift the director set")
            assertDirectorRefusal(admin.envelope(), "admin")
            assertEquals(emptyList(), seen.toList())

            // control: the same tools run on the plain ops path
            assertEquals(200, client.send("/api/v1/ops/dir_hidden", """{"select":"peek"}""").status.value)
            assertEquals(200, client.send("/api/v1/ops/dir_orch", """{"select":"peek"}""").status.value)
            assertEquals(2, seen.size)
        }

    @Test
    fun `S10 a hidden select inside a visible tool keeps invalid_request and does not run`() =
        withApp(fixtureRegistry) { _ ->
            val response = client.send("/api/v1/ops/director/dir_probe", """{"select":"deep"}""")
            assertEquals(400, response.status.value, response.bodyAsText())
            assertEquals("invalid_request", response.envelope().code())
            assertEquals(emptyList(), seen.toList(), "the hidden select never ran")

            // control: the same select runs on the plain ops path
            val control = client.send("/api/v1/ops/dir_probe", """{"select":"deep"}""")
            assertEquals(200, control.status.value, control.bodyAsText())
            assertEquals(1, seen.size)
        }

    @Test
    fun `S10 an unknown tool on the director path is invalid_request on field tool`() =
        withApp(fixtureRegistry) { _ ->
            val response = client.send("/api/v1/ops/director/no_such_tool", """{"select":"peek"}""")
            assertEquals(400, response.status.value, response.bodyAsText())
            assertEquals("invalid_request", response.envelope().code())
            assertEquals(
                "tool",
                response
                    .envelope()
                    .firstField()
                    .getValue("field")
                    .jsonPrimitive.content
            )
            assertEquals(emptyList(), seen.toList())
        }

    @Test
    fun `S10 the fault order of the ops route holds on the director path`() =
        withApp(fixtureRegistry) { _ ->
            val path = "/api/v1/ops/director/dir_probe"
            val body = """{"select":"peek"}"""

            assertEquals(401, client.send(path, body, token = null).status.value, "no principal")
            assertEquals(401, client.send(path, body, token = "not-a-real-token").status.value, "unknown token")

            val contentType = client.send(path, body, ct = ContentType.Text.Plain)
            assertEquals(400, contentType.status.value)
            assertEquals(
                "Content-Type",
                contentType
                    .envelope()
                    .firstField()
                    .getValue("field")
                    .jsonPrimitive.content
            )

            val key = client.send(path, body, extra = listOf("Idempotency-Key" to "k-1"))
            assertEquals(400, key.status.value)
            assertEquals(
                "Idempotency-Key",
                key
                    .envelope()
                    .firstField()
                    .getValue("field")
                    .jsonPrimitive.content
            )

            val notObject = client.send(path, "[]")
            assertEquals(400, notObject.status.value)
            assertEquals("invalid_request", notObject.envelope().code())

            assertEquals(emptyList(), seen.toList(), "none of the refused calls ran")
            assertEquals(200, client.send(path, body).status.value, "control: the clean call runs")
            assertEquals(1, seen.size)
        }

    @Test
    fun `S13 the provenance of a REST call names api colon id, kind, proof, lowercased host and the served profile`() =
        withApp(fixtureRegistry) { _ ->
            val host = listOf("X-Client-Host" to "Dashboard")
            assertEquals(200, client.send("/api/v1/ops/dir_probe", """{"select":"peek"}""", token = "raw-agent", extra = host).status.value)
            assertEquals(
                200,
                client.send("/api/v1/ops/director/dir_probe", """{"select":"peek"}""", token = "raw-agent", extra = host).status.value
            )
            assertEquals(2, seen.size)

            val viaOps = assertNotNull(seen[0].provenance())
            assertEquals("api:dir-agent", viaOps.principalId)
            assertEquals(PrincipalKind.AGENT, viaOps.kind)
            assertEquals(ProofStatus.ISSUED, viaOps.proofStatus)
            assertEquals("dashboard", viaOps.host)
            assertNull(viaOps.sessionId, "a REST call has no session until R14")
            assertNull(viaOps.runId)
            assertNull(viaOps.seat)
            assertEquals("rest", viaOps.profile)

            val viaDirector = assertNotNull(seen[1].provenance())
            assertEquals("director", viaDirector.profile)
            assertEquals("api:dir-agent", viaDirector.principalId)
            assertEquals("dashboard", viaDirector.host)
        }

    @Test
    fun `S13 the default token kind is SERVICE and a call without X-Client-Host still carries a host`() =
        withApp(fixtureRegistry) { _ ->
            assertEquals(200, client.send("/api/v1/ops/dir_probe", """{"select":"peek"}""", token = "raw-read").status.value)
            val prov = assertNotNull(seen.single().provenance())
            assertEquals(PrincipalKind.SERVICE, prov.kind)
            assertEquals("api:dir-read", prov.principalId)
            val host = assertNotNull(prov.host, "the REST host chain always ends in a name (Q7)")
            assertTrue(host.isNotBlank())
        }

    // ------------------------------------------------------------------ part two: the production registry

    private suspend fun seedTree(rig: EventLogRig): Pair<UUID, UUID> {
        val root = rig.seed("Director root zephyrquux")
        val child = rig.seed("Director child", parent = root)
        rig.raw.dependencyRepository().create(
            Dependency(
                id = UUID.randomUUID(),
                fromItemId = root.id,
                toItemId = child.id,
                type = DependencyType.BLOCKS,
                unblockAt = null,
                createdAt = Instant.now(),
            ),
        )
        return root.id to child.id
    }

    @Test
    fun `S10 every member of the director set answers on the director path with the same envelope as ops`() =
        withApp(ToolRegistry(buildToolSpecs())) { rig ->
            val (root, child) = seedTree(rig)
            val members =
                listOf(
                    Triple("items_read", """{"select":"get","itemId":"$root"}""", "Director root zephyrquux"),
                    Triple("items_read", """{"select":"list"}""", "Director child"),
                    Triple("items_read", """{"select":"overview"}""", "Director root zephyrquux"),
                    Triple("items_read", """{"select":"blocked"}""", "Director child"),
                    Triple("deps_read", """{"select":"list","item":"$root"}""", child.toString()),
                    Triple("deps_read", """{"select":"graph","item":"$root"}""", child.toString()),
                )
            for ((tool, body, needle) in members) {
                val viaDirector = client.send("/api/v1/ops/director/$tool", body)
                assertEquals(200, viaDirector.status.value, "$tool $body: ${viaDirector.bodyAsText()}")
                val viaOps = client.send("/api/v1/ops/$tool", body)
                assertEquals(200, viaOps.status.value, "$tool $body (ops): ${viaOps.bodyAsText()}")
                val directorText = normalized(viaDirector.bodyAsText())
                assertTrue(needle in directorText, "$tool $body must carry the seeded data '$needle': $directorText")
                assertEquals(normalized(viaOps.bodyAsText()), directorText, "$tool $body: byte-equal modulo meta.reqId")
            }
        }

    @Test
    fun `S10 report usage answers on the director path with the same envelope as ops`() =
        withApp(ToolRegistry(buildToolSpecs())) { _ ->
            // A fixed past window keeps `until` deterministic and the aggregate empty for any call-log state.
            val body = """{"select":"usage","since":"2020-01-01T00:00:00Z","until":"2020-01-02T00:00:00Z"}"""
            val viaDirector = client.send("/api/v1/ops/director/report", body)
            assertEquals(200, viaDirector.status.value, viaDirector.bodyAsText())
            val viaOps = client.send("/api/v1/ops/report", body)
            assertEquals(200, viaOps.status.value, viaOps.bodyAsText())
            val directorData = viaDirector.envelope().getValue("data").jsonObject
            val opsData = viaOps.envelope().getValue("data").jsonObject
            assertEquals(0, directorData.getValue("items").jsonArray.size, "no call falls in the 2020 window")
            assertEquals(opsData.getValue("items"), directorData.getValue("items"))
            assertEquals(opsData.getValue("page"), directorData.getValue("page"))
            val window = directorData.getValue("window").jsonObject
            assertTrue("2020-01-01T00:00:00" in window.getValue("since").jsonPrimitive.content, "since echoed: $window")
            assertTrue("2020-01-02T00:00:00" in window.getValue("until").jsonPrimitive.content, "until echoed: $window")
            assertEquals("raw", window.getValue("basis").jsonPrimitive.content)
            assertEquals(opsData.getValue("window").jsonObject.getValue("since"), window.getValue("since"))
            assertEquals(opsData.getValue("window").jsonObject.getValue("until"), window.getValue("until"))
        }

    @Test
    fun `S10 the other selects of items_read are invalid_request on the director path and served on ops`() =
        withApp(ToolRegistry(buildToolSpecs())) { rig ->
            val (root, _) = seedTree(rig)
            val hidden =
                listOf(
                    """{"select":"search","query":"zephyrquux"}""",
                    """{"select":"context","itemId":"$root"}""",
                    """{"select":"related","itemId":"$root"}""",
                    """{"select":"next","limit":5}""",
                    """{"select":"schema","type":"no-such-type"}""",
                    """{"select":"history"}""",
                )
            for (body in hidden) {
                val refused = client.send("/api/v1/ops/director/items_read", body, token = "raw-admin")
                assertEquals(400, refused.status.value, "$body: ${refused.bodyAsText()}")
                assertEquals("invalid_request", refused.envelope().code(), body)
                val control = client.send("/api/v1/ops/items_read", body, token = "raw-admin")
                assertEquals(200, control.status.value, "control $body: ${control.bodyAsText()}")
            }
        }

    @Test
    fun `S10 every registered tool other than items_read, deps_read and report is forbidden director on the director path`() {
        val registry = ToolRegistry(buildToolSpecs())
        val outside = registry.tools.map { it.name }.filter { it != "items_read" && it != "deps_read" && it != "report" }
        assertTrue(outside.isNotEmpty(), "fixture: the production registry has tools outside the director set")
        assertTrue(
            "notes_read" in outside && "docs_write" in outside && "config" in outside,
            "fixture: the sensitive tools are in the list"
        )
        withApp(registry) { rig ->
            val before = rig.rows().size
            for (tool in outside) {
                val response = client.send("/api/v1/ops/director/$tool", "{}", token = "raw-admin")
                assertEquals(403, response.status.value, "$tool: ${response.bodyAsText()}")
                assertDirectorRefusal(response.envelope(), tool)
            }
            assertEquals(before, rig.rows().size, "no refused call wrote an event")

            // control: a director-set tool on the same path, same credential, is served
            assertEquals(200, client.send("/api/v1/ops/director/items_read", """{"select":"overview"}""", token = "raw-admin").status.value)
            // the report tool is in the director set now: an empty body reaches the tool, which refuses the missing select
            val report = client.send("/api/v1/ops/director/report", "{}", token = "raw-admin")
            assertEquals(400, report.status.value, report.bodyAsText())
            assertEquals("invalid_request", report.envelope().code())
        }
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.api.configName
import io.github.jpicklyk.mcptask.current.application.api.grants
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksResult
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.findByFilters
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.opsRoutes
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.auditVerification
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.toCallPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.DocsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Security
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item b9527ef9) for the capability split (`write-docs` versus `write-config`, plus the
 * parseable `runs` and `decide`), the bearer pipeline's header contract, the `profile: director` credential guard on the REST
 * routes and the audited REST actor under every proof status: scenarios S8, S9, S11, S12 and S14 (capability part) of the frozen
 * test-plan.
 *
 * Oracles (never read from a run): the R10 plan paragraph and task-scope items 5 to 8 and AC3 ("`write-config` grants no
 * `docs_write` op; `write-docs` grants all six and nothing else"; `config` ops stay `write-config`; a `profile: director`
 * credential is forbidden everywhere except `/api/v1/ops/director/{tool}`), `api-rest.md` section 2 (capabilities are additive and
 * `admin` implies all of them) and section 1 (failure table: missing header 401 `invalid_request`, bad token 401
 * `invalid_token`, missing capability 403 `forbidden` with `detail {scope: "capability", required}`), the envelope spec
 * (per-element refusal in a non-atomic batch is 207 `partial_failure` with the element's own `forbidden` error; forbidden wins
 * over validation), `api-rest.md` section 22 (a REST write is attributed to `api:<tokenId>`, kind external, no parent, no proof;
 * a request-body `actor` is ignored) and task-scope item 8 (verification UNCHECKED for ISSUED and NONE, VERIFIED for VERIFIED and
 * VERIFIED_STALE). Expected values are literals.
 *
 * Negative-assertion conditions: every "not forbidden" or "not stored" assertion is paired with the same call shape under a
 * token that differs only in the capability (or in the profile) and IS refused or IS stored, so a guard that refused or allowed
 * everything fails one of the pair; "no write" assertions are checked in the database, not only in the response.
 *
 * Harness: the production REST topology ([installParityRestApp]: real composition, ContentNegotiation, `installRestApiRoutes`)
 * for bearer tokens; for JWT and unauthenticated credentials the plugin is installed directly together with `opsRoutes` (the
 * production topology helper does not take a JWKS verifier).
 *
 * Domain invariants satisfied by construction: documents and items are created through the composition's own services and
 * repositories (a child gets its parent's root), token-file fixtures come from one generator (64-hex digest, `scope` mapping,
 * unique ids, recognized keys only), JWTs carry the configured issuer and audience and a whole-second `exp`.
 */
class CapabilitySplitAndProfileGuardTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey = RSAKeyGenerator(2048).keyID("capability-split-key").generate()
        private const val ISSUER = "https://idp.test.example"
        private const val AUDIENCE = "task-orchestrator-api"
    }

    private val fx = FixtureTools()
    private val registry: ToolRegistry by lazy { ToolRegistry(buildToolSpecs() + fx.registry.tools) }
    private var fileCounter = 0

    // ------------------------------------------------------------------ fixtures

    private fun sha256(input: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(input: String): String = sha256(input).joinToString("") { "%02x".format(it) }

    private fun yamlEntry(
        id: String,
        raw: String,
        capabilities: String,
        vararg extra: String,
        expiresAt: String? = null,
    ): String {
        val lines =
            mutableListOf(
                "  - id: $id",
                "    token_sha256: \"${sha256Hex(raw)}\"",
                "    scope:",
                "      root_ids: null",
                "      tags_include: []",
                "    capabilities: $capabilities",
            )
        if (expiresAt != null) lines += "    expires_at: \"$expiresAt\""
        extra.forEach { lines += "    $it" }
        return lines.joinToString("\n")
    }

    private fun loadYaml(vararg entries: String): ApiAuthConfig.Bearer {
        val file = dir.resolve("split-tokens-${fileCounter++}.yaml").toFile()
        file.writeText("version: 1\ntokens:\n" + entries.joinToString("\n") + "\n")
        return ApiAuthConfig.Bearer(tokens = BearerTokenStore(file.absolutePath).loadWithEntries())
    }

    /** A token holding exactly [capabilities], built directly (the loader path is covered by the resolver tests). */
    private fun capabilityToken(
        raw: String,
        id: String,
        capabilities: Set<ApiCapability>,
    ): Pair<HashBytes, BearerTokenStore.TokenEntry> =
        HashBytes(sha256(raw)) to
            BearerTokenStore.TokenEntry(
                Principal(id, PrincipalKind.SERVICE, ProofStatus.ISSUED, AccessScope.unrestricted(), capabilities),
                expiresAt = null,
            )

    private fun withApp(
        auth: ApiAuthConfig.Bearer,
        block: suspend ApplicationTestBuilder.(EventLogRig) -> Unit,
    ) = testApplication {
        val rig = EventLogRig.build(db.db, dir)
        application { installParityRestApp(rig, auth, registry) }
        block(rig)
    }

    private suspend fun HttpClient.send(
        path: String,
        body: String,
        token: String?,
        extra: List<Pair<String, String>> = emptyList(),
    ): HttpResponse =
        post(path) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            extra.forEach { (k, v) -> header(k, v) }
            setBody(body)
        }

    private suspend fun HttpResponse.envelope(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /**
     * The `required` capability when the call (the first element of a batch, else the whole call) was refused as a capability
     * `forbidden`; null when it was not refused that way.
     */
    private fun JsonObject.capabilityRefusal(): String? {
        val element =
            this["data"]
                ?.jsonObject
                ?.get("results")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("error")
                ?.jsonObject
        val error = element ?: this["error"]?.jsonObject ?: return null
        if (error["code"]?.jsonPrimitive?.content != "forbidden") return null
        val detail = error["detail"]?.jsonObject ?: return null
        if (detail["scope"]?.jsonPrimitive?.content != "capability") return null
        return detail["required"]?.jsonPrimitive?.content
    }

    // ------------------------------------------------------------------ S9 unit seam

    @Test
    fun `S9 the three new capabilities parse from their config spellings and print back`() {
        assertEquals(ApiCapability.WRITE_DOCS, ApiCapability.fromConfigString("write-docs"))
        assertEquals(ApiCapability.RUNS, ApiCapability.fromConfigString("runs"))
        assertEquals(ApiCapability.DECIDE, ApiCapability.fromConfigString("decide"))
        for ((capability, spelling) in listOf(
            ApiCapability.WRITE_DOCS to "write-docs",
            ApiCapability.RUNS to "runs",
            ApiCapability.DECIDE to "decide"
        )) {
            assertEquals(spelling, capability.configName())
            assertTrue(spelling in ApiCapability.validValues(), "validValues must list '$spelling': ${ApiCapability.validValues()}")
        }
    }

    @Test
    fun `S9 a capability grants only itself and admin grants every capability`() {
        for (held in ApiCapability.entries) {
            for (required in ApiCapability.entries) {
                val expected = held == required || held == ApiCapability.ADMIN
                assertEquals(expected, setOf(held).grants(required), "holding only $held, required $required")
            }
        }
        assertFalse(emptySet<ApiCapability>().grants(ApiCapability.READ), "the empty set grants nothing")
        assertFalse(setOf(ApiCapability.WRITE_CONFIG).grants(ApiCapability.WRITE_DOCS), "write-config no longer grants write-docs")
        assertFalse(setOf(ApiCapability.WRITE_DOCS).grants(ApiCapability.WRITE_CONFIG), "write-docs does not grant write-config")
        assertFalse(setOf(ApiCapability.WRITE_DOCS, ApiCapability.RUNS, ApiCapability.DECIDE).grants(ApiCapability.READ))
    }

    // ------------------------------------------------------------------ S12 unit seam (verification mapping)

    @Test
    fun `S12 the audit verification is VERIFIED for verified and verified_stale and UNCHECKED for issued and none`() {
        val expected =
            mapOf(
                ProofStatus.VERIFIED to VerificationStatus.VERIFIED,
                ProofStatus.VERIFIED_STALE to VerificationStatus.VERIFIED,
                ProofStatus.ISSUED to VerificationStatus.UNCHECKED,
                ProofStatus.NONE to VerificationStatus.UNCHECKED,
            )
        assertEquals(ProofStatus.entries.toSet(), expected.keys, "fixture: every proof status has an expectation")
        for ((proof, status) in expected) {
            val principal = Principal("audit-$proof", PrincipalKind.AGENT, proof, AccessScope.unrestricted(), setOf(ApiCapability.READ))
            assertEquals(status, principal.auditVerification().status, "proof $proof")
            assertEquals(status, principal.toCallPrincipal().verification.status, "proof $proof via toCallPrincipal")
            val auditId = if (proof == ProofStatus.NONE) "audit-$proof" else "api:audit-$proof"
            assertEquals(auditId, principal.toCallPrincipal().claim.id, "NONE audits as the bare id")
        }
    }

    // ------------------------------------------------------------------ S8 header contract over REST

    @Test
    fun `S8 a valid bearer runs the call as api colon id and a bad, missing or expired one is 401`() {
        val auth =
            loadYaml(
                yamlEntry("hdr-read", "raw-hdr-read", "[read]"),
                yamlEntry("hdr-expired", "raw-hdr-expired", "[read]", expiresAt = "2020-01-01T00:00:00Z"),
            )
        withApp(auth) { _ ->
            val body = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""

            val ok = client.send("/api/v1/ops/fx_read", body, "raw-hdr-read")
            assertEquals(200, ok.status.value, ok.bodyAsText())
            assertEquals("api:hdr-read", assertNotNull(fx.seen.single().principal).claim.id)

            val missing = client.send("/api/v1/ops/fx_read", body, token = null)
            assertEquals(401, missing.status.value)
            assertTrue(missing.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_request\""))

            val bad = client.send("/api/v1/ops/fx_read", body, "not-a-known-token")
            assertEquals(401, bad.status.value)
            assertTrue(bad.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_token\""))

            val expired = client.send("/api/v1/ops/fx_read", body, "raw-hdr-expired")
            assertEquals(401, expired.status.value)
            assertTrue(expired.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_token\""))

            assertEquals(1, fx.seen.size, "only the valid call reached the handler")
        }
    }

    // ------------------------------------------------------------------ S9 REST: docs_write needs write-docs

    private fun docsBodies(
        root: UUID,
        item: UUID,
    ): List<Pair<String, String>> =
        listOf(
            "upsert" to DocsParityScenarios.ops(DocsParityScenarios.upsert(root, "plan", "fresh-plan", "fresh body")),
            "archive" to DocsParityScenarios.ops(DocsParityScenarios.op("archive", root, "plan", "p1")),
            "unarchive" to DocsParityScenarios.ops(DocsParityScenarios.op("unarchive", root, "plan", "p1")),
            "attach" to DocsParityScenarios.ops(DocsParityScenarios.attach("attach", root, "plan", "p1", item)),
            "detach" to DocsParityScenarios.ops(DocsParityScenarios.attach("detach", root, "plan", "p1", item)),
            "delete" to DocsParityScenarios.ops(DocsParityScenarios.op("delete", root, "plan", "p1")),
        )

    @Test
    fun `S9 a write-config-only token is forbidden write-docs on all six docs_write ops and nothing is stored`() {
        val auth = ApiAuthConfig.Bearer(tokens = mapOf(capabilityToken("raw-cfg-only", "cfg-only", setOf(ApiCapability.WRITE_CONFIG))))
        withApp(auth) { rig ->
            val root = rig.seed("docs root")
            val child = rig.seed("docs child", parent = root)
            val seeded = rig.ctx.documentService.write(root.id, DocumentKind.PLAN, "p1", "seed body")
            assertTrue(seeded is Outcome.Ok, "fixture: the seed document is written")

            for ((op, body) in docsBodies(root.id, child.id)) {
                val response = client.send("/api/v1/ops/docs_write", body, "raw-cfg-only")
                assertEquals(207, response.status.value, "$op: ${response.bodyAsText()}")
                assertEquals("write-docs", response.envelope().capabilityRefusal(), "$op must require write-docs")
            }
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "fresh-plan"), "the refused upsert stored nothing")
            assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "p1"), "the refused delete removed nothing")
        }
    }

    @Test
    fun `S9 a write-docs-only token is not forbidden on any of the six docs_write ops and the upsert is stored`() {
        val auth = ApiAuthConfig.Bearer(tokens = mapOf(capabilityToken("raw-docs-only", "docs-only", setOf(ApiCapability.WRITE_DOCS))))
        withApp(auth) { rig ->
            val root = rig.seed("docs root")
            val child = rig.seed("docs child", parent = root)
            val seeded = rig.ctx.documentService.write(root.id, DocumentKind.PLAN, "p1", "seed body")
            assertTrue(seeded is Outcome.Ok, "fixture: the seed document is written")

            // order matters for the state machine: archive, unarchive, attach, detach, delete on p1, then the fresh upsert
            val order = listOf("archive", "unarchive", "attach", "detach", "delete", "upsert")
            val bodies = docsBodies(root.id, child.id).toMap()
            for (op in order) {
                val response = client.send("/api/v1/ops/docs_write", bodies.getValue(op), "raw-docs-only")
                assertNull(
                    response.envelope().capabilityRefusal(),
                    "$op must not be refused for a write-docs token: ${response.bodyAsText()}"
                )
            }
            val stored = rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "fresh-plan")
            assertNotNull(stored, "the upsert ran and stored the document")
            assertEquals("fresh body", stored.body)
        }
    }

    @Test
    fun `S9 write-docs does not open the other write tools and write-config still opens config`() {
        val auth =
            ApiAuthConfig.Bearer(
                tokens =
                    mapOf(
                        capabilityToken("raw-docs", "docs-only", setOf(ApiCapability.WRITE_DOCS)),
                        capabilityToken("raw-cfg", "cfg-only", setOf(ApiCapability.WRITE_CONFIG)),
                    ),
            )
        withApp(auth) { rig ->
            val root = rig.seed("cfg root")
            val configPush =
                """{"ops":[{"op":"push","rootId":"${root.id}","configYaml":${JsonPrimitive(EventLogRig.GATED_CONFIG)}}]}"""

            val refused = client.send("/api/v1/ops/config", configPush, "raw-docs")
            assertEquals("write-config", refused.envelope().capabilityRefusal(), "write-docs must not open config push")

            val allowed = client.send("/api/v1/ops/config", configPush, "raw-cfg")
            assertEquals(200, allowed.status.value, allowed.bodyAsText())

            val itemWrite = client.send("/api/v1/ops/items_write", """{"ops":[{"op":"create","title":"nope"}]}""", "raw-docs")
            assertEquals("write-items", itemWrite.envelope().capabilityRefusal())

            val noteWrite =
                client.send(
                    "/api/v1/ops/notes_write",
                    """{"ops":[{"op":"upsert","itemId":"${root.id}","key":"k","role":"work","body":"x"}]}""",
                    "raw-docs",
                )
            assertEquals("write-notes", noteWrite.envelope().capabilityRefusal())
            assertEquals(emptyList(), rig.raw.noteRepository().findByItemId(root.id), "nothing was written by the refused note op")
        }
    }

    @Test
    fun `S9 probe every other non-admin capability, held alone, is refused on docs_write while admin runs it`() {
        val tokens = mutableMapOf<HashBytes, BearerTokenStore.TokenEntry>()
        val others = ApiCapability.entries.filter { it != ApiCapability.WRITE_DOCS && it != ApiCapability.ADMIN }
        for (capability in others) {
            val (hash, entry) = capabilityToken("raw-only-${capability.name}", "only-${capability.name.lowercase()}", setOf(capability))
            tokens[hash] = entry
        }
        val (adminHash, adminEntry) = capabilityToken("raw-admin", "admin-docs", setOf(ApiCapability.ADMIN))
        tokens[adminHash] = adminEntry
        withApp(ApiAuthConfig.Bearer(tokens = tokens)) { rig ->
            val root = rig.seed("docs root")
            val body = DocsParityScenarios.ops(DocsParityScenarios.upsert(root.id, "plan", "by-who", "text"))
            for (capability in others) {
                val response = client.send("/api/v1/ops/docs_write", body, "raw-only-${capability.name}")
                assertEquals("write-docs", response.envelope().capabilityRefusal(), "holding only $capability")
            }
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "by-who"), "no refused token stored the document")

            val admin = client.send("/api/v1/ops/docs_write", body, "raw-admin")
            assertEquals(200, admin.status.value, admin.bodyAsText())
            assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "by-who"), "control: admin stores the same body")
        }
    }

    // ------------------------------------------------------------------ S11 director-profile credential on REST

    private fun profileAuth(): ApiAuthConfig.Bearer =
        loadYaml(
            yamlEntry("pg-dir", "raw-pg-dir", "[read]", "profile: director"),
            yamlEntry("pg-dir-admin", "raw-pg-dir-admin", "[admin]", "profile: director"),
            yamlEntry("pg-plain", "raw-pg-plain", "[read]"),
            yamlEntry("pg-admin", "raw-pg-admin", "[admin]"),
        )

    @Test
    fun `S11 a director-profile credential may call the director path and is forbidden director on every other ops path`() =
        withApp(profileAuth()) { rig ->
            val root = rig.seed("director root")

            val served = client.send("/api/v1/ops/director/items_read", """{"select":"overview"}""", "raw-pg-dir")
            assertEquals(200, served.status.value, served.bodyAsText())

            val readBody = """{"select":"get","itemId":"${root.id}"}"""
            for (path in listOf("/api/v1/ops/items_read", "/api/v1/ops/notes_read", "/api/v1/ops/docs_write", "/api/v1/ops/config")) {
                val response = client.send(path, readBody, "raw-pg-dir")
                assertEquals(403, response.status.value, "$path: ${response.bodyAsText()}")
                assertEquals("director", response.envelope().capabilityRefusal(), "$path must be forbidden with required director")
            }

            // control: a credential without the profile reaches the same non-director path
            val control = client.send("/api/v1/ops/items_read", readBody, "raw-pg-plain")
            assertEquals(200, control.status.value, control.bodyAsText())
        }

    @Test
    fun `S11 a director-profile credential is forbidden director on the item resource route`() =
        withApp(profileAuth()) { rig ->
            val root = rig.seed("director root")

            val refused = client.get("/api/v1/items/${root.id}") { header(HttpHeaders.Authorization, "Bearer raw-pg-dir") }
            assertEquals(403, refused.status.value, refused.bodyAsText())
            assertEquals("director", refused.envelope().capabilityRefusal())

            val control = client.get("/api/v1/items/${root.id}") { header(HttpHeaders.Authorization, "Bearer raw-pg-plain") }
            assertEquals(200, control.status.value, control.bodyAsText())
        }

    @Test
    fun `S11 probe admin with the director profile is still forbidden and nothing is written`() =
        withApp(profileAuth()) { rig ->
            val before =
                rig.raw
                    .workItemRepository()
                    .findByFilters()
                    .items.size

            val refused =
                client.send(
                    "/api/v1/ops/items_write",
                    """{"ops":[{"op":"create","title":"should not exist","type":"test-item"}]}""",
                    "raw-pg-dir-admin"
                )
            assertEquals(403, refused.status.value, refused.bodyAsText())
            assertEquals("director", refused.envelope().capabilityRefusal())
            assertEquals(
                before,
                rig.raw
                    .workItemRepository()
                    .findByFilters()
                    .items.size,
                "the refused create wrote nothing"
            )

            val control =
                client.send(
                    "/api/v1/ops/items_write",
                    """{"ops":[{"op":"create","title":"should exist","type":"test-item"}]}""",
                    "raw-pg-admin"
                )
            assertEquals(200, control.status.value, control.bodyAsText())
            assertEquals(
                before + 1,
                rig.raw
                    .workItemRepository()
                    .findByFilters()
                    .items.size,
                "control: the admin credential created the item"
            )
        }

    @Test
    fun `S11 probe encoded and dot-segment spellings never turn the director path into another path`() =
        withApp(profileAuth()) { rig ->
            val root = rig.seed("director root")
            val body = """{"select":"get","itemId":"${root.id}"}"""
            val spellings =
                listOf(
                    "/api/v1/ops/%64irector/notes_read",
                    "/api/v1/ops/director/..%2Fnotes_read",
                    "/api/v1/ops/director%2Fnotes_read",
                    "/api/v1/ops/Director/items_read",
                    "/api/v1/ops/director",
                )
            for (path in spellings) {
                val response = client.send(path, body, "raw-pg-dir")
                assertFalse(
                    response.status.isSuccess(),
                    "$path must not be served to a director credential: ${response.status} ${response.bodyAsText()}"
                )
                assertFalse("\"ok\":true" in response.bodyAsText().replace(" ", ""), "$path leaked an ok body")
            }
            // control: the exact director spelling is served
            assertTrue(client.send("/api/v1/ops/director/items_read", """{"select":"overview"}""", "raw-pg-dir").status.isSuccess())
        }

    @Test
    fun `S11 an unauthenticated call to the director path is 401 and a plain read credential is served`() =
        withApp(profileAuth()) { _ ->
            assertEquals(401, client.send("/api/v1/ops/director/items_read", """{"select":"overview"}""", token = null).status.value)
            assertEquals(200, client.send("/api/v1/ops/director/items_read", """{"select":"overview"}""", "raw-pg-plain").status.value)
        }

    // ------------------------------------------------------------------ S12 audited actor under every proof status

    private fun provider(stale: Boolean): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        val cache = CacheState(fromStaleCache = stale, ageSeconds = if (stale) 100L else null)
        val result = JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), cache)
        coEvery { provider.getKeySet() } returns result
        coEvery { provider.getKeySetForIssuer(any()) } returns result
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun jwksConfig() =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = ISSUER,
            audience = AUDIENCE,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    private fun jwt(
        subject: String,
        claims: Map<String, Any> = emptyMap(),
    ): String {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(Date.from(Instant.ofEpochSecond(Instant.now().epochSecond + 300)))
        claims.forEach { (k, v) -> builder.claim(k, v) }
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("capability-split-key").build(), builder.build())
        signed.sign(RSASSASigner(rsaKey))
        return signed.serialize()
    }

    /** `opsRoutes` behind [ApiBearerAuth] configured directly, so a JWKS verifier or the unauthenticated mode can be wired. */
    private fun Application.ownApp(
        rig: EventLogRig,
        authCfg: ApiAuthConfig,
        entries: Map<HashBytes, BearerTokenStore.TokenEntry> = emptyMap(),
        verifier: JwksApiVerifier? = null,
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            route("/api/v1") {
                install(ApiBearerAuth) {
                    authConfig = authCfg
                    tokenEntries = entries
                    jwksVerifier = verifier
                }
                opsRoutes(registry, buildToolDispatcher(rig.ctx))
            }
        }
    }

    private fun noteBody(itemId: UUID): String = """{"ops":[{"op":"upsert","itemId":"$itemId","key":"k1","role":"work","body":"hello"}]}"""

    private suspend fun assertStoredAs(
        rig: EventLogRig,
        itemId: UUID,
        expectedActorId: String,
        expectedVerification: VerificationStatus,
        label: String,
    ) {
        val note = assertNotNull(rig.raw.noteRepository().findByItemIdAndKey(itemId, "k1"), "$label: the note was stored")
        val claim = assertNotNull(note.actorClaim, "$label: the stored note carries an actor")
        assertEquals(expectedActorId, claim.id, "$label: actor id")
        assertEquals(ActorKind.EXTERNAL, claim.kind, "$label: every transport principal is external")
        assertNull(claim.parent, "$label: no parent")
        assertNull(claim.proof, "$label: no proof is echoed")
        assertEquals(expectedVerification, assertNotNull(note.verification, "$label: verification").status, "$label: verification status")
    }

    @Test
    fun `S12 an issued human token stores the note as api colon id, external, unchecked`() {
        val auth = loadYaml(yamlEntry("note-human", "raw-note-human", "[write-notes]", "kind: human"))
        withApp(auth) { rig ->
            val item = rig.seed("owner")
            val response = client.send("/api/v1/ops/notes_write", noteBody(item.id), "raw-note-human")
            assertEquals(200, response.status.value, response.bodyAsText())
            assertStoredAs(rig, item.id, "api:note-human", VerificationStatus.UNCHECKED, "issued/human")
        }
    }

    @Test
    fun `S12 a verified JWT stores the note as api colon sub, external, verified, whatever to_kind it carries`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val verifier = JwksApiVerifier(jwksConfig(), provider(stale = false))
            application { ownApp(rig, jwksConfig(), verifier = verifier) }
            val item = rig.seed("owner")
            val token = jwt("jwt-writer", mapOf("to_capabilities" to listOf("write-notes"), "to_kind" to "human"))

            val response = client.send("/api/v1/ops/notes_write", noteBody(item.id), token)
            assertEquals(200, response.status.value, response.bodyAsText())
            assertStoredAs(rig, item.id, "api:jwt-writer", VerificationStatus.VERIFIED, "verified/human")
        }

    @Test
    fun `S12 a JWT checked against a stale key set is still stored as verified`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val verifier = JwksApiVerifier(jwksConfig(), provider(stale = true))
            application { ownApp(rig, jwksConfig(), verifier = verifier) }
            val item = rig.seed("owner")
            val token = jwt("jwt-stale-writer", mapOf("to_capabilities" to listOf("write-notes")))

            val response = client.send("/api/v1/ops/notes_write", noteBody(item.id), token)
            assertEquals(200, response.status.value, response.bodyAsText())
            assertStoredAs(rig, item.id, "api:jwt-stale-writer", VerificationStatus.VERIFIED, "verified_stale")
        }

    @Test
    fun `S12 the unauthenticated mode stores the note as local, external, unchecked`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            application { ownApp(rig, ApiAuthConfig.Unauthenticated) }
            val item = rig.seed("owner")

            val response = client.send("/api/v1/ops/notes_write", noteBody(item.id), token = null)
            assertEquals(200, response.status.value, response.bodyAsText())
            assertStoredAs(rig, item.id, "local", VerificationStatus.UNCHECKED, "none")
        }

    // ------------------------------------------------------------------ S6 route level

    @Test
    fun `S6 a JWT with an unknown to_kind is 401 invalid_token at the REST plugin and a valid one is served`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val verifier = JwksApiVerifier(jwksConfig(), provider(stale = false))
            application { ownApp(rig, jwksConfig(), verifier = verifier) }
            val body = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""

            val robot = client.send("/api/v1/ops/fx_read", body, jwt("jwt-robot", mapOf("to_kind" to "robot")))
            assertEquals(401, robot.status.value)
            assertTrue(robot.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_token\""))
            assertTrue(fx.seen.isEmpty(), "the rejected JWT never reached the handler")

            val ok = client.send("/api/v1/ops/fx_read", body, jwt("jwt-agent", mapOf("to_kind" to "agent")))
            assertEquals(200, ok.status.value, ok.bodyAsText())
            assertEquals("api:jwt-agent", assertNotNull(fx.seen.single().principal).claim.id)
        }

    @Test
    fun `S14 a token with an empty capability list is forbidden read on a select while a read token is served`() {
        val auth =
            loadYaml(
                yamlEntry("empty-caps", "raw-empty-caps", "[]"),
                yamlEntry("some-caps", "raw-some-caps", "[read]"),
            )
        withApp(auth) { _ ->
            val body = """{"select":"get","id":"${FixtureTools.GET_OK}"}"""
            val refused = client.send("/api/v1/ops/fx_read", body, "raw-empty-caps")
            assertEquals(403, refused.status.value, refused.bodyAsText())
            assertEquals("read", refused.envelope().capabilityRefusal(), "an empty set grants nothing, not everything")
            assertTrue(fx.seen.isEmpty())

            assertEquals(200, client.send("/api/v1/ops/fx_read", body, "raw-some-caps").status.value)
        }
    }
}

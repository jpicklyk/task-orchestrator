package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.configYaml
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for `PUT /api/v1/roots/{rootId}/config` as a strict
 * admission point: scenarios S2 (extensions stored), S3 (plugin key -> 422 `parse_error` with the path), S4 (one 422 per
 * former soft skip), S5/S6/S7 (scope and server-layer rules).
 *
 * Harness: the production wiring is `ConfigService(..., ConfigValidator(NetworkntConfigStructuralValidator(),
 * GlobalConfigSource { ... }))`. [configureProjectConfigTestApp] builds the service with the DEFAULT validator (no
 * structural pass, empty server layer), so this file builds its own application with the production validator and an
 * injectable server layer. The 422 body shape (`parse_error`, `"path":"..."` entries in `details.errors`) follows the
 * frozen error table and the existing assertions in [ProjectConfigPutRouteTest].
 */
class ProjectConfigRoutesStrictTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun Application.strictApp(
        repo: DefaultRepositoryProvider,
        unitOfWork: UnitOfWork,
        server: ConfigLayer? = null
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            route("/api/v1") {
                val authConfig = makeWriteAuthConfig()
                install(ApiBearerAuth) {
                    this.authConfig = authConfig
                    tokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
                }
                projectConfigRoutes(
                    repo,
                    ConfigService(
                        repo,
                        YamlConfigDocumentParser,
                        unitOfWork,
                        ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { server })
                    )
                )
            }
        }
    }

    private fun serverLayer(yaml: String): ConfigLayer {
        val parsed = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
        check(parsed is ConfigDocumentParser.Outcome.Parsed) { "server fixture must parse: $parsed" }
        return ConfigLayer(parsed.document, "server-fp", ConfigSource.GLOBAL)
    }

    private fun createRoot(
        repo: DefaultRepositoryProvider,
        title: String = "Project Root"
    ): WorkItem = runBlocking { repo.workItemRepository().create(WorkItem(title = title, type = "project", depth = 0)) }

    private suspend fun HttpClient.putConfig(
        rootId: UUID,
        yaml: String
    ): HttpResponse =
        put("/api/v1/roots/$rootId/config") {
            header("Authorization", "Bearer $WRITE_TOKEN")
            contentType(ContentType.Text.Plain)
            setBody(yaml)
        }

    private fun String.hasPath(
        pointer: String,
        within: Boolean
    ): Boolean = contains("\"path\":\"$pointer\"") || (within && contains("\"path\":\"$pointer/"))

    // ---------------------------------------------------------------------------------------------
    // S2 - extensions are stored byte-equal
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 PUT with extensions returns 200, stores the document byte-equal and GET serves it`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { strictApp(repo, db.unitOfWork()) }
            val yaml = "extensions:\n  claude-code:\n    orchestration:\n      mode: workflow\n" + StrictConfigCases.VALID_ROOT_YAML

            val put = client.putConfig(root.id, yaml)
            val get =
                client.get("/api/v1/roots/${root.id}/config") { header("Authorization", "Bearer $WRITE_TOKEN") }

            assertEquals(HttpStatusCode.OK, put.status, "extensions must be admitted: ${put.bodyAsText()}")
            assertEquals(yaml, runBlocking { repo.configStore().get(root.id) }?.configYaml, "stored byte-equal")
            assertEquals(HttpStatusCode.OK, get.status)
            assertTrue(get.bodyAsText().contains("orchestration"), "GET serves the stored document including the extension content")
        }

    // ---------------------------------------------------------------------------------------------
    // S3 - plugin key at the top level
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 PUT with a top-level orchestration key returns 422 parse_error naming the path and leaves history unchanged`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { strictApp(repo, db.unitOfWork()) }
            assertEquals(HttpStatusCode.OK, client.putConfig(root.id, StrictConfigCases.VALID_ROOT_YAML).status)
            val before = runBlocking { repo.configStore().history(ConfigScope.ROOT, root.id, 100) }

            val response = client.putConfig(root.id, "orchestration:\n  mode: workflow\n" + StrictConfigCases.VALID_ROOT_YAML)

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("parse_error"), "the code is parse_error: $body")
            assertTrue(body.hasPath("/orchestration", within = false), "details.errors names /orchestration: $body")
            assertEquals(before, runBlocking { repo.configStore().history(ConfigScope.ROOT, root.id, 100) }, "history unchanged")
        }

    // ---------------------------------------------------------------------------------------------
    // S4 - every former soft skip is a 422 at its pointer
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 each defect is a 422 parse_error at its pointer with nothing stored, and the repaired twin is a 200`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            application { strictApp(repo, db.unitOfWork()) }

            val failures = mutableListOf<String>()
            for (case in StrictConfigCases.ROOT_CASES) {
                val root = createRoot(repo, "Root ${case.name}")
                val broken = client.putConfig(root.id, case.yaml)
                val body = broken.bodyAsText()
                if (broken.status != HttpStatusCode.UnprocessableEntity) failures += "${case.name}: status ${broken.status}"
                if (!body.contains("parse_error")) failures += "${case.name}: no parse_error in $body"
                if (!body.hasPath(case.errorPath, case.withinEntry)) failures += "${case.name}: no path ${case.errorPath} in $body"
                if (runBlocking { repo.configStore().get(root.id) } != null) failures += "${case.name}: a rejected push stored a version"

                val fixed = client.putConfig(root.id, case.fixedYaml)
                if (fixed.status !=
                    HttpStatusCode.OK
                ) {
                    failures += "${case.name}: repaired twin status ${fixed.status}: ${fixed.bodyAsText()}"
                }
            }

            assertTrue(failures.isEmpty(), "failures:\n" + failures.joinToString("\n"))
        }

    // ---------------------------------------------------------------------------------------------
    // S5 / S6 / S7
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 PUT carrying resources or actor_authentication is a 422 at those keys`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { strictApp(repo, db.unitOfWork()) }

            val response =
                client.putConfig(
                    root.id,
                    "resources:\n  db-cred:\n    description: \"x\"\nactor_authentication:\n  mode: jwks\n" +
                        StrictConfigCases.VALID_ROOT_YAML
                )

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertTrue(body.hasPath("/resources", within = false), "body: $body")
            assertTrue(body.hasPath("/actor_authentication", within = false), "body: $body")
            assertNull(runBlocking { repo.configStore().get(root.id) })
        }

    @Test
    fun `S6 default_traits resolves against the server layer over REST`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val admittedRoot = createRoot(repo, "admitted")
            val schema = "work_item_schemas:\n  x:\n    default_traits: [ghost]\n    notes: []\n"
            val serverWithGhost = serverLayer("traits:\n  ghost:\n    notes: []\n")
            application { strictApp(repo, db.unitOfWork(), serverWithGhost) }

            val admitted = client.putConfig(admittedRoot.id, schema)

            assertEquals(HttpStatusCode.OK, admitted.status, "a trait defined by the server layer resolves: ${admitted.bodyAsText()}")
            assertNotNull(runBlocking { repo.configStore().get(admittedRoot.id) }, "the admitted document is stored")
        }

    @Test
    fun `S6 default_traits naming an unknown trait is a 422 at the list index over REST`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { strictApp(repo, db.unitOfWork()) }

            val response = client.putConfig(root.id, "work_item_schemas:\n  x:\n    default_traits: [ghost]\n    notes: []\n")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(
                response.bodyAsText().hasPath("/work_item_schemas/x/default_traits/0", within = false),
                "body: ${response.bodyAsText()}"
            )
            assertNull(runBlocking { repo.configStore().get(root.id) })
        }

    @Test
    fun `S7 a trait resource key is accepted only when the server registry declares it over REST`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val declaredRoot = createRoot(repo, "declared")
            val yaml = "traits:\n  needs-db:\n    resources: [db-cred]\n"
            val registry = serverLayer("resources:\n  db-cred:\n    description: \"x\"\n")
            application { strictApp(repo, db.unitOfWork(), registry) }

            val declared = client.putConfig(declaredRoot.id, yaml)

            assertEquals(HttpStatusCode.OK, declared.status, "declared key: ${declared.bodyAsText()}")
            assertNotNull(runBlocking { repo.configStore().get(declaredRoot.id) }, "the admitted document is stored")
        }

    @Test
    fun `S7 a trait resource key the server registry does not declare is a 422 over REST`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { strictApp(repo, db.unitOfWork()) }

            val response = client.putConfig(root.id, "traits:\n  needs-db:\n    resources: [db-cred]\n")

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(response.bodyAsText().hasPath("/traits/needs-db/resources/0", within = true), "body: ${response.bodyAsText()}")
            assertNull(runBlocking { repo.configStore().get(root.id) })
        }

    // ---------------------------------------------------------------------------------------------
    // S8 - advisory findings do not reject
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 PUT with only an advisory finding is a 200 carrying schemaWarnings and no ignoredSections`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { strictApp(repo, db.unitOfWork()) }
            val yaml =
                "work_item_schemas:\n  feature-task:\n    seats:\n      - { name: impl, phase: work, enters: true }\n" +
                    "    notes:\n      - key: spec\n        role: work\n        required: true\n"

            val response = client.putConfig(root.id, yaml)

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("schemaWarnings"), "advisory findings surface as schemaWarnings: $body")
            assertFalse(body.contains("ignoredSections"), "ignoredSections is gone: $body")
        }
}

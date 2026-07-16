package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigTool
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the REST `PUT/GET/DELETE /api/v1/roots/{rootId}/config` routes registered by
 * [projectConfigRoutes]. Reuses [WRITE_TOKEN] (granted [io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability.WRITE_CONFIG]
 * — see [ApiTestHelper.makeWriteAuthConfig]) and [READ_ONLY_TOKEN] from [WriteRoutesTest] for the
 * capability-denial cases.
 */
private const val PROJECT_CONFIG_READ_ONLY_TOKEN = TEST_TOKEN

private val VALID_YAML =
    """
    work_item_schemas:
      feature-task:
        notes:
          - key: spec
            role: queue
            required: true
    """.trimIndent()

/**
 * Configures a test Ktor application with only [projectConfigRoutes] registered.
 *
 * [authConfig] accepts any [ApiAuthConfig] variant — e.g. [ApiAuthConfig.Unauthenticated] for
 * opt-in unauth-mode tests (see [ProjectConfigUnauthenticatedModeTest]). Token entries are only
 * populated when it is [ApiAuthConfig.Bearer].
 */
fun Application.configureProjectConfigTestApp(
    repo: DefaultRepositoryProvider,
    authConfig: ApiAuthConfig = makeWriteAuthConfig(),
) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    routing {
        route("/api/v1") {
            install(ApiBearerAuth) {
                this.authConfig = authConfig
                tokenEntries =
                    (authConfig as? ApiAuthConfig.Bearer)?.tokens?.mapValues { (_, p) ->
                        BearerTokenStore.TokenEntry(p, expiresAt = null)
                    } ?: emptyMap()
            }
            projectConfigRoutes(repo)
        }
    }
}

private fun createRoot(
    repo: DefaultRepositoryProvider,
    title: String = "Project Root",
    type: String? = "project",
): WorkItem =
    runBlocking {
        (repo.workItemRepository().create(WorkItem(title = title, type = type, depth = 0)) as Result.Success).data
    }

class ProjectConfigPutRouteTest {
    @Test
    fun `PUT roots rootId config happy path returns 200 with ETag and persists the row`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.parse("application/yaml"))
                    setBody(VALID_YAML)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(root.id.toString()), "Response should echo rootItemId: $body")
            val etag = response.headers[HttpHeaders.ETag]
            assertNotNull(etag, "ETag header should be present")
            assertTrue(etag.startsWith("\"cfg-"), "ETag should use the cfg- prefix: $etag")

            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue(persisted is Result.Success)
            val config = (persisted as Result.Success).data
            assertNotNull(config, "Config should be persisted")
            assertEquals(VALID_YAML, config!!.configYaml)
        }

    @Test
    fun `PUT roots rootId config surfaces ignoredSections when the doc has an unhonored top-level key`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.parse("application/yaml"))
                    setBody("$VALID_YAML\nactor_authentication:\n  mode: jwks\n")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("ignoredSections"), "Response should surface ignoredSections: $body")
            assertTrue(body.contains("actor_authentication"), "ignoredSections should name actor_authentication: $body")
        }

    @Test
    fun `PUT roots rootId config omits ignoredSections when the doc only uses honored keys`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.parse("application/yaml"))
                    setBody(VALID_YAML)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("ignoredSections"), "ignoredSections must be omitted entirely when empty: $body")
        }

    @Test
    fun `PUT roots rootId config without WRITE_CONFIG capability returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_YAML)
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "Config should NOT be created")
        }

    @Test
    fun `PUT roots rootId config with token scope lacking the root returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            val otherRoot = createRoot(repo, title = "Other Root")
            // WRITE_TOKEN scoped to a DIFFERENT root than the one we're pushing to.
            application { configureProjectConfigTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(otherRoot.id))) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_YAML)
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "Config should NOT be created outside token scope")
        }

    @Test
    fun `PUT roots rootId config with malformed YAML returns 422 and stores nothing`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("work_item_schemas: [this is not, a valid: map")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("parse_error") || body.contains("failed to parse"), "Should report a parse error: $body")

            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "Malformed YAML must never be stored")
        }

    @Test
    fun `PUT roots rootId config with a YAML type tag (CWE-502) is rejected with 422 and stores nothing`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            // A !!-tagged non-standard type — SafeConstructor must refuse to instantiate it (CWE-502).
            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("work_item_schemas: !!java.net.URL [\"http://evil.example\"]")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "A type-tagged payload must never be stored")
        }

    @Test
    fun `PUT roots rootId config over the size cap returns 413`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val oversized = "x".repeat(131_073) // MAX_CONFIG_YAML_BYTES (131072) + 1
            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(oversized)
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "Oversized payload must never be stored")
        }

    @Test
    fun `PUT roots rootId config with stale If-Match returns 412`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            // First push creates the row.
            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, "\"cfg-0000000000000000000000000000000000000000000000000000000000000000\"")
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_YAML + "\n")
                }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("etag_mismatch"), "Should report etag_mismatch: $body")
        }

    @Test
    fun `PUT roots rootId config to unknown root returns 404`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${UUID.randomUUID()}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_YAML)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PUT roots rootId config to a non-depth-0 root returns 422`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent = createRoot(repo, title = "Parent")
            val child =
                runBlocking {
                    (
                        repo.workItemRepository().create(
                            WorkItem(title = "Child", parentId = parent.id, depth = 1),
                        ) as Result.Success
                    ).data
                }
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${child.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_YAML)
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("depth-0"), "Should name the depth-0 constraint: $body")
        }

    @Test
    fun `PUT roots rootId config with a mismatched embedded project rootId returns 422 naming both ids`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            val otherRootId = UUID.randomUUID()
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("project:\n  rootId: $otherRootId\n$VALID_YAML")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("rootid_mismatch"), "Should report rootid_mismatch: $body")
            assertTrue(body.contains(root.id.toString()), "Should name the target rootId: $body")
            assertTrue(body.contains(otherRootId.toString()), "Should name the embedded rootId: $body")

            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "Mismatched embedded rootId must never be stored")
        }

    @Test
    fun `PUT roots rootId config with force=true bypasses a mismatched embedded project rootId`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            val otherRootId = UUID.randomUUID()
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config?force=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("project:\n  rootId: $otherRootId\n$VALID_YAML")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data != null, "force=true should allow the push to persist")
        }

    @Test
    fun `PUT roots rootId config with a known-old (superseded) fingerprint returns 409 superseded`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }
            val yamlB = VALID_YAML + "\n"

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }
            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(yamlB)
            }

            // VALID_YAML is now superseded by yamlB — pushing it again must be rejected as 409,
            // distinct from the 412 If-Match/concurrent-write case.
            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_YAML)
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("superseded"), "Should report the superseded error code: $body")

            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertEquals(yamlB, (persisted as Result.Success).data?.configYaml, "Rejected push must not overwrite the current row")
        }

    @Test
    fun `PUT roots rootId config with force=true bypasses a superseded fingerprint`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }
            val yamlB = VALID_YAML + "\n"

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }
            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(yamlB)
            }

            val response =
                client.put("/api/v1/roots/${root.id}/config?force=true") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_YAML)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertEquals(VALID_YAML, (persisted as Result.Success).data?.configYaml)
        }
}

class ProjectConfigGetRouteTest {
    @Test
    fun `GET roots rootId config returns stored config`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }

            val response =
                client.get("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(root.id.toString()))
        }

    @Test
    fun `GET roots rootId config with matching If-None-Match returns 304`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }

            val firstGet =
                client.get("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                }
            val etag = firstGet.headers[HttpHeaders.ETag]
            assertNotNull(etag)

            val response =
                client.get("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                    header(HttpHeaders.IfNoneMatch, etag)
                }

            assertEquals(HttpStatusCode.NotModified, response.status)
        }

    @Test
    fun `GET roots rootId config with token scope lacking the root returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            val otherRoot = createRoot(repo, title = "Other Root")
            application { configureProjectConfigTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(otherRoot.id))) }

            val response =
                client.get("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `GET roots rootId config for a root with no pushed config returns 404`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.get("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET roots rootId config with no fingerprint query param omits relation`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }

            val response =
                client.get("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(!response.bodyAsText().contains("relation"), "relation must be omitted when ?fingerprint= is absent")
        }

    @Test
    fun `GET roots rootId config with fingerprint matching current returns relation current`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }
            val currentFingerprint = runBlocking { repo.projectConfigRepository().computeFingerprint(VALID_YAML) }

            val response =
                client.get("/api/v1/roots/${root.id}/config?fingerprint=$currentFingerprint") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"relation\":\"current\""))
        }

    @Test
    fun `GET roots rootId config with a superseded fingerprint returns relation superseded`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }
            val yamlB = VALID_YAML + "\n"

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }
            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(yamlB)
            }
            val supersededFingerprint = runBlocking { repo.projectConfigRepository().computeFingerprint(VALID_YAML) }

            val response =
                client.get("/api/v1/roots/${root.id}/config?fingerprint=$supersededFingerprint") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"relation\":\"superseded\""))
        }

    @Test
    fun `GET roots rootId config with an unrelated fingerprint returns relation unknown`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }

            val response =
                client.get("/api/v1/roots/${root.id}/config?fingerprint=${"0".repeat(64)}") {
                    header("Authorization", "Bearer $PROJECT_CONFIG_READ_ONLY_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"relation\":\"unknown\""))
        }
}

class ProjectConfigDeleteRouteTest {
    @Test
    fun `DELETE roots rootId config removes the row and returns 204`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/config") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }

            val response =
                client.delete("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "Config row should be deleted")
        }

    @Test
    fun `DELETE roots rootId config with token scope lacking the root returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            val otherRoot = createRoot(repo, title = "Other Root")
            application { configureProjectConfigTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(otherRoot.id))) }

            val response =
                client.delete("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `DELETE roots rootId config for a root with no pushed config returns 404`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.delete("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}

/**
 * Opt-in unauthenticated REST mode (`API_AUTH_MODE=none` + `API_ALLOW_UNAUTHENTICATED=true`).
 * With [ApiAuthConfig.Unauthenticated] installed, every request — including one with NO
 * Authorization header at all — is attached the synthetic ADMIN/unrestricted principal and must
 * reach this WRITE_CONFIG-gated, scope-checked route successfully. This is the config-sync hook's
 * exact use case: a token-less PUT against a local unauthenticated server.
 */
class ProjectConfigUnauthenticatedModeTest {
    @Test
    fun `PUT roots rootId config with no Authorization header succeeds in unauthenticated mode`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo, authConfig = ApiAuthConfig.Unauthenticated) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    // Deliberately no Authorization header.
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_YAML)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue(persisted is Result.Success)
            val config = (persisted as Result.Success).data
            assertNotNull(config, "Config should be persisted even with no token")
            assertEquals(VALID_YAML, config!!.configYaml)
        }

    @Test
    fun `GET roots rootId config with no Authorization header succeeds in unauthenticated mode`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo, authConfig = ApiAuthConfig.Unauthenticated) }

            client.put("/api/v1/roots/${root.id}/config") {
                contentType(ContentType.Text.Plain)
                setBody(VALID_YAML)
            }

            val response = client.get("/api/v1/roots/${root.id}/config")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(root.id.toString()))
        }
}

/**
 * Convergence test (task-scope acceptance criterion): the MCP `manage_project_config` tool's
 * `push` operation and the REST `PUT /api/v1/roots/{rootId}/config` route both delegate to
 * [io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService] — pushing the
 * SAME payload through each surface (to two different depth-0 roots) must produce identical
 * fingerprint + stored bytes.
 */
class ProjectConfigConvergenceTest {
    @Test
    fun `MCP tool push and REST PUT converge on identical DB state for the same payload`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val rootViaTool = createRoot(repo, title = "Root Via Tool")
            val rootViaRest = createRoot(repo, title = "Root Via REST")

            // Push via the MCP tool.
            val tool = ManageProjectConfigTool()
            val context = ToolExecutionContext(repo)
            runBlocking {
                tool.execute(
                    buildJsonObject {
                        put("operation", JsonPrimitive("push"))
                        put("rootItemId", JsonPrimitive(rootViaTool.id.toString()))
                        put("configYaml", JsonPrimitive(VALID_YAML))
                    },
                    context,
                )
            }

            // Push the SAME content via the REST route.
            application { configureProjectConfigTestApp(repo) }
            val response =
                client.put("/api/v1/roots/${rootViaRest.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(VALID_YAML)
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val configViaTool = (runBlocking { repo.projectConfigRepository().get(rootViaTool.id) } as Result.Success).data
            val configViaRest = (runBlocking { repo.projectConfigRepository().get(rootViaRest.id) } as Result.Success).data
            assertNotNull(configViaTool)
            assertNotNull(configViaRest)
            assertEquals(configViaTool!!.fingerprint, configViaRest!!.fingerprint, "Fingerprints must match for identical content")
            assertEquals(configViaTool.configYaml, configViaRest.configYaml, "Stored YAML bytes must match")
        }
}

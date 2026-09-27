package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `diagnosis`/`test-plan` notes on item `aa664be1` —
 * S10 (`POST /items/{id}/advance`) and S11 (`GET /items/{id}/gate`) map to this file per the
 * test-plan's file list. Oracle: D6 ("REST: 503 + ErrorDto(\"config_unavailable\"), no Retry-After
 * ... RFC 9110 15.6.4 temporary inability, not a 409 conflict") plus the verbatim
 * `ItemWriteRoutes.kt`/`ItemRoutes.kt` excerpts, which construct `schemaResolutionContext` as
 * `ToolExecutionContext(..., perRootConfigService = PerRootConfigService(repositoryProvider.projectConfigRepository()))`
 * from the SAME [RepositoryProvider] the route function is given — so swapping
 * `projectConfigRepository()` on that provider (via [FailableRepositoryProvider]) is sufficient to
 * drive the failure through the real route stack, with no need to construct a
 * `ToolExecutionContext`/`PerRootConfigService` directly in this file.
 *
 * Harness mirrors [AdvanceRouteResourceLeaseTest]'s `LeaseOverridingProvider` pattern (a
 * `RepositoryProvider by delegate` override) and [ApiTestHelper]'s `buildH2RepositoryProvider`.
 */
class ConfigUnavailableRoutesTest {
    @Test
    fun `S10 - POST items id advance returns 503 config_unavailable when the per-root config read fails cold`() =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val (root, item) =
                runBlocking {
                    val r = h2.workItemRepository().create(WorkItem(title = "Root S10", depth = 0)).getOrNull()!!
                    val i =
                        h2
                            .workItemRepository()
                            .create(
                                WorkItem(title = "Child S10", role = Role.QUEUE, parentId = r.id, rootId = r.id, depth = 1)
                            ).getOrNull()!!
                    r to i
                }
            val failable = FailableProjectConfigRepository(h2.projectConfigRepository())
            failable.failFingerprint = true
            val provider = FailableRepositoryProvider(h2, failable)
            application { configureAdvanceApp(provider, NoSchemaWorkItemSchemaService) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("config_unavailable"), "body: $body")

            val persisted = runBlocking { h2.workItemRepository().getById(item.id).getOrNull()!! }
            assertEquals(Role.QUEUE, persisted.role, "role must be unchanged when the advance is rejected for config_unavailable")
            assertEquals(
                null,
                response.headers["Retry-After"],
                "D6: no Retry-After on a config_unavailable 503 (only SHEDDING carries retryAfterMs)"
            )
        }

    @Test
    fun `S11 - GET items id gate returns 503 config_unavailable when the per-root config read fails cold`() =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    val r = h2.workItemRepository().create(WorkItem(title = "Root S11", depth = 0)).getOrNull()!!
                    // A real row must exist so getFingerprint succeeds with a non-null value first —
                    // resolve() only reaches the .get() read (the one failFingerprint=false/failGet=true
                    // is meant to intercept) once the fingerprint check itself has NOT short-circuited
                    // on Success(null)/absence.
                    h2.projectConfigRepository().upsert(r.id, "work_item_schemas:\n  T:\n    notes: []\n")
                    h2
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Child S11", role = Role.WORK, parentId = r.id, rootId = r.id, depth = 1)
                        ).getOrNull()!!
                }
            val failable = FailableProjectConfigRepository(h2.projectConfigRepository())
            failable.failGet = true
            val provider = FailableRepositoryProvider(h2, failable)
            application {
                configureTestApp(makeTestAuthConfig()) {
                    itemGateRoutes(
                        provider,
                        ToolExecutionContext(
                            provider,
                            NoSchemaWorkItemSchemaService,
                            perRootConfigService = PerRootConfigService(provider.projectConfigRepository()),
                        ).configResolver,
                    )
                }
            }

            val response =
                client.get("/api/v1/items/${item.id}/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("config_unavailable"))
        }
}

/**
 * Wraps a real [ProjectConfigRepository] (the [io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository]
 * a [io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider] hands
 * back from `projectConfigRepository()`) and lets tests force [get]/[getFingerprint] to return
 * `Result.Error(RepositoryError.DatabaseError("x"))` on demand — the test-plan harness's "test
 * ProjectConfigRepository wrapping SQLiteProjectConfigRepository" fixture, authored here per this
 * file's ownership (no shared harness file; see the sibling copies in the tool-level
 * config-unavailable test files for the identical rationale).
 */
private class FailableProjectConfigRepository(
    private val delegate: ProjectConfigRepository
) : ProjectConfigRepository by delegate {
    @Volatile var failFingerprint: Boolean = false

    @Volatile var failGet: Boolean = false

    override suspend fun getFingerprint(rootItemId: UUID) =
        if (failFingerprint) Result.Error(RepositoryError.DatabaseError("x")) else delegate.getFingerprint(rootItemId)

    override suspend fun get(rootItemId: UUID) = if (failGet) Result.Error(RepositoryError.DatabaseError("x")) else delegate.get(rootItemId)
}

/** An H2-backed provider with only [projectConfigRepository] swapped, mirroring `LeaseOverridingProvider`. */
private class FailableRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failable: FailableProjectConfigRepository
) : RepositoryProvider by delegate {
    override fun projectConfigRepository(): ProjectConfigRepository = failable
}

/** No global schema for any type — mirrors the test-plan harness's "Global: no T, no default". */
private object NoSchemaWorkItemSchemaService : WorkItemSchemaService {
    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null
}

/** Registers only [itemWriteRoutes], mirroring [AdvanceRouteResourceLeaseTest]'s `configureLeaseTestApp`. */
private fun Application.configureAdvanceApp(
    provider: RepositoryProvider,
    schemaService: WorkItemSchemaService
) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    val authConfig = makeWriteAuthConfig()
    routing {
        route("/api/v1") {
            install(ApiBearerAuth) {
                this.authConfig = authConfig
                tokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
            }
            itemWriteRoutes(
                provider,
                DegradedModePolicy.ACCEPT_CACHED,
                IdempotencyCache(),
                ToolExecutionContext(
                    provider,
                    schemaService,
                    statusLabelService = NoOpStatusLabelService,
                    perRootConfigService = PerRootConfigService(provider.projectConfigRepository()),
                ).advanceServiceFactory(),
            )
        }
    }
}

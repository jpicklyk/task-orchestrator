package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.getFingerprint
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `diagnosis`/`test-plan` notes on item `aa664be1` —
 * S10 (`POST /items/{id}/advance`) and S11 (`GET /items/{id}/gate`) map to this file per the
 * test-plan's file list. Oracle: D6 ("REST: 503 + ErrorDto(\"config_unavailable\"), no Retry-After
 * ... RFC 9110 15.6.4 temporary inability, not a 409 conflict") plus the verbatim
 * `ItemWriteRoutes.kt`/`ItemRoutes.kt` excerpts, which construct `schemaResolutionContext` as
 * `ToolExecutionContext(..., perRootConfigService = PerRootConfigService(repositoryProvider.configStore()))`
 * from the SAME [RepositoryProvider] the route function is given — so swapping
 * `configStore()` on that provider (via [FailableRepositoryProvider]) is sufficient to
 * drive the failure through the real route stack, with no need to construct a
 * `ToolExecutionContext`/`PerRootConfigService` directly in this file.
 *
 * Harness mirrors [AdvanceRouteResourceLeaseTest]'s `LeaseOverridingProvider` pattern (a
 * `RepositoryProvider by delegate` override) and the SQLite test fixture (`SqliteTestDatabase.repositoryProvider()`).
 */
class ConfigUnavailableRoutesTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `S11 - GET items id gate returns 503 config_unavailable when the per-root config read fails cold`() =
        testApplication {
            val sqlite = db.repositoryProvider()
            val item =
                runBlocking {
                    val r = sqlite.workItemRepository().create(WorkItem(title = "Root S11", depth = 0))!!
                    // A real row must exist so getFingerprint succeeds with a non-null value first —
                    // resolve() only reaches the .get() read (the one failFingerprint=false/failGet=true
                    // is meant to intercept) once the fingerprint check itself has NOT short-circuited
                    // on Success(null)/absence.
                    sqlite.configStore().upsert(r.id, "work_item_schemas:\n  T:\n    notes: []\n")
                    sqlite
                        .workItemRepository()
                        .create(
                            WorkItem(title = "Child S11", role = Role.WORK, parentId = r.id, rootId = r.id, depth = 1)
                        )!!
                }
            val failable = FailableProjectConfigRepository(sqlite.configStore())
            failable.failGet = true
            val provider = FailableRepositoryProvider(sqlite, failable)
            application {
                configureTestApp(makeTestAuthConfig()) {
                    val perRoot = PerRootConfigService(provider.configStore())
                    ToolExecutionContext(
                        provider,
                        perRootConfigService = perRoot,
                        configResolver = TestConfigResolver.resolver(NoSchemaWorkItemSchemaService, perRoot),
                        unitOfWork = db.unitOfWork(),
                    ).let { ctx -> itemGateRoutes(provider, ctx.configResolver, ctx.transitionPreview()) }
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
 * Wraps a real [ConfigStore] (the [io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteConfigStore]
 * a [io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider] hands
 * back from `configStore()`) and lets tests force [get]/[getFingerprint] to return
 * `throw IllegalStateException("x")` on demand — the test-plan harness's "test
 * ConfigStore wrapping SqliteConfigStore" fixture, authored here per this
 * file's ownership (no shared harness file; see the sibling copies in the tool-level
 * config-unavailable test files for the identical rationale).
 */
private class FailableProjectConfigRepository(
    private val delegate: ConfigStore
) : ConfigStore by delegate {
    @Volatile var failFingerprint: Boolean = false

    @Volatile var failGet: Boolean = false

    override suspend fun head(
        scope: ConfigScope,
        rootId: UUID?
    ) = if (failFingerprint) throw IllegalStateException("x") else delegate.head(scope, rootId)

    override suspend fun version(id: UUID) = if (failGet) throw IllegalStateException("x") else delegate.version(id)
}

/** An SQLite-backed provider with only [projectConfigRepository] swapped, mirroring `LeaseOverridingProvider`. */
private class FailableRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failable: FailableProjectConfigRepository
) : RepositoryProvider by delegate {
    override fun configStore(): ConfigStore = failable
}

/** No global schema for any type — mirrors the test-plan harness's "Global: no T, no default". */
private val NoSchemaWorkItemSchemaService: ConfigDocument = ConfigDocument.EMPTY

/** Registers only [itemWriteRoutes], mirroring [AdvanceRouteResourceLeaseTest]'s `configureLeaseTestApp`. */
private fun Application.configureAdvanceApp(
    provider: RepositoryProvider,
    schemaService: ConfigDocument,
    unitOfWork: UnitOfWork
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
                IdempotencyService(unitOfWork),
                PerRootConfigService(provider.configStore()).let { perRoot ->
                    ToolExecutionContext(
                        provider,
                        statusLabelService = NoOpStatusLabelService,
                        perRootConfigService = perRoot,
                        configResolver = TestConfigResolver.resolver(schemaService, perRoot),
                        unitOfWork = unitOfWork
                    ).advanceServiceFactory()
                },
                unitOfWork,
            )
        }
    }
}

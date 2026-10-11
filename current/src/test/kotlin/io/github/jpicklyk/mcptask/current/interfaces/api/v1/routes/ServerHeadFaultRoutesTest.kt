package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for fix round 2 of Q17 (F1/F2): server-head reads outside the resolver map to the typed 3.x REST
 * faults. A store outage is transient `config_unavailable` (503); an unloadable head is permanent `config_invalid` (422).
 */
class ServerHeadFaultRoutesTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun faultingProvider(): RepositoryProvider {
        val store = mockk<ConfigStore>()
        coEvery { store.head(any(), any()) } throws SQLException("disk I/O error")
        val provider = mockk<RepositoryProvider>(relaxed = true)
        every { provider.configStore() } returns store
        return provider
    }

    private fun Application.infoApp(configService: ConfigService) {
        install(ContentNegotiation) { json(McpJson) }
        routing { route("/api/v1") { serviceRoutes(configService = configService) } }
    }

    private fun Application.putApp(
        repo: DefaultRepositoryProvider,
        unitOfWork: UnitOfWork,
        server: ServerConfigSource
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
                        ConfigValidator(NetworkntConfigStructuralValidator()),
                        server
                    )
                )
            }
        }
    }

    @Test
    fun `F1 GET info answers 503 config_unavailable when the server head read faults`(): Unit =
        testApplication {
            val service = ConfigService(faultingProvider(), YamlConfigDocumentParser, mockk<UnitOfWork>(relaxed = true))
            application { infoApp(service) }

            val response = client.get("/api/v1/info")

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, body)
            assertTrue(body.contains("config_unavailable"), body)
        }

    @Test
    fun `F1 serverHead surfaces a store read fault as PerRootConfigUnavailableException with a null root`(): Unit =
        runBlocking {
            val service = ConfigService(faultingProvider(), YamlConfigDocumentParser, mockk<UnitOfWork>(relaxed = true))

            val thrown = runCatching { service.serverHead() }.exceptionOrNull()

            assertTrue(thrown is PerRootConfigUnavailableException, "got $thrown")
            assertEquals(null, thrown.rootId)
        }

    @Test
    fun `F2 PUT root config answers 422 config_invalid when the server head is unloadable`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "Root", type = "project", depth = 0)) }
            application { putApp(repo, db.unitOfWork()) { throw PerRootConfigInvalidException(null, 3, "no canonical form") } }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(StrictConfigCases.VALID_ROOT_YAML)
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, body)
            assertTrue(body.contains("config_invalid"), body)
        }

    @Test
    fun `F2 PUT root config answers 503 config_unavailable when the server head read faults`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "Root", type = "project", depth = 0)) }
            application {
                putApp(repo, db.unitOfWork()) { throw PerRootConfigUnavailableException(null, "Server config is unavailable", null) }
            }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(StrictConfigCases.VALID_ROOT_YAML)
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, body)
            assertTrue(body.contains("config_unavailable"), body)
        }
}

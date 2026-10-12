package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
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
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for fix round 2 of Q17 (F1/F2): server-head reads outside the resolver map to typed faults. A store
 * outage is transient (`unavailable` on `/info` and from the `config` tool, Q32); an unloadable head is
 * permanent `config_invalid`.
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

    /** A `config` push of the valid root document through a service whose server layer is [server]. */
    private fun pushThroughTool(
        repo: DefaultRepositoryProvider,
        rootId: java.util.UUID,
        server: ServerConfigSource
    ): JsonObject =
        runBlocking {
            val service =
                ConfigService(
                    repo,
                    YamlConfigDocumentParser,
                    db.unitOfWork(),
                    ConfigValidator(NetworkntConfigStructuralValidator()),
                    server
                )
            ToolExecutionContext(repo, unitOfWork = db.unitOfWork(), configService = service).callTool(
                "config",
                buildJsonObject {
                    put(
                        "ops",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("op", "push")
                                    put("rootId", rootId.toString())
                                    put("configYaml", StrictConfigCases.VALID_ROOT_YAML)
                                }
                            )
                        }
                    )
                }
            )
        }

    @Test
    fun `F1 GET info answers 503 unavailable when the server head read faults`(): Unit =
        testApplication {
            val service = ConfigService(faultingProvider(), YamlConfigDocumentParser, mockk<UnitOfWork>(relaxed = true))
            application { infoApp(service) }

            val response = client.get("/api/v1/info")

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, body)
            assertTrue(body.contains("\"code\":\"unavailable\""), body)
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
    fun `F2 config push answers config_invalid when the server head is unloadable`() {
        val repo = db.repositoryProvider()
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "Root", type = "project", depth = 0)) }

        val body = pushThroughTool(repo, root.id) { throw PerRootConfigInvalidException(null, 3, "no canonical form") }

        assertEquals("config_invalid", body.errorAt(0)["code"]!!.jsonPrimitive.content, body.toString())
    }

    @Test
    fun `F2 config push answers unavailable when the server head read faults`() {
        val repo = db.repositoryProvider()
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "Root", type = "project", depth = 0)) }

        val body = pushThroughTool(repo, root.id) { throw PerRootConfigUnavailableException(null, "Server config is unavailable", null) }

        assertEquals("unavailable", body.errorAt(0)["code"]!!.jsonPrimitive.content, body.toString())
    }
}

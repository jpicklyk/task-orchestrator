package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `diagnosis`/`test-plan` notes on item `aa664be1` — S12
 * maps to this file per the test-plan's file list. Oracle: D5 ("Other tools fail whole-call via
 * McpToolAdapter `structuredContent.error`") plus the verbatim `McpToolAdapter.kt` excerpt's
 * dedicated `catch (e: PerRootConfigUnavailableException)` branch: `errorKind`/`kind` =
 * `"transient"`, `errorCode`/`code` = `"config_unavailable"`, no `retryAfterMs` ("deliberate, per
 * the documented ErrorKind rule — only SHEDDING gets a retryAfterMs").
 *
 * Harness mirrors [McpToolAdapterIntegrationTest]'s full-protocol pattern: a real [Server]/[Client]
 * pair over [ChannelTransport.createLinkedPair], a minimal anonymous [ToolDefinition] whose
 * `execute()` throws (that file's own `failingTool()` throws a generic [RuntimeException]; this one
 * throws [PerRootConfigUnavailableException], the specific type this fix's dedicated catch
 * targets), driven through the real client via [Client.callTool] and inspected via
 * `CallToolResult.isError`/`structuredContent` — per harness pointer #5, a small anonymous tool is
 * sufficient since McpToolAdapter's catch is tool-agnostic; the real `get_context` tool is not
 * needed.
 */
class McpToolAdapterConfigUnavailableTest {
    private lateinit var server: Server
    private lateinit var client: Client
    private lateinit var adapter: McpToolAdapter

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "test-server", version = "1.0.0"),
                    options =
                        ServerOptions(
                            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
                        )
                )
            adapter = McpToolAdapter()
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "test-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
            server.createSession(serverTransport)
            client.connect(clientTransport)
        }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            client.close()
            server.close()
        }

    private val dummyContext =
        ToolExecutionContext(
            repositoryProvider =
                DefaultRepositoryProvider(
                    DatabaseManager(
                        Database.connect(
                            "jdbc:h2:mem:mcpadapter_config_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                            driver = "org.h2.Driver"
                        )
                    ).also { DirectDatabaseSchemaManager().updateSchema() }
                )
        )

    /**
     * A minimal tool whose `execute()` always throws [PerRootConfigUnavailableException] — mirrors
     * [McpToolAdapterIntegrationTest]'s `failingTool()` pattern exactly, substituting the specific
     * exception type this fix's dedicated adapter catch targets. The tool name is irrelevant to the
     * catch (it is tool-agnostic per the class KDoc), but `get_context` is used since that is the
     * real call site the test-plan's S12 names.
     */
    private fun configUnavailableTool(rootId: UUID): ToolDefinition =
        object : ToolDefinition {
            override val name = "get_context"
            override val description = "Always throws PerRootConfigUnavailableException, for S12"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.WORKFLOW

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = throw PerRootConfigUnavailableException(rootId, "Per-root config unavailable for root $rootId")
        }

    @Test
    fun `S12 - a tool throwing PerRootConfigUnavailableException fails the whole call as transient config_unavailable`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            adapter.registerToolWithServer(server, configUnavailableTool(rootId), dummyContext)

            val result = client.callTool(name = "get_context", arguments = emptyMap())

            assertEquals(true, result.isError)
            val structured = assertNotNull(result.structuredContent, "Error response must carry structuredContent")
            val error = assertNotNull(structured["error"]?.jsonObject, "structuredContent must contain the error object")
            assertEquals("transient", error["kind"]?.jsonPrimitive?.content)
            assertEquals("config_unavailable", error["code"]?.jsonPrimitive?.content)
            assertEquals(null, error["retryAfterMs"], "D5: no retryAfterMs on config_unavailable — only SHEDDING carries one")

            val textContent = result.content.filterIsInstance<TextContent>()
            assertTrue(textContent.isNotEmpty(), "Expected at least one TextContent")
            assertTrue(
                textContent[0].text.contains(rootId.toString()),
                "the adapter's own error message must name the failing root: ${textContent[0].text}"
            )
        }
}

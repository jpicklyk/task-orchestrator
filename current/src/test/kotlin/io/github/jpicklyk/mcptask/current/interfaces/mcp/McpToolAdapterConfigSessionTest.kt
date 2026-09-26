package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
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
import kotlinx.serialization.json.buildJsonObject
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
 * S9/S10 — the per-call [io.github.jpicklyk.mcptask.current.application.config.ConfigSession]
 * install at the [McpToolAdapter] call boundary (Part B), driven through the REAL adapter and a
 * real MCP client/server pair — mirrors [McpToolAdapterConfigUnavailableTest]'s harness exactly,
 * substituting a per-root-read-counting/failing [PerRootConfigSource] for its throwing
 * [ToolDefinition]. Oracle: `test-plan` S9 ("via McpToolAdapter harness: tool resolving one root
 * twice = 1 read; two calls = 2 (no leak)") and S10 ("cold failure inside a session -> same
 * transient config_unavailable envelope"), plus the D5 envelope shape already pinned by
 * [McpToolAdapterConfigUnavailableTest]'s S12 (same assertion block, reused here to prove the
 * envelope is unchanged when the failure now originates from inside a `withConfigSession`-wrapped
 * call rather than a bare tool throw).
 *
 * NEW-SURFACE (test-plan S9/S10): the `withConfigSession` wrap at the four call sites is
 * introduced by this feature (Part B). No source revert can yield behavioral red — the plan's
 * substitute is "drop withConfigSession at call sites", an orchestrator-run mutation.
 */
class McpToolAdapterConfigSessionTest {
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

    private class CountingPerRootConfigSource(
        private val provide: suspend (UUID) -> ConfigLayer?
    ) : PerRootConfigSource {
        val reads = mutableMapOf<UUID, Int>()

        override suspend fun layer(rootId: UUID): ConfigLayer? {
            reads[rootId] = (reads[rootId] ?: 0) + 1
            return provide(rootId)
        }
    }

    private class FailingPerRootConfigSource : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? =
            throw PerRootConfigUnavailableException(rootId, "Per-root config unavailable for root $rootId")
    }

    private fun contextWithSource(source: PerRootConfigSource): ToolExecutionContext =
        ToolExecutionContext(
            repositoryProvider =
                DefaultRepositoryProvider(
                    DatabaseManager(
                        Database.connect(
                            "jdbc:h2:mem:mcpadapter_session_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                            driver = "org.h2.Driver"
                        )
                    ).also { DirectDatabaseSchemaManager().updateSchema() }
                ),
            perRootConfigService = source
        )

    /**
     * Calls `resolveTypeSchema` [calls] times against [rootId] inside one tool execution — under
     * S9's oracle, all of them must collapse to a single underlying per-root read when the adapter
     * has installed a session around this whole `execute()` call.
     */
    private fun countingRootTool(
        rootId: UUID,
        calls: Int
    ): ToolDefinition =
        object : ToolDefinition {
            override val name = "get_context"
            override val description = "Calls resolveTypeSchema $calls time(s) against one root, for S9"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.WORKFLOW

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement {
                repeat(calls) { context.resolveTypeSchema("feature-task", rootId) }
                return buildJsonObject {}
            }
        }

    private fun failingRootTool(rootId: UUID): ToolDefinition =
        object : ToolDefinition {
            override val name = "get_context"
            override val description = "Resolves a root whose per-root source fails, for S10"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.WORKFLOW

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement {
                context.resolveTypeSchema("feature-task", rootId)
                return buildJsonObject {}
            }
        }

    @Test
    fun `S9 - resolving one root twice within a single tool call performs exactly one underlying per-root read`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val source = CountingPerRootConfigSource { null }
            val context = contextWithSource(source)
            adapter.registerToolWithServer(server, countingRootTool(rootId, calls = 2), context)

            val result = client.callTool(name = "get_context", arguments = emptyMap())

            assertEquals(false, result.isError, "sanity: the call must succeed for the read count to be meaningful")
            assertEquals(1, source.reads[rootId], "two resolutions of the same root in one call must collapse to one read")
        }

    @Test
    fun `S9 - two separate tool calls each read fresh, with no leak across calls`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val source = CountingPerRootConfigSource { null }
            val context = contextWithSource(source)
            adapter.registerToolWithServer(server, countingRootTool(rootId, calls = 1), context)

            client.callTool(name = "get_context", arguments = emptyMap())
            client.callTool(name = "get_context", arguments = emptyMap())

            assertEquals(2, source.reads[rootId], "a fresh call must not reuse the previous call's session memo")
        }

    @Test
    fun `S10 - a cold per-root failure inside a session surfaces the same transient config_unavailable envelope as a bare throw`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val context = contextWithSource(FailingPerRootConfigSource())
            adapter.registerToolWithServer(server, failingRootTool(rootId), context)

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

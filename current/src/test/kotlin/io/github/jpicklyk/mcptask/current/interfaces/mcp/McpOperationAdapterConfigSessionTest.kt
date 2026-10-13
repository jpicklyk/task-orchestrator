package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * S9/S10 of the config-session feature, at the 4.0 call boundary (Q32 port of the removed 3.x adapter's
 * McpToolAdapterConfigSessionTest): [io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher] runs every
 * select inside `withConfigSession`, so one MCP call through [McpOperationAdapter] resolves a root's per-root config once.
 *
 * Oracle: `test-plan` S9 ("a tool resolving one root twice = 1 read; two calls = 2 (no leak)") and S10 (a cold per-root
 * failure inside a session surfaces the same envelope as the same failure thrown bare). The probe is a one-select test
 * tool whose handler resolves a type schema against one root a chosen number of times.
 */
class McpOperationAdapterConfigSessionTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val pairs = mutableListOf<Pair<Client, Server>>()

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            pairs.forEach { (client, server) -> closeInMemoryPair(client, server) }
        }

    @Serializable
    data class ResolveReq(
        @Doc("How many times to resolve the root's config.") val times: Int = 1
    )

    private class CountingPerRootConfigSource : PerRootConfigSource {
        val reads = mutableMapOf<UUID, Int>()

        override suspend fun layer(rootId: UUID): ConfigLayer? {
            reads[rootId] = (reads[rootId] ?: 0) + 1
            return null
        }
    }

    private class FailingPerRootConfigSource : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? =
            throw PerRootConfigUnavailableException(rootId, "Per-root config unavailable for root $rootId")
    }

    /** `probe` select `resolve` resolves the `feature-task` type schema against [rootId] `times` times; `throw` throws bare. */
    private fun probeTool(rootId: UUID): SelectToolSpec =
        SelectToolSpec(
            "probe",
            "Config-session probe.",
            listOf(
                SelectSpec.Single<ResolveReq, String>(
                    name = "resolve",
                    doc = "Resolve one root's config.",
                    profiles = knowledge(),
                    request = serializer<ResolveReq>(),
                    handler = { env, _, req ->
                        repeat(req.times) { env.resolveTypeSchema("feature-task", rootId) }
                        Outcome.Ok("resolved")
                    },
                    data = { JsonPrimitive(it) }
                ),
                SelectSpec.Single<ResolveReq, String>(
                    name = "throw",
                    doc = "Throw the per-root failure bare.",
                    profiles = knowledge(),
                    request = serializer<ResolveReq>(),
                    handler = {
                        _,
                        _,
                        _
                        ->
                        throw PerRootConfigUnavailableException(rootId, "Per-root config unavailable for root $rootId")
                    },
                    data = { JsonPrimitive(it) }
                )
            )
        )

    private suspend fun connect(
        source: PerRootConfigSource,
        rootId: UUID
    ): Client {
        val env =
            ToolExecutionContext(repositoryProvider = db.repositoryProvider(), perRootConfigService = source, unitOfWork = db.unitOfWork())
        val server = Server(serverInfo = Implementation(name = "session-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        McpOperationAdapter(buildToolDispatcher(env)).register(server, ToolRegistry(listOf(probeTool(rootId))), ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "session-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        pairs += client to server
        return client
    }

    private suspend fun Client.probe(
        select: String,
        times: Int = 1
    ): CallToolResult =
        callTool(
            buildCallToolRequest {
                name = "probe"
                arguments(JsonObject(mapOf("select" to JsonPrimitive(select), "times" to JsonPrimitive(times))))
            }
        )

    private fun CallToolResult.error(): JsonObject =
        assertNotNull(structuredContent?.get("error")?.jsonObject, "an error envelope: $structuredContent")

    @Test
    fun `S9 resolving one root twice within a single call performs exactly one underlying per-root read`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val source = CountingPerRootConfigSource()
            val client = connect(source, rootId)

            val result = client.probe("resolve", times = 2)

            assertEquals(false, result.isError, "sanity: the call must succeed for the read count to be meaningful: $result")
            assertEquals(1, source.reads[rootId], "two resolutions of the same root in one call must collapse to one read")
        }

    @Test
    fun `S9 two separate calls each read fresh, with no leak across calls`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val source = CountingPerRootConfigSource()
            val client = connect(source, rootId)

            client.probe("resolve")
            client.probe("resolve")

            assertEquals(2, source.reads[rootId], "a fresh call must not reuse the previous call's session memo")
        }

    @Test
    fun `S10 a cold per-root failure inside a session surfaces the same envelope as the failure thrown bare`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val client = connect(FailingPerRootConfigSource(), rootId)

            val inSession = client.probe("resolve")
            val bare = client.probe("throw")

            assertEquals(true, inSession.isError, "$inSession")
            assertEquals(true, bare.isError, "$bare")
            val sessionError = inSession.error()
            val bareError = bare.error()
            assertEquals(bareError["code"], sessionError["code"], "the session changes nothing about the error code")
            assertEquals(bareError["kind"], sessionError["kind"], "nor its kind")
            assertEquals(bareError["message"]?.jsonPrimitive?.content, sessionError["message"]?.jsonPrimitive?.content, "nor its message")
        }
}

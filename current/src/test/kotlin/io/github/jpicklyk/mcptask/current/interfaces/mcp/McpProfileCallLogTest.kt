package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.CallLogRows
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests (test-author seat, item f6d23c40, Q29 tool profiles) for the profile recorded in `call_log.request_shape`:
 * every 4.0 MCP call row carries `"profile": "<wire>"` of the SERVER that handled it, the key is server-derived (a client
 * argument of the same name never wins), it survives the shape key cap, and the 3.x tool rows get no profile key. Scenarios S4
 * and S12 of the frozen test-plan plus probes, driven through the real [McpOperationAdapter] and [buildProfileServer] over the
 * in-memory client/server pair, the real production registry and the real composition and call-log writer.
 *
 * Oracles: plan Q29 row and F26 (the profile is recorded in `request_shape`, no migration), task-scope item 8 (the value is the
 * profile wire name; the key is server-derived, overrides a client key `profile`, survives the 16-key cap on conforming shape keys (CallLogFieldsTest F1); `request_shape` is
 * therefore non-null on every 4.0 MCP call; 3.x `McpToolAdapter` rows and REST rows get no profile key) and the declared
 * `CallLogFields` constants. Expected values are the literal wire names "knowledge" and "orchestration".
 *
 * Negative-assertion conditions: "3.x rows have no profile key" is paired with a 4.0 row in the same server that does have it
 * (so a global stamp on every row would fail it); the spoof tests send a client `profile` that differs from the server's, so a
 * client-wins merge fails them.
 *
 * Forbidden-construct declaration: the 3.x-row assertion accepts either a null `request_shape` or a JSON object without the key
 * (the pre-existing rule is "null if empty"); both mean "no profile recorded". No assumeTrue, no @Disabled.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class McpProfileCallLogTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private lateinit var rig: EventLogRig
    private lateinit var writer: CallLogWriter
    private lateinit var adapter: McpOperationAdapter
    private val pairs = mutableListOf<Pair<Client, Server>>()

    @BeforeEach
    fun setUp() {
        rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)
        writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)
        adapter = McpOperationAdapter(buildToolDispatcher(rig.ctx), callLog = writer)
    }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            pairs.forEach { (client, server) -> closeInMemoryPair(client, server) }
        }

    // ------------------------------------------------------------------ helpers

    private suspend fun connect(server: Server): Client {
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "profile-call-log-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        pairs += client to server
        return client
    }

    /** A server with only the 4.0 tools of [profile], registered through [operationAdapter] (default: the shared [adapter]). */
    private suspend fun connectOps(
        profile: ToolProfile,
        operationAdapter: McpOperationAdapter = adapter
    ): Client {
        val server =
            Server(serverInfo = Implementation(name = "profile-call-log-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        operationAdapter.register(server, ToolRegistry(buildToolSpecs()), profile)
        return connect(server)
    }

    private suspend fun Client.run(
        tool: String,
        arguments: String
    ): CallToolResult =
        callTool(
            buildCallToolRequest {
                name = tool
                arguments(Json.parseToJsonElement(arguments).jsonObject)
            }
        )

    private fun reqIdOf(result: CallToolResult): String =
        assertNotNull(
            result.meta
                ?.get("reqId")
                ?.jsonPrimitive
                ?.contentOrNull,
            "_meta.reqId must be present: ${result.meta}"
        )

    private suspend fun rowOf(result: CallToolResult): Map<String, Any?> {
        writer.flushNow()
        val reqId = reqIdOf(result)
        return CallLogRows.read(db.jdbcUrl).single { it["req_id"] == reqId }
    }

    private fun Map<String, Any?>.shape(): JsonObject? = (get("request_shape") as String?)?.let { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.profileValue(): JsonPrimitive = getValue("profile").jsonPrimitive

    // ------------------------------------------------------------------ S4 the profile is recorded

    @Test
    fun `S4 a knowledge call with no flags records the knowledge profile and a non-null shape`(): Unit =
        runBlocking {
            val client = connectOps(ToolProfile.KNOWLEDGE)

            val row = rowOf(client.run("items_read", """{"select":"list"}"""))

            val shape = assertNotNull(row.shape(), "request_shape must be non-null on every 4.0 MCP call: ${row["request_shape"]}")
            assertEquals("knowledge", shape.profileValue().content)
            assertTrue(shape.profileValue().isString, "the profile is a JSON string, not a boolean: $shape")
        }

    @Test
    fun `S4 an orchestration call with no flags records the orchestration profile`(): Unit =
        runBlocking {
            val client = connectOps(ToolProfile.ORCHESTRATION)

            val row = rowOf(client.run("items_read", """{"select":"list"}"""))

            val shape = assertNotNull(row.shape(), "request_shape must be non-null: ${row["request_shape"]}")
            assertEquals("orchestration", shape.profileValue().content)
        }

    @Test
    fun `S4 the profile is added next to the existing flag keys without displacing them`(): Unit =
        runBlocking {
            val client = connectOps(ToolProfile.KNOWLEDGE)

            val row = rowOf(client.run("items_read", """{"select":"overview","includeChildren":true}"""))

            val shape = assertNotNull(row.shape(), "request_shape: ${row["request_shape"]}")
            assertEquals("knowledge", shape.profileValue().content)
            assertEquals(true, shape["includeChildren"]?.jsonPrimitive?.booleanOrNull, "the boolean flag is still recorded: $shape")
        }

    @Test
    fun `S4 a refused hidden select still records its row with the knowledge profile`(): Unit =
        runBlocking {
            val client = connectOps(ToolProfile.KNOWLEDGE)

            val result = client.run("items_read", """{"select":"context"}""")

            assertEquals(true, result.isError, "control: the hidden select is refused")
            val row = rowOf(result)
            assertEquals("invalid_request", row["error_code"], "the refusal is recorded as invalid_request: $row")
            assertEquals("knowledge", assertNotNull(row.shape(), "request_shape: ${row["request_shape"]}").profileValue().content)
        }

    @Test
    fun `S4 one shared operation adapter records each server's own profile`(): Unit =
        runBlocking {
            val knowledge = connectOps(ToolProfile.KNOWLEDGE)
            val orchestration = connectOps(ToolProfile.ORCHESTRATION)

            val knowledgeRow = rowOf(knowledge.run("items_read", """{"select":"list"}"""))
            val orchestrationRow = rowOf(orchestration.run("items_read", """{"select":"list"}"""))

            assertEquals("knowledge", knowledgeRow.shape()!!.profileValue().content)
            assertEquals("orchestration", orchestrationRow.shape()!!.profileValue().content)
        }

    @Test
    fun `S4 a profile server built by buildProfileServer records its profile for 4_0 rows and none for 3x rows`(): Unit =
        runBlocking {
            for (profile in ToolProfile.entries) {
                val server =
                    buildProfileServer(
                        profile = profile,
                        serverName = "profile-call-log-built",
                        version = "1.0.0",
                        registry = ToolRegistry(buildToolSpecs()),
                        operationAdapter = adapter,
                        legacyAdapter = McpToolAdapter(callLog = writer),
                        legacyTools = buildMcpTools(),
                        toolContext = rig.ctx
                    )
                val client = connect(server)

                val opsRow = rowOf(client.run("items_read", """{"select":"list"}"""))
                assertEquals(profile.wire, opsRow.shape()!!.profileValue().content, "4.0 row of the ${profile.wire} server")

                // No 3.x control row: the 3.x McpToolAdapter has no registered tools any more (Q23 removed the last two).
            }
        }

    // ------------------------------------------------------------------ S12 server-derived wins

    @Test
    fun `S12 a client profile true on a knowledge call is overridden by the server value`(): Unit =
        runBlocking {
            val client = connectOps(ToolProfile.KNOWLEDGE)

            val result = client.run("items_read", """{"select":"list","profile":true}""")
            val row = rowOf(result)

            val shape = assertNotNull(row.shape(), "request_shape: ${row["request_shape"]}")
            assertEquals("knowledge", shape.profileValue().content, "the stored profile is server-derived: $shape")
            assertTrue(shape.profileValue().isString, "the client's boolean must not survive: $shape")
        }

    @Test
    fun `S12 a client profile string naming the other profile does not change the recorded profile`(): Unit =
        runBlocking {
            val knowledge = connectOps(ToolProfile.KNOWLEDGE)
            val orchestration = connectOps(ToolProfile.ORCHESTRATION)

            val spoofedUp = rowOf(knowledge.run("items_read", """{"select":"list","profile":"orchestration"}"""))
            val spoofedDown = rowOf(orchestration.run("items_read", """{"select":"list","profile":false}"""))

            assertEquals("knowledge", spoofedUp.shape()!!.profileValue().content, "a knowledge call cannot claim orchestration")
            assertEquals("orchestration", spoofedDown.shape()!!.profileValue().content, "an orchestration call keeps its own profile")
        }

    @Test
    fun `S12 the profile survives the shape key cap when the client sends more flags than the cap`(): Unit =
        runBlocking {
            val client = connectOps(ToolProfile.KNOWLEDGE)
            val flags = (1..20).joinToString(",") { "\"a%02d\":true".format(it) }

            val row = rowOf(client.run("items_read", """{"select":"list",$flags}"""))

            val shape = assertNotNull(row.shape(), "request_shape: ${row["request_shape"]}")
            assertEquals("knowledge", shape.profileValue().content, "keys sorting before 'profile' must not push it out: $shape")
        }
}

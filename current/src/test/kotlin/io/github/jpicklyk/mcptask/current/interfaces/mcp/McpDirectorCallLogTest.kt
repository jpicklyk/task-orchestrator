package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.bootstrap.buildProfileServer
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
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
 * Independent tests (test-author seat, item b342d320, R5 director profile) for the profile recorded in
 * `call_log.request_shape` when the SERVER is the director profile (scenario S7 of the frozen test-plan, decision D8): a
 * director call records `"profile": "director"`, the key is server-derived (a client argument of the same name never wins), and
 * a refused hidden select still records its row with the director profile.
 *
 * Oracles: task-scope D8 ("a director call records `profile=director`", through the existing `request_shape.profile`) and the
 * Q29 contract the sibling McpProfileCallLogTest pins for the other two profiles (the value is the profile wire name; the key is
 * server-derived and overrides a client key `profile`). Expected values are the literal wire names.
 *
 * Negative-assertion conditions: the spoof tests send a client `profile` that differs from the server's, so a client-wins merge
 * fails them; the shared-adapter test makes three calls through one adapter, so a stamp taken from the wrong server fails it.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class McpDirectorCallLogTest {
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

    private suspend fun connect(profile: ToolProfile): Client {
        val server =
            buildProfileServer(
                profile = profile,
                serverName = "director-call-log-server",
                version = "1.0.0",
                registry = ToolRegistry(buildToolSpecs()),
                operationAdapter = adapter
            )
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "director-call-log-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        pairs += client to server
        return client
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

    private suspend fun rowOf(result: CallToolResult): Map<String, Any?> {
        writer.flushNow()
        val reqId =
            assertNotNull(
                result.meta
                    ?.get("reqId")
                    ?.jsonPrimitive
                    ?.contentOrNull,
                "_meta.reqId must be present: ${result.meta}"
            )
        return CallLogRows.read(db.jdbcUrl).single { it["req_id"] == reqId }
    }

    private fun Map<String, Any?>.shape(): JsonObject? = (get("request_shape") as String?)?.let { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.profile(): String = getValue("profile").jsonPrimitive.content

    @Test
    fun `S7 a director call records the director profile in a non-null request shape`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.DIRECTOR)

            val row = rowOf(client.run("items_read", """{"select":"list"}"""))

            val shape = assertNotNull(row.shape(), "request_shape must be non-null on every 4.0 MCP call: ${row["request_shape"]}")
            assertEquals("director", shape.profile())
            assertTrue(shape.getValue("profile").jsonPrimitive.isString, "the profile is a JSON string: $shape")
        }

    @Test
    fun `S7 a client profile argument cannot change the recorded director profile`(): Unit =
        runBlocking {
            val director = connect(ToolProfile.DIRECTOR)
            val orchestration = connect(ToolProfile.ORCHESTRATION)

            val spoofedUp = rowOf(director.run("items_read", """{"select":"list","profile":"orchestration"}"""))
            val spoofedBool = rowOf(director.run("items_read", """{"select":"list","profile":true}"""))
            val spoofedDown = rowOf(orchestration.run("items_read", """{"select":"list","profile":"director"}"""))

            assertEquals("director", spoofedUp.shape()!!.profile(), "a director call cannot claim orchestration")
            assertEquals("director", spoofedBool.shape()!!.profile(), "a client boolean cannot replace the server value")
            assertEquals("orchestration", spoofedDown.shape()!!.profile(), "an orchestration call cannot claim director")
        }

    @Test
    fun `S7 a refused hidden select on director still records its row with the director profile`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.DIRECTOR)

            val result = client.run("items_read", """{"select":"search","query":"zephyrquux"}""")

            assertEquals(true, result.isError, "control: the hidden select is refused")
            val row = rowOf(result)
            assertEquals("invalid_request", row["error_code"], "the refusal is recorded as invalid_request, not forbidden: $row")
            assertEquals("director", assertNotNull(row.shape(), "request_shape: ${row["request_shape"]}").profile())
        }

    @Test
    fun `S7 one shared operation adapter records each of the three servers' own profile`(): Unit =
        runBlocking {
            val rows =
                ToolProfile.entries.associateWith { profile ->
                    rowOf(connect(profile).run("items_read", """{"select":"list"}"""))
                }
            for ((profile, row) in rows) {
                assertEquals(profile.wire, row.shape()!!.profile(), "the ${profile.wire} server records its own profile")
            }
            assertEquals(setOf("knowledge", "orchestration", "director"), rows.values.map { it.shape()!!.profile() }.toSet())
        }
}

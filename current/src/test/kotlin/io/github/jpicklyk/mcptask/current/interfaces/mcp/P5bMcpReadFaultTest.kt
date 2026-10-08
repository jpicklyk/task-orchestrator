package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent P5b test S9 / carry-in F10 through the REAL MCP path (a Server/Client pair over a linked channel with the
 * production McpToolAdapter, the harness McpToolAdapterConfigUnavailableTest uses): a store fault on an MCP read
 * (query_items get) must come back as DATABASE_ERROR, never RESOURCE_NOT_FOUND and never an empty or generic internal
 * failure. The adapter-level message text is NOT DECLARED, so only the code and the error flag are asserted.
 * Fault = the work_items table renamed away after a healthy control read.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class P5bMcpReadFaultTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db

    private lateinit var server: Server
    private lateinit var client: Client

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "p5b-read-fault-server", version = "1.0.0"),
                    options = inMemoryTestServerOptions()
                )
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "p5b-read-fault-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
            server.createSession(serverTransport)
            client.connect(clientTransport)
            McpToolAdapter().registerToolWithServer(
                server,
                QueryItemsTool(),
                ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
            )
        }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            closeInMemoryPair(client, server)
        }

    @Test
    fun `S9 F10 query_items get on a store read fault is a DATABASE_ERROR error result, never RESOURCE_NOT_FOUND`(): Unit =
        runBlocking {
            val item = db.repositoryProvider().workItemRepository().create(WorkItem(title = "S9 mcp read", depth = 0))
            val args = mapOf("operation" to "get", "itemId" to item.id.toString())

            val healthy = client.callTool(name = "query_items", arguments = args)
            assertTrue(healthy.isError != true, "control: the read works before the fault: ${healthy.content}")

            DriverManager.getConnection(db.jdbcUrl).use { c ->
                c.createStatement().use { it.execute("ALTER TABLE work_items RENAME TO work_items_gone") }
            }
            val result = client.callTool(name = "query_items", arguments = args)

            assertEquals(true, result.isError, "a store fault must be an error result: ${result.content}")
            val structured = assertNotNull(result.structuredContent, "the error result carries structuredContent")
            val code =
                assertNotNull(
                    structured["error"]
                        ?.jsonObject
                        ?.get("code")
                        ?.jsonPrimitive
                        ?.content,
                    "error.code present: $structured"
                )
            assertEquals(ErrorCodes.DATABASE_ERROR, code, "$structured")
            val text = result.content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
            assertFalse(
                ErrorCodes.RESOURCE_NOT_FOUND in structured.toString() || ErrorCodes.RESOURCE_NOT_FOUND in text,
                "$structured / $text"
            )
        }
}

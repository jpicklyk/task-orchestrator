package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildMcpTools
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.ParityCoverage
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 450aa813) for the tool-surface half of scenario S15 of the frozen test-plan: the
 * production registry exposes `items_read` with the six selects (since Q30 including related), `tools/list` over a real MCP client/server pair shows
 * `items_read` and no `query_items`, and `query_notes` no longer offers a `search` operation.
 *
 * Oracles: task-scope item 1 (tool `items_read`, selects get, list, schema, search, overview, registered in
 * `buildToolSpecs()`), item 12 (the 3.x `QueryItemsTool` and its `buildMcpTools()` entry are deleted; `query_notes` loses its
 * `search` operation, and the whole tool only if Q11 had already reduced it to search-only, which has not happened: `get` and
 * `list` remain) and the acceptance criterion "tools/list shows items_read (selects get, list, schema, search, overview) and no
 * query_items; query_notes has no search". Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ItemsReadRegistryTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    @Test
    fun `S15 the production registry holds items_read with exactly the nine selects`() {
        val members = ParityCoverage.members(ToolRegistry(buildToolSpecs())).filter { it.first == "items_read" }

        assertEquals(
            setOf("get", "list", "schema", "search", "overview", "related", "context", "next", "blocked"),
            members.map { it.second }.toSet()
        )
        assertEquals(9, members.size, "no select is declared twice")
    }

    @Test
    fun `S15 the 3x tool list has no query_items and query_notes offers no search operation`() {
        val tools = buildMcpTools()

        assertFalse("query_items" in tools.map { it.name }, "query_items is deleted")
        // Merge r1: Q11 moved query_notes get/list to notes_read, so with search removed the whole tool is gone.
        assertFalse("query_notes" in tools.map { it.name }, "query_notes (and so its search operation) is deleted")
        assertTrue(tools.isNotEmpty(), "control: the 3.x tool list is not empty")
    }

    @Test
    fun `S15 an MCP client lists items_read and no query_items`() =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val server = Server(serverInfo = Implementation(name = "ir-server", version = "1.0.0"), options = inMemoryTestServerOptions())
            McpToolAdapter().registerToolsWithServer(server, buildMcpTools(), rig.ctx)
            McpOperationAdapter(buildToolDispatcher(rig.ctx)).register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            val client =
                Client(
                    clientInfo = Implementation(name = "ir-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
                )
            server.createSession(serverTransport)
            client.connect(clientTransport)
            try {
                val names = client.listTools().tools.map { it.name }

                assertTrue("items_read" in names, names.toString())
                assertFalse("query_items" in names, names.toString())
                assertEquals(names.distinct(), names, "no tool is listed twice")
            } finally {
                closeInMemoryPair(client, server)
            }
        }
}

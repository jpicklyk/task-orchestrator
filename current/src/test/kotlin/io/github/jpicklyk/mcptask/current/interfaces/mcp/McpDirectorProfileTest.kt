package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.bootstrap.CurrentMcpServer
import io.github.jpicklyk.mcptask.current.bootstrap.Failed
import io.github.jpicklyk.mcptask.current.bootstrap.Reason
import io.github.jpicklyk.mcptask.current.bootstrap.Started
import io.github.jpicklyk.mcptask.current.bootstrap.StartupOutcome
import io.github.jpicklyk.mcptask.current.bootstrap.buildProfileServer
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.bootstrap.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiAuthMode
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiPrincipal
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiScope
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.twRoot
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.twTree
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item b342d320, R5 director profile) for the third MCP profile over real transports:
 * `/mcp/director` over HTTP through the production wiring ([installMcpStreamableHttp] with a `directorServer`), stdio with
 * `MCP_TOOL_PROFILE=director`, and the real [CurrentMcpServer] for the HTTP mounts and the startup failure message. Scenarios
 * S4, S5, S6, S10, S14 and S15 of the frozen test-plan.
 *
 * Oracles: task-scope AC1 (stdio `MCP_TOOL_PROFILE=director` serves the director set; an unknown value fails startup and the
 * message lists all three wire values), AC3 (the director set is exactly `items_read` get/list/overview/blocked and `deps_read`
 * list/graph), AC4 (`/mcp`, `/mcp/knowledge` and `/mcp/director` exact and case-sensitive through ONE mount call; every other
 * `/mcp/<x>` is 404 at Setup with no fallback to orchestration; a session keeps the profile it was initialized with;
 * `/mcp/director` joins the REST-bearer exemption; Host allowlist and CORS apply exactly as on `/mcp`; a null director server
 * means 404), AC5/AC10 (a member absent from the profile looks absent: `invalid_request` at its pointer, not `forbidden`; the
 * director `instructions` are one sentence naming the profile and `describe` with no tool list), and plan section 3.1 / S12 (the
 * director altitude rule: no note-body read is loaded). Expected sets are written from those clauses; none is read from a run.
 *
 * Negative-assertion conditions: every refusal on the director path is paired with a control in the same application that DOES
 * run the same call on `/mcp` (item moves, tree created, note list answered), so a call that fails for an unrelated reason cannot
 * pass; the bad-path 404s are paired with initialize calls on the three real paths; the 401 on the ops route is paired with a
 * 200 for a valid token; the stdio refusal is paired with the served `tools/list`.
 *
 * Forbidden-construct declaration: the bearer-mode "other `/mcp/<x>` paths are not served" and the NUL-suffixed path assert a
 * non-2xx status and no session id rather than one exact status, because the bearer plugin (401) runs before routing (404) on
 * non-exempt paths and a malformed path may be answered 400 by the engine; every one is a refusal. No assumeTrue, no @Disabled,
 * no swallowed assertions.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class McpDirectorProfileTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val acceptBoth = "application/json, text/event-stream"

    private val initializeBody =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"director-profile-test","version":"1.0"}}}"""

    private val directorTools = setOf("items_read", "deps_read")

    private val knowledgeTools =
        setOf("items_read", "items_write", "notes_read", "notes_write", "deps_read", "deps_write", "docs_read", "docs_write")

    private val orchestrationTools = knowledgeTools + setOf("advance", "config")

    private val directorSelects = setOf("get", "list", "overview", "blocked")

    // ------------------------------------------------------------------ fixtures

    private class Servers(
        val rig: EventLogRig,
        val orchestration: Server,
        val knowledge: Server,
        val director: Server
    )

    private fun newServers(): Servers {
        val rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)
        val registry = ToolRegistry(buildToolSpecs())
        val adapter = McpOperationAdapter(buildToolDispatcher(rig.ctx))

        fun server(profile: ToolProfile): Server =
            buildProfileServer(
                profile = profile,
                serverName = "director-profile-test",
                version = "1.0.0",
                registry = registry,
                operationAdapter = adapter
            )
        return Servers(rig, server(ToolProfile.ORCHESTRATION), server(ToolProfile.KNOWLEDGE), server(ToolProfile.DIRECTOR))
    }

    private fun cfg(vararg env: Pair<String, String>): AppConfig = AppConfig.fromEnv(env = mapOf(*env)::get)

    private fun Application.mountAll(
        s: Servers,
        appConfig: AppConfig = cfg()
    ) = installMcpStreamableHttp(s.orchestration, appConfig, knowledgeServer = s.knowledge, directorServer = s.director)

    private class Reply(
        val status: HttpStatusCode,
        val body: String,
        val sessionId: String?,
        val headers: Map<String, String>
    ) {
        val envelope: JsonObject? =
            runCatching {
                val payload =
                    body
                        .lineSequence()
                        .firstOrNull { it.startsWith("data:") }
                        ?.removePrefix("data:")
                        ?.trim()
                        ?: body.trim()
                Json.parseToJsonElement(payload).jsonObject
            }.getOrNull()

        val result: JsonObject? get() = envelope?.get("result") as? JsonObject

        val toolNames: Set<String>?
            get() = (result?.get("tools") as? JsonArray)?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet()

        fun tool(name: String): JsonObject =
            (result!!["tools"] as JsonArray).map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

        fun toolCallSucceeded(): Boolean {
            val r = result
            return status == HttpStatusCode.OK && r != null && r["isError"]?.jsonPrimitive?.booleanOrNull != true
        }

        val structuredError: JsonObject? get() = (result?.get("structuredContent") as? JsonObject)?.get("error") as? JsonObject

        val instructions: String? get() = result?.get("instructions")?.jsonPrimitive?.contentOrNull
    }

    private suspend fun HttpResponse.reply(): Reply =
        Reply(
            status,
            bodyAsText(),
            headers["mcp-session-id"],
            headers.entries().associate {
                it.key.lowercase() to
                    it.value.joinToString(",")
            }
        )

    private suspend fun HttpClient.rpc(
        path: String,
        body: String,
        session: String? = null,
        host: String? = null,
        origin: String? = null
    ): Reply =
        post(path) {
            header(HttpHeaders.Accept, acceptBoth)
            if (session != null) header("mcp-session-id", session)
            if (host != null) header(HttpHeaders.Host, host)
            if (origin != null) header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.reply()

    private suspend fun HttpClient.handshake(path: String): Pair<String, Reply> {
        val init = rpc(path, initializeBody)
        assertEquals(HttpStatusCode.OK, init.status, "initialize on $path: ${init.body}")
        val session = assertNotNull(init.sessionId, "initialize on $path must assign a session id")
        rpc(path, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", session)
        return session to init
    }

    private suspend fun HttpClient.listTools(
        path: String,
        session: String
    ): Reply = rpc(path, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", session)

    private suspend fun HttpClient.callTool(
        path: String,
        session: String,
        tool: String,
        arguments: String
    ): Reply = rpc(path, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""", session)

    private fun advanceArgs(itemId: UUID) = """{"ops":[{"op":"transition","itemId":"$itemId","trigger":"start"}]}"""

    private suspend fun roleOf(
        rig: EventLogRig,
        id: UUID
    ): Role? =
        rig.raw
            .workItemRepository()
            .getById(id)
            ?.role

    private fun workItemCount(): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM work_items").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun JsonObject.properties(): JsonObject = getValue("inputSchema").jsonObject.getValue("properties").jsonObject

    private fun JsonObject.enumOf(property: String): Set<String> =
        properties()
            .getValue(property)
            .jsonObject
            .getValue("enum")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

    // ------------------------------------------------------------------ S4 the director listing

    @Test
    fun `S4 mcp director lists exactly items_read and deps_read with the four and two selects`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }

            val (session, _) = client.handshake("/mcp/director")
            val listing = client.listTools("/mcp/director", session)
            val names = assertNotNull(listing.toolNames, "tools/list must return a tools result: ${listing.body}")

            assertEquals(directorTools, names, "R5 AC3: exactly the two director tools")
            assertEquals(directorSelects, listing.tool("items_read").enumOf("select"), "items_read director selects")
            assertEquals(setOf("list", "graph"), listing.tool("deps_read").enumOf("select"), "deps_read director selects")
        }
    }

    @Test
    fun `S4 the director rendering carries no field of a hidden select and the other mounts are unchanged`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }

            val (d, _) = client.handshake("/mcp/director")
            val directorItems = client.listTools("/mcp/director", d).tool("items_read")
            assertFalse("query" in directorItems.properties().keys, "search's query field must not leak into the director rendering")
            assertTrue(
                "select" in directorItems.properties().keys && "describe" in directorItems.properties().keys,
                "control: shared fields stay"
            )

            val (o, _) = client.handshake("/mcp")
            val orchestration = client.listTools("/mcp", o)
            assertEquals(orchestrationTools, orchestration.toolNames, "R5 changes no orchestration membership")
            assertTrue("query" in orchestration.tool("items_read").properties().keys, "control: orchestration still offers search's query")
            assertTrue("search" in orchestration.tool("items_read").enumOf("select"))

            val (k, _) = client.handshake("/mcp/knowledge")
            assertEquals(knowledgeTools, client.listTools("/mcp/knowledge", k).toolNames, "R5 changes no knowledge membership")
        }
    }

    // ------------------------------------------------------------------ S6 instructions

    @Test
    fun `S6 director instructions are one sentence naming the profile and describe with no tool list`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }

            val byPath = listOf("/mcp", "/mcp/knowledge", "/mcp/director").associateWith { client.handshake(it).second.instructions }
            val instructions = assertNotNull(byPath.getValue("/mcp/director"), "the director initialize result carries instructions")

            assertTrue("director" in instructions, "names the profile: $instructions")
            assertTrue("describe" in instructions, "points at describe: $instructions")
            val sentences = instructions.trim().split(Regex("(?<=[.!?])\\s+"))
            assertEquals(1, sentences.size, "AC10: one sentence: $instructions")
            assertTrue(instructions.length < 600, "one short sentence, not a manual: ${instructions.length} chars")
            val everyToolName = orchestrationTools + setOf("get_context", "create_work_tree", "query_items", "manage_items")
            for (name in everyToolName) {
                val word = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
                assertFalse(word.containsMatchIn(instructions), "no tool-name list ($name): $instructions")
            }
            assertEquals(3, byPath.values.toSet().size, "the three profiles carry three different instructions: $byPath")
            assertNotEquals(byPath.getValue("/mcp"), instructions)
        }
    }

    // ------------------------------------------------------------------ S10 hidden surfaces

    @Test
    fun `S10 a director session cannot run notes_read, advance or items_write, and nothing changes while orchestration runs them`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }
            val noteItem = s.rig.seed("S10 note holder")
            val advanceTarget = s.rig.seed("S10 advance target")
            val advanceControl = s.rig.seed("S10 advance control")
            val treeArgs = """{"ops":[${twTree(twRoot("S10 hidden tree"))}]}"""

            val (d, _) = client.handshake("/mcp/director")
            val before = workItemCount()
            val notes = client.callTool("/mcp/director", d, "notes_read", """{"select":"list","itemId":"${noteItem.id}"}""")
            assertFalse(notes.toolCallSucceeded(), "notes_read is not a director tool: ${notes.body}")
            val docs = client.callTool("/mcp/director", d, "docs_read", """{"select":"list"}""")
            assertFalse(docs.toolCallSucceeded(), "docs_read is not a director tool: ${docs.body}")
            val advance = client.callTool("/mcp/director", d, "advance", advanceArgs(advanceTarget.id))
            assertFalse(advance.toolCallSucceeded(), "advance is not a director tool: ${advance.body}")
            assertEquals(Role.QUEUE, roleOf(s.rig, advanceTarget.id), "the item must not have moved")
            val tree = client.callTool("/mcp/director", d, "items_write", treeArgs)
            assertFalse(tree.toolCallSucceeded(), "items_write is not a director tool: ${tree.body}")
            assertEquals(before, workItemCount(), "no work item may be created through the director session")

            val (o, _) = client.handshake("/mcp")
            assertTrue(
                client.callTool("/mcp", o, "notes_read", """{"select":"list","itemId":"${noteItem.id}"}""").toolCallSucceeded(),
                "control: notes_read answers on orchestration"
            )
            assertTrue("docs_read" in client.listTools("/mcp", o).toolNames.orEmpty(), "control: docs_read is an orchestration tool")
            assertTrue(client.callTool("/mcp", o, "advance", advanceArgs(advanceControl.id)).toolCallSucceeded(), "control: advance runs")
            assertEquals(Role.WORK, roleOf(s.rig, advanceControl.id), "control: the same call moves an item on orchestration")
            assertTrue(client.callTool("/mcp", o, "items_write", treeArgs).toolCallSucceeded(), "control: the tree op runs")
            assertEquals(before + 1, workItemCount(), "control: the tree op created its root")
        }
    }

    @Test
    fun `S10 the hidden items_read selects are invalid_request at select listing the four director selects, never forbidden`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }
            val item = s.rig.seed("S10 select probe")
            val hidden =
                mapOf(
                    "search" to """{"select":"search","query":"zephyrquux"}""",
                    "context" to """{"select":"context"}""",
                    "history" to """{"select":"history"}""",
                    "next" to """{"select":"next","limit":5}""",
                    "schema" to """{"select":"schema","type":"no-such-type"}""",
                    "related" to """{"select":"related","itemId":"${item.id}"}"""
                )

            val (d, _) = client.handshake("/mcp/director")
            val (o, _) = client.handshake("/mcp")
            for ((select, args) in hidden) {
                val refused = client.callTool("/mcp/director", d, "items_read", args)
                assertFalse(refused.toolCallSucceeded(), "$select must not run on director: ${refused.body}")
                val error = assertNotNull(refused.structuredError, "$select: a tool-level error envelope is expected: ${refused.body}")
                assertEquals("invalid_request", error["code"]?.jsonPrimitive?.content, "$select looks absent, not forbidden: $error")
                assertTrue("/select" in error.toString(), "$select: the error points at /select: $error")
                for (visible in directorSelects) {
                    assertTrue(
                        visible in error.toString(),
                        "$select: the error lists the director select $visible: $error"
                    )
                }

                val control = client.callTool("/mcp", o, "items_read", args)
                assertTrue(control.toolCallSucceeded(), "control: $select is served on orchestration: ${control.body}")
            }
        }
    }

    @Test
    fun `S10 the four director selects run on the director profile`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }
            val item = s.rig.seed("S10 visible probe")

            val (d, _) = client.handshake("/mcp/director")
            for (args in listOf(
                """{"select":"get","itemId":"${item.id}"}""",
                """{"select":"list"}""",
                """{"select":"overview"}""",
                """{"select":"blocked"}"""
            )) {
                val result = client.callTool("/mcp/director", d, "items_read", args)
                assertTrue(result.toolCallSucceeded(), "$args must run on director: ${result.body}")
            }
            val get = client.callTool("/mcp/director", d, "items_read", """{"select":"get","itemId":"${item.id}"}""")
            assertTrue("S10 visible probe" in get.body, "the director get returns the item: ${get.body}")
        }
    }

    @Test
    fun `S10 describe narrowed to a hidden select is invalid_request on director while a visible one and orchestration answer`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }

            val (d, _) = client.handshake("/mcp/director")
            val narrowedHidden = client.callTool("/mcp/director", d, "items_read", """{"describe":true,"select":"search"}""")
            assertFalse(narrowedHidden.toolCallSucceeded(), "describe of a hidden select must not answer: ${narrowedHidden.body}")
            assertEquals(
                "invalid_request",
                narrowedHidden.structuredError
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content,
                "describe of a hidden select looks absent: ${narrowedHidden.body}"
            )

            val narrowedVisible = client.callTool("/mcp/director", d, "items_read", """{"describe":true,"select":"get"}""")
            assertTrue(narrowedVisible.toolCallSucceeded(), "control: describe of a director select answers: ${narrowedVisible.body}")

            val full = client.callTool("/mcp/director", d, "items_read", """{"describe":true}""")
            assertTrue(full.toolCallSucceeded(), "control: an unnarrowed describe answers: ${full.body}")
            val data = full.result!!["structuredContent"]!!.jsonObject["data"]!!.jsonObject
            assertEquals(
                directorSelects,
                data["operations"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet(),
                "the director describe lists the four director selects and no hidden one"
            )
            val described =
                data["inputSchema"]!!
                    .jsonObject["properties"]!!
                    .jsonObject["select"]!!
                    .jsonObject["enum"]!!
                    .jsonArray
            assertEquals(
                directorSelects,
                described
                    .map {
                        it.jsonPrimitive.content
                    }.toSet(),
                "the described input schema offers the director selects only"
            )

            val (o, _) = client.handshake("/mcp")
            val orchestration = client.callTool("/mcp", o, "items_read", """{"describe":true,"select":"search"}""")
            assertTrue(orchestration.toolCallSucceeded(), "control: describe of search answers on orchestration: ${orchestration.body}")
        }
    }

    // ------------------------------------------------------------------ S15 session replay

    @Test
    fun `S15 a director session id replayed on mcp or mcp knowledge never lists or runs notes_read`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }
            val item = s.rig.seed("S15 note holder")
            val advanceTarget = s.rig.seed("S15 advance target")

            val (d, _) = client.handshake("/mcp/director")
            val (o, _) = client.handshake("/mcp")
            val (k, _) = client.handshake("/mcp/knowledge")

            for (path in listOf("/mcp", "/mcp/knowledge")) {
                val replayedList = client.listTools(path, d)
                val listed = replayedList.toolNames.orEmpty()
                assertTrue(
                    listed.all {
                        it in directorTools
                    },
                    "a director session replayed on $path must not list more than the director set: ${replayedList.body}"
                )

                val replayedNotes = client.callTool(path, d, "notes_read", """{"select":"list","itemId":"${item.id}"}""")
                assertFalse(
                    replayedNotes.toolCallSucceeded(),
                    "notes_read must not run through a director session on $path: ${replayedNotes.body}"
                )
            }
            val replayedAdvance = client.callTool("/mcp", d, "advance", advanceArgs(advanceTarget.id))
            assertFalse(replayedAdvance.toolCallSucceeded(), "advance must not run through a director session: ${replayedAdvance.body}")
            assertEquals(Role.QUEUE, roleOf(s.rig, advanceTarget.id), "the item must not have moved")

            // Controls: the other sessions work on their own paths and the director session is intact on its own.
            assertTrue(
                client.callTool("/mcp", o, "notes_read", """{"select":"list","itemId":"${item.id}"}""").toolCallSucceeded(),
                "control: notes_read runs in an orchestration session"
            )
            assertTrue(
                client.callTool("/mcp/knowledge", k, "notes_read", """{"select":"list","itemId":"${item.id}"}""").toolCallSucceeded(),
                "control: notes_read runs in a knowledge session"
            )
            assertEquals(directorTools, client.listTools("/mcp/director", d).toolNames, "control: the director session is intact")
        }
    }

    @Test
    fun `S15 a director session keeps the director set for its whole life`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }
            val (d, _) = client.handshake("/mcp/director")
            client.handshake("/mcp")
            repeat(3) { assertEquals(directorTools, client.listTools("/mcp/director", d).toolNames, "listing $it") }
            assertFalse(
                client
                    .callTool(
                        "/mcp/director",
                        d,
                        "notes_read",
                        """{"select":"list","itemId":"${UUID.randomUUID()}"}"""
                    ).toolCallSucceeded(),
                "even after other sessions initialized, a director session never gains notes_read"
            )
        }
    }

    // ------------------------------------------------------------------ S14 path exactness and mount

    @Test
    fun `S14 sibling paths of mcp director are 404 with no session while the three real paths initialize`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }

            for (path in listOf("/mcp", "/mcp/knowledge", "/mcp/director")) {
                assertEquals(HttpStatusCode.OK, client.rpc(path, initializeBody).status, "control: $path initializes")
            }
            for (path in listOf(
                "/mcp/Director",
                "/mcp/DIRECTOR",
                "/mcp/director/x",
                "/mcp/directors",
                "/mcp/director2",
                "/mcp/dir",
                "/mcp/orchestration"
            )) {
                val reply = client.rpc(path, initializeBody)
                assertEquals(HttpStatusCode.NotFound, reply.status, "$path must be 404, never an orchestration fallback: ${reply.body}")
                assertNull(reply.sessionId, "$path must not issue a session")
                assertNull(reply.result, "$path must not return an initialize result")
            }
        }
    }

    @Test
    fun `S14 a NUL-suffixed or space-suffixed director path is refused and issues no session`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }

            assertEquals(HttpStatusCode.OK, client.rpc("/mcp/director", initializeBody).status, "control: the exact path initializes")
            for (path in listOf("/mcp/director%00", "/mcp/director%20")) {
                val reply = client.rpc(path, initializeBody)
                assertTrue(reply.status.value !in 200..299, "$path must not be served: ${reply.status} ${reply.body}")
                assertNull(reply.sessionId, "$path must not issue a session")
            }
        }
    }

    @Test
    fun `S14 when no director server is passed the director path is not mounted and the others are unaffected`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }

            val absent = client.rpc("/mcp/director", initializeBody)
            assertEquals(HttpStatusCode.NotFound, absent.status, "a null directorServer means /mcp/director is not mounted: ${absent.body}")
            assertNull(absent.sessionId)
            val (k, _) = client.handshake("/mcp/knowledge")
            assertEquals(knowledgeTools, client.listTools("/mcp/knowledge", k).toolNames, "knowledge is unaffected")
            val (o, _) = client.handshake("/mcp")
            assertEquals(orchestrationTools, client.listTools("/mcp", o).toolNames, "/mcp is unaffected")
        }
    }

    @Test
    fun `S14 a director server without a knowledge server mounts director only`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), directorServer = s.director) }

            val (d, _) = client.handshake("/mcp/director")
            assertEquals(directorTools, client.listTools("/mcp/director", d).toolNames)
            val absent = client.rpc("/mcp/knowledge", initializeBody)
            assertEquals(HttpStatusCode.NotFound, absent.status, "a null knowledgeServer means /mcp/knowledge is not mounted")
            assertNull(absent.sessionId)
        }
    }

    // ------------------------------------------------------------------ S14 REST bearer exemption

    private val bearerToken = "director-rest-bearer-token"

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun Application.wireBearer(s: Servers) {
        val principal =
            ApiPrincipal(
                tokenId = "director-test",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER
            )
        val key = HashBytes(sha256(bearerToken))
        val appConfig = cfg()
        mountAll(s, appConfig)
        installRestApiRoutes(
            apiConfig = ApiAuthConfig.Bearer(tokens = mapOf(key to principal)),
            eventBus = null,
            effectiveProvider = s.rig.ctx.repositoryProvider,
            apiTokenEntries = mapOf(key to BearerTokenStore.TokenEntry(principal, expiresAt = null)),
            allowQueryToken = false,
            serverName = "director-profile-test",
            serverVersion = "1.0.0",
            actorAuthEnabled = s.rig.composition.actorAuthEnabled,
            serverConfig = s.rig.composition.serverConfig,
            toolContext = s.rig.ctx,
            degradedModePolicy = s.rig.composition.degradedModePolicy,
            appConfig = appConfig
        )
    }

    @Test
    fun `S14 with the REST API in bearer mode mcp director is served without a token while the ops route still answers 401`() {
        val s = newServers()
        testApplication {
            application { wireBearer(s) }

            val (session, init) = client.handshake("/mcp/director")
            assertEquals(HttpStatusCode.OK, init.status, "initialize on /mcp/director needs no REST bearer")
            assertEquals(
                directorTools,
                client.listTools("/mcp/director", session).toolNames,
                "the whole director session works without a token"
            )

            val (o, _) = client.handshake("/mcp")
            assertEquals(orchestrationTools, client.listTools("/mcp", o).toolNames, "control: /mcp stays open too")

            val ops =
                client.post("/api/v1/ops/items_read") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"select":"list"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, ops.status, "the REST ops route must still require a bearer token")
            val authorised =
                client.post("/api/v1/ops/items_read") {
                    header(HttpHeaders.Authorization, "Bearer $bearerToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"select":"list"}""")
                }
            assertEquals(HttpStatusCode.OK, authorised.status, "control: the same route answers 200 with the token")
        }
    }

    @Test
    fun `S14 the bearer exemption is exact - no sibling of mcp director is reachable without a token`() {
        val s = newServers()
        testApplication {
            application { wireBearer(s) }

            for (path in listOf("/mcp/Director", "/mcp/director/x", "/mcp/directors", "/mcp/dir")) {
                val reply = client.rpc(path, initializeBody)
                assertTrue(reply.status.value !in 200..299, "$path must not be served: ${reply.status} ${reply.body}")
                assertNull(reply.sessionId, "$path must not issue a session")
            }
            assertEquals(
                HttpStatusCode.OK,
                client.rpc("/mcp/director", initializeBody).status,
                "control: the exact director path is served"
            )
        }
    }

    // ------------------------------------------------------------------ S14 Host, Origin and CORS parity

    private val rebindHost = "attacker.example:3001"

    @Test
    fun `S14 a non-allowlisted Host or a foreign Origin is refused on mcp director exactly as on mcp`() {
        val s = newServers()
        testApplication {
            application { mountAll(s) }

            val orchestration = client.rpc("/mcp", initializeBody, host = rebindHost)
            val director = client.rpc("/mcp/director", initializeBody, host = rebindHost)
            assertEquals(HttpStatusCode.Forbidden, orchestration.status, "baseline: /mcp refuses a rebinding Host")
            assertEquals(orchestration.status, director.status, "/mcp/director must refuse the same Host: ${director.body}")
            assertTrue("MCP_ALLOWED_HOSTS" in director.body, "the refusal names MCP_ALLOWED_HOSTS: ${director.body}")
            assertNull(director.sessionId)
            assertEquals(
                HttpStatusCode.OK,
                client.rpc("/mcp/director", initializeBody, host = "localhost:3001").status,
                "control: loopback Host"
            )

            for (origin in listOf("http://evil.example.com", "http://attacker.example:3001")) {
                val o = client.rpc("/mcp", initializeBody, origin = origin)
                val d = client.rpc("/mcp/director", initializeBody, origin = origin)
                assertEquals(HttpStatusCode.Forbidden, o.status, "baseline: /mcp refuses Origin $origin")
                assertEquals(o.status, d.status, "/mcp/director must refuse Origin $origin: ${d.body}")
                assertNull(d.sessionId)
            }
            assertEquals(HttpStatusCode.OK, client.rpc("/mcp/director", initializeBody).status, "control: no Origin is served")
        }
    }

    @Test
    fun `S14 the configured host allowlist and CORS handling extend to mcp director`() {
        val s = newServers()
        testApplication {
            application {
                mountAll(s, cfg("MCP_ALLOWED_HOSTS" to "tohost.lan, box.lan:8443", "CORS_ALLOWED_ORIGINS" to "https://dash.example.com"))
            }

            for (host in listOf("TOHOST.lan:9999", "box.lan:8443")) {
                assertEquals(HttpStatusCode.OK, client.rpc("/mcp", initializeBody, host = host).status, "baseline: /mcp serves $host")
                assertEquals(
                    HttpStatusCode.OK,
                    client.rpc("/mcp/director", initializeBody, host = host).status,
                    "/mcp/director serves $host"
                )
            }
            assertEquals(HttpStatusCode.Forbidden, client.rpc("/mcp", initializeBody, host = "box.lan:8444").status, "baseline: wrong port")
            assertEquals(HttpStatusCode.Forbidden, client.rpc("/mcp/director", initializeBody, host = "box.lan:8444").status, "wrong port")

            for (origin in listOf("https://dash.example.com", "http://evil.example.com")) {
                val o = client.rpc("/mcp", initializeBody, origin = origin)
                val d = client.rpc("/mcp/director", initializeBody, origin = origin)
                assertEquals(o.status, d.status, "POST with Origin $origin: same status on both paths")
                assertEquals(
                    o.headers["access-control-allow-origin"],
                    d.headers["access-control-allow-origin"],
                    "same CORS allow-origin for $origin"
                )
            }

            suspend fun preflight(path: String): Reply =
                client
                    .request(path) {
                        method = HttpMethod.Options
                        header(HttpHeaders.Origin, "https://dash.example.com")
                        header(HttpHeaders.AccessControlRequestMethod, "POST")
                        header(HttpHeaders.AccessControlRequestHeaders, "content-type")
                    }.reply()
            val o = preflight("/mcp")
            val d = preflight("/mcp/director")
            assertEquals(o.status, d.status, "preflight status must match")
            assertEquals(o.headers["access-control-allow-origin"], d.headers["access-control-allow-origin"], "preflight allow-origin")
            assertEquals(o.headers["access-control-allow-methods"], d.headers["access-control-allow-methods"], "preflight allow-methods")
        }
    }

    // ------------------------------------------------------------------ real server: stdio, HTTP mounts, startup failure

    private fun appConfigFor(
        name: String,
        transport: String,
        profile: String?,
        extra: Map<String, String> = emptyMap()
    ): AppConfig {
        val home = dir.resolve(name).also { Files.createDirectories(it) }
        val agent = home.resolve("agent").also { Files.createDirectories(it) }
        val env =
            buildMap {
                put("DATABASE_PATH", home.resolve("profile.db").toString())
                put("MCP_TRANSPORT", transport)
                put("READINESS_FILE", home.resolve("ready").toString())
                put("AGENT_CONFIG_DIR", agent.toString())
                if (profile != null) put("MCP_TOOL_PROFILE", profile)
                putAll(extra)
            }
        return AppConfig.fromEnv { key -> env[key] }
    }

    private class Run(
        val thread: Thread,
        val outcome: AtomicReference<Any?>,
        val failure: AtomicReference<Throwable?>
    )

    private fun startRun(server: CurrentMcpServer): Run {
        val outcome = AtomicReference<Any?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val thread =
            Thread {
                try {
                    outcome.set(server.run())
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
        thread.isDaemon = true
        thread.name = "director-profile-test-run"
        thread.start()
        return Run(thread, outcome, failure)
    }

    private class StdioSession(
        val run: Run,
        val pipeOut: PipedOutputStream,
        val out: ByteArrayOutputStream
    )

    private fun startStdio(
        name: String,
        profile: String?
    ): StdioSession {
        val pipeIn = PipedInputStream(65_536)
        val pipeOut = PipedOutputStream(pipeIn)
        val out = ByteArrayOutputStream()
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = ShutdownCoordinator(),
                appConfig = appConfigFor(name, "stdio", profile),
                stdioInput = { pipeIn },
                stdioOutput = { out }
            )
        return StdioSession(startRun(server), pipeOut, out)
    }

    private fun StdioSession.send(line: String) {
        pipeOut.write((line + "\n").toByteArray(Charsets.UTF_8))
        pipeOut.flush()
    }

    private fun StdioSession.responseWithId(id: Int): JsonObject? =
        String(out.toByteArray(), Charsets.UTF_8)
            .lineSequence()
            .filter { it.startsWith("{") }
            .mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .firstOrNull { it["id"]?.jsonPrimitive?.intOrNull == id }

    private fun StdioSession.awaitResponse(id: Int): JsonObject {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            responseWithId(id)?.let { return it }
            if (!run.thread.isAlive) break
            Thread.sleep(50)
        }
        throw AssertionError(
            "no response with id $id; run alive=${run.thread.isAlive} outcome=${run.outcome.get()} failure=${run.failure.get()}"
        )
    }

    private fun StdioSession.finish() {
        pipeOut.close()
        run.thread.join(20_000)
        assertFalse(run.thread.isAlive, "run() must return once stdin closes")
        assertNull(run.failure.get(), "run() threw: ${run.failure.get()}")
        assertEquals(Started, run.outcome.get(), "stdin EOF is a clean shutdown")
    }

    @Test
    fun `S5 stdio with MCP_TOOL_PROFILE director serves exactly the director set and refuses notes_read`() {
        val session = startStdio("s5-director", "director")
        session.send(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",""" +
                """"capabilities":{},"clientInfo":{"name":"director-stdio-test","version":"1"}}}"""
        )
        val init = session.awaitResponse(1)
        session.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        session.send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val listing = session.awaitResponse(2)
        session.send(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"notes_read","arguments":""" +
                """{"select":"list","itemId":"${UUID.randomUUID()}"}}}"""
        )
        val refused = session.awaitResponse(3)
        session.send(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"items_read","arguments":{"select":"list"}}}"""
        )
        val control = session.awaitResponse(4)
        session.finish()

        val tools = (listing["result"]!!.jsonObject["tools"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(directorTools, tools, "AC1: stdio with MCP_TOOL_PROFILE=director serves the director set")
        val instructions = init["result"]!!.jsonObject["instructions"]?.jsonPrimitive?.contentOrNull
        assertTrue("director" in instructions.orEmpty(), "instructions name the profile: $instructions")

        val refusedAsError =
            refused["error"] != null ||
                refused["result"]
                    ?.jsonObject
                    ?.get("isError")
                    ?.jsonPrimitive
                    ?.booleanOrNull == true
        assertTrue(refusedAsError, "notes_read must not run on the director stdio profile: $refused")
        val controlResult = control["result"]?.jsonObject
        assertNotNull(controlResult, "control: items_read list is answered: $control")
        assertNotEquals(true, controlResult["isError"]?.jsonPrimitive?.booleanOrNull, "control: items_read list succeeds: $control")
    }

    private fun runWithHook(
        name: String,
        transport: String,
        profile: String?
    ): Pair<StartupOutcome, List<String>> {
        val hookCalls = mutableListOf<String>()
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = ShutdownCoordinator(),
                appConfig = appConfigFor(name, transport, profile, mapOf("MCP_HTTP_HOST" to "127.0.0.1", "MCP_HTTP_PORT" to "0")),
                onBeforeTransportStart = {
                    hookCalls += it
                    throw IllegalStateException("stop-at-transport")
                },
                stdioInput = { java.io.ByteArrayInputStream(ByteArray(0)) },
                stdioOutput = { ByteArrayOutputStream() }
            )
        return server.run() to hookCalls
    }

    @Test
    fun `S1 an unknown MCP_TOOL_PROFILE fails startup on both transports and the message lists all three wire values`() {
        for (transport in listOf("stdio", "http")) {
            val (outcome, hookCalls) = runWithHook("s1-$transport", transport, "directors")
            assertTrue(outcome is Failed, "expected Failed, got $outcome")
            assertEquals(Reason.UNKNOWN_TOOL_PROFILE, outcome.reason, "detail: ${outcome.detail}")
            assertTrue(
                "MCP_TOOL_PROFILE" in outcome.detail && "directors" in outcome.detail,
                "names the variable and the value: ${outcome.detail}"
            )
            for (wire in listOf("knowledge", "orchestration", "director")) {
                assertTrue(wire in outcome.detail, "the valid values include '$wire': ${outcome.detail}")
            }
            assertEquals(emptyList(), hookCalls, "the failure comes before any transport starts")
        }
        val (control, controlHook) = runWithHook("s1-control", "stdio", "director")
        assertTrue(
            control is Failed && control.reason == Reason.TRANSPORT_START,
            "control: director is a valid value and reaches the transport stage: $control"
        )
        assertEquals(listOf("stdio"), controlHook)
    }

    private class HttpReply(
        val status: Int,
        val body: String,
        val session: String?
    ) {
        val result: JsonObject? =
            runCatching {
                val payload =
                    body
                        .lineSequence()
                        .firstOrNull { it.startsWith("data:") }
                        ?.removePrefix("data:")
                        ?.trim()
                        ?: body.trim()
                Json.parseToJsonElement(payload).jsonObject["result"]?.jsonObject
            }.getOrNull()
    }

    private fun post(
        client: java.net.http.HttpClient,
        port: Int,
        path: String,
        body: String,
        session: String? = null
    ): HttpReply {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .apply { if (session != null) header("mcp-session-id", session) }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = client.send(request, BodyHandlers.ofString())
        return HttpReply(response.statusCode(), response.body(), response.headers().firstValue("mcp-session-id").orElse(null))
    }

    private fun toolsAt(
        client: java.net.http.HttpClient,
        port: Int,
        path: String
    ): Set<String> {
        val init = post(client, port, path, initializeBody)
        assertEquals(200, init.status, "initialize on $path: ${init.body}")
        val session = assertNotNull(init.session, "session id for $path")
        post(client, port, path, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", session)
        val listing = post(client, port, path, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", session)
        assertEquals(200, listing.status, "tools/list on $path: ${listing.body}")
        return (listing.result!!["tools"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
    }

    @Test
    fun `S4 the real server mounts the three profiles on http whatever MCP_TOOL_PROFILE says`() {
        val port = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        val coordinator = ShutdownCoordinator()
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = coordinator,
                appConfig =
                    appConfigFor("s4-http", "http", "director", mapOf("MCP_HTTP_HOST" to "127.0.0.1", "MCP_HTTP_PORT" to port.toString()))
            )
        val run = startRun(server)
        try {
            val ready = dir.resolve("s4-http").resolve("ready")
            val deadline = System.currentTimeMillis() + 90_000
            while (!Files.exists(ready) && System.currentTimeMillis() < deadline && run.thread.isAlive) Thread.sleep(50)
            assertTrue(Files.exists(ready), "http server must become ready; outcome=${run.outcome.get()} failure=${run.failure.get()}")

            val client =
                java.net.http.HttpClient
                    .newHttpClient()
            assertEquals(orchestrationTools, toolsAt(client, port, "/mcp"), "/mcp is orchestration even when MCP_TOOL_PROFILE=director")
            assertEquals(knowledgeTools, toolsAt(client, port, "/mcp/knowledge"), "/mcp/knowledge is knowledge")
            assertEquals(directorTools, toolsAt(client, port, "/mcp/director"), "/mcp/director is the director profile")
            assertEquals(404, post(client, port, "/mcp/Director", initializeBody).status, "the director path is case-sensitive")
            assertEquals(404, post(client, port, "/mcp/directors", initializeBody).status, "a sibling of the director path is not served")
        } finally {
            coordinator.initiateShutdown("test")
            coordinator.awaitCompletion(20_000)
            run.thread.join(20_000)
        }
        assertFalse(run.thread.isAlive, "run() must return after shutdown")
        assertNull(run.failure.get(), "run() threw: ${run.failure.get()}")
    }
}

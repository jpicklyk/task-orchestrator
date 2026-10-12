package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.twRoot
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.twTree
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f6d23c40, Q29 tool profiles) for the two-profile HTTP mount: `/mcp` serves the
 * orchestration profile and `/mcp/knowledge` the knowledge profile, both from one Application through the production wiring
 * ([installMcpStreamableHttp] with a `knowledgeServer`, [installRestApiRoutes] for the bearer cases), over real HTTP through
 * Ktor's test engine. Scenarios S1, S2, S5, S7, S8, S9, S10, S11 of the frozen test-plan.
 *
 * Oracles: plans/v4-phase2-core.md section 2 (profile visibility), section 3.1 (the eleven-tool orchestration surface, the
 * eight-tool knowledge surface, the one-sentence per-profile instructions), amendment U23 (the knowledge profile hides
 * `create_tree` and `complete_tree`), the task-scope acceptance criteria AC1, AC2, AC3, AC6 and decisions D3 and D4 (exact,
 * case-sensitive knowledge path; everything else under `/mcp/` is 404), the existing `/mcp` exemption from the REST bearer
 * (McpRestAuthBypassTest) and the existing Host and Origin behaviour of `/mcp` (DnsRebindingHostAllowlistTest,
 * McpStreamableHttpTransportTest), which `/mcp/knowledge` must match exactly. Expected tool-name sets are written from the plan
 * text; none is read from a run.
 *
 * Negative-assertion conditions (what would make each fail without the feature):
 *  - S2 names set: a knowledge server that also registered the 3.x leftovers or `advance` lists them.
 *  - S7/S11: the advance call is paired with a control call on an orchestration session that DOES move the same kind of item,
 *    and the create_tree rejection is paired with a control that creates a root.
 *  - S9: the bad paths are paired with initialize calls on `/mcp` and `/mcp/knowledge` in the same application.
 *  - S8: the 401 on the ops route is paired with a 200 for a valid token on a REST route in the same application.
 *
 * Forbidden-construct declaration: S8's "other `/mcp/<x>` paths in bearer mode are not served" asserts a non-2xx status and no
 * session id rather than one exact status, because the bearer plugin (401) runs before routing (404) on non-exempt paths; both
 * are refusals. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class McpProfileHttpTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val acceptBoth = "application/json, text/event-stream"

    private val initializeBody =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"profile-http-test","version":"1.0"}}}"""

    private val knowledgeTools =
        setOf("items_read", "items_write", "notes_read", "notes_write", "deps_read", "deps_write", "docs_read", "docs_write")

    private val orchestrationTools = knowledgeTools + setOf("advance", "config")

    private val orchestrationOnly = orchestrationTools - knowledgeTools

    // ------------------------------------------------------------------ fixtures

    private class Servers(
        val rig: EventLogRig,
        val orchestration: Server,
        val knowledge: Server
    )

    private fun newServers(): Servers {
        val rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)
        val registry = ToolRegistry(buildToolSpecs())
        val adapter = McpOperationAdapter(buildToolDispatcher(rig.ctx))
        val legacy = McpToolAdapter()

        fun server(profile: ToolProfile): Server =
            buildProfileServer(
                profile = profile,
                serverName = "profile-http-test",
                version = "1.0.0",
                registry = registry,
                operationAdapter = adapter,
                legacyAdapter = legacy,
                legacyTools = buildMcpTools(),
                toolContext = rig.ctx
            )
        return Servers(rig, server(ToolProfile.ORCHESTRATION), server(ToolProfile.KNOWLEDGE))
    }

    private fun cfg(vararg env: Pair<String, String>): AppConfig = AppConfig.fromEnv(env = mapOf(*env)::get)

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

        /** Tool names of a tools/list reply, or null when the reply is not a tools/list result. */
        val toolNames: Set<String>?
            get() = (result?.get("tools") as? JsonArray)?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet()

        fun tool(name: String): JsonObject =
            (result!!["tools"] as JsonArray).map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

        /** True only for HTTP 200 carrying a JSON-RPC result that is not a tool error. */
        fun toolCallSucceeded(): Boolean {
            val r = result
            return status == HttpStatusCode.OK && r != null && r["isError"]?.jsonPrimitive?.booleanOrNull != true
        }

        /** The structured error object of a tool-call result, if any. */
        val structuredError: JsonObject? get() = (result?.get("structuredContent") as? JsonObject)?.get("error") as? JsonObject
    }

    private suspend fun HttpResponse.reply(): Reply =
        Reply(
            status,
            bodyAsText(),
            headers["mcp-session-id"],
            headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
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

    /** initialize + notifications/initialized on [path]; returns the session id and the initialize reply. */
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
    ): Reply =
        rpc(
            path,
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
            session
        )

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

    private fun JsonObject.selectEnum(): List<String> =
        getValue("inputSchema")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("select")
            .jsonObject
            .getValue("enum")
            .jsonArray
            .map { it.jsonPrimitive.content }

    // ------------------------------------------------------------------ S1 / S2 / S5

    @Test
    fun `S1 mcp lists the eleven orchestration tools and both paths mount in one application`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }

            val (session, _) = client.handshake("/mcp")
            val names = assertNotNull(client.listTools("/mcp", session).toolNames, "tools/list must return a tools result")

            assertEquals(orchestrationTools, names, "plan 3.1: the knowledge tools plus advance and config")
            assertTrue("advance" in names, "advance is orchestration-only and must be served on /mcp")
        }
    }

    @Test
    fun `S1 orchestration control - select context next blocked and the tree ops are offered on mcp`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }
            val (session, _) = client.handshake("/mcp")
            val listing = client.listTools("/mcp", session)

            val selects = listing.tool("items_read").selectEnum()
            assertTrue(selects.containsAll(listOf("context", "next", "blocked")), "orchestration offers context/next/blocked: $selects")
            val write = listing.tool("items_write").getValue("inputSchema").toString()
            assertTrue("create_tree" in write && "complete_tree" in write, "orchestration offers both tree ops")
        }
    }

    @Test
    fun `S2 mcp knowledge lists exactly the eight knowledge tools with the orchestration-only selects and ops hidden`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }

            val (session, _) = client.handshake("/mcp/knowledge")
            val listing = client.listTools("/mcp/knowledge", session)
            val names = assertNotNull(listing.toolNames, "tools/list must return a tools result: ${listing.body}")

            assertEquals(knowledgeTools, names, "plan 2 / W2 3.1: exactly the eight knowledge tools")
            orchestrationOnly.forEach { assertFalse(it in names, "$it must not be listed on /mcp/knowledge") }

            val selects = listing.tool("items_read").selectEnum()
            assertTrue("get" in selects && "list" in selects, "control: the knowledge selects are still offered: $selects")
            listOf("context", "next", "blocked").forEach { assertFalse(it in selects, "items_read select $it must be hidden: $selects") }

            val write = listing.tool("items_write").getValue("inputSchema").toString()
            assertTrue("update" in write, "control: ordinary ops are still offered")
            assertFalse("create_tree" in write, "U23: create_tree is hidden from knowledge")
            assertFalse("complete_tree" in write, "U23: complete_tree is hidden from knowledge")
        }
    }

    @Test
    fun `S5 initialize instructions name the profile mention describe and list no tool names`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }

            val byProfile =
                listOf(
                    ToolProfile.ORCHESTRATION to client.handshake("/mcp").second,
                    ToolProfile.KNOWLEDGE to client.handshake("/mcp/knowledge").second
                )
            val texts = mutableListOf<String>()
            for ((profile, init) in byProfile) {
                val instructions =
                    assertNotNull(
                        init.result
                            ?.get("instructions")
                            ?.jsonPrimitive
                            ?.contentOrNull,
                        "instructions for $profile"
                    )
                texts += instructions
                assertTrue(profile.wire in instructions, "instructions must name the profile '${profile.wire}': $instructions")
                assertTrue("describe" in instructions, "instructions must point at describe: $instructions")
                assertFalse("v3" in instructions.lowercase(), "no v3 reference: $instructions")
                val allNames = orchestrationTools + setOf("get_context", "create_work_tree", "query_items", "manage_items")
                allNames.forEach { name ->
                    val word = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
                    assertFalse(word.containsMatchIn(instructions), "no tool-name list ($name): $instructions")
                }
                assertTrue(instructions.length < 600, "one short sentence, not a manual: ${instructions.length} chars")
            }
            assertNotEquals(texts[0], texts[1], "the two profiles carry different instructions")
        }
    }

    // ------------------------------------------------------------------ S7 / S11 hidden surfaces cannot be executed

    @Test
    fun `S7 a knowledge session refuses select context as invalid_request at select and orchestration answers the same call`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }
            val item = s.rig.seed("S7 probe")

            val (k, _) = client.handshake("/mcp/knowledge")
            val refused = client.callTool("/mcp/knowledge", k, "items_read", """{"select":"context","itemId":"${item.id}"}""")
            assertEquals(false, refused.toolCallSucceeded(), "context must not run on knowledge: ${refused.body}")
            val error = assertNotNull(refused.structuredError, "a tool-level error envelope is expected: ${refused.body}")
            assertEquals("invalid_request", error["code"]?.jsonPrimitive?.content, "hidden select looks absent, not forbidden: $error")
            assertTrue("/select" in error.toString(), "the error points at /select: $error")

            val (o, _) = client.handshake("/mcp")
            val control = client.callTool("/mcp", o, "items_read", """{"select":"context","itemId":"${item.id}"}""")
            assertTrue(control.toolCallSucceeded(), "control: the same call is served on the orchestration profile: ${control.body}")
        }
    }

    @Test
    fun `S7 a knowledge session cannot run advance and the item role is unchanged while orchestration moves its item`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }
            val target = s.rig.seed("S7 advance target")
            val controlItem = s.rig.seed("S7 advance control")

            val (k, _) = client.handshake("/mcp/knowledge")
            val refused = client.callTool("/mcp/knowledge", k, "advance", advanceArgs(target.id))
            assertEquals(false, refused.toolCallSucceeded(), "advance must not run on knowledge: ${refused.body}")
            assertEquals(Role.QUEUE, roleOf(s.rig, target.id), "the item must not have moved")

            val (o, _) = client.handshake("/mcp")
            val control = client.callTool("/mcp", o, "advance", advanceArgs(controlItem.id))
            assertTrue(control.toolCallSucceeded(), "control: advance runs on orchestration: ${control.body}")
            assertEquals(Role.WORK, roleOf(s.rig, controlItem.id), "control: the same call moves an item on orchestration")
        }
    }

    @Test
    fun `S7 a knowledge session cannot reach the 3x leftovers or the tree ops`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }
            val root = s.rig.seed("S7 rules root")
            val treeArgs = """{"ops":[${twTree(twRoot("Hidden tree"))}]}"""

            val (k, _) = client.handshake("/mcp/knowledge")
            val before = workItemCount()
            val rules = client.callTool("/mcp/knowledge", k, "query_rules", """{"operation":"list","rootId":"${root.id}"}""")
            assertEquals(false, rules.toolCallSucceeded(), "query_rules is orchestration-only: ${rules.body}")
            val config = client.callTool("/mcp/knowledge", k, "manage_project_config", """{"operation":"get","rootId":"${root.id}"}""")
            assertEquals(false, config.toolCallSucceeded(), "manage_project_config is orchestration-only: ${config.body}")
            val tree = client.callTool("/mcp/knowledge", k, "items_write", treeArgs)
            assertEquals(false, tree.toolCallSucceeded(), "create_tree is hidden from knowledge: ${tree.body}")
            assertEquals(before, workItemCount(), "no work item may be created by the hidden tree op")

            val (o, _) = client.handshake("/mcp")
            val control = client.callTool("/mcp", o, "items_write", treeArgs)
            assertTrue(control.toolCallSucceeded(), "control: create_tree runs on orchestration: ${control.body}")
            assertEquals(before + 1, workItemCount(), "control: the tree op created its root")
        }
    }

    @Test
    fun `S11 a knowledge session id replayed on mcp never lists or runs orchestration-only tools`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }
            val target = s.rig.seed("S11 advance target")

            val (k, _) = client.handshake("/mcp/knowledge")
            val (o, _) = client.handshake("/mcp")

            val replayedList = client.listTools("/mcp", k)
            val listed = replayedList.toolNames.orEmpty()
            orchestrationOnly.forEach {
                assertFalse(
                    it in listed,
                    "replayed knowledge session must not list $it on /mcp: ${replayedList.body}"
                )
            }

            val replayedCall = client.callTool("/mcp", k, "advance", advanceArgs(target.id))
            assertEquals(false, replayedCall.toolCallSucceeded(), "advance must not run through a knowledge session: ${replayedCall.body}")
            assertEquals(Role.QUEUE, roleOf(s.rig, target.id), "the item must not have moved")

            // Controls: the orchestration session lists advance and the knowledge session still works on its own path.
            assertTrue("advance" in client.listTools("/mcp", o).toolNames.orEmpty(), "control: orchestration session lists advance")
            assertEquals(
                knowledgeTools,
                client.listTools("/mcp/knowledge", k).toolNames,
                "control: the knowledge session is intact on its own path"
            )
        }
    }

    // ------------------------------------------------------------------ S9 path exactness

    @Test
    fun `S9 sibling paths under mcp are 404 with no session while the two real paths initialize`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }

            assertEquals(HttpStatusCode.OK, client.rpc("/mcp", initializeBody).status, "control: /mcp initializes")
            assertEquals(HttpStatusCode.OK, client.rpc("/mcp/knowledge", initializeBody).status, "control: /mcp/knowledge initializes")

            for (path in listOf(
                "/mcp/orchestration",
                "/mcp/Knowledge",
                "/mcp/KNOWLEDGE",
                "/mcp/knowledge/x",
                "/mcp/knowledge2",
                "/mcp/other"
            )) {
                val reply = client.rpc(path, initializeBody)
                assertEquals(HttpStatusCode.NotFound, reply.status, "$path must be 404, never an orchestration fallback: ${reply.body}")
                assertNull(reply.sessionId, "$path must not issue a session")
                assertNull(reply.result, "$path must not return an initialize result")
            }
        }
    }

    @Test
    fun `S9 when no knowledge server is passed the knowledge path is not mounted and mcp is unaffected`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg()) }

            val absent = client.rpc("/mcp/knowledge", initializeBody)
            assertEquals(HttpStatusCode.NotFound, absent.status, "null knowledgeServer means /mcp/knowledge is not mounted: ${absent.body}")
            assertNull(absent.sessionId)
            val (session, _) = client.handshake("/mcp")
            assertEquals(orchestrationTools, client.listTools("/mcp", session).toolNames, "/mcp keeps serving the orchestration set")
        }
    }

    // ------------------------------------------------------------------ S8 REST bearer

    private val bearerToken = "profile-rest-bearer-token"

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun bearer(): Pair<ApiAuthConfig.Bearer, Map<HashBytes, BearerTokenStore.TokenEntry>> {
        val principal =
            ApiPrincipal(
                tokenId = "profile-test",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER
            )
        val key = HashBytes(sha256(bearerToken))
        return ApiAuthConfig.Bearer(tokens = mapOf(key to principal)) to
            mapOf(key to BearerTokenStore.TokenEntry(principal, expiresAt = null))
    }

    private fun Application.wireBearer(
        s: Servers,
        appConfig: AppConfig
    ) {
        val (apiConfig, entries) = bearer()
        installMcpStreamableHttp(s.orchestration, appConfig, knowledgeServer = s.knowledge)
        installRestApiRoutes(
            apiConfig = apiConfig,
            eventBus = null,
            effectiveProvider = s.rig.ctx.repositoryProvider,
            apiTokenEntries = entries,
            allowQueryToken = false,
            serverName = "profile-http-test",
            serverVersion = "1.0.0",
            actorAuthEnabled = s.rig.composition.actorAuthEnabled,
            serverConfig = s.rig.composition.serverConfig,
            toolContext = s.rig.ctx,
            degradedModePolicy = s.rig.composition.degradedModePolicy,
            appConfig = appConfig
        )
    }

    @Test
    fun `S8 with the REST API in bearer mode mcp knowledge is served without a token while the ops route still answers 401`() {
        val s = newServers()
        testApplication {
            application { wireBearer(s, cfg()) }

            val (session, init) = client.handshake("/mcp/knowledge")
            assertEquals(HttpStatusCode.OK, init.status, "initialize on /mcp/knowledge needs no REST bearer")
            assertEquals(
                knowledgeTools,
                client.listTools("/mcp/knowledge", session).toolNames,
                "the whole knowledge session works without a token"
            )

            val (orchestrationSession, _) = client.handshake("/mcp")
            assertEquals(orchestrationTools, client.listTools("/mcp", orchestrationSession).toolNames, "control: /mcp stays open too")

            val ops =
                client.post("/api/v1/ops/notes_read") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.Unauthorized, ops.status, "the REST ops route must still require a bearer token")

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/transitions").status, "REST routes stay gated")
            val authorised = client.get("/api/v1/transitions") { header(HttpHeaders.Authorization, "Bearer $bearerToken") }
            assertEquals(HttpStatusCode.OK, authorised.status, "control: the same route answers 200 with the token")
        }
    }

    @Test
    fun `S8 the exemption is exact - no other path under mcp is reachable without a token in bearer mode`() {
        val s = newServers()
        testApplication {
            application { wireBearer(s, cfg()) }

            for (path in listOf("/mcp/orchestration", "/mcp/Knowledge", "/mcp/knowledge/x", "/mcp/knowledge2")) {
                val reply = client.rpc(path, initializeBody)
                assertTrue(reply.status.value !in 200..299, "$path must not be served: ${reply.status} ${reply.body}")
                assertNull(reply.sessionId, "$path must not issue a session")
            }
            assertEquals(
                HttpStatusCode.OK,
                client.rpc("/mcp/knowledge", initializeBody).status,
                "control: the exact knowledge path is served"
            )
        }
    }

    // ------------------------------------------------------------------ S10 Host and Origin parity

    private val rebindHost = "attacker.example:3001"
    private val rebindOrigin = "http://attacker.example:3001"

    @Test
    fun `S10 a non-allowlisted Host is refused on mcp knowledge exactly as on mcp`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }

            val orchestration = client.rpc("/mcp", initializeBody, host = rebindHost)
            val knowledge = client.rpc("/mcp/knowledge", initializeBody, host = rebindHost)

            assertEquals(HttpStatusCode.Forbidden, orchestration.status, "baseline: /mcp refuses a rebinding Host")
            assertEquals(orchestration.status, knowledge.status, "/mcp/knowledge must refuse the same Host: ${knowledge.body}")
            assertTrue("MCP_ALLOWED_HOSTS" in knowledge.body, "the refusal names MCP_ALLOWED_HOSTS: ${knowledge.body}")
            assertNull(knowledge.sessionId)
            assertEquals(
                HttpStatusCode.OK,
                client.rpc("/mcp/knowledge", initializeBody, host = "localhost:3001").status,
                "control: loopback Host is served"
            )
        }
    }

    @Test
    fun `S10 the configured allowlist extends to mcp knowledge`() {
        val s = newServers()
        testApplication {
            application {
                installMcpStreamableHttp(
                    s.orchestration,
                    cfg("MCP_ALLOWED_HOSTS" to "tohost.lan, box.lan:8443"),
                    knowledgeServer = s.knowledge
                )
            }

            for (host in listOf("TOHOST.lan:9999", "box.lan:8443")) {
                assertEquals(HttpStatusCode.OK, client.rpc("/mcp", initializeBody, host = host).status, "baseline: /mcp serves $host")
                assertEquals(
                    HttpStatusCode.OK,
                    client.rpc("/mcp/knowledge", initializeBody, host = host).status,
                    "/mcp/knowledge serves $host"
                )
            }
            assertEquals(
                HttpStatusCode.Forbidden,
                client.rpc("/mcp", initializeBody, host = "box.lan:8444").status,
                "baseline: wrong port refused"
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.rpc("/mcp/knowledge", initializeBody, host = "box.lan:8444").status,
                "wrong port refused on knowledge"
            )
        }
    }

    @Test
    fun `S10 a foreign Origin is refused on mcp knowledge exactly as on mcp`() {
        val s = newServers()
        testApplication {
            application { installMcpStreamableHttp(s.orchestration, cfg(), knowledgeServer = s.knowledge) }

            for (origin in listOf("http://evil.example.com", rebindOrigin)) {
                val orchestration = client.rpc("/mcp", initializeBody, origin = origin)
                val knowledge = client.rpc("/mcp/knowledge", initializeBody, origin = origin)
                assertEquals(HttpStatusCode.Forbidden, orchestration.status, "baseline: /mcp refuses Origin $origin")
                assertEquals(orchestration.status, knowledge.status, "/mcp/knowledge must refuse Origin $origin: ${knowledge.body}")
                assertNull(knowledge.sessionId)
            }
            assertEquals(HttpStatusCode.OK, client.rpc("/mcp/knowledge", initializeBody).status, "control: no Origin is served")
        }
    }

    @Test
    fun `S10 CORS handling of mcp knowledge matches mcp for allowed, foreign and preflight requests`() {
        val s = newServers()
        testApplication {
            application {
                installMcpStreamableHttp(
                    s.orchestration,
                    cfg("CORS_ALLOWED_ORIGINS" to "https://dash.example.com"),
                    knowledgeServer = s.knowledge
                )
            }

            for (origin in listOf("https://dash.example.com", "http://evil.example.com")) {
                val orchestration = client.rpc("/mcp", initializeBody, origin = origin)
                val knowledge = client.rpc("/mcp/knowledge", initializeBody, origin = origin)
                assertEquals(orchestration.status, knowledge.status, "POST with Origin $origin: same status on both paths")
                assertEquals(
                    orchestration.headers["access-control-allow-origin"],
                    knowledge.headers["access-control-allow-origin"],
                    "POST with Origin $origin: same CORS allow-origin on both paths"
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
            val orchestrationPreflight = preflight("/mcp")
            val knowledgePreflight = preflight("/mcp/knowledge")
            assertEquals(orchestrationPreflight.status, knowledgePreflight.status, "preflight status must match")
            assertEquals(
                orchestrationPreflight.headers["access-control-allow-origin"],
                knowledgePreflight.headers["access-control-allow-origin"],
                "preflight allow-origin must match"
            )
            assertEquals(
                orchestrationPreflight.headers["access-control-allow-methods"],
                knowledgePreflight.headers["access-control-allow-methods"],
                "preflight allow-methods must match"
            )
        }
    }
}

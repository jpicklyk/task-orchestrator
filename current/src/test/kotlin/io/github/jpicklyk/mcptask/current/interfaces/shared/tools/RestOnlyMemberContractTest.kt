package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.bootstrap.buildProfileServer
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.bootstrap.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.parity.FixtureParityCatalog
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ApiReferenceDocs
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item b342d320, R5 profile sets) for the profile-set contract of a select or op:
 * `knowledge()` / `orchestration()` / `director()`, MCP visibility as set membership, and the EMPTY set meaning REST only.
 * Scenarios S9, S11 and S16 of the frozen test-plan, plus the pure helper contract of AC2.
 *
 * Oracles: task-scope AC2 (`knowledge()` = {KNOWLEDGE, ORCHESTRATION}, `orchestration()` = {ORCHESTRATION}, `director()` =
 * {DIRECTOR}, members compose with `+`, MCP visibility in profile p = `p in profiles`), AC5 (an empty set is absent on every MCP
 * profile: tools/list, select and op enums, `describe`, and dispatch, where it is `invalid_request` like any hidden member; REST
 * `POST /api/v1/ops/{tool}` sees every registered member whatever its set), AC7 (the api-reference profile column lists each
 * member's set by wire name, an empty set as "REST only") and decision D2 (ONE visibility predicate for REST, never selectable
 * through `MCP_TOOL_PROFILE`). The hidden-member error shape (`invalid_request` at `/select` or `/ops/0/op`) is the existing
 * contract of McpProfileHttpTest S7. Expected sets are written from the clauses, never read from a run.
 *
 * Fixture: `ro_read` (select) and `ro_write` (ops) each carry five members: `shared` {knowledge, orchestration}, `orch`
 * {orchestration}, `dir` {director}, `all` {knowledge, orchestration, director} and `web` {} (REST only); `ro_web` is a select tool
 * whose ONLY member is REST only.
 *
 * Negative-assertion conditions: every "absent" assertion on `web` is paired with a REST control in the same fixture that DOES
 * run `web`, so a fixture whose member simply does not work cannot pass; every "hidden on profile X" call is paired with the
 * same call served on a profile that contains the member.
 *
 * Domain invariants satisfied by construction: each member declares a non-blank doc and a request class whose only field has a
 * `@Doc`; the members touch no database table.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class RestOnlyMemberContractTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    @Serializable
    data class RunReq(
        @Doc("Marker.") val marker: String = "x"
    )

    private fun sel(
        name: String,
        profiles: Set<ToolProfile>
    ) = SelectSpec.Single<RunReq, JsonObject>(
        name = name,
        doc = "Run $name.",
        profiles = profiles,
        request = serializer<RunReq>(),
        handler = { _, _, _ -> Outcome.Ok(buildJsonObject { put("ran", name) }) },
        data = { it }
    )

    private fun op(
        name: String,
        profiles: Set<ToolProfile>
    ) = OpSpec<RunReq, String>(
        name = name,
        doc = "Run $name.",
        profiles = profiles,
        capability = ApiCapability.WRITE_ITEMS,
        request = serializer<RunReq>(),
        execute = { _, _, _ -> Outcome.Ok("ran:$name") }
    )

    private val all3: Set<ToolProfile> = knowledge() + director()
    private val noProfile: Set<ToolProfile> = emptySet()

    private val readTool =
        SelectToolSpec(
            "ro_read",
            "Profile-set read fixture.",
            listOf(
                sel("shared", knowledge()),
                sel("orch", orchestration()),
                sel("dir", director()),
                sel("all", all3),
                sel("web", noProfile)
            )
        )

    private val writeTool =
        OpsToolSpec<String>(
            name = "ro_write",
            description = "Profile-set write fixture.",
            ops =
                listOf(
                    op("shared", knowledge()),
                    op("orch", orchestration()),
                    op("dir", director()),
                    op("all", all3),
                    op("web", noProfile)
                ),
            codec = FixtureTools.StringCodec,
            element = { buildJsonObject { put("id", it) } }
        )

    private val webTool = SelectToolSpec("ro_web", "A tool whose only member is REST only.", listOf(sel("web_only", noProfile)))

    private val registry = ToolRegistry(listOf(readTool, writeTool, webTool))

    // ------------------------------------------------------------------ the pure helper contract (AC2)

    @Test
    fun `AC2 the three helpers return exactly the sets of the task scope`() {
        assertEquals(setOf(ToolProfile.KNOWLEDGE, ToolProfile.ORCHESTRATION), knowledge())
        assertEquals(setOf(ToolProfile.ORCHESTRATION), orchestration())
        assertEquals(setOf(ToolProfile.DIRECTOR), director())
        assertEquals(setOf(ToolProfile.KNOWLEDGE, ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR), knowledge() + director())
        assertEquals(setOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR), orchestration() + director())
    }

    @Test
    fun `AC2 a profile includes a set exactly when it is a member of it`() {
        assertTrue(ToolProfile.DIRECTOR.includes(director()))
        assertTrue(ToolProfile.DIRECTOR.includes(knowledge() + director()))
        assertTrue(ToolProfile.KNOWLEDGE.includes(knowledge()))
        assertTrue(ToolProfile.ORCHESTRATION.includes(knowledge()), "knowledge() contains ORCHESTRATION")
        assertFalse(ToolProfile.DIRECTOR.includes(knowledge()), "a knowledge member is not a director member")
        assertFalse(ToolProfile.KNOWLEDGE.includes(orchestration()))
        assertFalse(ToolProfile.KNOWLEDGE.includes(director()))
        for (profile in ToolProfile.entries) {
            assertFalse(profile.includes(noProfile), "${profile.wire} must not include the empty set")
            assertEquals(
                profile.includes(knowledge() + director()),
                profile.sees(knowledge() + director()),
                "a profile sees what it includes"
            )
        }
    }

    @Test
    fun `AC5 the REST view sees every set including the empty one and no profile sees the empty one`() {
        for (set in listOf(noProfile, director(), orchestration(), knowledge(), knowledge() + director())) {
            assertTrue(ToolView.Rest.sees(set), "REST sees $set")
        }
        for (profile in ToolProfile.entries) assertFalse(profile.sees(noProfile), "${profile.wire} sees the empty set")
    }

    // ------------------------------------------------------------------ registry and rendering seams (AC5)

    @Test
    fun `AC5 a tool whose only member is REST only is visible to REST and to no MCP profile`() {
        assertEquals(listOf("ro_read", "ro_write", "ro_web"), registry.visible(ToolView.Rest).map { it.name })
        for (profile in ToolProfile.entries) {
            assertFalse("ro_web" in registry.visible(profile).map { it.name }, "ro_web must not be listed on ${profile.wire}")
            assertNull(McpOperationAdapter.toolFor(webTool, profile), "ro_web renders no tools/list entry on ${profile.wire}")
            assertNull(ToolSchemaRenderer.inputSchema(webTool, profile), "ro_web renders no schema on ${profile.wire}")
        }
        val rest = assertNotNull(ToolSchemaRenderer.inputSchema(webTool, ToolView.Rest), "the REST rendering shows the member")
        assertTrue("web_only" in rest.toString(), rest.toString())
    }

    @Test
    fun `AC5 per-profile member lists follow set membership and REST lists all members`() {
        assertEquals(listOf("shared", "orch", "dir", "all", "web"), readTool.visibleSelects(ToolView.Rest).map { it.name })
        assertEquals(listOf("shared", "orch", "dir", "all", "web"), writeTool.visibleOps(ToolView.Rest).map { it.name })
        assertEquals(listOf("shared", "orch", "all"), readTool.visibleSelects(ToolProfile.ORCHESTRATION).map { it.name })
        assertEquals(listOf("shared", "all"), readTool.visibleSelects(ToolProfile.KNOWLEDGE).map { it.name })
        assertEquals(listOf("dir", "all"), readTool.visibleSelects(ToolProfile.DIRECTOR).map { it.name })
        assertEquals(listOf("shared", "orch", "all"), writeTool.visibleOps(ToolProfile.ORCHESTRATION).map { it.name })
        assertEquals(listOf("shared", "all"), writeTool.visibleOps(ToolProfile.KNOWLEDGE).map { it.name })
        assertEquals(listOf("dir", "all"), writeTool.visibleOps(ToolProfile.DIRECTOR).map { it.name })
    }

    // ------------------------------------------------------------------ MCP over HTTP: S11

    private class Servers(
        val rig: EventLogRig,
        val orchestration: Server,
        val knowledge: Server,
        val director: Server
    )

    private fun newServers(): Servers {
        val rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)
        val adapter = McpOperationAdapter(buildToolDispatcher(rig.ctx))

        fun server(profile: ToolProfile): Server =
            buildProfileServer(
                profile = profile,
                serverName = "rest-only-test",
                version = "1.0.0",
                registry = registry,
                operationAdapter = adapter
            )
        return Servers(rig, server(ToolProfile.ORCHESTRATION), server(ToolProfile.KNOWLEDGE), server(ToolProfile.DIRECTOR))
    }

    private val acceptBoth = "application/json, text/event-stream"

    private val initializeBody =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"rest-only-test","version":"1.0"}}}"""

    private class Reply(
        val status: HttpStatusCode,
        val body: String,
        val sessionId: String?
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
                Json.parseToJsonElement(payload).jsonObject["result"] as? JsonObject
            }.getOrNull()

        fun tool(name: String): JsonObject? =
            (result?.get("tools") as? JsonArray)?.map { it.jsonObject }?.singleOrNull { it["name"]!!.jsonPrimitive.content == name }

        val toolNames: Set<String> get() =
            (result?.get("tools") as? JsonArray)
                ?.map {
                    it.jsonObject["name"]!!.jsonPrimitive.content
                }?.toSet()
                .orEmpty()

        val succeeded: Boolean get() =
            status == HttpStatusCode.OK &&
                result != null &&
                result["isError"]?.jsonPrimitive?.booleanOrNull != true

        val error: JsonObject? get() = (result?.get("structuredContent") as? JsonObject)?.get("error") as? JsonObject

        /** The error of the first element of an ops batch (a batch whose only element failed answers partial_failure). */
        val firstElementError: JsonObject?
            get() =
                (((result?.get("structuredContent") as? JsonObject)?.get("data") as? JsonObject)?.get("results") as? JsonArray)
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("error") as? JsonObject
    }

    private suspend fun HttpResponse.reply() = Reply(status, bodyAsText(), headers["mcp-session-id"])

    private suspend fun HttpClient.rpc(
        path: String,
        body: String,
        session: String? = null
    ): Reply =
        post(path) {
            header(HttpHeaders.Accept, acceptBoth)
            if (session != null) header("mcp-session-id", session)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.reply()

    private suspend fun HttpClient.session(path: String): String {
        val init = rpc(path, initializeBody)
        assertEquals(HttpStatusCode.OK, init.status, "initialize on $path: ${init.body}")
        val id = assertNotNull(init.sessionId, "session id on $path")
        rpc(path, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", id)
        return id
    }

    private suspend fun HttpClient.listing(
        path: String,
        session: String
    ) = rpc(path, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", session)

    private suspend fun HttpClient.callTool(
        path: String,
        session: String,
        tool: String,
        arguments: String
    ) = rpc(path, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""", session)

    private fun JsonObject.properties(): JsonObject = getValue("inputSchema").jsonObject.getValue("properties").jsonObject

    private fun JsonObject.selectEnum(): Set<String> =
        properties()
            .getValue("select")
            .jsonObject
            .getValue("enum")
            .let { (it as JsonArray).map { v -> v.jsonPrimitive.content }.toSet() }

    private fun JsonObject.opEnum(): Set<String> =
        properties()
            .getValue("ops")
            .jsonObject
            .getValue("items")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("op")
            .jsonObject
            .getValue("enum")
            .let { (it as JsonArray).map { v -> v.jsonPrimitive.content }.toSet() }

    private val paths =
        listOf(
            "/mcp" to ToolProfile.ORCHESTRATION,
            "/mcp/knowledge" to ToolProfile.KNOWLEDGE,
            "/mcp/director" to ToolProfile.DIRECTOR
        )

    private fun testMcp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(Servers) -> Unit) {
        val s = newServers()
        testApplication {
            application {
                installMcpStreamableHttp(
                    s.orchestration,
                    AppConfig.fromEnv(env = emptyMap<String, String>()::get),
                    s.knowledge,
                    s.director
                )
            }
            block(s)
        }
    }

    @Test
    fun `S11 the REST-only member is absent from tools list and from every select and op enum on all three profiles`() =
        testMcp {
            val expectedMembers =
                mapOf(
                    ToolProfile.ORCHESTRATION to setOf("shared", "orch", "all"),
                    ToolProfile.KNOWLEDGE to setOf("shared", "all"),
                    ToolProfile.DIRECTOR to setOf("dir", "all")
                )
            for ((path, profile) in paths) {
                val listing = client.listing(path, client.session(path))
                assertEquals(
                    setOf("ro_read", "ro_write"),
                    listing.toolNames,
                    "$path lists the two tools that have a visible member, never ro_web"
                )
                assertEquals(expectedMembers.getValue(profile), listing.tool("ro_read")!!.selectEnum(), "$path ro_read select enum")
                assertEquals(expectedMembers.getValue(profile), listing.tool("ro_write")!!.opEnum(), "$path ro_write op enum")
                assertFalse(
                    "web" in listing.body.replace("web_only", ""),
                    "$path: the REST-only member leaks into tools/list: ${listing.body}"
                )
                assertFalse("ro_web" in listing.body, "$path lists the REST-only tool")
            }
        }

    @Test
    fun `S11 calling the REST-only member is invalid_request at its pointer on every profile while a visible member runs`() =
        testMcp {
            for ((path, profile) in paths) {
                val session = client.session(path)
                val select = client.callTool(path, session, "ro_read", """{"select":"web"}""")
                assertFalse(select.succeeded, "$path: web must not run as a select: ${select.body}")
                assertEquals(
                    "invalid_request",
                    select.error
                        ?.get("code")
                        ?.jsonPrimitive
                        ?.content,
                    "$path: ${select.body}"
                )
                assertTrue("/select" in select.error.toString(), "$path: the error points at /select: ${select.error}")

                val write = client.callTool(path, session, "ro_write", """{"ops":[{"op":"web"}]}""")
                assertFalse(write.succeeded, "$path: web must not run as an op: ${write.body}")
                val element = assertNotNull(write.firstElementError, "$path: the failed element carries its own error: ${write.body}")
                assertEquals("invalid_request", element["code"]?.jsonPrimitive?.content, "$path: ${write.body}")
                assertTrue("/ops/0/op" in element.toString(), "$path: the element error points at /ops/0/op: $element")

                val visible = if (profile == ToolProfile.DIRECTOR) "dir" else "shared"
                val control = client.callTool(path, session, "ro_read", """{"select":"$visible"}""")
                assertTrue(control.succeeded, "control: $visible runs on $path: ${control.body}")
                val writeControl = client.callTool(path, session, "ro_write", """{"ops":[{"op":"$visible"}]}""")
                assertTrue(writeControl.succeeded, "control: op $visible runs on $path: ${writeControl.body}")
            }
        }

    @Test
    fun `S11 a member runs only on profiles whose set contains it`() =
        testMcp {
            val matrix =
                mapOf(
                    "shared" to setOf(ToolProfile.KNOWLEDGE, ToolProfile.ORCHESTRATION),
                    "orch" to setOf(ToolProfile.ORCHESTRATION),
                    "dir" to setOf(ToolProfile.DIRECTOR),
                    "all" to setOf(ToolProfile.KNOWLEDGE, ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR)
                )
            for ((path, profile) in paths) {
                val session = client.session(path)
                for ((member, profiles) in matrix) {
                    val result = client.callTool(path, session, "ro_read", """{"select":"$member"}""")
                    assertEquals(profile in profiles, result.succeeded, "select $member on $path (${profile.wire}): ${result.body}")
                    val op = client.callTool(path, session, "ro_write", """{"ops":[{"op":"$member"}]}""")
                    assertEquals(profile in profiles, op.succeeded, "op $member on $path (${profile.wire}): ${op.body}")
                }
            }
        }

    @Test
    fun `S11 describe never shows the REST-only member and describing it by name is invalid_request on every profile`() =
        testMcp {
            for ((path, _) in paths) {
                val session = client.session(path)
                for (tool in listOf("ro_read", "ro_write")) {
                    val described = client.callTool(path, session, tool, """{"describe":true}""")
                    assertTrue(described.succeeded, "$path $tool describe: ${described.body}")
                    assertFalse(
                        Regex("(?<![A-Za-z0-9_])web(?![A-Za-z0-9_])").containsMatchIn(described.body),
                        "$path $tool describe leaks web: ${described.body}"
                    )
                }
                val narrowedSelect = client.callTool(path, session, "ro_read", """{"describe":true,"select":"web"}""")
                assertFalse(narrowedSelect.succeeded, "$path: ${narrowedSelect.body}")
                assertEquals(
                    "invalid_request",
                    narrowedSelect.error
                        ?.get("code")
                        ?.jsonPrimitive
                        ?.content,
                    narrowedSelect.body
                )
            }
        }

    // ------------------------------------------------------------------ REST: S9

    private fun asWriter(
        client: HttpClient,
        tool: String
    ): suspend (String) -> Pair<HttpStatusCode, String> =
        { body ->
            val response =
                client.post("/api/v1/ops/$tool") {
                    header(HttpHeaders.Authorization, "Bearer ${FixtureParityCatalog.writer.raw}")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            response.status to response.bodyAsText()
        }

    @Test
    fun `S9 REST ops runs the REST-only member, an orchestration-only member and a director-only member`() {
        val rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)
        testApplication {
            application { installParityRestApp(rig, parityAuthConfig(FixtureParityCatalog.writer), registry) }
            val read = asWriter(client, "ro_read")
            val write = asWriter(client, "ro_write")
            val webOnly = asWriter(client, "ro_web")

            for (member in listOf("shared", "orch", "dir", "all", "web")) {
                val (status, body) = read("""{"select":"$member"}""")
                assertEquals(HttpStatusCode.OK, status, "REST select $member: $body")
                assertTrue("\"ran\":\"$member\"" in body, "REST select $member ran: $body")

                val (opStatus, opBody) = write("""{"ops":[{"op":"$member"}]}""")
                assertEquals(HttpStatusCode.OK, opStatus, "REST op $member: $opBody")
                assertTrue("ran:$member" in opBody, "REST op $member ran: $opBody")
            }
            val (status, body) = webOnly("""{"select":"web_only"}""")
            assertEquals(HttpStatusCode.OK, status, "a tool whose only member is REST only is still served over REST: $body")
            assertTrue("\"ran\":\"web_only\"" in body, body)
        }
    }

    @Test
    fun `S9 REST describe lists every member including the REST-only one`() {
        val rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)
        testApplication {
            application { installParityRestApp(rig, parityAuthConfig(FixtureParityCatalog.writer), registry) }
            for (tool in listOf("ro_read", "ro_write")) {
                val (status, body) = asWriter(client, tool)("""{"describe":true}""")
                assertEquals(HttpStatusCode.OK, status, body)
                for (member in listOf("shared", "orch", "dir", "all", "web")) {
                    assertTrue("\"name\":\"$member\"" in body, "REST describe of $tool lists $member: $body")
                }
            }
        }
    }

    // ------------------------------------------------------------------ the api-reference profile column: S16

    private fun metaOf(
        block: String,
        tool: String,
        kind: String,
        member: String
    ): String {
        val line = block.lines().single { it.startsWith("**`$tool` $kind `$member`**") }
        return line.substringAfter("`$member`** (").substringBefore(";")
    }

    @Test
    fun `S16 the api-reference profile column lists each member's set by wire name and an empty set as REST only`() {
        val selectBlock = ApiReferenceDocs.block("ro_read", registry)
        val opsBlock = ApiReferenceDocs.block("ro_write", registry)
        val wires = ToolProfile.entries.map { it.wire }

        val expected =
            mapOf(
                "shared" to setOf("knowledge", "orchestration"),
                "orch" to setOf("orchestration"),
                "dir" to setOf("director"),
                "all" to setOf("knowledge", "orchestration", "director"),
                "web" to emptySet()
            )
        for ((member, named) in expected) {
            for ((block, kind, tool) in listOf(Triple(selectBlock, "select", "ro_read"), Triple(opsBlock, "op", "ro_write"))) {
                val meta = metaOf(block, tool, kind, member)
                if (named.isEmpty()) {
                    assertTrue("REST only" in meta, "$tool $member: an empty set is shown as REST only: $meta")
                    assertTrue(wires.none { it in meta }, "$tool $member: a REST-only member names no profile: $meta")
                } else {
                    assertEquals(named, wires.filter { it in meta }.toSet(), "$tool $member: the profile column names its set: $meta")
                    assertFalse("REST only" in meta, "$tool $member: a non-empty set is not REST only: $meta")
                }
            }
        }
    }

    @Test
    fun `S16 a tool whose only member is REST only is still described in the api reference`() {
        val block = ApiReferenceDocs.block("ro_web", registry)
        assertTrue("REST only" in metaOf(block, "ro_web", "select", "web_only"), block)
    }
}

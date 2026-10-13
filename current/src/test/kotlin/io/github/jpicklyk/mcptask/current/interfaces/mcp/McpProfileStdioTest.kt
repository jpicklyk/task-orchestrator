package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.bootstrap.CurrentMcpServer
import io.github.jpicklyk.mcptask.current.bootstrap.Failed
import io.github.jpicklyk.mcptask.current.bootstrap.Reason
import io.github.jpicklyk.mcptask.current.bootstrap.Started
import io.github.jpicklyk.mcptask.current.bootstrap.StartupOutcome
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f6d23c40, Q29 tool profiles) for `MCP_TOOL_PROFILE`: stdio serves the selected
 * profile, an unknown value fails startup on both transports, the value is parsed trimmed and case-insensitively with empty
 * meaning unset, and HTTP ignores the variable (both paths always mount). Scenarios S3, S6, S13, S14 of the frozen test-plan,
 * driven through the real [CurrentMcpServer.run] with piped stdio (the `stdioInput` and `stdioOutput` seams) and a real
 * loopback HTTP port.
 *
 * Oracles: task-scope AC4 and decisions D1 (default is orchestration) and D2 (an unknown value fails startup fail-closed, is
 * validated on both transports and applied to stdio only; the value is trimmed, case-insensitive and empty means unset, the
 * `EnvBoolean` convention), plan section 3.1 for the two tool-name sets (written here from the plan text), and the MCP stdio
 * framing (one JSON-RPC message per line). Expected sets are never read from a run.
 *
 * Negative-assertion conditions: the "transport hook never fired" assertion of S6 is paired with a control run (valid value,
 * same hook) in which the hook DOES fire; the "no readiness marker" assertion is paired with the stdio runs that do create it.
 *
 * Forbidden-construct declaration: none (no assumeTrue, no @Disabled, no swallowed assertions). The bounded polling loops fail
 * with the run outcome in the message when the awaited event does not happen.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class McpProfileStdioTest {
    @TempDir
    lateinit var dir: Path

    private val knowledgeTools =
        setOf("items_read", "items_write", "notes_read", "notes_write", "deps_read", "deps_write", "docs_read", "docs_write")

    private val orchestrationTools = knowledgeTools + setOf("advance", "config")

    // ------------------------------------------------------------------ ToolProfile.parse and AppConfig (S13)

    @Test
    fun `S13 parse trims and ignores case for the two profile names`() {
        assertEquals(ToolProfile.KNOWLEDGE, ToolProfile.parse("knowledge"))
        assertEquals(ToolProfile.ORCHESTRATION, ToolProfile.parse("orchestration"))
        assertEquals(ToolProfile.KNOWLEDGE, ToolProfile.parse("  Knowledge "))
        assertEquals(ToolProfile.KNOWLEDGE, ToolProfile.parse("KNOWLEDGE"))
        assertEquals(ToolProfile.KNOWLEDGE, ToolProfile.parse("\tknowledge\n"))
        assertEquals(ToolProfile.ORCHESTRATION, ToolProfile.parse(" ORCHESTRATION "))
        assertEquals(ToolProfile.ORCHESTRATION, ToolProfile.parse("Orchestration"))
    }

    @Test
    fun `S13 parse treats null empty and blank as unset which means orchestration`() {
        assertEquals(ToolProfile.ORCHESTRATION, ToolProfile.parse(null))
        assertEquals(ToolProfile.ORCHESTRATION, ToolProfile.parse(""))
        assertEquals(ToolProfile.ORCHESTRATION, ToolProfile.parse("   "))
    }

    @Test
    fun `S13 parse returns null for anything else including near misses`() {
        val rejected =
            listOf(
                "bogus",
                "knowledg",
                "knowledge2",
                "know ledge",
                "knowledge,orchestration",
                "orchestrator",
                "both",
                "all",
                "full",
                "0",
                "true",
                "knowledg\u00E9"
            )
        for (raw in rejected) {
            assertNull(ToolProfile.parse(raw), "'$raw' is not a profile name and must not parse")
        }
    }

    @Test
    fun `S13 parse agrees with the wire name of every profile`() {
        for (profile in ToolProfile.entries) {
            assertEquals(profile, ToolProfile.parse(profile.wire), "round trip of ${profile.name}")
        }
        assertEquals(setOf("knowledge", "orchestration", "director"), ToolProfile.entries.map { it.wire }.toSet())
        assertEquals(3, ToolProfile.entries.size, "R5: exactly three profiles")
    }

    @Test
    fun `S1 parse accepts director trimmed and in any case`() {
        assertEquals(ToolProfile.DIRECTOR, ToolProfile.parse("director"))
        assertEquals(ToolProfile.DIRECTOR, ToolProfile.parse(" Director "))
        assertEquals(ToolProfile.DIRECTOR, ToolProfile.parse("DIRECTOR"))
        assertEquals(ToolProfile.DIRECTOR, ToolProfile.parse("\tdirector\n"))
        assertEquals("director", ToolProfile.DIRECTOR.wire)
    }

    @Test
    fun `S1 parse rejects director near misses instead of coercing them`() {
        // Oracle: task-scope AC1, trimmed and case-insensitive only; anything else is unknown and fails startup (fail closed).
        // The NUL probes are an oracle judgement: NUL is not whitespace, so a NUL-padded name is not the name.
        val rejected =
            listOf("directors", "dir", "director2", "dire ctor", "director,knowledge", "directo", "director\u0000", "\u0000director")
        for (raw in rejected) {
            assertNull(ToolProfile.parse(raw), "'${raw.replace("\u0000", "<NUL>")}' is not a profile name and must not parse")
        }
    }

    @Test
    fun `S1 the director value reaches parse through AppConfig and empty or unset still means orchestration`() {
        assertEquals(
            ToolProfile.DIRECTOR,
            ToolProfile.parse(AppConfig.fromEnv(env = mapOf("MCP_TOOL_PROFILE" to " Director ")::get).mcpToolProfileRaw),
            "the env value reaches parse"
        )
        assertEquals(ToolProfile.ORCHESTRATION, ToolProfile.parse(null), "control: unset is still orchestration, never director")
        assertEquals(ToolProfile.ORCHESTRATION, ToolProfile.parse("  "), "control: blank is still orchestration")
    }

    @Test
    fun `S13 AppConfig carries MCP_TOOL_PROFILE raw and unset stays null`() {
        assertNull(AppConfig.fromEnv(env = emptyMap<String, String>()::get).mcpToolProfileRaw, "unset is null, not a default string")
        assertEquals(
            "bogus",
            AppConfig.fromEnv(env = mapOf("MCP_TOOL_PROFILE" to "bogus")::get).mcpToolProfileRaw,
            "an unknown value is carried raw"
        )
        assertEquals(
            ToolProfile.KNOWLEDGE,
            ToolProfile.parse(AppConfig.fromEnv(env = mapOf("MCP_TOOL_PROFILE" to "  Knowledge ")::get).mcpToolProfileRaw),
            "the env value reaches parse"
        )
        assertEquals(
            ToolProfile.ORCHESTRATION,
            ToolProfile.parse(AppConfig.fromEnv(env = mapOf("MCP_TOOL_PROFILE" to "")::get).mcpToolProfileRaw),
            "an empty env value means unset"
        )
    }

    // ------------------------------------------------------------------ stdio driver

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

    private fun readinessOf(name: String): Path = dir.resolve(name).resolve("ready")

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
        thread.name = "profile-stdio-test-run"
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

    private class Served(
        val tools: Set<String>,
        val instructions: String?
    )

    /** Drives initialize + tools/list over piped stdio and shuts the run down by closing stdin. */
    private fun serveOverStdio(
        name: String,
        profile: String?
    ): Served {
        val session = startStdio(name, profile)
        session.send(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",""" +
                """"capabilities":{},"clientInfo":{"name":"profile-stdio-test","version":"1"}}}"""
        )
        val init = session.awaitResponse(1)
        session.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        session.send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val listing = session.awaitResponse(2)
        val tools =
            (listing["result"]!!.jsonObject["tools"] as JsonArray)
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                .toSet()
        val instructions = init["result"]!!.jsonObject["instructions"]?.jsonPrimitive?.contentOrNull
        session.finish()
        return Served(tools, instructions)
    }

    // ------------------------------------------------------------------ S3 stdio honours the profile

    @Test
    fun `S3 stdio with MCP_TOOL_PROFILE knowledge serves exactly the knowledge tools`() {
        val served = serveOverStdio("s3-knowledge", "knowledge")

        assertEquals(knowledgeTools, served.tools, "plan 3.1: the eight knowledge tools")
        assertTrue("knowledge" in served.instructions.orEmpty(), "instructions name the profile: ${served.instructions}")
    }

    @Test
    fun `S3 stdio with MCP_TOOL_PROFILE unset serves the full orchestration set`() {
        val served = serveOverStdio("s3-unset", null)

        assertEquals(orchestrationTools, served.tools, "D1: unset means orchestration, today's tool set plus the 4.0 tools")
        assertTrue("orchestration" in served.instructions.orEmpty(), "instructions name the profile: ${served.instructions}")
    }

    @Test
    fun `S3 stdio with MCP_TOOL_PROFILE orchestration serves the full orchestration set`() {
        val served = serveOverStdio("s3-orchestration", "orchestration")

        assertEquals(orchestrationTools, served.tools)
    }

    // ------------------------------------------------------------------ S13 env spelling reaches the server

    @Test
    fun `S13 stdio accepts a padded mixed-case value as knowledge`() {
        val served = serveOverStdio("s13-padded", "  Knowledge ")

        assertEquals(knowledgeTools, served.tools, "the padded, mixed-case value selects knowledge")
    }

    @Test
    fun `S13 stdio treats an empty value as unset and serves orchestration`() {
        val served = serveOverStdio("s13-empty", "")

        assertEquals(orchestrationTools, served.tools, "empty means unset, not an error and not knowledge")
    }

    // ------------------------------------------------------------------ S6 unknown value fails startup

    private fun runWithHook(
        name: String,
        transport: String,
        profile: String?
    ): Triple<StartupOutcome, List<String>, Boolean> {
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
        val outcome = server.run()
        return Triple(outcome, hookCalls, Files.exists(readinessOf(name)))
    }

    private fun assertUnknownProfile(
        transport: String,
        raw: String
    ) {
        val name = "s6-$transport-${raw.hashCode().toUInt()}"
        val (outcome, hookCalls, readiness) = runWithHook(name, transport, raw)

        assertTrue(outcome is Failed, "expected Failed, got $outcome")
        assertEquals(Reason.UNKNOWN_TOOL_PROFILE, outcome.reason, "detail: ${outcome.detail}")
        assertTrue("MCP_TOOL_PROFILE" in outcome.detail, "detail must name the variable: ${outcome.detail}")
        assertTrue(raw in outcome.detail, "detail must name the offending value '$raw': ${outcome.detail}")
        assertEquals(emptyList(), hookCalls, "the failure must come before any transport starts")
        assertFalse(readiness, "a rejected value must never produce the readiness marker")
    }

    @Test
    fun `S6 an unknown MCP_TOOL_PROFILE fails stdio startup naming the variable and the value`() {
        assertUnknownProfile("stdio", "bogus")
    }

    @Test
    fun `S6 an unknown MCP_TOOL_PROFILE fails http startup too`() {
        assertUnknownProfile("http", "bogus")
    }

    @Test
    fun `S6 a near-miss value is rejected rather than coerced`() {
        assertUnknownProfile("stdio", "orchestrator")
    }

    @Test
    fun `S6 control - a valid value reaches the transport stage where the same hook fires`() {
        for (transport in listOf("stdio", "http")) {
            val (outcome, hookCalls, _) = runWithHook("s6-control-$transport", transport, "knowledge")

            assertTrue(outcome is Failed, "the hook stops the run: $outcome")
            assertEquals(Reason.TRANSPORT_START, outcome.reason, "detail: ${outcome.detail}")
            assertEquals(listOf(transport), hookCalls, "the hook fires exactly once with the transport name")
        }
    }

    // ------------------------------------------------------------------ S14 http ignores the variable

    private data class HttpReply(
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
        client: HttpClient,
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
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpReply(response.statusCode(), response.body(), response.headers().firstValue("mcp-session-id").orElse(null))
    }

    private fun toolsAt(
        client: HttpClient,
        port: Int,
        path: String
    ): Set<String> {
        val init =
            post(
                client,
                port,
                path,
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",""" +
                    """"capabilities":{},"clientInfo":{"name":"profile-stdio-test","version":"1"}}}"""
            )
        assertEquals(200, init.status, "initialize on $path: ${init.body}")
        val session = assertNotNull(init.session, "session id for $path")
        post(client, port, path, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", session)
        val listing = post(client, port, path, """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", session)
        assertEquals(200, listing.status, "tools/list on $path: ${listing.body}")
        return (listing.result!!["tools"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
    }

    @Test
    fun `S14 http mounts both profiles regardless of MCP_TOOL_PROFILE`() {
        val port = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        val coordinator = ShutdownCoordinator()
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = coordinator,
                appConfig =
                    appConfigFor(
                        "s14",
                        "http",
                        "knowledge",
                        mapOf("MCP_HTTP_HOST" to "127.0.0.1", "MCP_HTTP_PORT" to port.toString())
                    )
            )
        val run = startRun(server)
        try {
            val ready = readinessOf("s14")
            val deadline = System.currentTimeMillis() + 90_000
            while (!Files.exists(ready) && System.currentTimeMillis() < deadline && run.thread.isAlive) Thread.sleep(50)
            assertTrue(Files.exists(ready), "http server must become ready; outcome=${run.outcome.get()} failure=${run.failure.get()}")

            val client = HttpClient.newHttpClient()
            assertEquals(orchestrationTools, toolsAt(client, port, "/mcp"), "/mcp is orchestration even when MCP_TOOL_PROFILE=knowledge")
            assertEquals(knowledgeTools, toolsAt(client, port, "/mcp/knowledge"), "/mcp/knowledge is knowledge")
        } finally {
            coordinator.initiateShutdown("test")
            coordinator.awaitCompletion(20_000)
            run.thread.join(20_000)
        }
        assertFalse(run.thread.isAlive, "run() must return after shutdown")
        assertNull(run.failure.get(), "run() threw: ${run.failure.get()}")
    }
}

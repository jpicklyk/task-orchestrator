package io.github.jpicklyk.mcptask.current.bootstrap

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureTestApp
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.serviceRoutes
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item 779a9d0d -- "Remove the inert actor_authentication config path and
 * the /info actorAuthenticationEnabled field". Covers test-plan scenarios S1-S8 and the applicable
 * adversarial probes. S9 (a per-root push carrying the key is rejected at `/actor_authentication`) is
 * covered by the existing `ConfigServiceStrictPushTest` S5 and gets no new test here.
 *
 * Oracle source: the item's frozen `task-scope` and `test-plan` notes (queue phase), the CLAUDE.md
 * `READINESS_FILE` / `DEGRADED_MODE_POLICY` contracts and the CHANGELOG 3.x `auditing` rename -- never the
 * implementation. Every scenario is EXISTING-SURFACE: [ServerComposition], [CurrentMcpServer], [AppConfig]
 * and `serviceRoutes` all predate the change. Only the observable behavior (a leftover key fails startup,
 * `/info` no longer carries the field) is new, so a plain revert yields behavioral red.
 *
 * The startup guard sits in `ServerComposition.build()`, ahead of the boot-import data step, so the
 * build()-level scenarios isolate the guard from the strict-mode import (which would reject other unknown
 * keys later, at `run`).
 */
class ActorAuthenticationRemovedTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    // ---- fixtures ----

    /** A minimal global-config body that is valid at the server level. Valid YAML on its own. */
    private val base =
        "work_item_schemas:\n" +
            "  feature-task:\n" +
            "    notes:\n" +
            "      - key: spec\n" +
            "        role: queue\n" +
            "        required: true\n"

    private fun writeConfig(
        dir: Path,
        content: String
    ): Path {
        val configDir = dir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val file = configDir.resolve("config.yaml")
        Files.writeString(file, content)
        return file
    }

    private fun appConfigFor(
        dir: Path,
        extraEnv: Map<String, String> = emptyMap()
    ): AppConfig {
        val env = mapOf("AGENT_CONFIG_DIR" to dir.toString()) + extraEnv
        return AppConfig.fromEnv { key -> env[key] }
    }

    private fun build(
        dir: Path,
        extraEnv: Map<String, String> = emptyMap()
    ) = ServerComposition(appConfigFor(dir, extraEnv), db.databaseManager, ShutdownCoordinator()).build()

    private fun normalized(text: String) = text.replace(Char(92), '/')

    // -------------------------------------------------------------------------
    // S1 -- happy / control: a config with only accepted keys builds. Control for S2-S4.
    // -------------------------------------------------------------------------

    @Test
    fun `S1 a config with schemas and the hook-local actor_attribution key builds`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "actor_attribution:\n  required: true\n$base")

        assertNotNull(build(tempDir))
    }

    @Test
    fun `S1 a config with schemas and actor_attribution under extensions builds`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "extensions:\n  claude-code:\n    actor_attribution:\n      required: true\n$base")

        assertNotNull(build(tempDir))
    }

    // -------------------------------------------------------------------------
    // S2 -- failure: a leftover actor_authentication block fails startup with an upgrade message.
    // -------------------------------------------------------------------------

    @Test
    fun `S2 a leftover actor_authentication block with a jwks verifier fails build naming the key the path and Remove`(
        @TempDir tempDir: Path
    ) {
        val configFile =
            writeConfig(
                tempDir,
                "actor_authentication:\n" +
                    "  enabled: true\n" +
                    "  require_sub_match: true\n" +
                    "  verifier:\n" +
                    "    type: jwks\n" +
                    "    jwks_uri: https://issuer.example.test/.well-known/jwks.json\n" +
                    "    issuer: https://issuer.example.test\n" +
                    "    audience: task-orchestrator\n" +
                    base
            )

        val ex = assertFailsWith<IllegalArgumentException> { build(tempDir) }

        val message = ex.message.orEmpty()
        assertTrue("actor_authentication" in message, "message must name the key: $message")
        assertTrue(
            normalized(configFile.toString()) in normalized(message),
            "message must carry the config path $configFile: $message"
        )
        assertTrue("Remove" in message, "message must tell the operator to Remove the block: $message")
    }

    // -------------------------------------------------------------------------
    // S3 -- edge: key presence alone fails startup, whatever the value.
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "S3 actor_authentication value [{index}] fails build")
    @ValueSource(
        strings = [
            "actor_authentication: null\n",
            "actor_authentication: ~\n",
            "actor_authentication:\n",
            "actor_authentication: {}\n",
            "actor_authentication: false\n",
            "actor_authentication: some-scalar\n",
            "actor_authentication:\n  enabled: true\n",
        ]
    )
    fun `S3 any value of a top-level actor_authentication key fails build`(
        keyLine: String,
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, keyLine + base)

        val ex = assertFailsWith<IllegalArgumentException>("fixture: ${keyLine.trim()}") { build(tempDir) }

        assertTrue("actor_authentication" in ex.message.orEmpty(), "message must name the key: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // S4 -- edge: only a top-level key counts.
    // -------------------------------------------------------------------------

    @Test
    fun `S4 a comment mentioning actor_authentication does not fail build`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "# actor_authentication: x\n$base")

        assertNotNull(build(tempDir))
    }

    @Test
    fun `S4 actor_authentication nested under extensions does not fail build`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "extensions:\n  x:\n    actor_authentication: 1\n$base")

        assertNotNull(build(tempDir))
    }

    // -------------------------------------------------------------------------
    // S5 -- failure: run() propagates the guard failure before any transport starts or readiness is marked.
    // -------------------------------------------------------------------------

    @Test
    fun `S5 run over a leftover actor_authentication block throws IAE before the transport and the readiness marker`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "actor_authentication:\n  enabled: true\n$base")
        val readinessFile = tempDir.resolve("ready")
        val env =
            mapOf(
                "AGENT_CONFIG_DIR" to tempDir.toString(),
                "DATABASE_PATH" to tempDir.resolve("s5-${System.nanoTime()}.db").toString(),
                "MCP_TRANSPORT" to "stdio",
                "READINESS_FILE" to readinessFile.toString()
            )
        val transportStarted = AtomicBoolean(false)
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = ShutdownCoordinator(),
                appConfig = AppConfig.fromEnv { key -> env[key] },
                // Reaching the transport start would normally block on stdin; a throwing hook stands in
                // for it. The guard must fire before this is ever invoked.
                onBeforeTransportStart = { _: String ->
                    transportStarted.set(true)
                    throw IllegalStateException("transport must not be reached")
                }
            )

        val ex = assertFailsWith<IllegalArgumentException> { server.run() }

        assertTrue("actor_authentication" in ex.message.orEmpty(), "message must name the key: ${ex.message}")
        assertFalse(transportStarted.get(), "the transport start hook must not be reached")
        assertFalse(Files.exists(readinessFile), "readiness marker must not exist when startup fails")
    }

    // -------------------------------------------------------------------------
    // S6 -- failure, preserved: a set DEGRADED_MODE_POLICY fails startup whatever the value.
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "S6 DEGRADED_MODE_POLICY value [{index}] fails build with no config file")
    @ValueSource(strings = ["reject", "accept-cached", ""])
    fun `S6 a set DEGRADED_MODE_POLICY fails build naming the variable`(
        value: String,
        @TempDir tempDir: Path
    ) {
        val ex =
            assertFailsWith<IllegalArgumentException>("value '$value'") {
                build(tempDir, mapOf("DEGRADED_MODE_POLICY" to value))
            }

        assertTrue("DEGRADED_MODE_POLICY" in ex.message.orEmpty(), "message must name the variable: ${ex.message}")
    }

    @Test
    fun `S6 a whitespace-only DEGRADED_MODE_POLICY still fails build`(
        @TempDir tempDir: Path
    ) {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                build(tempDir, mapOf("DEGRADED_MODE_POLICY" to "  "))
            }

        assertTrue("DEGRADED_MODE_POLICY" in ex.message.orEmpty(), "message must name the variable: ${ex.message}")
    }

    @Test
    fun `S6 an unset DEGRADED_MODE_POLICY with no config file builds`(
        @TempDir tempDir: Path
    ) {
        assertNotNull(build(tempDir))
    }

    // -------------------------------------------------------------------------
    // S7 -- failure, preserved: the legacy auditing key fails startup, without an actor_authentication hint.
    // -------------------------------------------------------------------------

    @Test
    fun `S7 a legacy auditing key fails build naming auditing and offering no did-you-mean hint`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "auditing:\n  enabled: true\n$base")

        val ex = assertFailsWith<IllegalArgumentException> { build(tempDir) }

        val message = ex.message.orEmpty()
        assertTrue("auditing" in message, "message must name the key: $message")
        assertFalse("Did you mean" in message, "no rename hint any more: $message")
    }

    // -------------------------------------------------------------------------
    // S8 -- happy: GET /api/v1/info no longer carries actorAuthenticationEnabled.
    // -------------------------------------------------------------------------

    @Test
    fun `S8 info response carries no actorAuthenticationEnabled field and keeps the service fields`() =
        testApplication {
            application {
                configureTestApp {
                    serviceRoutes(
                        repositoryProvider = null,
                        serverName = "info-removed-test",
                        serverVersion = "9.9.9",
                        verifier = "jwks",
                    )
                }
            }

            val response = client.get("/api/v1/info") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }

            assertEquals(HttpStatusCode.OK, response.status)
            val data = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
            assertFalse("actorAuthenticationEnabled" in data.keys, "removed field must be gone: ${data.keys}")
            assertEquals("info-removed-test", data["serverName"]!!.jsonPrimitive.content)
            assertEquals("9.9.9", data["version"]!!.jsonPrimitive.content)
            assertEquals("jwks", data["verifier"]!!.jsonPrimitive.content)
        }

    // -------------------------------------------------------------------------
    // Probes
    // -------------------------------------------------------------------------

    @Test
    fun `probe differently cased Actor_Authentication key is not the removed key and builds`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "Actor_Authentication:\n  enabled: true\n$base")

        assertNotNull(build(tempDir))
    }

    @Test
    fun `probe an empty config file builds`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "")

        assertNotNull(build(tempDir))
    }

    @Test
    fun `probe a comment-only config file builds`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "# nothing configured\n")

        assertNotNull(build(tempDir))
    }

    @Test
    fun `probe the actor_authentication key plus a YAML syntax error still fails build with an IAE naming the config file`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "actor_authentication: [\n")

        val ex = assertFailsWith<IllegalArgumentException> { build(tempDir) }

        assertTrue("config.yaml" in ex.message.orEmpty(), "message must name the config file: ${ex.message}")
    }

    @Test
    fun `probe building twice over the same failing config fails the same way both times`(
        @TempDir tempDir: Path
    ) {
        writeConfig(tempDir, "actor_authentication:\n  enabled: true\n$base")

        val first = assertFailsWith<IllegalArgumentException> { build(tempDir) }
        val second = assertFailsWith<IllegalArgumentException> { build(tempDir) }

        assertEquals(first.message, second.message)
    }
}

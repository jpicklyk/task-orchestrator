package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.bootstrap.CurrentMcpServer
import io.github.jpicklyk.mcptask.current.bootstrap.Failed
import io.github.jpicklyk.mcptask.current.bootstrap.Reason
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8ae4c8e7, R14) for the `/mcp` authentication mode decision and its startup
 * predicate: [McpAuthMode.resolve] (pure), the `MCP_ALLOW_UNAUTHENTICATED` flag in [AppConfig.fromEnv], and
 * [CurrentMcpServer.run] failing with [Reason.MCP_AUTH]. Scenario S7 and the mode table of task-scope item 2.
 *
 * Oracles (written from the plan, never from a run): V9 (HTTP with the REST API disabled serves `/mcp` unauthenticated only when
 * `MCP_ALLOW_UNAUTHENTICATED=true`, otherwise startup fails naming the variable and the readiness marker is never written), V10
 * (stdio and `API_AUTH_MODE=none` run as the local principal and need no flag), task-scope item 2's mode table (stdio -> Local;
 * HTTP + Unauthenticated -> Local; HTTP + Disabled -> Local only with the flag, else a refusal naming `MCP_ALLOW_UNAUTHENTICATED`;
 * HTTP + Bearer or Jwks -> Credential, whatever the flag), item 1 (`EnvBoolean.require`: `true`/`1`/`yes` and `false`/`0`/`no`,
 * case-insensitive and trimmed; an unparseable value fails startup; unset is false), AC5, and the `TransportStartFailureTest`
 * precedent for driving `CurrentMcpServer.run` (a throwing `onBeforeTransportStart` stands in for the bind, so a run that gets
 * past authentication ends as TRANSPORT_START).
 *
 * Negative-assertion conditions (what makes each fail without the feature): the refusal is paired with the same configuration
 * plus the flag, which must NOT be MCP_AUTH (so a predicate that always refuses, or never refuses, fails one of the pair); each
 * "Local" row is paired with the Credential row of the same transport, so a resolver that always answers Local fails.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled. The startup tests read `API_ENABLED` only from the injected
 * environment map; they assume the process environment does not set it (the same assumption `TransportStartFailureTest` makes).
 *
 * Domain invariants satisfied by construction: the bearer config holds one plain token entry; the Jwks config is built with the
 * field set the loader produces (url, issuer, audience, algorithms, cache TTL).
 */
class McpAuthModeTest {
    private val clock: () -> Instant = { Instant.parse("2026-10-13T10:00:00Z") }

    private val bearerEntries: Map<HashBytes, BearerTokenStore.TokenEntry> =
        HashBytes(MessageDigest.getInstance("SHA-256").digest("mode-token".toByteArray())).let { key ->
            mapOf(
                key to
                    BearerTokenStore.TokenEntry(
                        Principal("svc", PrincipalKind.SERVICE, ProofStatus.ISSUED, AccessScope.unrestricted(), setOf(ApiCapability.READ)),
                        expiresAt = null
                    )
            )
        }

    private val jwks =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = "https://idp.test.example",
            audience = "task-orchestrator-api",
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300
        )

    private fun resolve(
        transport: String,
        api: ApiAuthConfig,
        allow: Boolean,
        entries: Map<HashBytes, BearerTokenStore.TokenEntry> = emptyMap()
    ): McpAuthResolution = McpAuthMode.resolve(transport, api, entries, null, { emptySet() }, clock, allow)

    private fun mode(resolution: McpAuthResolution): McpAuthMode = assertIs<McpAuthResolution.Resolved>(resolution).mode

    // ------------------------------------------------------------------ the pure decision

    @Test
    fun `the flag's environment variable name is spelled MCP_ALLOW_UNAUTHENTICATED`() {
        assertEquals("MCP_ALLOW_UNAUTHENTICATED", McpAuthMode.ALLOW_UNAUTHENTICATED_ENV)
    }

    @Test
    fun `stdio is Local whatever the API config and the flag are`() {
        val configs = listOf(ApiAuthConfig.Disabled, ApiAuthConfig.Unauthenticated, ApiAuthConfig.Bearer(tokens = bearerEntries))
        for (api in configs) {
            for (allow in listOf(false, true)) {
                assertEquals(McpAuthMode.Local, mode(resolve("stdio", api, allow, bearerEntries)), "stdio / $api / flag=$allow")
            }
        }
    }

    @Test
    fun `http with the API in unauthenticated mode is Local without the flag and with it`() {
        for (allow in listOf(false, true)) {
            assertEquals(McpAuthMode.Local, mode(resolve("http", ApiAuthConfig.Unauthenticated, allow)), "flag=$allow")
        }
    }

    @Test
    fun `http with the API disabled is refused without the flag, naming it, and Local with the flag`() {
        val refused = assertIs<McpAuthResolution.Refused>(resolve("http", ApiAuthConfig.Disabled, allow = false))
        assertTrue("MCP_ALLOW_UNAUTHENTICATED" in refused.detail, "the refusal names the variable: ${refused.detail}")

        assertEquals(McpAuthMode.Local, mode(resolve("http", ApiAuthConfig.Disabled, allow = true)), "control: the flag opens it")
    }

    @Test
    fun `http with the API in bearer mode is Credential and the flag changes nothing`() {
        val bearer = ApiAuthConfig.Bearer(tokens = bearerEntries)
        for (allow in listOf(false, true)) {
            val resolved = mode(resolve("http", bearer, allow, bearerEntries))
            assertIs<McpAuthMode.Credential>(resolved, "bearer / flag=$allow")
        }
    }

    @Test
    fun `http with the API in jwks mode is Credential even with no verifier, and the flag changes nothing`() {
        for (allow in listOf(false, true)) {
            assertIs<McpAuthMode.Credential>(mode(resolve("http", jwks, allow)), "jwks / flag=$allow")
        }
    }

    // ------------------------------------------------------------------ the flag in AppConfig

    private fun flagFrom(value: String?): Boolean =
        AppConfig.fromEnv { if (it == "MCP_ALLOW_UNAUTHENTICATED") value else null }.mcpAllowUnauthenticated

    @Test
    fun `MCP_ALLOW_UNAUTHENTICATED is false when unset and does not follow the API flag`() {
        assertFalse(flagFrom(null))
        val apiFlagOnly = AppConfig.fromEnv { if (it == "API_ALLOW_UNAUTHENTICATED") "true" else null }
        assertFalse(apiFlagOnly.mcpAllowUnauthenticated, "the REST confirmation flag is not the MCP flag")
    }

    @Test
    fun `MCP_ALLOW_UNAUTHENTICATED accepts true 1 yes and false 0 no, case-insensitive and trimmed`() {
        for (value in listOf("true", "TRUE", "True", "1", "yes", "YES", " true ", "\tyes\t")) {
            assertTrue(flagFrom(value), "'$value' is true")
        }
        for (value in listOf("false", "FALSE", "0", "no", "NO", " false ")) {
            assertFalse(flagFrom(value), "'$value' is false")
        }
    }

    @Test
    fun `MCP_ALLOW_UNAUTHENTICATED with an unparseable value fails startup naming the variable`() {
        for (value in listOf("maybe", "2", "enabled", "truee")) {
            val failure = assertThrows<IllegalArgumentException>("'$value' must not be accepted") { flagFrom(value) }
            assertTrue("MCP_ALLOW_UNAUTHENTICATED" in failure.message.orEmpty(), "message names the variable: ${failure.message}")
        }
    }

    // ------------------------------------------------------------------ the startup predicate

    private fun appConfigFor(
        tempDir: Path,
        transport: String,
        flag: String?
    ): AppConfig {
        val env =
            buildMap {
                put("DATABASE_PATH", tempDir.resolve("mcp-auth-mode-${System.nanoTime()}.db").toString())
                put("MCP_TRANSPORT", transport)
                put("MCP_HTTP_HOST", "127.0.0.1")
                put("MCP_HTTP_PORT", "0")
                put("READINESS_FILE", tempDir.resolve("ready").toString())
                if (flag != null) put("MCP_ALLOW_UNAUTHENTICATED", flag)
            }
        return AppConfig.fromEnv { key -> env[key] }
    }

    private fun run(
        tempDir: Path,
        transport: String,
        flag: String?
    ) = CurrentMcpServer(
        version = "test",
        shutdownCoordinator = ShutdownCoordinator(),
        appConfig = appConfigFor(tempDir, transport, flag),
        onBeforeTransportStart = { throw IllegalStateException("bind boom") }
    ).run()

    @Test
    fun `S7 http with the API disabled and the flag unset or false fails startup as MCP_AUTH naming the flag and writes no marker`(
        @TempDir tempDir: Path
    ) {
        for (flag in listOf(null, "false")) {
            val outcome = run(tempDir, "http", flag)
            assertTrue(outcome is Failed, "flag=$flag: expected Failed, got $outcome")
            assertEquals(Reason.MCP_AUTH, outcome.reason, "flag=$flag: ${outcome.detail}")
            assertTrue("MCP_ALLOW_UNAUTHENTICATED" in outcome.detail, "the detail names the variable: ${outcome.detail}")
            assertFalse(Files.exists(tempDir.resolve("ready")), "flag=$flag: the readiness marker is never written")
        }
    }

    @Test
    fun `S7 control - http with the API disabled and the flag true gets past authentication to the transport start`(
        @TempDir tempDir: Path
    ) {
        for (flag in listOf("true", "1", "yes")) {
            val outcome = run(tempDir, "http", flag)
            assertTrue(outcome is Failed, "flag=$flag: expected the stand-in bind failure, got $outcome")
            assertEquals(Reason.TRANSPORT_START, outcome.reason, "flag=$flag: ${outcome.detail}")
            assertTrue("bind boom" in outcome.detail, outcome.detail)
        }
    }

    @Test
    fun `S7 control - stdio needs no flag and is never an MCP_AUTH failure`(
        @TempDir tempDir: Path
    ) {
        for (flag in listOf(null, "false")) {
            val outcome = run(tempDir, "stdio", flag)
            assertTrue(outcome is Failed, "flag=$flag: expected the stand-in bind failure, got $outcome")
            assertEquals(Reason.TRANSPORT_START, outcome.reason, "flag=$flag: ${outcome.detail}")
        }
    }
}

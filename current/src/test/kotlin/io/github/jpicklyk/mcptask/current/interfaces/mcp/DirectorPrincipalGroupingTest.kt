package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.report.ReportFixture
import io.github.jpicklyk.mcptask.current.application.report.ReportKit
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.McpResult
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
 * Independent tests (test-author seat, item f65fef01, R29) for scenario S16 (r16 review 3): `report` `usage` grouped by `principal` on
 * the director profile (`/mcp/director`) under each way a caller can be authenticated.
 *
 * Oracle (never read from a run), R9 AC4 and the report docs: grouping by `principal` needs the `admin` capability, else the call is
 * `forbidden` with scope `capability` and required `admin`; a credential that carries `admin` is answered; the MCP profiles run as
 * the local principal when no credential is in play (stdio, or HTTP without a bearer mode), and the local principal is unrestricted.
 * A caller without `admin` still gets every other dimension (the default `tool` grouping is the control).
 *
 * Method: the same tool dispatcher and director registration the HTTP mount serves, over an in-memory SDK client/server pair, with the
 * access seam set to each caller (`parityAccess` of a credential, or `CallAccess.LOCAL`). The mount itself (path, bearer parsing) is
 * covered by `McpDirectorProfileTest` and `McpBearerAuthTest`; what differs per auth mode is the access this test varies.
 *
 * Negative-assertion fixture conditions: the forbidden call is paired with the admin and local calls over the same seeded call-log
 * row (principal `pid-a`), which DO return that row, so the refusal is the access decision and not an empty table.
 *
 * Domain invariants satisfied by construction: the call-log row is built by [ReportKit.rec] (outcome derived from the error code).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class DirectorPrincipalGroupingTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())
    private val reader = ParityToken("grp-reader", setOf(ApiCapability.READ))
    private val admin = ParityToken("grp-admin", setOf(ApiCapability.READ, ApiCapability.ADMIN))

    private fun rig(): EventLogRig {
        val rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG, clock)
        runBlocking {
            ReportFixture(db.db, clock).seed(
                ReportKit.rec(1, ReportKit.NOW.minusSeconds(3_600), "x_tool", principalId = "pid-a", principalKind = "subagent")
            )
        }
        return rig
    }

    private fun usage(
        rig: EventLogRig,
        access: CallAccess,
        arguments: String
    ): McpResult =
        runBlocking {
            callMcp(
                rig.composition.toolContext,
                registry,
                { _: String? -> access },
                "report",
                Json.parseToJsonElement(arguments).jsonObject,
                ToolProfile.DIRECTOR
            )
        }

    private val byPrincipal = """{"select":"usage","groupBy":"principal"}"""

    @Test
    fun `S16 a credential without admin is forbidden on the capability admin when it groups by principal`() {
        val result = usage(rig(), parityAccess(reader), byPrincipal)
        val body = result.structured.toString()

        assertTrue(result.isError, body)
        assertTrue("\"code\":\"forbidden\"" in body, body)
        assertTrue("\"scope\":\"capability\"" in body && "\"required\":\"admin\"" in body, body)
        assertFalse("pid-a" in body, "no principal is named in the refusal: $body")
    }

    @Test
    fun `S16 a credential with admin is answered and sees the principal`() {
        val result = usage(rig(), parityAccess(admin), byPrincipal)
        val body = result.structured.toString()

        assertFalse(result.isError, body)
        assertTrue("\"principal\":\"pid-a\"" in body, "the group key is the recorded principal: $body")
        assertEquals(1, Regex("\"principal\":\"pid-a\"").findAll(body).count(), "one group: $body")
    }

    @Test
    fun `S16 the local principal of stdio and of an HTTP mount without bearer auth is answered`() {
        val result = usage(rig(), CallAccess.LOCAL, byPrincipal)
        val body = result.structured.toString()

        assertFalse(result.isError, body)
        assertTrue("\"principal\":\"pid-a\"" in body, body)
    }

    @Test
    fun `S16 control - the same credential without admin is answered when it groups by tool`() {
        val result = usage(rig(), parityAccess(reader), """{"select":"usage"}""")
        val body = result.structured.toString()

        assertFalse(result.isError, "the refusal is about the dimension, not the report: $body")
        assertTrue("\"tool\":\"x_tool\"" in body, body)
    }
}

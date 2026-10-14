package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.CfgYaml
import io.github.jpicklyk.mcptask.current.parity.McpResult
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
import io.github.jpicklyk.mcptask.current.parity.ParityHarness
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.RunsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests (third test-author seat, item 8df44a22, R20 fix round) for review findings N1 and N5.
 *
 * N1 oracle (fix declarations): under a restricted scope an unknown full UUID, an unknown prefix and a prefix whose candidates
 * are all out of scope give the SAME `forbidden` as an existing out-of-scope run (the item no-match shape of ScopedIdsTest:
 * scope is no existence oracle); an ambiguous prefix keeps only admitted candidates (one resolves, two or more list only
 * those); an unrestricted scope keeps `not_found` / `ambiguous_id`. N5 oracle: the ceiling breach reason states the ceiling in
 * decimal USD (the field's unit), never in micro-USD.
 *
 * Negative-assertion conditions: every forbidden case is paired with an in-scope control that resolves; every refusal also
 * asserts the database snapshot is unchanged.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class RunsIdScopeAndCeilingTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())

    private val r1 = RunsParityScenarios.r1
    private val r2 = RunsParityScenarios.r2

    // Two admitted (root R1) and one foreign (root R2) run sharing the prefix f1000000.
    private val x1 = UUID.fromString("f1000000-0000-4000-8000-000000000001")
    private val x2 = UUID.fromString("f1000000-0000-4000-8000-000000000002")
    private val x3 = UUID.fromString("f1000000-0000-4000-8000-000000000003")

    // One admitted and one foreign run sharing the prefix f2000000.
    private val y1 = UUID.fromString("f2000000-0000-4000-8000-000000000001")
    private val y2 = UUID.fromString("f2000000-0000-4000-8000-000000000002")

    // Only a foreign run carries the prefix f3000000.
    private val z1 = UUID.fromString("f3000000-0000-4000-8000-000000000001")

    // Two foreign runs (root R2) sort before the only admitted run (root R1) of the prefix f4000000.
    private val w1 = UUID.fromString("f4000000-0000-4000-8000-000000000001")
    private val w2 = UUID.fromString("f4000000-0000-4000-8000-000000000002")
    private val w3 = UUID.fromString("f4000000-0000-4000-8000-000000000003")

    private val caps = setOf(ApiCapability.RUNS, ApiCapability.READ)
    private val rootScoped = ParityToken("ids-root", caps, rootIds = setOf(r1))
    private val tagScoped = ParityToken("ids-tag", caps, tagsInclude = setOf("alpha"))
    private val humanRoot = RunsParityScenarios.humanRootScoped
    private val humanOpen = RunsParityScenarios.human

    private fun rig(seed: suspend ParitySeeder.() -> Unit): EventLogRig {
        val rig = EventLogRig.build(db.db, dir, clock = PARITY_CLOCK)
        runBlocking {
            val provider = db.db.repositoryProvider()
            ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork()).seed()
        }
        return rig
    }

    private val seeded: suspend ParitySeeder.() -> Unit = {
        with(RunsParityScenarios) { tree() }
        run(x1, r1, openedBy = "api:ids", openedByKind = "service")
        run(x2, r1, openedBy = "api:ids", openedByKind = "service")
        run(x3, r2, openedBy = "api:ids", openedByKind = "service")
        run(y1, r1, openedBy = "api:ids", openedByKind = "service")
        run(y2, r2, openedBy = "api:ids", openedByKind = "service")
        run(z1, r2, openedBy = "api:ids", openedByKind = "service")
        run(w1, r2, openedBy = "api:ids", openedByKind = "service")
        run(w2, r2, openedBy = "api:ids", openedByKind = "service")
        run(w3, r1, openedBy = "api:ids", openedByKind = "service")
    }

    private fun mcp(
        rig: EventLogRig,
        token: ParityToken,
        tool: String,
        arguments: String,
        profile: ToolProfile
    ): McpResult =
        runBlocking {
            callMcp(
                rig.composition.toolContext,
                registry,
                { _: String? -> parityAccess(token) },
                tool,
                Json.parseToJsonElement(arguments).jsonObject,
                profile
            )
        }

    private fun get(
        rig: EventLogRig,
        token: ParityToken,
        id: Any
    ) = mcp(rig, token, "runs_read", RunsParityScenarios.get(id), ToolProfile.ORCHESTRATION)

    private fun pause(
        rig: EventLogRig,
        token: ParityToken,
        id: Any
    ) = mcp(rig, token, "runs_write", RunsParityScenarios.ops(RunsParityScenarios.pause(id)), ToolProfile.DIRECTOR)

    private fun text(result: McpResult) = result.structured.toString()

    private fun assertScopeForbidden(
        label: String,
        result: McpResult,
        scope: String,
        required: String
    ) {
        val body = text(result)
        assertTrue(result.isError, "$label: ${result.structured}")
        assertTrue("\"code\":\"forbidden\"" in body, "$label: ${result.structured}")
        assertTrue("\"scope\":\"$scope\"" in body && "\"required\":\"$required\"" in body, "$label: ${result.structured}")
        assertFalse("not_found" in body || "ambiguous_id" in body, "$label: scope must be no existence oracle: ${result.structured}")
    }

    // ------------------------------------------------------------------ N1 runs_read get

    @Test
    fun `N1 root scope - an unknown uuid, an unknown prefix and a foreign-only prefix give the forbidden of an out-of-scope run`() {
        val rig = rig(seeded)
        val before = ParityHarness.snapshot(db.db.jdbcUrl)

        assertScopeForbidden("existing out-of-scope run", get(rig, rootScoped, z1), "root", r1.toString())
        assertScopeForbidden("unknown uuid", get(rig, rootScoped, RunsParityScenarios.ghost), "root", r1.toString())
        assertScopeForbidden("unknown prefix", get(rig, rootScoped, "dddddddd"), "root", r1.toString())
        assertScopeForbidden("foreign-only prefix", get(rig, rootScoped, "f3000000"), "root", r1.toString())
        assertEquals(before, ParityHarness.snapshot(db.db.jdbcUrl))

        // control: an in-scope run resolves under the same token
        val control = get(rig, rootScoped, y1)
        assertFalse(control.isError, control.structured.toString())
        assertTrue("\"runId\":\"$y1\"" in text(control), control.structured.toString())
    }

    @Test
    fun `N1 tag scope - an unknown uuid, an unknown prefix and a foreign-only prefix are forbidden, never not_found`() {
        val rig = rig(seeded)
        val cases =
            listOf(
                "unknown uuid" to RunsParityScenarios.ghost.toString(),
                "unknown prefix" to "dddddddd",
                "foreign-only prefix" to "f3000000"
            )
        for ((label, id) in cases) {
            val result = get(rig, tagScoped, id)
            val body = text(result)
            assertTrue(result.isError, "$label: ${result.structured}")
            assertTrue("\"code\":\"forbidden\"" in body, "$label: ${result.structured}")
            assertFalse("not_found" in body || "ambiguous_id" in body, "$label: ${result.structured}")
        }
        val control = get(rig, tagScoped, y1)
        assertFalse(control.isError, control.structured.toString())
    }

    @Test
    fun `N1 an ambiguous prefix under a restricted scope lists only the admitted candidates`() {
        val rig = rig(seeded)
        for (token in listOf(rootScoped, tagScoped)) {
            val result = get(rig, token, "f1000000")
            val body = text(result)
            assertTrue(result.isError, "${token.id}: ${result.structured}")
            assertTrue("\"code\":\"ambiguous_id\"" in body, "${token.id}: ${result.structured}")
            assertTrue("$x1" in body && "$x2" in body, "${token.id}: both admitted candidates are listed: ${result.structured}")
            assertFalse("$x3" in body, "${token.id}: the foreign candidate is never named: ${result.structured}")
        }
    }

    @Test
    fun `N1 a prefix with a single admitted candidate resolves to it under a restricted scope`() {
        val rig = rig(seeded)
        for (token in listOf(rootScoped, tagScoped)) {
            val result = get(rig, token, "f2000000")
            assertFalse(result.isError, "${token.id}: ${result.structured}")
            assertTrue("\"runId\":\"$y1\"" in text(result), "${token.id}: ${result.structured}")
            assertFalse("$y2" in text(result), "${token.id}: ${result.structured}")
        }
    }

    @Test
    fun `N1 the only admitted candidate resolves even when several foreign runs share the prefix`() {
        val rig = rig(seeded)
        val result = get(rig, rootScoped, "f4000000")
        assertFalse(result.isError, result.structured.toString())
        assertTrue("\"runId\":\"$w3\"" in text(result), result.structured.toString())
        assertFalse("$w1" in text(result) || "$w2" in text(result), result.structured.toString())
    }

    @Test
    fun `N1 an unrestricted scope keeps not_found for unknown ids and ambiguous_id listing every candidate`() {
        val rig = rig(seeded)
        val reader = RunsParityScenarios.reader
        val unknownUuid = get(rig, reader, RunsParityScenarios.ghost)
        val unknownPrefix = get(rig, reader, "dddddddd")
        assertTrue("\"code\":\"not_found\"" in text(unknownUuid), unknownUuid.structured.toString())
        assertTrue("\"code\":\"not_found\"" in text(unknownPrefix), unknownPrefix.structured.toString())
        assertFalse("forbidden" in text(unknownUuid) || "forbidden" in text(unknownPrefix))

        val ambiguous = get(rig, reader, "f1000000")
        assertTrue("\"code\":\"ambiguous_id\"" in text(ambiguous), ambiguous.structured.toString())
        assertTrue(listOf(x1, x2, x3).count { "$it" in text(ambiguous) } >= 2, ambiguous.structured.toString())
    }

    // ------------------------------------------------------------------ N1 runs_write id-taking ops

    @Test
    fun `N1 a write op by an unknown or foreign-only id under a restricted scope is the same forbidden and writes nothing`() {
        val rig = rig(seeded)
        val before = ParityHarness.snapshot(db.db.jdbcUrl)
        val ops =
            listOf<Pair<String, (Any) -> Pair<String, ToolProfile>>>(
                "pause" to { id -> RunsParityScenarios.ops(RunsParityScenarios.pause(id)) to ToolProfile.DIRECTOR },
                "resume" to { id -> RunsParityScenarios.ops(RunsParityScenarios.resume(id)) to ToolProfile.DIRECTOR },
                "close" to { id -> RunsParityScenarios.ops(RunsParityScenarios.close(id, "cancelled")) to ToolProfile.DIRECTOR },
                "update" to { id ->
                    RunsParityScenarios.ops(RunsParityScenarios.update(id, ""","hints":{"x":"y"}""")) to ToolProfile.ORCHESTRATION
                }
            )
        for ((name, build) in ops) {
            for (id in listOf<Any>(RunsParityScenarios.ghost, "dddddddd", "f3000000")) {
                val (args, profile) = build(id)
                val result = mcp(rig, humanRoot, "runs_write", args, profile)
                val body = text(result)
                assertTrue(result.isError, "$name $id: ${result.structured}")
                assertTrue("\"code\":\"forbidden\"" in body, "$name $id: ${result.structured}")
                assertTrue("\"scope\":\"root\"" in body && "\"required\":\"$r1\"" in body, "$name $id: ${result.structured}")
                assertFalse("not_found" in body, "$name $id: scope must be no existence oracle: ${result.structured}")
            }
        }
        assertEquals(before, ParityHarness.snapshot(db.db.jdbcUrl), "the refused calls wrote nothing")
    }

    @Test
    fun `N1 a write op by an ambiguous prefix lists only admitted candidates, and a single admitted candidate resolves`() {
        val rig = rig(seeded)
        val ambiguous = pause(rig, humanRoot, "f1000000")
        val body = text(ambiguous)
        assertTrue(ambiguous.isError, ambiguous.structured.toString())
        assertTrue("\"code\":\"ambiguous_id\"" in body, ambiguous.structured.toString())
        assertTrue("$x1" in body && "$x2" in body, ambiguous.structured.toString())
        assertFalse("$x3" in body, "the foreign candidate is never named: ${ambiguous.structured}")

        val single = pause(rig, humanRoot, "f2000000")
        assertFalse(single.isError, single.structured.toString())
        assertTrue("\"status\":\"paused\"" in text(single) && "\"runId\":\"$y1\"" in text(single), single.structured.toString())
    }

    @Test
    fun `N1 a write op under an unrestricted scope keeps not_found and the full ambiguous list`() {
        val rig = rig(seeded)
        val unknown = pause(rig, humanOpen, "dddddddd")
        assertTrue("\"code\":\"not_found\"" in text(unknown), unknown.structured.toString())
        val ambiguous = pause(rig, humanOpen, "f1000000")
        assertTrue("\"code\":\"ambiguous_id\"" in text(ambiguous), ambiguous.structured.toString())
        assertTrue(listOf(x1, x2, x3).count { "$it" in text(ambiguous) } >= 2, ambiguous.structured.toString())
    }

    // ------------------------------------------------------------------ N5

    @Test
    fun `N5 a costUsd ceiling breach states the ceiling in decimal USD and never the micro-USD figure`() {
        val rig =
            rig {
                with(RunsParityScenarios) { tree() }
                config(r1, CfgYaml.rev(1) + "runs:\n  budgets:\n    cost_usd: 2.5\n")
            }
        val before = ParityHarness.snapshot(db.db.jdbcUrl)

        val over =
            mcp(
                rig,
                RunsParityScenarios.coord,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.open(r1, ""","budget":{"costUsd":3}""")),
                ToolProfile.ORCHESTRATION
            )
        val body = text(over)
        assertTrue(over.isError, over.structured.toString())
        assertTrue("/budget/costUsd" in body, "the refusal names the field: ${over.structured}")
        assertTrue("2.5" in body, "the ceiling is stated in USD: ${over.structured}")
        assertFalse("2500000" in body, "the micro-USD figure never reaches the reason: ${over.structured}")
        assertEquals(before, ParityHarness.snapshot(db.db.jdbcUrl), "a refused open stores nothing")

        // control: a budget at the ceiling is accepted
        val atCeiling =
            mcp(
                rig,
                RunsParityScenarios.coord,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.open(r1, ""","budget":{"costUsd":2.5}""")),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(atCeiling.isError, atCeiling.structured.toString())
        assertTrue("\"costUsd\":2.5" in text(atCeiling), atCeiling.structured.toString())
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.PlannedSurfaceChecks
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDescriber
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.visibleNames
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
import io.github.jpicklyk.mcptask.current.parity.ParityHarness
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.RunsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.parity.parityCoordinatorToken
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ToolGoldens
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Independent tests (test-author seat, item 8df44a22, R20) for the registered surface of `runs_read` / `runs_write` and for the
 * MCP-only behaviour the parity harness cannot state: scenarios S13 (the orchestration profile has no `pause`), S18 (`mark` is on no
 * MCP profile), S20 (sets, goldens, deltas, director allow-lists, token totals) and the MCP attribution rules (D-kind, D-coord).
 *
 * Oracles (never read from a run): plan 3.4c / task-scope "Members and placement" (`runs_read` get and list in orchestration and
 * director, capability READ; `runs_write` open and close in both, update in orchestration only, pause and resume in director only,
 * mark with the EMPTY set, every op capability RUNS, close the only destructive op, registered after `report`), the declared error
 * sets (open NOT_FOUND / AMBIGUOUS_ID / FORBIDDEN / DUPLICATE; mark and get NOT_FOUND / AMBIGUOUS_ID / FORBIDDEN; list NOT_FOUND /
 * AMBIGUOUS_ID), R5 AC5 (a member with an empty set is unknown on every MCP profile: `invalid_request` at `/ops/0/op`, nothing written; for an ops tool that is a failed ELEMENT, so the whole call is the 207 `partial_failure` of the envelope spec, resolved from api-rest.md and DirectorOpsRouteTest, not from the implementation), the frozen
 * ceilings of R5 AC8/AC9 (a tool entry at most 8,000 compact characters; profile totals 22,000 / 42,000 / 14,000; production plus
 * planned within each) and the stop rule of 3.4c, decision D-goldens (real goldens for the four profile/tool pairs, the four deltas
 * for the members R20 does not ship, no full planned file left), D-kind (`openedByKind` is `agent` for a lifted body actor whatever
 * kind it declares, absent with no actor) and D-coord (the coordinator is the caller whose id equals `openedBy`, null matching null).
 *
 * Negative-assertion conditions: every "hidden" call is paired with the same call on a profile that contains the member and
 * succeeds; every refusal also asserts the run and event tables are unchanged.
 *
 * Domain invariants satisfied by construction: runs are seeded through `ParitySeeder.run` (state fields derived from the status);
 * items are children of their run's root; tokens are `ParityToken`s whose principal is built by the harness.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class RunsToolsSurfaceTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())

    private val read get() = registry["runs_read"] as SelectToolSpec
    private val write get() = registry["runs_write"] as OpsToolSpec<*>

    private fun readMember(name: String) = read.selects.single { it.name == name }

    private fun writeMember(name: String) = write.ops.single { it.name == name }

    // ------------------------------------------------------------------ registration

    @Test
    fun `both tools are registered after report, runs_read as a select tool and runs_write as an ops tool`() {
        val names = registry.tools.map { it.name }
        assertTrue("runs_read" in names && "runs_write" in names, "registered: $names")
        assertTrue(names.indexOf("runs_read") > names.indexOf("report"), "runs_read is registered after report: $names")
        assertTrue(names.indexOf("runs_write") > names.indexOf("report"), "runs_write is registered after report: $names")
        assertNotNull(registry["runs_read"] as? SelectToolSpec)
        assertNotNull(registry["runs_write"] as? OpsToolSpec<*>)
    }

    @Test
    fun `the members are exactly get list seats frontier decisions decision, and the twelve write ops`() {
        // R22 adds the `seats` select and the `assign` / `end_seat` ops; R25 adds the `frontier` select (task-scope Build 3);
        // R24 adds the `decisions` / `decision` selects and the `raise` / `ack` / `answer` / `withdraw` ops.
        assertEquals(setOf("get", "list", "seats", "frontier", "decisions", "decision"), read.selects.map { it.name }.toSet())
        assertEquals(6, read.selects.size)
        assertEquals(
            setOf("open", "update", "close", "pause", "resume", "mark", "assign", "end_seat", "raise", "ack", "answer", "withdraw"),
            write.ops.map { it.name }.toSet()
        )
        assertEquals(12, write.ops.size)
    }

    @Test
    fun `profile sets follow plan 3-4c - mark is the empty set, pause and resume director only, update orchestration only`() {
        val both = setOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR)
        assertEquals(both, readMember("get").profiles)
        assertEquals(both, readMember("list").profiles)
        // R25 (task-scope Build 3, "profiles orchestration()+director()"): the frontier is served on both profiles
        assertEquals(both, readMember("frontier").profiles)
        assertEquals(both, writeMember("open").profiles)
        assertEquals(both, writeMember("close").profiles)
        assertEquals(setOf(ToolProfile.ORCHESTRATION), writeMember("update").profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), writeMember("pause").profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), writeMember("resume").profiles)
        assertEquals(emptySet(), writeMember("mark").profiles, "mark is REST only (R5 empty-set contract)")
        // R22: the seat members are orchestration only (the director never assigns or ends a seat)
        assertEquals(setOf(ToolProfile.ORCHESTRATION), readMember("seats").profiles)
        assertEquals(setOf(ToolProfile.ORCHESTRATION), writeMember("assign").profiles)
        assertEquals(setOf(ToolProfile.ORCHESTRATION), writeMember("end_seat").profiles)
        // R24 (task-scope "Placement"): `raise` is orchestration; ack, answer, withdraw and both decision reads are director only.
        assertEquals(setOf(ToolProfile.ORCHESTRATION), writeMember("raise").profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), writeMember("ack").profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), writeMember("answer").profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), writeMember("withdraw").profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), readMember("decisions").profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), readMember("decision").profiles)
    }

    @Test
    fun `reads need READ, ack and answer need DECIDE, every other write op needs RUNS, and close is destructive`() {
        assertTrue(read.selects.all { it.capability == ApiCapability.READ }, "reads need READ (decisions and decision included)")
        // R24 (task-scope D6): raise and withdraw are RUNS, ack and answer are DECIDE.
        assertEquals(
            setOf("ack", "answer"),
            write.ops
                .filter { it.capability == ApiCapability.DECIDE }
                .map { it.name }
                .toSet(),
            "ack and answer need the decide capability"
        )
        assertTrue(
            write.ops.filter { it.name !in setOf("ack", "answer") }.all { it.capability == ApiCapability.RUNS },
            "every other write op needs RUNS (assign, end_seat, raise and withdraw included)"
        )
        val destructive = write.ops.filter { it.destructive }.map { it.name }
        assertTrue("close" in destructive, "close is destructive")
        assertTrue(
            destructive.none { it in setOf("open", "update", "pause", "resume", "mark", "assign", "end_seat", "raise", "ack", "answer") },
            "only close and possibly withdraw are destructive: $destructive"
        )
    }

    @Test
    fun `the declared error sets of open, mark, get and list`() {
        assertEquals(
            setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.DUPLICATE),
            writeMember("open").errors
        )
        assertEquals(setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN), writeMember("mark").errors)
        assertEquals(setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN), readMember("get").errors)
        assertEquals(setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID), readMember("list").errors)
    }

    // ------------------------------------------------------------------ visibility

    private fun visible(
        tool: String,
        profile: ToolProfile
    ): Set<String> = visibleNames(registry[tool]!!, profile).toSet()

    @Test
    fun `visibility per profile - knowledge sees neither tool, mark is on no profile`() {
        assertEquals(emptySet(), visible("runs_read", ToolProfile.KNOWLEDGE))
        assertEquals(emptySet(), visible("runs_write", ToolProfile.KNOWLEDGE))
        assertEquals(setOf("get", "list", "seats", "frontier"), visible("runs_read", ToolProfile.ORCHESTRATION))
        assertEquals(setOf("open", "update", "close", "assign", "end_seat", "raise"), visible("runs_write", ToolProfile.ORCHESTRATION))
        assertEquals(setOf("get", "list", "frontier", "decisions", "decision"), visible("runs_read", ToolProfile.DIRECTOR))
        assertEquals(
            setOf("open", "close", "pause", "resume", "ack", "answer", "withdraw"),
            visible("runs_write", ToolProfile.DIRECTOR)
        )
        for (profile in ToolProfile.entries) {
            assertFalse("mark" in visible("runs_write", profile), "mark must be absent on ${profile.wire}")
        }
        assertTrue(registry.visible(ToolProfile.KNOWLEDGE).none { it.name.startsWith("runs_") })
        assertTrue(registry.visible(ToolProfile.ORCHESTRATION).map { it.name }.containsAll(listOf("runs_read", "runs_write")))
        assertTrue(registry.visible(ToolProfile.DIRECTOR).map { it.name }.containsAll(listOf("runs_read", "runs_write")))
    }

    private fun rendered(
        tool: String,
        profile: ToolProfile
    ): JsonObject =
        McpJson
            .encodeToJsonElement(
                Tool.serializer(),
                assertNotNull(McpOperationAdapter.toolFor(registry[tool]!!, profile), "$tool on ${profile.wire}")
            ).jsonObject

    private fun opEnum(tool: JsonObject): Set<String> =
        tool
            .getValue("inputSchema")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("ops")
            .jsonObject
            .getValue("items")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("op")
            .jsonObject
            .getValue("enum")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

    @Test
    fun `the rendered op enum is the visible set of the profile and never carries mark`() {
        assertEquals(
            setOf("open", "update", "close", "assign", "end_seat", "raise"),
            opEnum(rendered("runs_write", ToolProfile.ORCHESTRATION))
        )
        assertEquals(
            setOf("open", "close", "pause", "resume", "ack", "answer", "withdraw"),
            opEnum(rendered("runs_write", ToolProfile.DIRECTOR))
        )
        assertNull(McpOperationAdapter.toolFor(registry["runs_write"]!!, ToolProfile.KNOWLEDGE), "control: knowledge renders no runs_write")
        assertNull(McpOperationAdapter.toolFor(registry["runs_read"]!!, ToolProfile.KNOWLEDGE))
    }

    @Test
    fun `the rendered annotations - reads are read-only, writes are destructive because close is visible in both profiles`() {
        for (profile in listOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR)) {
            val readAnnotations = rendered("runs_read", profile).getValue("annotations").jsonObject
            assertEquals("true", readAnnotations.getValue("readOnlyHint").jsonPrimitive.content, "runs_read ${profile.wire}")
            val writeAnnotations = rendered("runs_write", profile).getValue("annotations").jsonObject
            assertEquals("false", writeAnnotations.getValue("readOnlyHint").jsonPrimitive.content, "runs_write ${profile.wire}")
            assertEquals("true", writeAnnotations.getValue("destructiveHint").jsonPrimitive.content, "runs_write ${profile.wire}")
        }
    }

    @Test
    fun `describe lists the visible ops of the profile and no mark`() {
        fun described(profile: ToolProfile): List<String> =
            ToolDescriber
                .describe(registry["runs_write"]!!, profile, null)
                .getValue("operations")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content
                }
        assertEquals(setOf("open", "update", "close", "assign", "end_seat", "raise"), described(ToolProfile.ORCHESTRATION).toSet())
        assertEquals(setOf("open", "close", "pause", "resume", "ack", "answer", "withdraw"), described(ToolProfile.DIRECTOR).toSet())
        val selects =
            ToolDescriber
                .describe(registry["runs_read"]!!, ToolProfile.DIRECTOR, null)
                .getValue("operations")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content
                }
        assertEquals(setOf("get", "list", "frontier", "decisions", "decision"), selects.toSet())
    }

    // ------------------------------------------------------------------ S20 budget, goldens, deltas

    @Test
    fun `S20 the runs tool entries fit the per-tool ceiling and the budget guards hold`() {
        for (profile in listOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR)) {
            val sizes = ToolSurfaceChecks.sizes(registry, profile)
            for (tool in listOf("runs_read", "runs_write")) {
                val size = assertNotNull(sizes[tool], "$tool is sized on ${profile.wire}")
                assertTrue(size in 1..ToolSurfaceChecks.PER_TOOL_CEILING, "$tool on ${profile.wire} is $size compact characters")
            }
        }
        assertEquals(emptyList(), ToolSurfaceChecks.docViolations(registry).filter { it.startsWith("runs_") })
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(registry))
    }

    @Test
    fun `S20 production plus planned stays within the frozen profile ceilings, the stop rule of 3-4c`() {
        val totals = PlannedSurfaceChecks.totals(registry)
        assertEquals(22_000, totals.getValue(ToolProfile.KNOWLEDGE).ceiling)
        assertEquals(42_000, totals.getValue(ToolProfile.ORCHESTRATION).ceiling)
        assertEquals(14_000, totals.getValue(ToolProfile.DIRECTOR).ceiling)
        for ((profile, t) in totals) {
            assertTrue(
                t.sum <= t.ceiling,
                "${profile.wire}: production ${t.production} + planned ${t.planned} = ${t.sum} exceeds ${t.ceiling}"
            )
        }
        assertEquals(emptyList(), PlannedSurfaceChecks.violations(registry))
    }

    private fun resource(name: String) = RunsToolsSurfaceTest::class.java.getResource("/golden/tools/$name")

    @Test
    fun `S20 real goldens exist for the four profile and tool pairs, none for knowledge, and none names mark`() {
        for (file in listOf(
            "runs_read.orchestration.json",
            "runs_read.director.json",
            "runs_write.orchestration.json",
            "runs_write.director.json"
        )) {
            val golden = assertNotNull(resource(file), "golden $file")
            val text = golden.readText()
            val entry = McpJson.decodeFromString(Tool.serializer(), text)
            assertEquals(file.substringBefore('.'), entry.name, "$file names its tool")
            assertTrue(ToolGoldens.compact(entry).length <= ToolSurfaceChecks.PER_TOOL_CEILING, "$file fits the per-tool ceiling")
            assertFalse("\"mark\"" in text, "$file must not offer the REST-only mark")
        }
        assertNull(resource("runs_read.knowledge.json"))
        assertNull(resource("runs_write.knowledge.json"))
    }

    @Test
    fun `S20 the director golden of runs_write offers pause and resume and the orchestration golden does not`() {
        val orchestration = assertNotNull(resource("runs_write.orchestration.json")).readText()
        val director = assertNotNull(resource("runs_write.director.json")).readText()
        assertTrue("\"pause\"" in director && "\"resume\"" in director, "director offers pause and resume")
        assertFalse("\"pause\"" in orchestration || "\"resume\"" in orchestration, "orchestration offers neither")
        assertTrue("\"update\"" in orchestration, "orchestration offers update")
        assertFalse("\"update\"" in director, "director does not offer update")
    }

    @Test
    fun `S20 the planned directory keeps no runs delta and no report delta after R28, and no full runs entry`() {
        for (file in listOf("runs_read.orchestration", "runs_read.director", "runs_write.orchestration", "runs_write.director")) {
            assertNull(resource("planned/$file.json"), "$file.json is stale once the tool is visible")
        }
        assertNull(resource("planned/runs_write.orchestration.delta.json"), "R26 shipped the `block` property, so its delta is gone")
        assertNull(resource("planned/runs_read.orchestration.delta.json"), "R22 shipped the seats select, so its delta is gone")
        // R24 shipped decisions, decision, ack, answer and withdraw: both director deltas are spent.
        assertNull(resource("planned/runs_read.director.delta.json"), "R24 shipped the decisions and decision selects")
        assertNull(resource("planned/runs_write.director.delta.json"), "R24 shipped ack, answer and withdraw")
        assertNull(resource("planned/report.director.delta.json"), "R28 shipped the rollup runId, so no planned file remains")
    }

    // ------------------------------------------------------------------ MCP behaviour

    private val coord = RunsParityScenarios.coord
    private val human = RunsParityScenarios.human

    private fun rig(seed: suspend ParitySeeder.() -> Unit): EventLogRig {
        val rig = EventLogRig.build(db.db, dir, clock = PARITY_CLOCK)
        runBlocking {
            val provider = db.db.repositoryProvider()
            ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork()).seed()
        }
        return rig
    }

    private val seeded: suspend ParitySeeder.() -> Unit = {
        with(RunsParityScenarios) {
            tree()
            run(ra, r1, listOf(c1, c2), openedBy = "api:rw-coord", openedByKind = "service")
        }
    }

    private fun snapshot() = ParityHarness.snapshot(db.db.jdbcUrl)

    private fun mcp(
        rig: EventLogRig,
        token: ParityToken?,
        tool: String,
        arguments: String,
        profile: ToolProfile
    ) = runBlocking {
        callMcp(
            rig.composition.toolContext,
            registry,
            token?.let { t -> { _: String? -> parityAccess(t) } },
            tool,
            Json.parseToJsonElement(arguments).jsonObject,
            profile
        )
    }

    private fun errorCode(result: io.github.jpicklyk.mcptask.current.parity.McpResult): String? =
        result.structured["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content

    @Test
    fun `S13 pause and resume are unknown on the orchestration profile, update on the director profile, and nothing is written`() {
        val rig = rig(seeded)
        val before = snapshot()
        val ra = RunsParityScenarios.ra

        val pause = mcp(rig, human, "runs_write", RunsParityScenarios.ops(RunsParityScenarios.pause(ra)), ToolProfile.ORCHESTRATION)
        val resume = mcp(rig, human, "runs_write", RunsParityScenarios.ops(RunsParityScenarios.resume(ra)), ToolProfile.ORCHESTRATION)
        val update =
            mcp(
                rig,
                coord,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.update(ra, ""","hints":{"x":"y"}""")),
                ToolProfile.DIRECTOR
            )

        for ((label, result) in listOf("pause" to pause, "resume" to resume, "update" to update)) {
            assertTrue(result.isError, "$label must be refused: ${result.structured}")
            assertEquals("partial_failure", errorCode(result), "$label: a hidden op is a failed element: ${result.structured}")
            assertTrue(
                "\"code\":\"invalid_request\"" in result.structured.toString() && "/ops/0/op" in result.structured.toString(),
                "$label: the element is invalid_request at the op selector: ${result.structured}"
            )
        }
        assertEquals(before, snapshot(), "the refused calls wrote nothing")
    }

    @Test
    fun `S13 control - the same calls succeed on the profile that contains the member`() {
        val rig = rig(seeded)
        val ra = RunsParityScenarios.ra
        val paused = mcp(rig, human, "runs_write", RunsParityScenarios.ops(RunsParityScenarios.pause(ra)), ToolProfile.DIRECTOR)
        assertFalse(paused.isError, paused.structured.toString())
        assertTrue("\"status\":\"paused\"" in paused.structured.toString(), paused.structured.toString())
        val resumed = mcp(rig, human, "runs_write", RunsParityScenarios.ops(RunsParityScenarios.resume(ra)), ToolProfile.DIRECTOR)
        assertFalse(resumed.isError, resumed.structured.toString())
        assertTrue("\"status\":\"open\"" in resumed.structured.toString(), resumed.structured.toString())
        val updated =
            mcp(
                rig,
                coord,
                "runs_write",
                RunsParityScenarios.ops(
                    RunsParityScenarios.update(ra, ""","hints":{"x":"y"}"""),
                    top = ""","seatToken":"${parityCoordinatorToken(ra)}""""
                ),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(updated.isError, updated.structured.toString())
        assertTrue("\"hints\":{\"x\":\"y\"}" in updated.structured.toString(), updated.structured.toString())
    }

    @Test
    fun `S18 mark is unknown on every MCP profile that serves the tool and writes nothing, while the REST-only contract holds`() {
        val rig = rig(seeded)
        val before = snapshot()
        for (profile in listOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR)) {
            val result =
                mcp(rig, coord, "runs_write", RunsParityScenarios.ops(RunsParityScenarios.mark(RunsParityScenarios.ra, "retro")), profile)
            assertTrue(result.isError, "${profile.wire}: ${result.structured}")
            assertEquals("partial_failure", errorCode(result), "${profile.wire}: ${result.structured}")
            assertTrue(
                "\"code\":\"invalid_request\"" in result.structured.toString() && "/ops/0/op" in result.structured.toString(),
                "${profile.wire}: ${result.structured}"
            )
            assertEquals(before, snapshot(), "${profile.wire}: nothing written")
        }
        assertEquals(0, snapshot().getValue("events").count { it["type"] == "run.marked" })
    }

    @Test
    fun `D-kind a supplied principal is recorded as an agent whatever kind it declares and no principal records the local opener`() {
        val rig = rig(seeded)
        val withActor =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(
                    RunsParityScenarios.open(RunsParityScenarios.r1, ""","items":["${RunsParityScenarios.c3}"]"""),
                    top = ""","actor":{"id":"orch-A","kind":"user"}"""
                ),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(withActor.isError, withActor.structured.toString())
        assertTrue("\"openedBy\":\"orch-A\"" in withActor.structured.toString(), withActor.structured.toString())
        assertTrue(
            "\"openedByKind\":\"agent\"" in withActor.structured.toString(),
            "a declared user kind never becomes human: ${withActor.structured}"
        )

        val anonymous =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.open(RunsParityScenarios.r2)),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(anonymous.isError, anonymous.structured.toString())
        assertTrue(
            "\"openedBy\":\"local\"" in anonymous.structured.toString(),
            "R14: a principal-less call runs as the local principal: ${anonymous.structured}"
        )
        assertTrue("\"openedByKind\":\"agent\"" in anonymous.structured.toString(), anonymous.structured.toString())
    }

    private fun coordinatorTokenOf(text: String): String = Regex("\"coordinatorToken\":\"([A-Za-z0-9_-]{43})\"").find(text)!!.groupValues[1]

    @Test
    fun `D-coord on MCP the coordinator is the token open returned - the opener without it, and another actor with it, are refused`() {
        val rig = rig(seeded)
        val opened =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(
                    RunsParityScenarios.open(RunsParityScenarios.r1, ""","items":["${RunsParityScenarios.c3}"]"""),
                    top = ""","actor":{"id":"orch-A","kind":"subagent"}"""
                ),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(opened.isError, opened.structured.toString())
        val runId = Regex("\"runId\":\"([0-9a-f-]{36})\"").find(opened.structured.toString())!!.groupValues[1]
        val token = coordinatorTokenOf(opened.structured.toString())
        val asA = ""","actor":{"id":"orch-A","kind":"subagent"}"""
        val asB = ""","actor":{"id":"orch-B","kind":"subagent"}"""

        // D3: the opener fallback is removed - orch-A opened the run but, with no token, is not its coordinator
        val openerNoToken =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.update(runId, ""","hints":{"x":"y"}"""), top = asA),
                ToolProfile.ORCHESTRATION
            )
        assertTrue("\"code\":\"forbidden\"" in openerNoToken.structured.toString(), openerNoToken.structured.toString())
        assertTrue(
            "\"scope\":\"principal_kind\"" in openerNoToken.structured.toString() &&
                "\"required\":\"coordinator\"" in openerNoToken.structured.toString(),
            openerNoToken.structured.toString()
        )

        // D2: the token is bound to the principal it was issued to, so another actor presenting it is unauthenticated
        val stranger =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(
                    RunsParityScenarios.update(runId, ""","hints":{"x":"y"}"""),
                    top = asB + ""","seatToken":"$token""""
                ),
                ToolProfile.ORCHESTRATION
            )
        assertEquals("unauthenticated", errorCode(stranger), stranger.structured.toString())
        assertFalse(token in stranger.structured.toString(), "the refusal never echoes the token")

        val closeByStranger =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.close(runId, "cancelled"), top = asB),
                ToolProfile.ORCHESTRATION
            )
        assertTrue("\"code\":\"forbidden\"" in closeByStranger.structured.toString(), closeByStranger.structured.toString())
        assertTrue(
            "\"required\":\"coordinator,director,human\"" in closeByStranger.structured.toString(),
            closeByStranger.structured.toString()
        )

        val updateByCoordinator =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(
                    RunsParityScenarios.update(runId, ""","hints":{"x":"y"}"""),
                    top = asA + ""","seatToken":"$token""""
                ),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(updateByCoordinator.isError, updateByCoordinator.structured.toString())

        val closeByCoordinator =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.close(runId, "completed"), top = asA + ""","seatToken":"$token""""),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(closeByCoordinator.isError, closeByCoordinator.structured.toString())
        assertTrue("\"closedBy\":\"orch-A\"" in closeByCoordinator.structured.toString(), closeByCoordinator.structured.toString())
    }

    @Test
    fun `D-coord on MCP a principal-less call runs as local and coordinates only with the token its open returned`() {
        val rig = rig(seeded)
        val opened =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.open(RunsParityScenarios.r2)),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(opened.isError, opened.structured.toString())
        val runId = Regex("\"runId\":\"([0-9a-f-]{36})\"").find(opened.structured.toString())!!.groupValues[1]
        val token = coordinatorTokenOf(opened.structured.toString())

        // D3: the opener (local, no actor) is not the coordinator without the token ...
        val noToken =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.update(runId, ""","hints":{"x":"y"}""")),
                ToolProfile.ORCHESTRATION
            )
        assertTrue("\"code\":\"forbidden\"" in noToken.structured.toString(), noToken.structured.toString())
        assertTrue("\"required\":\"coordinator\"" in noToken.structured.toString(), noToken.structured.toString())

        // ... and is with it
        val updated =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(RunsParityScenarios.update(runId, ""","hints":{"x":"y"}"""), top = ""","seatToken":"$token""""),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(updated.isError, updated.structured.toString())
        assertTrue("\"version\":2" in updated.structured.toString(), updated.structured.toString())
        // control: an actor that is not the opener presenting the token is refused as unauthenticated (D2), not let in
        val named =
            mcp(
                rig,
                null,
                "runs_write",
                RunsParityScenarios.ops(
                    RunsParityScenarios.update(runId, ""","hints":{"x":"z"}"""),
                    top = ""","actor":{"id":"orch-C","kind":"subagent"},"seatToken":"$token""""
                ),
                ToolProfile.ORCHESTRATION
            )
        assertEquals("unauthenticated", errorCode(named), named.structured.toString())
    }

    @Test
    fun `the runs_read get and list answer on both MCP profiles with the same run view`() {
        val rig = rig(seeded)
        val ra = RunsParityScenarios.ra
        val onOrchestration = mcp(rig, RunsParityScenarios.reader, "runs_read", RunsParityScenarios.get(ra), ToolProfile.ORCHESTRATION)
        val onDirector = mcp(rig, RunsParityScenarios.reader, "runs_read", RunsParityScenarios.get(ra), ToolProfile.DIRECTOR)
        assertFalse(onOrchestration.isError, onOrchestration.structured.toString())
        assertFalse(onDirector.isError, onDirector.structured.toString())

        fun body(r: io.github.jpicklyk.mcptask.current.parity.McpResult): JsonObject = r.structured.getValue("data").jsonObject
        assertEquals(body(onOrchestration), body(onDirector), "the served profile does not change the view")
        assertEquals(ra.toString(), body(onDirector).getValue("runId").jsonPrimitive.content)
        val list = mcp(rig, RunsParityScenarios.reader, "runs_read", RunsParityScenarios.list(), ToolProfile.DIRECTOR)
        assertFalse(list.isError, list.structured.toString())
        assertEquals(
            1,
            list.structured
                .getValue("data")
                .jsonObject
                .getValue("items")
                .jsonArray.size
        )
    }
}

package io.github.jpicklyk.mcptask.current.run.internal.wire

import io.github.jpicklyk.mcptask.current.bootstrap.profileInstructions
import io.github.jpicklyk.mcptask.current.config.ServerFeatures
import io.github.jpicklyk.mcptask.current.identity.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.identity.BearerTokenStore
import io.github.jpicklyk.mcptask.current.identity.HashBytes
import io.github.jpicklyk.mcptask.current.identity.internal.wire.rest.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.serviceRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.wellKnownRoutes
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ToolTextOracle
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDescriber
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorFixTemplates
import io.github.jpicklyk.mcptask.current.parity.DecisionsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.McpResult
import io.github.jpicklyk.mcptask.current.parity.PARITY_CLOCK
import io.github.jpicklyk.mcptask.current.parity.ParityHarness
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.RunsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.call.Principal
import io.github.jpicklyk.mcptask.current.platform.call.PrincipalKind
import io.github.jpicklyk.mcptask.current.platform.call.ProofStatus
import io.github.jpicklyk.mcptask.current.platform.port.AccessScope
import io.github.jpicklyk.mcptask.current.platform.tool.OpsToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import io.github.jpicklyk.mcptask.current.platform.tool.ToolSpec
import io.github.jpicklyk.mcptask.current.platform.tool.visibleNames
import io.github.jpicklyk.mcptask.current.report.internal.app.WasteToolClasses
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a331be0d, B1 stage 2) for the registered surface of `decisions_read` and
 * `decisions_write` and for the MCP-only behaviour the parity harness cannot state: scenarios S2 (the `waitingOn` filter), S4
 * (`includeContext`), S7 (delegate and escalate over a chain), S8 (the deprecated runs_* aliases), S9 (the advertised features), S11
 * (profile placement, the orchestration refusals), S12 (the two fix templates) and S13 (budgets, instructions, waste classes).
 *
 * Oracles (never read from a run): the task-scope of a331be0d: D8 (decisions_read `list` is a director read; `includeContext`
 * defaults to false), D9 (list director READ; raise orchestration RUNS; withdraw orchestration and director RUNS; delegate
 * orchestration and director DECIDE; ack, answer and escalate director DECIDE; REST serves all), D10b (error sets), D10c
 * (registration after `runs_write`), D11 (the features end `answerProvenance`, `decisionTools`, `waitingOn`), D12 (alias docs begin
 * "Deprecated (removed in 5.0): use decisions_write <op>. " and "... use decisions_read list. "), D13 (instructions name only the
 * served ops), D14 (the two templates), D15 (no total ceiling; the per-tool ceiling stays); the stage-1 rules the declarations
 * quote (delegate leaves a decision open waiting on the director, escalate waits on the user with its reason; an alias answer and
 * a decisions_write answer of the same input record the same provenance); ADR 0005 (aliases stay).
 *
 * Negative-assertion conditions: every refusal is paired with the same call on a profile that contains the member and succeeds
 * (the parity catalog carries the successful half), and every refusal asserts the database snapshot unchanged.
 *
 * Domain invariants satisfied by construction: decisions are seeded through `ParitySeeder.decision` (answer and acknowledgement
 * fields derived from the status) or raised through the tool under test; a deviation is raised, never seeded, in the chain tests.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class DecisionToolsSurfaceTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())

    private val read get() = registry["decisions_read"] as SelectToolSpec
    private val write get() = registry["decisions_write"] as OpsToolSpec<*>
    private val runsRead get() = registry["runs_read"] as SelectToolSpec
    private val runsWrite get() = registry["runs_write"] as OpsToolSpec<*>

    private fun visible(
        tool: String,
        profile: ToolProfile
    ): Set<String> = visibleNames(registry[tool]!!, profile).toSet()

    // ------------------------------------------------------------------ registration (S11, S13)

    @Test
    fun `both tools are registered after runs_write, decisions_read as a select tool and decisions_write as an ops tool`() {
        val names = registry.tools.map { it.name }
        assertTrue("decisions_read" in names && "decisions_write" in names, "registered: $names")
        assertTrue(names.indexOf("decisions_read") > names.indexOf("runs_write"), "decisions_read follows runs_write: $names")
        assertTrue(names.indexOf("decisions_write") > names.indexOf("runs_write"), "decisions_write follows runs_write: $names")
        assertEquals(names.size, names.toSet().size, "tool names are unique")
    }

    @Test
    fun `the members are exactly list and the six write ops`() {
        assertEquals(listOf("list"), read.selects.map { it.name })
        assertEquals(setOf("raise", "ack", "delegate", "escalate", "answer", "withdraw"), write.ops.map { it.name }.toSet())
        assertEquals(6, write.ops.size)
    }

    @Test
    fun `profile sets and capabilities follow D9`() {
        val both = setOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR)
        val list = read.selects.single()
        assertEquals(setOf(ToolProfile.DIRECTOR), list.profiles)
        assertEquals(ApiCapability.READ, list.capability)

        fun op(name: String) = write.ops.single { it.name == name }
        assertEquals(setOf(ToolProfile.ORCHESTRATION), op("raise").profiles)
        assertEquals(ApiCapability.RUNS, op("raise").capability)
        assertEquals(both, op("withdraw").profiles)
        assertEquals(ApiCapability.RUNS, op("withdraw").capability)
        assertEquals(both, op("delegate").profiles)
        assertEquals(ApiCapability.DECIDE, op("delegate").capability)
        for (name in listOf("ack", "escalate", "answer")) {
            assertEquals(setOf(ToolProfile.DIRECTOR), op(name).profiles, "$name is a director op")
            assertEquals(ApiCapability.DECIDE, op(name).capability, "$name needs decide")
        }
    }

    @Test
    fun `the declared error sets follow D10b`() {
        fun errors(name: String) = write.ops.single { it.name == name }.errors
        assertEquals(setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN), read.selects.single().errors)
        val closedAndVersion =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.DECISION_CLOSED,
                ErrorCode.VERSION_CONFLICT
            )
        assertEquals(closedAndVersion, errors("ack"))
        assertEquals(closedAndVersion, errors("delegate"))
        assertEquals(closedAndVersion, errors("escalate"))
        assertEquals(closedAndVersion, errors("withdraw"))
        assertEquals(closedAndVersion + ErrorCode.UNAUTHENTICATED, errors("answer"))
        assertEquals(
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.IDEMPOTENCY_MISMATCH,
                ErrorCode.PAYLOAD_TOO_LARGE,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.SEAT_FORBIDDEN,
                ErrorCode.NOT_CLAIM_HOLDER
            ),
            errors("raise")
        )
    }

    @Test
    fun `visibility per profile - decisions_read is director only, orchestration serves raise, delegate and withdraw`() {
        assertEquals(emptySet(), visible("decisions_read", ToolProfile.KNOWLEDGE))
        assertEquals(emptySet(), visible("decisions_write", ToolProfile.KNOWLEDGE))
        assertEquals(emptySet(), visible("decisions_read", ToolProfile.ORCHESTRATION))
        assertEquals(setOf("list"), visible("decisions_read", ToolProfile.DIRECTOR))
        assertEquals(setOf("raise", "delegate", "withdraw"), visible("decisions_write", ToolProfile.ORCHESTRATION))
        assertEquals(setOf("ack", "delegate", "escalate", "answer", "withdraw"), visible("decisions_write", ToolProfile.DIRECTOR))
        assertTrue(
            McpOperationAdapter.toolFor(registry["decisions_read"]!!, ToolProfile.ORCHESTRATION) == null,
            "decisions_read is absent from the orchestration tools/list"
        )
        assertNotNull(McpOperationAdapter.toolFor(registry["decisions_read"]!!, ToolProfile.DIRECTOR))
        assertNotNull(McpOperationAdapter.toolFor(registry["decisions_write"]!!, ToolProfile.ORCHESTRATION))
        assertNotNull(McpOperationAdapter.toolFor(registry["decisions_write"]!!, ToolProfile.DIRECTOR))
    }

    // ------------------------------------------------------------------ S8 aliases stay, deprecated

    @Test
    fun `S8 the runs_ decision members are still served on their profiles with their old capabilities`() {
        fun op(name: String) = runsWrite.ops.single { it.name == name }
        assertEquals(setOf(ToolProfile.ORCHESTRATION), op("raise").profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), op("ack").profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), op("answer").profiles)
        assertEquals(setOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR), op("withdraw").profiles)
        assertEquals(ApiCapability.RUNS, op("raise").capability)
        assertEquals(ApiCapability.DECIDE, op("ack").capability)
        assertEquals(ApiCapability.DECIDE, op("answer").capability)
        assertEquals(ApiCapability.RUNS, op("withdraw").capability)
        assertEquals(setOf(ToolProfile.DIRECTOR), runsRead.selects.single { it.name == "decisions" }.profiles)
        assertEquals(setOf(ToolProfile.DIRECTOR), runsRead.selects.single { it.name == "decision" }.profiles)
    }

    /** The description of member [name] in the describe answer of [spec] on [profile]. */
    private fun memberDoc(
        spec: ToolSpec,
        profile: ToolProfile,
        name: String
    ): String =
        ToolDescriber
            .describe(spec, profile, null)
            .getValue("operations")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == name }
            .getValue("description")
            .jsonPrimitive.content

    @Test
    fun `S8 each alias doc begins with its deprecation notice naming the replacement`() {
        for (name in listOf("raise", "ack", "answer", "withdraw")) {
            val profile = if (name == "raise") ToolProfile.ORCHESTRATION else ToolProfile.DIRECTOR
            val doc = memberDoc(runsWrite, profile, name)
            assertTrue(
                doc.startsWith("Deprecated (removed in 5.0): use decisions_write $name. "),
                "runs_write $name doc starts with the notice: $doc"
            )
        }
        for (name in listOf("decisions", "decision")) {
            val doc = memberDoc(runsRead, ToolProfile.DIRECTOR, name)
            assertTrue(
                doc.startsWith("Deprecated (removed in 5.0): use decisions_read list. "),
                "runs_read $name doc starts with the notice: $doc"
            )
        }
        for (op in listOf("delegate", "escalate", "answer", "ack", "withdraw")) {
            assertFalse(memberDoc(write, ToolProfile.DIRECTOR, op).startsWith("Deprecated"), "decisions_write $op is not deprecated")
        }
        assertFalse(
            memberDoc(write, ToolProfile.ORCHESTRATION, "raise").startsWith("Deprecated"),
            "decisions_write raise is not deprecated"
        )
        assertFalse(memberDoc(read, ToolProfile.DIRECTOR, "list").startsWith("Deprecated"), "decisions_read list is not deprecated")
        // Control: the tool-level text of the runs tools says nothing of deprecation (D12: descriptions unchanged).
        assertFalse(ToolTextOracle.servedDescription(runsWrite, ToolProfile.DIRECTOR).contains("Deprecated (removed in 5.0)"))
    }

    // ------------------------------------------------------------------ S12 fix templates

    @Test
    fun `S12 the decision_closed fix names decisions_read and the gate_blocked fix names notes_write`() {
        assertEquals(
            "Decision {decisionId} is {status} and cannot change; read it with decisions_read list (decisionIds), or raise a new one.",
            ErrorFixTemplates.template(ErrorCode.DECISION_CLOSED)
        )
        val gate = assertNotNull(ErrorFixTemplates.template(ErrorCode.GATE_BLOCKED))
        assertTrue("notes_write" in gate, "gate_blocked fix names the tool notes_write: $gate")
        assertFalse("notes.write" in gate, "gate_blocked fix no longer spells the tool notes.write: $gate")
        for (code in ErrorCode.entries) {
            val template = ErrorFixTemplates.template(code) ?: continue
            assertFalse("notes.write" in template, "$code template spells notes.write: $template")
        }
    }

    // ------------------------------------------------------------------ S13 budgets, instructions, waste classes

    @Test
    fun `S13 every tool of every profile is within 9000 compact characters, the new tools included, and no total ceiling remains`() {
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(registry))
        for (profile in listOf(ToolProfile.ORCHESTRATION, ToolProfile.DIRECTOR)) {
            val sizes = ToolSurfaceChecks.sizes(registry, profile)
            for (tool in sizes.keys.filter { it.startsWith("decisions_") }) {
                assertTrue(sizes.getValue(tool) in 1..9_000, "$tool on ${profile.wire} is ${sizes.getValue(tool)} compact characters")
            }
            assertTrue(sizes.values.all { it <= 9_000 }, "${profile.wire}: $sizes")
        }
        assertTrue("decisions_write" in ToolSurfaceChecks.sizes(registry, ToolProfile.ORCHESTRATION))
        assertTrue("decisions_read" in ToolSurfaceChecks.sizes(registry, ToolProfile.DIRECTOR))
        assertEquals(9_000, ToolSurfaceChecks.PER_TOOL_CEILING)
        // AC14: the three total ceilings are gone from the source tree.
        val needle = "TOTAL_" + "CEILING"
        val root = listOf(Path.of("src"), Path.of("current", "src")).first { Files.isDirectory(it) }
        var scanned = 0
        val hits = mutableListOf<String>()
        Files.walk(root).use { stream ->
            for (path in stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()) {
                scanned++
                if (needle in Files.readString(path)) hits += path.toString()
            }
        }
        assertTrue(scanned > 100, "the scan covered the source tree ($scanned files)")
        assertEquals(emptyList(), hits, "no file names a total ceiling constant")
    }

    @Test
    fun `S13 the instructions name exactly the served tools within their caps and the served ops only`() {
        for (profile in ToolProfile.entries) ToolTextOracle.assertInstructions(profile, profileInstructions(profile))
        val director = profileInstructions(ToolProfile.DIRECTOR)
        assertTrue(ToolTextOracle.mentions(director, "decisions_read"), "director names decisions_read: $director")
        assertTrue(ToolTextOracle.mentions(director, "decisions_write"), "director names decisions_write: $director")
        assertFalse(ToolTextOracle.mentions(director, "raise"), "director cannot raise: $director")
        val orchestration = profileInstructions(ToolProfile.ORCHESTRATION)
        assertTrue(ToolTextOracle.mentions(orchestration, "decisions_write"), "orchestration names decisions_write: $orchestration")
        assertFalse(ToolTextOracle.mentions(orchestration, "decisions_read"), "orchestration does not serve decisions_read: $orchestration")
        for (word in listOf("ack", "answer", "escalate")) {
            assertFalse(ToolTextOracle.mentions(orchestration, word), "orchestration cannot $word: $orchestration")
        }
        val knowledge = profileInstructions(ToolProfile.KNOWLEDGE)
        assertFalse(ToolTextOracle.mentions(knowledge, "decisions_write"), knowledge)
        assertFalse(ToolTextOracle.mentions(knowledge, "decisions_read"), knowledge)
        for (tool in listOf("decisions_read", "decisions_write")) {
            assertTrue(tool in WasteToolClasses.EXCLUDED, "$tool is excluded from the waste classes")
            assertFalse(tool in WasteToolClasses.READ || tool in WasteToolClasses.WRITE, "$tool is in neither waste class")
        }
    }

    // ------------------------------------------------------------------ S9 advertised features

    @Test
    fun `S9 the advertised features carry answerProvenance, decisionTools and waitingOn in that order`() {
        val features = ServerFeatures.ADVERTISED
        val at = features.indexOf("answerProvenance")
        assertTrue(at >= 0, "answerProvenance is advertised: $features")
        assertEquals(listOf("answerProvenance", "decisionTools", "waitingOn"), features.subList(at, at + 3))
        assertEquals(features.size, features.toSet().size, "no feature is listed twice")
    }

    private fun featureArrays(element: JsonElement): List<List<String>> =
        when (element) {
            is JsonArray ->
                listOfNotNull(
                    element.takeIf { a -> a.isNotEmpty() && a.all { it is JsonPrimitive && it.isString } }?.map { it.jsonPrimitive.content }
                ) + element.flatMap { featureArrays(it) }

            is JsonObject -> element.values.flatMap { featureArrays(it) }

            else -> emptyList()
        }

    private fun assertAdvertises(
        body: String,
        label: String
    ) {
        val lists = featureArrays(Json.parseToJsonElement(body)).filter { "seats" in it }
        assertTrue(lists.isNotEmpty(), "$label carries a feature list: $body")
        for (list in lists) {
            val at = list.indexOf("answerProvenance")
            assertTrue(at >= 0, "$label lists answerProvenance: $list")
            assertEquals(listOf("answerProvenance", "decisionTools", "waitingOn"), list.subList(at, at + 3), label)
        }
    }

    @Test
    fun `S9 the info endpoint and the well-known document advertise the same features`() {
        val token = "decision-tools-info-token"
        val principal =
            Principal(
                id = "info-reader",
                scope = AccessScope.principal(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                kind = PrincipalKind.SERVICE,
                proofStatus = ProofStatus.ISSUED
            )
        val config =
            ApiAuthConfig.Bearer(
                tokens =
                    mapOf(
                        HashBytes(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))) to
                            BearerTokenStore.TokenEntry(principal, expiresAt = null)
                    )
            )
        testApplication {
            application {
                install(ContentNegotiation) { json(McpJson) }
                install(SSE)
                routing {
                    route("/api/v1") {
                        install(ApiBearerAuth) {
                            authConfig = config
                            tokenEntries = config.tokens
                        }
                        serviceRoutes(repositoryProvider = null, serverName = "decisions-test", serverVersion = "9.9.9")
                    }
                    wellKnownRoutes(serverName = "decisions-test", serverVersion = "9.9.9")
                }
            }
            val info = client.get("/api/v1/info") { header("Authorization", "Bearer $token") }
            assertEquals(HttpStatusCode.OK, info.status)
            assertAdvertises(info.bodyAsText(), "/api/v1/info")
            val wellKnown = client.get("/.well-known/mcp-task-orchestrator.json")
            assertEquals(HttpStatusCode.OK, wellKnown.status)
            assertAdvertises(wellKnown.bodyAsText(), "/.well-known/mcp-task-orchestrator.json")
        }
    }

    // ------------------------------------------------------------------ MCP behaviour over the real composition

    private val coord = RunsParityScenarios.coord
    private val decider = DecisionsParityScenarios.decider
    private val humanDecider = RunsParityScenarios.humanDecider

    private fun rig(seed: suspend ParitySeeder.() -> Unit): EventLogRig {
        val rig = EventLogRig.build(db.db, dir, clock = PARITY_CLOCK)
        runBlocking {
            val provider = db.db.repositoryProvider()
            ParitySeeder(provider.workItemRepository(), provider.dependencyRepository(), provider, db.db.unitOfWork()).seed()
        }
        return rig
    }

    private fun snapshot() = ParityHarness.snapshot(db.db.jdbcUrl)

    private fun mcp(
        rig: EventLogRig,
        token: ParityToken?,
        tool: String,
        arguments: String,
        profile: ToolProfile
    ): McpResult =
        runBlocking {
            callMcp(
                rig.composition.toolContext,
                registry,
                token?.let { t -> { _: String? -> parityAccess(t) } },
                tool,
                Json.parseToJsonElement(arguments).jsonObject,
                profile
            )
        }

    private fun events(type: String) = snapshot().getValue("events").filter { it["type"] == type }

    private fun results(result: McpResult): List<JsonObject> =
        result.structured.getValue("data").jsonObject.getValue("results").jsonArray.map {
            it.jsonObject
        }

    private fun items(result: McpResult): List<JsonObject> =
        result.structured.getValue("data").jsonObject.getValue("items").jsonArray.map {
            it.jsonObject
        }

    private fun raiseDeviation(
        key: String,
        extra: String = ""
    ) = """{"op":"raise","class":"deviation","question":"Which way?","options":[{"key":"a","label":"A"},{"key":"b","label":"B"}],""" +
        """"dedupeKey":"$key","rootId":"${RunsParityScenarios.r1}"$extra}"""

    private val tree: suspend ParitySeeder.() -> Unit = { RunsParityScenarios.tree.invoke(this) }

    @Test
    fun `S11 ack, answer, escalate unknown on orchestration and raise unknown on director, nothing written`() {
        val rig = rig(DecisionsParityScenarios.seedDecisions)
        val before = snapshot()
        val refused =
            listOf(
                Triple("ack", DecisionsParityScenarios.ack(DecisionsParityScenarios.dcOpen), ToolProfile.ORCHESTRATION),
                Triple("answer", DecisionsParityScenarios.answer(DecisionsParityScenarios.dcOpen), ToolProfile.ORCHESTRATION),
                Triple("escalate", DecisionsParityScenarios.escalate(DecisionsParityScenarios.dcDev), ToolProfile.ORCHESTRATION),
                Triple(
                    "raise",
                    DecisionsParityScenarios.raise("new-key", ""","rootId":"${RunsParityScenarios.r1}""""),
                    ToolProfile.DIRECTOR
                )
            )
        for ((label, element, profile) in refused) {
            val result = mcp(rig, decider, "decisions_write", DecisionsParityScenarios.ops(element), profile)
            val text = result.structured.toString()
            assertTrue(result.isError, "$label on ${profile.wire} must be refused: $text")
            assertEquals(
                "partial_failure",
                result.structured["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content,
                "$label: a hidden op is a failed element: $text"
            )
            assertTrue("\"code\":\"invalid_request\"" in text && "/ops/0/op" in text, "$label: invalid_request at the op selector: $text")
        }
        assertEquals(before, snapshot(), "the refused calls wrote nothing")
        // Control: the same ops succeed on the profile that serves them, so the refusals above are about placement.
        val ack =
            mcp(
                rig,
                decider,
                "decisions_write",
                DecisionsParityScenarios.ops(DecisionsParityScenarios.ack(DecisionsParityScenarios.dcOpen)),
                ToolProfile.DIRECTOR
            )
        assertFalse(ack.isError, ack.structured.toString())
        val raise =
            mcp(
                rig,
                coord,
                "decisions_write",
                DecisionsParityScenarios.ops(DecisionsParityScenarios.raise("new-key", ""","rootId":"${RunsParityScenarios.r1}"""")),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(raise.isError, raise.structured.toString())
    }

    @Test
    fun `S4 context is shown with includeContext true and omitted when it is false or absent`() {
        val rig = rig(tree)
        val withContext = raiseDeviation("with-context", ""","context":"the-background"""")
        val without = raiseDeviation("without-context")
        val raised = mcp(rig, coord, "decisions_write", DecisionsParityScenarios.ops(withContext, without), ToolProfile.ORCHESTRATION)
        assertFalse(raised.isError, raised.structured.toString())
        val idWith = results(raised)[0].getValue("id").jsonPrimitive.content
        val idWithout = results(raised)[1].getValue("id").jsonPrimitive.content

        fun list(extra: String) = items(mcp(rig, decider, "decisions_read", DecisionsParityScenarios.list(extra), ToolProfile.DIRECTOR))
        for (extra in listOf("", ""","includeContext":false""")) {
            val rows = list(extra)
            assertEquals(setOf(idWith, idWithout), rows.map { it.getValue("id").jsonPrimitive.content }.toSet(), "rows for [$extra]")
            for (row in rows) assertFalse("context" in row, "[$extra]: no context member: $row")
        }
        val rows = list(""","includeContext":true""").associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals(
            "the-background",
            rows
                .getValue(idWith)
                .getValue("context")
                .jsonPrimitive.content
        )
        assertFalse("context" in rows.getValue(idWithout), "a decision raised without context has none to show: ${rows[idWithout]}")
    }

    @Test
    fun `S2 and S7 the waitingOn filter follows delegate and escalate, a repeated delegate changes nothing`() {
        val rig = rig(tree)
        val raised = mcp(rig, coord, "decisions_write", DecisionsParityScenarios.ops(raiseDeviation("chain")), ToolProfile.ORCHESTRATION)
        assertFalse(raised.isError, raised.structured.toString())
        val id =
            results(raised)
                .single()
                .getValue("id")
                .jsonPrimitive.content

        fun waiting(value: String) =
            items(mcp(rig, decider, "decisions_read", DecisionsParityScenarios.list(""","waitingOn":"$value""""), ToolProfile.DIRECTOR))
                .map { it.getValue("id").jsonPrimitive.content }

        assertEquals(emptyList(), waiting("director"), "nothing waits before a delegate")
        assertEquals(emptyList(), waiting("user"))

        val delegated =
            mcp(
                rig,
                decider,
                "decisions_write",
                DecisionsParityScenarios.ops(DecisionsParityScenarios.delegate(id)),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(delegated.isError, delegated.structured.toString())
        val first = results(delegated).single()
        assertEquals("director", first.getValue("waitingOn").jsonPrimitive.content)
        assertEquals("open", first.getValue("status").jsonPrimitive.content)
        assertEquals(listOf(id), waiting("director"))
        assertEquals(emptyList(), waiting("user"))
        assertEquals(1, events("decision.delegated").size)

        val again =
            mcp(
                rig,
                decider,
                "decisions_write",
                DecisionsParityScenarios.ops(DecisionsParityScenarios.delegate(id)),
                ToolProfile.ORCHESTRATION
            )
        assertFalse(again.isError, "a repeated delegate is not an error: ${again.structured}")
        assertEquals(first.getValue("version"), results(again).single().getValue("version"), "a repeated delegate leaves the version")
        assertEquals(1, events("decision.delegated").size, "a repeated delegate records no second event")

        val escalated =
            mcp(
                rig,
                decider,
                "decisions_write",
                DecisionsParityScenarios.ops(DecisionsParityScenarios.escalate(id, "needs a human")),
                ToolProfile.DIRECTOR
            )
        assertFalse(escalated.isError, escalated.structured.toString())
        val view = results(escalated).single()
        assertEquals("user", view.getValue("waitingOn").jsonPrimitive.content)
        assertEquals("needs a human", view.getValue("waitingReason").jsonPrimitive.content)
        assertEquals(listOf(id), waiting("user"))
        assertEquals(emptyList(), waiting("director"), "escalate moves the wait from the director to the user")
        assertEquals(1, events("decision.escalated").size)
    }

    /** The `answer.decidedBy` member of an answer of [decision] sent through [tool] by [token] on [rig]. */
    private fun answeredBy(
        rig: EventLogRig,
        tool: String,
        token: ParityToken,
        decision: java.util.UUID
    ): JsonElement? {
        val element =
            if (tool == "runs_write") RunsParityScenarios.answer(decision, "b") else DecisionsParityScenarios.answer(decision, "b")
        val result = mcp(rig, token, tool, DecisionsParityScenarios.ops(element), ToolProfile.DIRECTOR)
        assertFalse(result.isError, "$tool answer: ${result.structured}")
        return results(result).single().getValue("answer").jsonObject["decidedBy"]
    }

    @Test
    fun `S8 a human credential answers as the user through the alias`() {
        val rig = rig(DecisionsParityScenarios.seedDecisions)
        assertEquals(JsonPrimitive("user"), answeredBy(rig, "runs_write", humanDecider, DecisionsParityScenarios.dcOpen))
    }

    @Test
    fun `S8 an alias answer and a decisions_write answer of the same input record the same decidedBy for a decide credential`() {
        val rig = rig(DecisionsParityScenarios.seedDecisions)
        // Two open `question` decisions raised by the same principal; one is answered through the alias, the other through the tool.
        val alias = answeredBy(rig, "runs_write", decider, DecisionsParityScenarios.dcOpen)
        val tool = answeredBy(rig, "decisions_write", decider, DecisionsParityScenarios.dcOther)
        assertEquals(alias, tool, "the alias derives the tool's decidedBy")
    }
}

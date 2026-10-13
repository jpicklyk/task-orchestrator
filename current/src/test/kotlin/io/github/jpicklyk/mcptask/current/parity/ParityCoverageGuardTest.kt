package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.director
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The seeded tree and the scenario catalog for [ParityFixtureTools]; the catalog is the fixture twin of [ProductionParity]. */
internal object FixtureParityCatalog {
    val r1: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    val c1: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    val r2: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")
    val missing: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")

    val admin = ParityToken("parity-admin", setOf(ApiCapability.ADMIN))
    val reader = ParityToken("parity-reader", setOf(ApiCapability.READ))
    val writer = ParityToken("parity-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS, ApiCapability.WRITE_NOTES))
    val itemsOnly = ParityToken("parity-items-only", setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS))
    val rootScoped =
        ParityToken(
            "parity-root-scoped",
            setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS, ApiCapability.WRITE_NOTES),
            rootIds = setOf(r1)
        )
    val tagScoped =
        ParityToken(
            "parity-tag-scoped",
            setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS, ApiCapability.WRITE_NOTES),
            tagsInclude = setOf("alpha")
        )

    /** R1 "Alpha root" (tag alpha) > C1 "Alpha child" (tags alpha,shared); R2 "Beta root" (tag beta). */
    val seedTree: suspend ParitySeeder.() -> Unit = {
        item(r1, "Alpha root", tags = "alpha")
        item(c1, "Alpha child", parent = r1, tags = "alpha,shared")
        item(r2, "Beta root", tags = "beta")
    }

    private fun get(id: UUID) = """{"select":"get","id":"$id"}"""

    private fun rename(
        id: UUID,
        title: String
    ) = """{"op":"rename","id":"$id","title":"$title"}"""

    private fun retag(
        id: UUID,
        tags: String
    ) = """{"op":"retag","id":"$id","tags":"$tags"}"""

    private fun scenario(
        name: String,
        tool: String,
        member: String,
        kind: ParityKind,
        token: ParityToken,
        arguments: String,
        expectStatus: Int,
        expectCode: String? = null,
        expectContains: List<String> = emptyList(),
        expectAbsent: List<String> = emptyList(),
        expectDbChanged: Boolean? = null
    ) = ParityScenario(
        name,
        tool,
        member,
        kind,
        token,
        arguments,
        expectStatus,
        expectCode,
        expectContains,
        expectAbsent,
        expectDbChanged,
        seedTree
    )

    val happy =
        listOf(
            scenario(
                "get happy",
                ParityFixtureTools.ITEMS,
                "get",
                ParityKind.HAPPY,
                reader,
                get(c1),
                200,
                expectContains = listOf("\"title\":\"Alpha child\""),
                expectDbChanged = false
            ),
            scenario(
                "list happy",
                ParityFixtureTools.ITEMS,
                "list",
                ParityKind.HAPPY,
                reader,
                """{"select":"list"}""",
                200,
                expectContains = listOf("Alpha root", "Alpha child", "Beta root")
            ),
            scenario(
                "rename happy",
                ParityFixtureTools.EDIT,
                "rename",
                ParityKind.HAPPY,
                writer,
                """{"ops":[${rename(c1, "Renamed child")}]}""",
                200,
                expectContains = listOf("\"id\":\"renamed:$c1\""),
                expectDbChanged = true
            ),
            scenario(
                "retag happy",
                ParityFixtureTools.EDIT,
                "retag",
                ParityKind.HAPPY,
                writer,
                """{"ops":[${retag(r2, "beta,new")}]}""",
                200,
                expectContains = listOf("\"id\":\"retagged:$r2\""),
                expectDbChanged = true
            )
        )

    val scopeForbidden =
        listOf(
            scenario(
                "get scope root",
                ParityFixtureTools.ITEMS,
                "get",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                get(r2),
                403,
                expectCode = "forbidden",
                expectContains = listOf("\"scope\":\"root\"", "\"required\":\"$r1\""),
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "get scope tag",
                ParityFixtureTools.ITEMS,
                "get",
                ParityKind.SCOPE_FORBIDDEN,
                tagScoped,
                get(r2),
                403,
                expectCode = "forbidden",
                expectContains = listOf("\"scope\":\"tag\"", "\"required\":\"alpha\""),
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "list filtered by root scope",
                ParityFixtureTools.ITEMS,
                "list",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"select":"list"}""",
                200,
                expectContains = listOf("Alpha root", "Alpha child"),
                expectAbsent = listOf("Beta root")
            ),
            scenario(
                "rename scope root",
                ParityFixtureTools.EDIT,
                "rename",
                ParityKind.SCOPE_FORBIDDEN,
                rootScoped,
                """{"ops":[${rename(r2, "Hijacked")}]}""",
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"forbidden\"", "\"scope\":\"root\""),
                expectDbChanged = false
            ),
            scenario(
                "retag scope tag",
                ParityFixtureTools.EDIT,
                "retag",
                ParityKind.SCOPE_FORBIDDEN,
                tagScoped,
                """{"ops":[${retag(r2, "alpha")}]}""",
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"forbidden\"", "\"scope\":\"tag\""),
                expectDbChanged = false
            )
        )

    val notFound =
        listOf(
            scenario(
                "get not found",
                ParityFixtureTools.ITEMS,
                "get",
                ParityKind.OTHER,
                admin,
                get(missing),
                404,
                expectCode = "not_found"
            )
        )

    val other =
        listOf(
            scenario(
                "rename capability forbidden",
                ParityFixtureTools.EDIT,
                "rename",
                ParityKind.OTHER,
                reader,
                """{"ops":[${rename(c1, "Nope")}]}""",
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"forbidden\"", "\"scope\":\"capability\"", "\"required\":\"write-items\""),
                expectDbChanged = false
            ),
            scenario(
                "rename atomic scope forbidden rolls back the in-scope element",
                ParityFixtureTools.EDIT,
                "rename",
                ParityKind.OTHER,
                rootScoped,
                """{"ops":[${rename(c1, "In scope")},${rename(r2, "Out of scope")}],"atomic":true}""",
                403,
                expectCode = "forbidden",
                expectContains = listOf("\"index\":1"),
                expectDbChanged = false
            ),
            scenario(
                "mixed ops allowed then capability forbidden",
                ParityFixtureTools.EDIT,
                "rename",
                ParityKind.OTHER,
                itemsOnly,
                """{"ops":[${rename(c1, "Mixed ok")},${retag(c1, "x")}]}""",
                207,
                expectCode = "partial_failure",
                expectContains = listOf("\"scope\":\"capability\"", "\"required\":\"write-notes\""),
                expectDbChanged = true
            )
        )

    val all: List<ParityScenario> get() = happy + scopeForbidden + notFound + other
}

/**
 * R5 fixture for the profile-aware parity harness (task-scope AC5, AC6): one select tool whose members carry every kind of
 * profile set. The REST-only member's name is a parameter, so a renamed REST-only member can be proved to stay exempt by its
 * EMPTY SET and not by its name.
 *
 * Domain invariants satisfied by construction: each member declares a non-blank doc and a request class whose fields all carry
 * a `@Doc`; no member touches the database, so no work-item invariant is in play.
 */
private class ProfileModeFixtures(
    val restOnlyName: String = "web_peek"
) {
    @Serializable
    data class PeekReq(
        @Doc("Marker.") val marker: String = "x"
    )

    private fun peek(
        name: String,
        profiles: Set<ToolProfile>
    ) = SelectSpec.Single<PeekReq, JsonObject>(
        name = name,
        doc = "Peek.",
        profiles = profiles,
        request = serializer<PeekReq>(),
        handler = { _, _, _ -> Outcome.Ok(buildJsonObject { put("who", name) }) },
        data = { it }
    )

    val tool =
        SelectToolSpec(
            "pm_items",
            "Profile-mode parity fixture.",
            listOf(
                peek("know_peek", knowledge()),
                peek("orch_peek", orchestration()),
                peek("dir_peek", director()),
                peek("know_only", setOf(ToolProfile.KNOWLEDGE)),
                peek(restOnlyName, emptySet())
            )
        )

    val registry: ToolRegistry get() = ToolRegistry(listOf(tool))

    fun scenario(
        member: String,
        kind: ParityKind = ParityKind.HAPPY,
        expectContains: List<String> = listOf("\"who\":\"$member\"")
    ) = ParityScenario(
        name = "pm_items $member",
        tool = "pm_items",
        member = member,
        kind = kind,
        token = FixtureParityCatalog.reader,
        arguments = """{"select":"$member"}""",
        expectStatus = 200,
        expectContains = expectContains,
        expectDbChanged = false
    )
}

/**
 * Independent tests (test-author seat, item f67e1762) for the parity harness and its coverage guard: scenarios S2, S13, S14,
 * S17, S18, S19 and S20 of the frozen test-plan (NEW-SURFACE, except S20).
 *
 * Oracles: plan 3.2 (one canonical behaviour: the same arguments over MCP and `POST /api/v1/ops/{tool}` return the same
 * envelope bytes and leave the same database), envelope spec statuses (200 ok, 207 partial_failure, 403 forbidden, 404
 * not_found) written as literals in [FixtureParityCatalog], task-scope D3 (both legs run under one CallAccess), D4 (coverage:
 * one HAPPY and one SCOPE_FORBIDDEN-under-a-restricted-scope scenario per select/op, or a reasoned exemption).
 *
 * Non-vacuity: the harness runs real scope-guarded fixtures whose forbidden outcomes differ from their happy ones, a database
 * snapshot that must change for the write scenarios, and two surface-dependent fixtures it must reject.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ParityCoverageGuardTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val fixtures = ParityFixtureTools()
    private val harness = ParityHarness(fixtures.registry)

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // ------------------------------------------------------------------ S2 / S13 / S14 harness runs

    @Test
    fun `S2 happy selects and ops return equal bodies and leave equal databases over real items`() {
        for (s in FixtureParityCatalog.happy) harness.run(s)
    }

    @Test
    fun `S13 scope-forbidden scenarios agree across MCP and REST under the same access`() {
        for (s in FixtureParityCatalog.scopeForbidden) harness.run(s)
    }

    @Test
    fun `S14 not_found agrees across MCP and REST`() {
        for (s in FixtureParityCatalog.notFound) harness.run(s)
    }

    @Test
    fun `capability-forbidden, atomic and mixed batches agree across MCP and REST`() {
        for (s in FixtureParityCatalog.other) harness.run(s)
    }

    // ------------------------------------------------------------------ S17 coverage rule

    private val catalog get() = FixtureParityCatalog.all

    @Test
    fun `S17 control - the fixture catalog covers every fixture select and op`() {
        assertEquals(
            listOf("pf_items" to "get", "pf_items" to "list", "pf_edit" to "rename", "pf_edit" to "retag"),
            ParityCoverage.members(fixtures.registry)
        )
        assertEquals(emptyList(), ParityCoverage.missing(fixtures.registry, catalog))
        assertEquals(emptyList(), ParityCoverage.stale(fixtures.registry, catalog))
    }

    @Test
    fun `S17 an empty catalog misses a happy and a scope-forbidden scenario for every member`() {
        val missing = ParityCoverage.missing(fixtures.registry, emptyList())
        assertEquals(8, missing.size, "4 members x 2 requirements: $missing")
        for ((tool, member) in ParityCoverage.members(fixtures.registry)) {
            assertTrue(MissingParity(tool, member, ParityKind.HAPPY) in missing, "$tool.$member happy")
            assertTrue(MissingParity(tool, member, ParityKind.SCOPE_FORBIDDEN) in missing, "$tool.$member scope-forbidden")
        }
    }

    @Test
    fun `S17 removing one happy scenario reports exactly that member`() {
        val without = catalog.filterNot { it.name == "list happy" }
        assertEquals(
            listOf(MissingParity(ParityFixtureTools.ITEMS, "list", ParityKind.HAPPY)),
            ParityCoverage.missing(fixtures.registry, without)
        )
    }

    @Test
    fun `S17 removing the only scope-forbidden scenario of an op reports exactly that op`() {
        val without = catalog.filterNot { it.name == "retag scope tag" }
        assertEquals(
            listOf(MissingParity(ParityFixtureTools.EDIT, "retag", ParityKind.SCOPE_FORBIDDEN)),
            ParityCoverage.missing(fixtures.registry, without)
        )
    }

    @Test
    fun `S17 a scope-forbidden scenario under an unrestricted scope is not counted`() {
        val unrestricted = catalog.single { it.name == "retag scope tag" }.copy(token = FixtureParityCatalog.writer)
        assertFalse(unrestricted.token.isScopeRestricted, "fixture: the swapped token has no scope restriction")
        val swapped = catalog.filterNot { it.name == "retag scope tag" } + unrestricted
        assertEquals(
            listOf(MissingParity(ParityFixtureTools.EDIT, "retag", ParityKind.SCOPE_FORBIDDEN)),
            ParityCoverage.missing(fixtures.registry, swapped)
        )
    }

    @Test
    fun `S17 a restricted-scope scenario of kind OTHER does not satisfy the scope requirement`() {
        val restrictedOther = catalog.single { it.name == "rename capability forbidden" }.copy(token = FixtureParityCatalog.rootScoped)
        val stillMissing =
            ParityCoverage.missing(
                fixtures.registry,
                catalog.filterNot { it.name == "rename scope root" } + restrictedOther
            )
        assertEquals(
            listOf(MissingParity(ParityFixtureTools.EDIT, "rename", ParityKind.SCOPE_FORBIDDEN)),
            stillMissing,
            "a restricted-scope scenario of kind OTHER still does not count: only SCOPE_FORBIDDEN does"
        )
    }

    @Test
    fun `S17 a valid exemption satisfies the scope requirement and a blank one is rejected`() {
        val without = catalog.filterNot { it.name == "retag scope tag" }
        val exempt = ScopeExempt(ParityFixtureTools.EDIT, "retag", "retag touches no item outside the caller's scope in this fixture")
        assertEquals(emptyList(), ParityCoverage.missing(fixtures.registry, without, listOf(exempt)))

        for (blank in listOf("", "   ", "\t")) {
            val message =
                assertFailsWith<IllegalArgumentException> {
                    ParityCoverage.missing(fixtures.registry, without, listOf(exempt.copy(reason = blank)))
                }.message.orEmpty()
            assertTrue("retag" in message, "the rejection names the member: $message")
        }
    }

    @Test
    fun `S17 an exemption does not excuse a missing happy scenario and an unregistered exemption is rejected`() {
        val without = catalog.filterNot { it.name == "retag happy" }
        val exempt = ScopeExempt(ParityFixtureTools.EDIT, "retag", "reason")
        assertEquals(
            listOf(MissingParity(ParityFixtureTools.EDIT, "retag", ParityKind.HAPPY)),
            ParityCoverage.missing(fixtures.registry, without, listOf(exempt))
        )
        assertFailsWith<IllegalArgumentException> {
            ParityCoverage.missing(fixtures.registry, catalog, listOf(ScopeExempt("pf_ghost", "nothing", "reason")))
        }
    }

    @Test
    fun `S17 a scenario naming an unregistered member is reported as stale`() {
        val ghost = catalog.first().copy(name = "ghost", tool = "pf_ghost")
        assertEquals(listOf("ghost"), ParityCoverage.stale(fixtures.registry, catalog + ghost).map { it.name })
    }

    // ------------------------------------------------------------------ S18 production guard

    @Test
    fun `S18 the production registry is fully covered by the production catalog`() {
        val production = ToolRegistry(buildToolSpecs())
        assertEquals(emptyList(), ParityCoverage.missing(production, ProductionParity.scenarios, ProductionParity.exemptions))
        assertEquals(emptyList(), ParityCoverage.stale(production, ProductionParity.scenarios))
        // the same call is not trivially empty: it reports an uncovered registry
        assertTrue(ParityCoverage.missing(fixtures.registry, ProductionParity.scenarios, ProductionParity.exemptions).isNotEmpty())
    }

    // ------------------------------------------------------------------ S19 non-vacuity

    @Test
    fun `S19 a tool whose body depends on the surface fails the harness`() {
        val divergent = ParityHarness(fixtures.divergentRegistry)
        val s =
            ParityScenario(
                name = "echo surface",
                tool = ParityFixtureTools.SURFACE,
                member = "echo",
                kind = ParityKind.OTHER,
                token = FixtureParityCatalog.admin,
                arguments = """{"select":"echo"}""",
                expectStatus = 200
            )
        val failure = assertFailsWith<AssertionError> { divergent.run(s) }
        assertTrue("body parity" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `S19 a tool with equal bodies but a surface-dependent database effect fails the harness`() {
        val divergent = ParityHarness(fixtures.divergentRegistry)
        val s =
            ParityScenario(
                name = "stamp surface",
                tool = ParityFixtureTools.STAMP,
                member = "stamp",
                kind = ParityKind.OTHER,
                token = FixtureParityCatalog.admin,
                arguments = """{"ops":[{"op":"stamp","id":"${FixtureParityCatalog.c1}"}]}""",
                expectStatus = 200,
                seed = FixtureParityCatalog.seedTree
            )
        val failure = assertFailsWith<AssertionError> { divergent.run(s) }
        assertTrue("work_items snapshot parity" in failure.message.orEmpty(), failure.message)
    }

    // ------------------------------------------------------------------ S20 MCP default path

    @Test
    fun `S20 the MCP default path lifts the body actor and runs unscoped, while the seam substitutes principal and scope`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val fx = FixtureTools()
            val body = """{"actor":{"id":"agent-1","kind":"subagent"},"ops":[{"op":"create","title":"S20"}]}"""

            val byDefault = callMcp(rig.ctx, fx.registry, null, "fx_write", obj(body))
            assertFalse(byDefault.isError, byDefault.text)
            val defaultCtx = fx.seen.single()
            assertEquals("agent-1", defaultCtx.principal?.claim?.id, "the default path lifts the actor from the arguments")
            assertEquals("mcp", defaultCtx.surface)
            assertTrue(defaultCtx.scope.isUnrestricted, "the default path is unscoped")

            val seamed = callMcp(rig.ctx, fx.registry, { parityAccess(FixtureParityCatalog.reader) }, "fx_write", obj(body))
            assertTrue(seamed.isError, "control: a READ-only access seam makes the same call forbidden: ${seamed.text}")
            assertEquals(1, fx.seen.size, "the forbidden element did not run")
        }

    // ------------------------------------------------------------------ R5 profile sets (b342d320): AC5, AC6, S11

    private val modes = ProfileModeFixtures()

    @Test
    fun `S11 the MCP leg of a member runs on a profile whose set contains it`() {
        val registry = modes.registry
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "pm_items", "know_peek"))
        assertEquals(ToolProfile.ORCHESTRATION, ParityCoverage.mcpLegProfile(registry, "pm_items", "orch_peek"))
        assertEquals(ToolProfile.DIRECTOR, ParityCoverage.mcpLegProfile(registry, "pm_items", "dir_peek"))
        assertEquals(ToolProfile.KNOWLEDGE, ParityCoverage.mcpLegProfile(registry, "pm_items", "know_only"))
        assertNull(ParityCoverage.mcpLegProfile(registry, "pm_items", "web_peek"), "an empty set has no MCP leg")
        for ((tool, member) in ParityCoverage.members(registry)) {
            val leg = ParityCoverage.mcpLegProfile(registry, tool, member)
            val declared = ParityCoverage.profilesOf(registry, tool, member)!!
            assertEquals(declared.isNotEmpty(), leg != null, "$tool.$member: a leg exists exactly when the set is non-empty")
            if (leg != null) assertTrue(leg in declared, "$tool.$member: the leg ${leg.wire} must be in the member's own set")
        }
    }

    @Test
    fun `S11 every non-empty member passes the harness on its own profile, including a director-only and a knowledge-only one`() {
        val harness = ParityHarness(modes.registry)
        for (member in listOf("know_peek", "orch_peek", "dir_peek", "know_only")) {
            harness.run(modes.scenario(member))
        }
    }

    @Test
    fun `S11 a REST-only member runs the REST leg only and is unknown on every MCP profile`() {
        val outcome = ParityHarness(modes.registry).run(modes.scenario("web_peek"))
        assertEquals(200, outcome.restStatus)
        assertTrue("\"who\":\"web_peek\"" in outcome.body, "the REST leg answered the member: ${outcome.body}")
        assertFalse(outcome.dbChanged)
    }

    @Test
    fun `S11 the REST-only exemption follows the empty set so a renamed member stays exempt`() {
        val renamed = ProfileModeFixtures(restOnlyName = "zz_renamed_rest")
        assertEquals(listOf("pm_items" to "zz_renamed_rest"), ParityCoverage.restOnly(renamed.registry))
        assertEquals(listOf("pm_items" to "web_peek"), ParityCoverage.restOnly(modes.registry))
        ParityHarness(renamed.registry).run(renamed.scenario("zz_renamed_rest"))
    }

    @Test
    fun `S11 a member with a non-empty set is never reported REST-only`() {
        val restOnly = ParityCoverage.restOnly(modes.registry)
        for ((tool, member) in ParityCoverage.members(modes.registry)) {
            val empty = ParityCoverage.profilesOf(modes.registry, tool, member)!!.isEmpty()
            assertEquals(empty, (tool to member) in restOnly, "$tool.$member")
        }
        assertEquals(1, restOnly.size, restOnly.toString())
    }

    @Test
    fun `S11 a REST-only member still needs a HAPPY and a SCOPE_FORBIDDEN scenario`() {
        val missing = ParityCoverage.missing(modes.registry, emptyList())
        assertTrue(MissingParity("pm_items", "web_peek", ParityKind.HAPPY) in missing, missing.toString())
        assertTrue(MissingParity("pm_items", "web_peek", ParityKind.SCOPE_FORBIDDEN) in missing, missing.toString())
        assertEquals(10, missing.size, "5 members x 2 requirements: $missing")
    }

    @Test
    fun `S11 the REST-only leg is not vacuous - a wrong body expectation fails it`() {
        val wrong = modes.scenario("web_peek", expectContains = listOf("\"who\":\"not_this_member\""))
        assertFailsWith<AssertionError> { ParityHarness(modes.registry).run(wrong) }
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config

import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDescriber
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import io.github.jpicklyk.mcptask.current.parity.ParityCoverage
import io.github.jpicklyk.mcptask.current.parity.ParityKind
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.ProductionParity
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item bfdc296b / Q23) for the registration, surface and REST-removal acceptance criteria:
 * scenarios S18 and S19 and AC1, AC2, AC4 and AC6 of the frozen test-plan.
 *
 * Oracles: task-scope Contract (one `config` OpsToolSpec with ops get, history, push, validate, rollback, reset, effective,
 * rules, each field set and required list as in the table; READ for get / history / validate / effective / rules,
 * WRITE_CONFIG for push / rollback / reset, reset destructive, every op ToolProfile.ORCHESTRATION, so knowledge hides the whole
 * tool), decision D2, AC1 (`manage_project_config` and `query_rules` gone from the MCP tool list), AC2 (a HAPPY and a
 * SCOPE_FORBIDDEN parity scenario per op), AC4 (the removed per-root config, effective-view, discovery, rule and bare
 * `GET /config` routes answer 404; `GET /api/v1/config/schema` is unchanged and still served with an ETag under READ), the 8,000
 * character per-tool budget of ToolSurfaceChecks and the field-documentation rule.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigRegistrationTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())
    private val opNames = listOf("get", "history", "push", "validate", "rollback", "reset", "effective", "rules")

    private val fields: Map<String, Set<String>> =
        mapOf(
            "get" to setOf("rootId", "version", "fingerprint"),
            "history" to setOf("rootId", "limit", "before"),
            "push" to setOf("rootId", "configYaml", "force", "expectedVersion"),
            "validate" to setOf("rootId", "configYaml"),
            "rollback" to setOf("rootId", "version", "expectedVersion"),
            "reset" to setOf("rootId"),
            "effective" to setOf("rootId"),
            "rules" to setOf("rootId", "key", "itemId", "noteKey")
        )

    private val required: Map<String, Set<String>> =
        mapOf(
            "get" to setOf("rootId"),
            "history" to setOf("rootId"),
            "push" to setOf("rootId", "configYaml"),
            "validate" to setOf("rootId", "configYaml"),
            "rollback" to setOf("rootId", "version"),
            "reset" to setOf("rootId"),
            "effective" to emptySet(),
            "rules" to emptySet()
        )

    // ------------------------------------------------------------------ S18 registry

    @Test
    fun `S18 the registry holds config with the eight ops, their capability, profile and destructive flags`() {
        val spec = assertNotNull(registry["config"])
        assertTrue(spec is OpsToolSpec<*>)
        assertEquals(opNames, spec.ops.map { it.name })
        val reads = setOf("get", "history", "validate", "effective", "rules")
        for (op in spec.ops) {
            val expected = if (op.name in reads) ApiCapability.READ else ApiCapability.WRITE_CONFIG
            assertEquals(expected, op.capability, op.name)
            assertEquals(orchestration(), op.profiles, op.name)
        }
        assertEquals(listOf("reset"), spec.ops.filter { it.destructive }.map { it.name }, "only reset is destructive")
    }

    @Test
    fun `S18 config is visible in the orchestration profile and absent from knowledge`() {
        assertTrue("config" in registry.visible(ToolProfile.ORCHESTRATION).map { it.name })
        assertTrue("config" !in registry.visible(ToolProfile.KNOWLEDGE).map { it.name })
        assertNull(ToolSurfaceChecks.sizes(registry, ToolProfile.KNOWLEDGE)["config"], "nothing of config is rendered for knowledge")
        assertTrue(registry.visible(ToolProfile.KNOWLEDGE).isNotEmpty(), "control: knowledge still renders other tools")
    }

    @Test
    fun `S18 describe lists the eight ops with exactly the contract's fields and required lists`() {
        val spec = checkNotNull(registry["config"])
        val described = ToolDescriber.describe(spec, ToolProfile.ORCHESTRATION, null)
        val operations = described.getValue("operations").jsonArray.map { it.jsonObject }
        assertEquals(opNames, operations.map { it.str("name") })
        for (op in operations) {
            val name = op.str("name")
            val parameters = op.getValue("parameters").jsonObject
            assertEquals(fields.getValue(name), parameters.getValue("properties").jsonObject.keys, "$name fields")
            val requiredList = (parameters["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            assertEquals(required.getValue(name), requiredList.toSet(), "$name required")
        }
    }

    @Test
    fun `S18 a field name shared by several ops has one schema and the shared fields are documented`() {
        val spec = checkNotNull(registry["config"])
        val operations =
            ToolDescriber
                .describe(spec, ToolProfile.ORCHESTRATION, null)
                .getValue("operations")
                .jsonArray
                .map { it.jsonObject }

        fun schemas(field: String): List<JsonObject> =
            operations.mapNotNull {
                it
                    .getValue("parameters")
                    .jsonObject
                    .getValue("properties")
                    .jsonObject[field]
                    ?.jsonObject
            }
        for (shared in listOf("rootId", "version", "expectedVersion", "configYaml")) {
            val found = schemas(shared)
            assertTrue(found.size >= 2, "$shared is shared by at least two ops")
            assertEquals(1, found.toSet().size, "$shared has one schema: $found")
        }
        val rootDoc =
            schemas("rootId")
                .first()
                .getValue("description")
                .jsonPrimitive.content
        assertTrue("UUID" in rootDoc && "prefix" in rootDoc, rootDoc)
        assertTrue(
            "131072" in
                schemas("configYaml")
                    .first()
                    .getValue("description")
                    .jsonPrimitive.content
        )
    }

    @Test
    fun `S18 the surface stays within the budget and documentation checks, and config fits its own 8000 character ceiling`() {
        assertEquals(emptyList(), ToolSurfaceChecks.budgetViolations(registry))
        assertEquals(emptyList(), ToolSurfaceChecks.docViolations(registry))
        val size = assertNotNull(ToolSurfaceChecks.sizes(registry, ToolProfile.ORCHESTRATION)["config"])
        assertTrue(size in 1..ToolSurfaceChecks.PER_TOOL_CEILING, "config orchestration surface is $size chars")
    }

    @Test
    fun `S18 the orchestration golden exists and no knowledge golden does`() {
        assertNotNull(ConfigRegistrationTest::class.java.getResource("/golden/tools/config.orchestration.json"))
        assertNull(
            ConfigRegistrationTest::class.java.getResource("/golden/tools/config.knowledge.json"),
            "nothing of config is visible in knowledge"
        )
    }

    // ------------------------------------------------------------------ S18 / AC2 parity catalog

    @Test
    fun `S18 the production parity catalog covers every config op with a happy and a restricted scope-forbidden scenario`() {
        assertEquals(emptyList(), ParityCoverage.missing(registry, ProductionParity.scenarios, ProductionParity.exemptions))
        assertEquals(emptyList(), ParityCoverage.stale(registry, ProductionParity.scenarios))
        assertEquals(opNames, ParityCoverage.members(registry).filter { it.first == "config" }.map { it.second })
        for (name in opNames) {
            val mine = ProductionParity.scenarios.filter { it.tool == "config" && it.member == name }
            assertTrue(mine.any { it.kind == ParityKind.HAPPY }, "$name has a HAPPY scenario")
            assertTrue(
                mine.any { it.kind == ParityKind.SCOPE_FORBIDDEN && it.token.isScopeRestricted },
                "$name has a restricted-scope SCOPE_FORBIDDEN scenario"
            )
            assertTrue(ProductionParity.exemptions.none { it.tool == "config" && it.member == name }, "$name is not exempted")
        }
    }

    // ------------------------------------------------------------------ S19 removal

    @Test
    fun `S19 manage_project_config and query_rules are gone and config is served`() {
        val names = ToolRegistry(buildToolSpecs()).tools.map { it.name }
        assertTrue("config" in names, "control: config is served by the production registry: $names")
        assertTrue("manage_project_config" !in names, "manage_project_config must not be registered: $names")
        assertTrue("query_rules" !in names, "query_rules must not be registered: $names")
    }

    @Test
    fun `S19 the removed config rule and discovery routes answer 404 while config schema is unchanged and ops config is served`(): Unit =
        runBlocking {
            val admin = ParityToken("cfg-reg-admin", setOf(ApiCapability.ADMIN))
            val root = UUID.fromString("a1000001-0000-4000-8000-000000000001")
            testApplication {
                val rig = EventLogRig.build(db.db, dir)
                application { installParityRestApp(rig, parityAuthConfig(admin), registry) }
                CfgKit(rig).root("R1", root)
                val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                    header(HttpHeaders.Authorization, "Bearer ${admin.raw}")
                    contentType(ContentType.Application.Json)
                }
                val gets =
                    listOf(
                        "/api/v1/config",
                        "/api/v1/roots/$root/config",
                        "/api/v1/roots/$root/config/effective",
                        "/api/v1/roots/$root/config/versions",
                        "/api/v1/roots/$root/config/versions/1",
                        "/api/v1/config/schemas",
                        "/api/v1/config/schemas/bug",
                        "/api/v1/config/traits",
                        "/api/v1/config/types",
                        "/api/v1/config/status-graph",
                        "/api/v1/roots/$root/rules",
                        "/api/v1/roots/$root/rules/some-rule"
                    )
                for (path in gets) {
                    val response = client.get(path) { auth() }
                    assertEquals(404, response.status.value, "GET $path: ${response.bodyAsText()}")
                }
                val put =
                    client.put("/api/v1/roots/$root/config") {
                        auth()
                        setBody("""{"configYaml":"work_item_schemas: {}"}""")
                    }
                assertEquals(404, put.status.value, "PUT /roots/{id}/config")
                val delete = client.delete("/api/v1/roots/$root/config") { auth() }
                assertEquals(404, delete.status.value, "DELETE /roots/{id}/config")
                val rollback =
                    client.post("/api/v1/roots/$root/config/rollback") {
                        auth()
                        setBody("""{"version":1}""")
                    }
                assertEquals(404, rollback.status.value, "POST /roots/{id}/config/rollback")

                val schema = client.get("/api/v1/config/schema") { auth() }
                assertEquals(200, schema.status.value, "control: the schema route is served")
                assertTrue(schema.headers[HttpHeaders.ETag].orEmpty().isNotBlank(), "control: the schema route carries its ETag")

                val ops =
                    client.post("/api/v1/ops/config") {
                        auth()
                        setBody("""{"ops":[{"op":"effective"}]}""")
                    }
                assertEquals(200, ops.status.value, ops.bodyAsText())
                assertTrue(
                    Json
                        .parseToJsonElement(ops.bodyAsText())
                        .jsonObject
                        .getValue("ok")
                        .jsonPrimitive.content
                        .toBoolean()
                )
                assertFalse("manage_project_config" in ops.bodyAsText())
            }
        }
}

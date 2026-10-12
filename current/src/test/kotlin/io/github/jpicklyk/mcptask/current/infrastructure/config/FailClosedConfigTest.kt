package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.bootstrap.CompositionResult
import io.github.jpicklyk.mcptask.current.bootstrap.ServerComposition
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeWriteAuthConfig
import io.github.jpicklyk.mcptask.current.test.actorArg
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import io.github.jpicklyk.mcptask.current.test.upsertNoteVia
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item ad684e46 / Q17) for the fail-closed boundaries: a stored config head (the server
 * head or a root's) with no canonical form, or one that cannot be decoded, is `config_invalid` on every surface. It is never
 * absence, never a fall-through to another layer and never `internal`. Read faults keep the earlier rules (last-known-good
 * outside a unit, `config_unavailable` inside one or when cold), now for the server scope too.
 *
 * Scenarios: S5 (the server head and a root head, through the layer, the resolver, the 3.x MCP adapter, the 3.x REST routes
 * and the 4.0 envelope) and the read-fault probes.
 *
 * Oracles: task-scope 1.4 (the server twin of `PerRootConfigService.layer`: NULL or undecodable canonical JSON throws
 * `PerRootConfigInvalidException(rootId = null, ...)`; a read fault serves the last known layer outside a unit and throws
 * `PerRootConfigUnavailableException(rootId = null, ...)` inside one or when cold, its message saying "server config"), 1.5
 * (3.x MCP `errorCode` "config_invalid" with permanent kind; 3.x REST 422 "config_invalid"; the 4.0 envelope
 * `DomainError(CONFIG_INVALID)` whose detail carries one violation with an empty path) and AC6.
 *
 * Fixture: the head is healthy first (a control call on the same surface succeeds), then its canonical form is destroyed by
 * raw SQL and a FRESH composition is built over the database. The immutable per-version cache of a running service would
 * otherwise keep serving the document it already decoded, so a cold service is the honest model of "restart on a bad head".
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class FailClosedConfigTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    /** The `spec` note is optional so that a healthy `start` transition is admitted (the controls rely on it). */
    private val base =
        "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: false\n"

    private val rootOnly = "work_item_schemas:\n  root-only-type:\n    notes: []\n"

    // ------------------------------------------------------------------------------------------------ fixtures

    private fun agent(name: String): Path = dir.resolve(name).also { Files.createDirectories(it) }

    private fun writeConfig(
        agent: Path,
        text: String?
    ) {
        val configDir = agent.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val file = configDir.resolve("config.yaml")
        if (text == null) Files.deleteIfExists(file) else Files.write(file, text.toByteArray(Charsets.UTF_8))
    }

    private fun compose(agent: Path): CompositionResult {
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") agent.toString() else null }
        return ServerComposition(
            appConfig = appConfig,
            databaseManager = db.databaseManager,
            shutdownCoordinator = ShutdownCoordinator()
        ).build()
    }

    /** A boot with [text] as the mounted file (null = none): build, then run the data steps (the import). */
    private fun boot(
        name: String,
        text: String?
    ): CompositionResult {
        val agent = agent(name)
        writeConfig(agent, text)
        return compose(agent).also { runBlocking { it.dataStepRunner.run() } }
    }

    /** A composition over the same database with no data steps run: every layer cache is cold. */
    private fun cold(name: String): CompositionResult = compose(agent(name).also { writeConfig(it, null) })

    private fun sql(statement: String) =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use {
                it.executeUpdate(statement)
            }
        }

    private fun nullServerCanonical() = sql("UPDATE config_versions SET canonical_json = NULL, model_version = NULL WHERE scope = 'server'")

    private fun nullRootCanonical() = sql("UPDATE config_versions SET canonical_json = NULL, model_version = NULL WHERE scope = 'root'")

    private fun garbleServerCanonical() = sql("UPDATE config_versions SET canonical_json = 'not json {' WHERE scope = 'server'")

    private fun seedRoot(): WorkItem =
        runBlocking {
            db.repositoryProvider().workItemRepository().create(
                WorkItem(title = "Fail-closed root", type = "project", depth = 0)
            )
        }

    private fun seedItem(root: WorkItem): WorkItem =
        runBlocking {
            db.repositoryProvider().workItemRepository().create(
                WorkItem(
                    title = "Fail-closed item",
                    type = "feature-task",
                    role = Role.QUEUE,
                    parentId = root.id,
                    rootId = root.id,
                    depth = 1
                )
            )
        }

    private fun pushRootConfig(
        root: WorkItem,
        yaml: String
    ) = runBlocking { db.repositoryProvider().configStore().upsert(root.id, yaml) }

    // ------------------------------------------------------------------------------------------------ the codes

    @Test
    fun `the two failure codes are the wire strings of the contract`() {
        assertEquals("config_invalid", PerRootConfigInvalidException.CODE)
        assertEquals("config_unavailable", PerRootConfigUnavailableException.CODE)
    }

    // ------------------------------------------------------------------------------------------------ S5: layer and resolver

    @Test
    fun `S5 a server head without canonical JSON throws PerRootConfigInvalidException with no root, never absence`() {
        val healthy = boot("a", base)
        assertNotNull(runBlocking { healthy.serverConfig.layer() }, "control: the healthy head is served")
        nullServerCanonical()

        val e = assertFailsWith<PerRootConfigInvalidException> { runBlocking { cold("cold").serverConfig.layer() } }

        assertNull(e.rootId, "the server scope has no root")
        assertEquals(1, e.version)
        assertTrue((e as Throwable) !is PerRootConfigUnavailableException, "invalid is a distinct failure from unavailable")
    }

    @Test
    fun `S5 a server head with undecodable canonical JSON fails closed the same way`() {
        boot("a", base)
        garbleServerCanonical()

        val e = assertFailsWith<PerRootConfigInvalidException> { runBlocking { cold("cold").serverConfig.layer() } }

        assertNull(e.rootId)
        assertEquals(1, e.version)
    }

    @Test
    fun `S5 schema resolution throws for the broken server head instead of resolving from nothing`() {
        val healthy = boot("a", base)
        assertNotNull(runBlocking { healthy.toolContext.resolveTypeSchema("feature-task", null) }, "control: the type resolves")
        val root = seedRoot()
        nullServerCanonical()
        val coldComposition = cold("cold")

        val noRoot =
            assertFailsWith<PerRootConfigInvalidException> {
                runBlocking {
                    coldComposition.toolContext.resolveTypeSchema(
                        "feature-task",
                        null
                    )
                }
            }
        assertNull(noRoot.rootId)
        val withRoot =
            assertFailsWith<PerRootConfigInvalidException> {
                runBlocking { cold("cold-2").toolContext.resolveTypeSchema("feature-task", root.id) }
            }
        assertNull(withRoot.rootId, "it is the server head that is broken, not the root's")
    }

    @Test
    fun `S5 a healthy root config does not rescue a broken server head`() {
        boot("a", base)
        val root = seedRoot()
        pushRootConfig(root, rootOnly)
        val control = cold("control")
        assertNotNull(
            runBlocking { control.toolContext.resolveTypeSchema("root-only-type", root.id) },
            "control: the root's own type resolves"
        )
        nullServerCanonical()

        assertFailsWith<PerRootConfigInvalidException> {
            runBlocking { cold("cold").toolContext.resolveTypeSchema("root-only-type", root.id) }
        }
    }

    @Test
    fun `S5 a root head without canonical JSON names the root and is not rescued by a healthy server head`() {
        boot("a", base)
        val root = seedRoot()
        pushRootConfig(root, rootOnly)
        nullRootCanonical()

        val e =
            assertFailsWith<PerRootConfigInvalidException> {
                runBlocking {
                    cold(
                        "cold"
                    ).toolContext.resolveTypeSchema("feature-task", root.id)
                }
            }

        assertEquals(root.id, e.rootId)
    }

    // ------------------------------------------------------------------------------------------------ S5: 3.x MCP

    /**
     * `items_read` select `schema` through the real dispatcher (merge r1: Q19 removed `query_items`, whose schema operation
     * this scenario first exercised; the type-schema lookup now lives on items_read and fails closed through the envelope).
     */
    private fun schemaRead(
        composition: CompositionResult,
        type: String,
        rootId: UUID? = null
    ): JsonObject =
        runBlocking {
            composition.toolContext.callTool(
                "items_read",
                buildJsonObject {
                    put("select", "schema")
                    put("type", type)
                    if (rootId != null) put("rootId", rootId.toString())
                }
            )
        }

    /**
     * `items_read` select `context` for one item through the real dispatcher (merge r2: Q22 removed `get_context` and
     * `GET /items/{id}/gate`; the item's gate status now lives on this select and fails closed through the envelope).
     */
    private fun contextRead(
        composition: CompositionResult,
        itemId: UUID
    ): JsonObject =
        runBlocking {
            composition.toolContext.callTool(
                "items_read",
                buildJsonObject {
                    put("select", "context")
                    put("itemId", itemId.toString())
                }
            )
        }

    @Test
    fun `S5 3x MCP - a broken server head is config_invalid on items_read schema and context, a healthy one is not`() {
        val healthy = boot("a", base)
        val root = seedRoot()
        val item = seedItem(root)
        assertEnvelopeOk(schemaRead(healthy, "feature-task"), "control: schema lookup succeeds")
        assertEnvelopeOk(contextRead(healthy, item.id), "control: items_read(context, itemId) succeeds")
        nullServerCanonical()
        val broken = cold("cold")

        assertEnvelopeConfigInvalid(schemaRead(broken, "feature-task"), "items_read(schema, type)")
        assertEnvelopeConfigInvalid(schemaRead(broken, "feature-task", root.id), "items_read(schema, type, rootId)")
        assertEnvelopeConfigInvalid(contextRead(broken, item.id), "items_read(context, itemId)")
    }

    @Test
    fun `S5 3x MCP - a broken root head is config_invalid, not the healthy server answer`() {
        val healthy = boot("a", base)
        val root = seedRoot()
        val item = seedItem(root)
        pushRootConfig(root, rootOnly)
        assertEnvelopeOk(schemaRead(healthy, "root-only-type", root.id), "control: the root's type resolves")
        nullRootCanonical()
        val broken = cold("cold")

        assertEnvelopeConfigInvalid(schemaRead(broken, "root-only-type", root.id), "items_read(schema, type, rootId)")
        assertEnvelopeConfigInvalid(contextRead(broken, item.id), "items_read(context, itemId)")
    }

    // ------------------------------------------------------------------------------------------------ S5: 3.x REST

    private fun Application.productionRest(composition: CompositionResult) {
        install(ContentNegotiation) { json(McpJson) }
        val authConfig = makeWriteAuthConfig()
        val tokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = tokenEntries,
            allowQueryToken = false,
            serverName = "fail-closed",
            serverVersion = "1.0.0",
            actorAuthEnabled = composition.actorAuthEnabled,
            serverConfig = composition.serverConfig,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy
        )
    }

    private fun rest(
        composition: CompositionResult,
        block: suspend ApplicationTestBuilder.() -> Unit
    ) = testApplication {
        application { productionRest(composition) }
        block()
    }

    private suspend fun ApplicationTestBuilder.fetch(
        path: String,
        token: String = TEST_TOKEN
    ) = client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun ApplicationTestBuilder.opsContext(itemId: UUID) =
        client.post("/api/v1/ops/items_read") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            contentType(ContentType.Application.Json)
            setBody("""{"select":"context","itemId":"$itemId"}""")
        }

    private suspend fun ApplicationTestBuilder.assertOpsContextConfigInvalid(
        itemId: UUID,
        what: String
    ) {
        val response = opsContext(itemId)
        val body = response.bodyAsText()
        // The ops route takes its HTTP status from the envelope error code (ErrorCode.CONFIG_INVALID = 422), as /gate did.
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "$what: $body")
        val parsed = McpJson.parseToJsonElement(body).jsonObject
        assertEquals("false", parsed["ok"]?.jsonPrimitive?.content, "$what: $body")
        assertEnvelopeConfigInvalid(parsed, what)
    }

    private suspend fun ApplicationTestBuilder.assertRestConfigInvalid(
        path: String,
        what: String
    ) {
        val response = fetch(path)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "$what: $body")
        assertTrue("config_invalid" in body, "$what: $body")
    }

    /** A `config` `effective` call for [root] (the server set when null) through the composition's real dispatcher. */
    private fun configEffective(
        composition: CompositionResult,
        root: UUID?
    ): JsonObject =
        runBlocking {
            composition.toolContext.callTool(
                "config",
                buildJsonObject {
                    put(
                        "ops",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("op", "effective")
                                    root?.let { put("rootId", it.toString()) }
                                }
                            )
                        )
                    )
                }
            )
        }

    @Test
    fun `S5 3x REST - a broken server head is config_invalid on the config effective op and fails ops items_read context`() {
        val healthy = boot("a", base)
        val root = seedRoot()
        val item = seedItem(root)
        assertEnvelopeOk(configEffective(healthy, null), "control: config effective (server set)")
        assertEnvelopeOk(configEffective(healthy, root.id), "control: config effective (root)")
        rest(healthy) {
            val control = opsContext(item.id)
            assertEquals(HttpStatusCode.OK, control.status, "control: ops items_read context: ${control.bodyAsText()}")
        }
        nullServerCanonical()
        val broken = cold("cold")

        assertEnvelopeConfigInvalid(configEffective(broken, null), "config effective (server set)")
        assertEnvelopeConfigInvalid(configEffective(broken, root.id), "config effective (root)")
        rest(broken) {
            assertOpsContextConfigInvalid(item.id, "POST /ops/items_read context")
        }
    }

    @Test
    fun `S5 3x REST - a broken root head is config_invalid on its config effective op and on ops items_read context`() {
        val healthy = boot("a", base)
        val root = seedRoot()
        val item = seedItem(root)
        pushRootConfig(root, rootOnly)
        assertEnvelopeOk(configEffective(healthy, root.id), "control: config effective (root)")
        nullRootCanonical()
        val broken = cold("cold")

        assertEnvelopeConfigInvalid(configEffective(broken, root.id), "config effective (root)")
        rest(broken) {
            assertOpsContextConfigInvalid(item.id, "POST /ops/items_read context")
        }
        assertEnvelopeOk(configEffective(broken, null), "the server head itself is healthy and still served")
    }

    // ------------------------------------------------------------------------------------------------ S5: 4.0 envelope

    private fun advanceBody(itemId: UUID): JsonObject =
        buildJsonObject {
            put("actor", actorArg("agent-fail-closed"))
            put(
                "ops",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("op", "transition")
                            put("itemId", itemId.toString())
                            put("trigger", "start")
                        }
                    )
                )
            )
        }

    /**
     * The error of an envelope: the first element's error when the batch reports per-element results (the whole-call error is
     * then only the `partial_failure` summary), else the whole-call error.
     */
    private fun errorOf(body: JsonObject): JsonObject {
        val element =
            body["data"]
                ?.jsonObject
                ?.get("results")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("error")
                ?.jsonObject
        return element ?: body["error"]!!.jsonObject
    }

    private fun assertEnvelopeOk(
        body: JsonObject,
        what: String
    ) = assertEquals("true", body["ok"]?.jsonPrimitive?.content, "$what: $body")

    private fun assertEnvelopeConfigInvalid(
        body: JsonObject,
        what: String
    ) {
        val error = errorOf(body)
        assertEquals("config_invalid", error["code"]?.jsonPrimitive?.content, "$what: $body")
        assertEquals("permanent", error["kind"]?.jsonPrimitive?.content, "$what: $body")
        val violations = error["detail"]!!.jsonObject["errors"]!!.jsonArray
        assertEquals("", violations[0].jsonObject["path"]?.jsonPrimitive?.content, "$what: the violation has an empty path: $body")
        assertTrue(
            violations[0]
                .jsonObject["reason"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
                .isNotBlank(),
            "$what: the reason is the message"
        )
    }

    private fun noteUpsert(
        composition: CompositionResult,
        itemId: UUID
    ): JsonObject =
        runBlocking { composition.toolContext.upsertNoteVia(itemId, "spec", "queue", "a body", actor = actorArg("agent-fail-closed")) }

    private fun transition(
        composition: CompositionResult,
        itemId: UUID
    ): JsonObject = runBlocking { composition.toolContext.callTool("advance", advanceBody(itemId)) }

    @Test
    fun `S5 4x envelope - fixture control - a config read fault on the same path is the mapped unavailable error, not internal`() {
        boot("a", base)
        val root = seedRoot()
        val item = seedItem(root)
        val faulty = cold("cold")
        rawExec(db.jdbcUrl, "ALTER TABLE config_heads RENAME TO config_heads_gone")

        val error = errorOf(transition(faulty, item.id))

        // The 4.0 catalog code for an unreadable config is `unavailable` (kind shedding), already mapped before this item.
        assertEquals("unavailable", error["code"]?.jsonPrimitive?.content, "$error")
    }

    @Test
    fun `S5 4x envelope - a broken server head is config_invalid on an advance transition`() {
        boot("a", base)
        val root = seedRoot()
        val item = seedItem(root)
        assertEnvelopeOk(
            transition(cold("control"), seedItem(root).id),
            "control: a cold composition over the healthy head admits the transition"
        )
        nullServerCanonical()

        assertEnvelopeConfigInvalid(transition(cold("cold"), item.id), "advance transition")
    }

    @Test
    fun `S5 4x envelope - a broken server head is config_invalid on a notes_write upsert`() {
        boot("a", base)
        val root = seedRoot()
        val item = seedItem(root)
        assertEnvelopeOk(
            noteUpsert(cold("control"), seedItem(root).id),
            "control: a cold composition over the healthy head admits the upsert"
        )
        nullServerCanonical()

        assertEnvelopeConfigInvalid(noteUpsert(cold("cold"), item.id), "notes_write upsert")
    }

    @Test
    fun `S5 4x envelope - a broken root head is config_invalid on an advance transition`() {
        boot("a", base)
        val root = seedRoot()
        val item = seedItem(root)
        val sibling = seedItem(root)
        pushRootConfig(root, rootOnly)
        assertEnvelopeOk(
            transition(cold("control"), sibling.id),
            "control: a cold composition over the healthy heads admits the transition"
        )
        nullRootCanonical()

        assertEnvelopeConfigInvalid(transition(cold("cold"), item.id), "advance transition")
    }

    @Test
    fun `S5 4x envelope - a broken root head is config_invalid on a notes_write upsert`() {
        boot("a", base)
        val root = seedRoot()
        val item = seedItem(root)
        val sibling = seedItem(root)
        pushRootConfig(root, rootOnly)
        assertEnvelopeOk(noteUpsert(cold("control"), sibling.id), "control: a cold composition over the healthy heads admits the upsert")
        nullRootCanonical()

        assertEnvelopeConfigInvalid(noteUpsert(cold("cold"), item.id), "notes_write upsert")
    }

    // ------------------------------------------------------------------------------------------------ read faults keep their rules

    @Test
    fun `a server head read fault serves the last known layer outside a unit and throws Unavailable inside one or cold`() {
        val composition = boot("a", base)
        val warm = assertNotNull(runBlocking { composition.serverConfig.layer() })
        val inUnitControl = runBlocking { composition.unitOfWork.read { composition.serverConfig.layer() } }
        assertNotNull(inUnitControl, "control: inside a unit the healthy head is served")
        rawExec(db.jdbcUrl, "ALTER TABLE config_heads RENAME TO config_heads_gone")

        val served = assertNotNull(runBlocking { composition.serverConfig.layer() }, "outside a unit the last known layer is served")
        assertEquals(warm.fingerprint, served.fingerprint)

        val inUnit =
            assertFailsWith<PerRootConfigUnavailableException> {
                runBlocking {
                    composition.unitOfWork.read {
                        composition.serverConfig
                            .layer()
                    }
                }
            }
        assertNull(inUnit.rootId, "the server scope has no root")
        assertTrue("server config" in inUnit.message.lowercase(), "the message says server config: ${inUnit.message}")

        val coldFault = assertFailsWith<PerRootConfigUnavailableException> { runBlocking { cold("cold").serverConfig.layer() } }
        assertNull(coldFault.rootId)
        assertTrue("server config" in coldFault.message.lowercase(), coldFault.message)
    }
}

package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.config.CanonicalConfigJson
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.DocumentSchemaCatalog
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.port.ConfigAppend
import io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ConfigService
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.security.configFingerprint
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.GoldenV17
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.UpgradeHarness
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.EffectiveConfigDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureTestApp
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.effectiveConfigRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeWriteAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.projectConfigRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.serviceRoutes
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item ad684e46 / Q17) for the boot import of the mounted global file
 * (`server-config-import`), the DB-only runtime read of the server layer, and the startup failure mapping.
 *
 * Scenarios: S1 (server version 1 verbatim), S2 (project.rootId targets a root and records the event), S3 (the U20
 * allowance: a 3.x file boots with stored warnings, `/info` serves them, a per-root push of the same text is still
 * rejected), S4 (every other defect fails startup as DATA_STEPS with every `path: reason`), S7 (idempotence, absent and
 * empty files never reset a head), S8 (two processes with different files alternate versions, last boot wins), S9
 * (the runtime follows the stored head, never the file), S12 (the upgrade invariant on a 3.x volume) and the probes.
 *
 * Oracles: task-scope sections 1.1, 1.2 (pipeline steps 1 to 6, decisions D1 to D8), 1.4 (runtime reads are DB-only), 1.8
 * (`/info.configWarnings`) and acceptance criteria AC1 to AC5 and AC9; the plan clauses U6 (last booting process wins on a
 * shared volume) and U20 (the four migration-only keys warn at boot); the canonical JSON codec rule (sorted keys) as
 * pinned by ConfigServiceVersioningTest; `configFingerprint` (CRLF is normalized before hashing, ConfigFingerprintTest).
 * Row shapes come from the checked-in schema snapshots of `config_versions`, `config_heads` and `data_steps`.
 *
 * Harness: the DataStepsStartupTest pattern (`CurrentMcpServer.run()` with a sentinel transport hook) for the startup
 * outcome; a real [ServerComposition] over the per-method SQLite database with `dataStepRunner.run()` for everything else.
 * Every failure scenario has a same-fixture control that boots.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class BootImportTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------------------------------------ fixtures

    private val base =
        "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n"

    /** The codec rule (sorted keys, no whitespace) applied by hand to [base]; the same literal ConfigServiceVersioningTest pins. */
    private val baseCanonical =
        "{\"work_item_schemas\":{\"feature-task\":{\"notes\":[{\"key\":\"spec\",\"required\":true,\"role\":\"queue\"}]}}}"

    private val other = "work_item_schemas:\n  other-task:\n    notes: []\n"

    /** An untouched 3.x file: the four migration-only top-level keys next to a valid schema. */
    private val legacy3x =
        "orchestration:\n  mode: workflow\nretrospective:\n  mode: dispatch\nactor_attribution:\n  required: true\n" +
            "schema_resolution: legacy\n" + base

    private val migrationOnlyKeys = listOf("orchestration", "retrospective", "actor_attribution", "schema_resolution")

    private val noopActorAuth = "actor_authentication:\n  verifier:\n    type: noop\n"

    private val dogfoodRoot: UUID = UUID.fromString("ce064d2c-7c03-4eac-ae7d-89a14c2ba273")

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

    private fun compose(
        agent: Path,
        manager: DatabaseManager = db.databaseManager
    ): CompositionResult {
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") agent.toString() else null }
        return ServerComposition(
            appConfig = appConfig,
            databaseManager = manager,
            shutdownCoordinator = ShutdownCoordinator()
        ).build()
    }

    /** A boot: write [text] (null = no file), build the composition, run the data steps. */
    private fun boot(
        agent: Path,
        text: String?
    ): CompositionResult {
        writeConfig(agent, text)
        val composition = compose(agent)
        runBlocking { composition.dataStepRunner.run() }
        return composition
    }

    private fun seedRoot(
        id: UUID = UUID.randomUUID(),
        title: String = "Project root"
    ): WorkItem =
        runBlocking {
            db.repositoryProvider().workItemRepository().create(WorkItem(id = id, title = title, type = "project", depth = 0))
        }

    // ------------------------------------------------------------------------------------------------ raw rows

    private data class VersionRow(
        val scope: String,
        val rootHex: String?,
        val version: Int,
        val source: String,
        val yaml: String,
        val fingerprint: String,
        val canonical: String?,
        val modelVersion: Int?,
        val warnings: List<String>
    )

    private fun <T> query(
        sql: String,
        map: (ResultSet) -> T
    ): List<T> =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } } }
        }

    private fun versionRows(): List<VersionRow> =
        query(
            "SELECT scope, lower(hex(root_id)), version, source, yaml, fingerprint, canonical_json, model_version, warnings_json " +
                "FROM config_versions ORDER BY scope, root_id, version"
        ) { rs ->
            VersionRow(
                scope = rs.getString(1),
                rootHex = rs.getString(2)?.takeIf { it.isNotEmpty() },
                version = rs.getInt(3),
                source = rs.getString(4),
                yaml = rs.getString(5),
                fingerprint = rs.getString(6),
                canonical = rs.getString(7),
                modelVersion = rs.getInt(8).takeUnless { rs.wasNull() },
                warnings =
                    rs.getString(9)?.let { json -> Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content } } ?: emptyList()
            )
        }

    private fun serverRows() = versionRows().filter { it.scope == "server" }

    private fun rootRows() = versionRows().filter { it.scope == "root" }

    /** The version number the server head points at, or null when there is no server head. */
    private fun serverHeadVersion(): Int? =
        query(
            "SELECT v.version FROM config_heads h JOIN config_versions v ON v.id = h.version_id WHERE h.scope = 'server'"
        ) { it.getInt(1) }
            .singleOrNull()

    private fun dataStepNames(): Set<String> = query("SELECT name FROM data_steps") { it.getString(1) }.toSet()

    private fun hex(id: UUID) = id.toString().replace("-", "").lowercase()

    private fun serverFingerprint(c: CompositionResult): String? = runBlocking { c.serverConfig.layer() }?.fingerprint

    // ------------------------------------------------------------------------------------------------ S1

    @Test
    fun `S1 a strict-valid file without project rootId becomes server version 1, verbatim, with no ledger row`() {
        val text = StrictConfigCases.floorFixture
        val composition = boot(agent("a"), text)

        val rows = versionRows()
        assertEquals(1, rows.size, "exactly one version row: $rows")
        val v = rows.single()
        assertEquals("server", v.scope)
        assertNull(v.rootHex)
        assertEquals(1, v.version)
        assertEquals("file-import", v.source)
        assertEquals(text.toByteArray(Charsets.UTF_8).toList(), v.yaml.toByteArray(Charsets.UTF_8).toList(), "yaml is byte-identical")
        assertEquals(configFingerprint(text), v.fingerprint)
        assertTrue(!v.canonical.isNullOrBlank(), "canonical_json is set")
        assertEquals(1, v.modelVersion)
        assertEquals(emptyList(), v.warnings, "a strict-valid file stores no warnings")
        assertEquals(1, serverHeadVersion())
        assertFalse("server-config-import" in dataStepNames(), "an EVERY_BOOT step leaves no data_steps row: ${dataStepNames()}")

        val layer = assertNotNull(runBlocking { composition.serverConfig.layer() }, "the runtime serves the stored head")
        assertEquals(configFingerprint(text), layer.fingerprint)
        assertEquals(ConfigSource.GLOBAL, layer.source)
    }

    @Test
    fun `S1 the stored canonical JSON of the imported file is the sorted-key form of the document`() {
        boot(agent("a"), base)

        assertEquals(baseCanonical, serverRows().single().canonical)
    }

    @Test
    fun `S1 a SERVER import records no config version event`() {
        val composition = boot(agent("a"), base)

        val configEvents = runBlocking { EventLogRig(db.db, composition).rows() }.filter { it.type == "config.version_created" }
        assertEquals(emptyList(), configEvents, "DomainEvent.rootId is non-null, so the server scope records no event (D5)")
    }

    @Test
    fun `S7 booting the same file three times leaves one version row and still no ledger row`() {
        val text = StrictConfigCases.floorFixture
        repeat(3) { boot(agent("boot-$it"), text) }

        assertEquals(1, versionRows().size)
        assertEquals(1, serverHeadVersion())
        assertFalse("server-config-import" in dataStepNames())
    }

    // ------------------------------------------------------------------------------------------------ S2

    @Test
    fun `S2 a file naming a depth-0 item in project rootId becomes that root's head and records the event`() {
        seedRoot(dogfoodRoot)
        val text = StrictConfigCases.dogfoodFixture
        val composition = boot(agent("a"), text)

        val rows = versionRows()
        assertEquals(1, rows.size, "only the root's version: $rows")
        val v = rows.single()
        assertEquals("root", v.scope)
        assertEquals(hex(dogfoodRoot), v.rootHex)
        assertEquals(1, v.version)
        assertEquals("file-import", v.source)
        assertEquals(text, v.yaml)
        assertEquals(configFingerprint(text), v.fingerprint)
        assertEquals(emptyList(), v.warnings, "the 4.0 dogfood fixture validates with zero warnings")
        assertNull(runBlocking { composition.serverConfig.layer() }, "the file did not become the server set")

        val events = runBlocking { EventLogRig(db.db, composition).rows() }.filter { it.type == "config.version_created" }
        assertEquals(1, events.size, "exactly one event: $events")
        val event = events.single()
        assertEquals(dogfoodRoot, event.rootId)
        assertEquals("config", event.entityKind)
        assertEquals("root", event.str("scope"))
        assertEquals("file-import", event.str("source"))
    }

    @Test
    fun `S2 the root head is what the root resolves, with source per-root and the file's fingerprint`() {
        seedRoot(dogfoodRoot)
        val text = StrictConfigCases.dogfoodFixture
        val composition = boot(agent("a"), text)

        val result =
            runBlocking {
                QueryItemsTool().execute(
                    buildJsonObject {
                        put("operation", JsonPrimitive("schema"))
                        put("type", JsonPrimitive("feature-implementation"))
                        put("rootId", JsonPrimitive(dogfoodRoot.toString()))
                    },
                    composition.toolContext
                )
            } as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "schema lookup must succeed: $result")
        val data = result["data"]!!.jsonObject
        assertEquals("per-root", data["configSource"]!!.jsonPrimitive.content)
        assertEquals(configFingerprint(text), data["configFingerprint"]!!.jsonPrimitive.content)
    }

    @Test
    fun `S2 a project rootId file imported again appends nothing and records no second event`() {
        seedRoot(dogfoodRoot)
        val text = StrictConfigCases.dogfoodFixture
        boot(agent("a"), text)
        val second = boot(agent("b"), text)

        assertEquals(1, rootRows().size)
        val events = runBlocking { EventLogRig(db.db, second).rows() }.filter { it.type == "config.version_created" }
        assertEquals(1, events.size, "an identical re-import is not a new version, so it is not a new event")
    }

    // ------------------------------------------------------------------------------------------------ S3 / U20

    @Test
    fun `S3 an untouched 3x file boots with one stored warning per migration-only key`() {
        boot(agent("a"), legacy3x)

        val v = serverRows().single()
        assertEquals(1, v.version)
        assertEquals("file-import", v.source)
        assertEquals(legacy3x, v.yaml)
        assertEquals(4, v.warnings.size, "one warning per key: ${v.warnings}")
        for (key in migrationOnlyKeys) {
            assertTrue(v.warnings.any { key in it }, "a warning must name '$key': ${v.warnings}")
        }
    }

    @Test
    fun `S3 each migration-only key alone boots with exactly one warning naming it`() {
        val yamls =
            mapOf(
                "orchestration" to "orchestration:\n  mode: workflow\n" + base,
                "retrospective" to "retrospective:\n  mode: dispatch\n" + base,
                "actor_attribution" to "actor_attribution:\n  required: true\n" + base,
                "schema_resolution" to "schema_resolution: legacy\n" + base
            )
        yamls.entries.forEachIndexed { index, (key, text) ->
            boot(agent("key-$index"), text)
            val head = serverRows().maxByOrNull { it.version }!!
            assertEquals(text, head.yaml, "the head is this boot's file for $key")
            assertEquals(1, head.warnings.size, "exactly one warning for $key: ${head.warnings}")
            assertTrue(key in head.warnings.single(), "the warning names '$key': ${head.warnings}")
        }
    }

    @Test
    fun `S3 info serves the stored warnings and a per-root push of the same text is still rejected`() {
        val composition = boot(agent("a"), legacy3x)
        val stored = serverRows().single().warnings
        val service = strictConfigService(composition)
        val root = seedRoot()

        testApplication {
            application { restApp(composition, service) }

            val info = client.get("/api/v1/info") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, info.status)
            val warnings =
                Json
                    .parseToJsonElement(
                        info.bodyAsText()
                    ).jsonObject["configWarnings"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            assertEquals(stored, warnings, "/info.configWarnings equals the stored server head warnings")
            assertEquals(4, warnings.size)

            val put =
                client.put("/api/v1/roots/${root.id}/config") {
                    header(HttpHeaders.Authorization, "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(legacy3x)
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, put.status, "the push keeps Q16 strictness: ${put.bodyAsText()}")
        }

        val pushed = runBlocking { service.push(root.id, legacy3x) }
        val invalid = assertIs<ConfigPushResult.Invalid>(pushed, "push result: $pushed")
        for (key in migrationOnlyKeys) {
            assertTrue("/$key" in invalid.errors.map { it.path }, "push errors must include /$key: ${invalid.errors}")
        }
        assertEquals(emptyList(), rootRows(), "nothing stored for the root")
    }

    @Test
    fun `S3 info carries no warnings after a strict-valid boot`() {
        val composition = boot(agent("a"), StrictConfigCases.floorFixture)
        val service = strictConfigService(composition)

        testApplication {
            application { restApp(composition, service) }

            val info = client.get("/api/v1/info") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, info.status)
            // An additive list field with an empty default: absent and empty both decode to the empty list.
            val warnings =
                Json
                    .parseToJsonElement(info.bodyAsText())
                    .jsonObject["configWarnings"]
                    ?.jsonArray
                    .orEmpty()
            assertEquals(emptyList(), warnings.map { it.jsonPrimitive.content })
        }
    }

    @Test
    fun `S3 a plugin key under extensions is stored without a warning`() {
        val text = "extensions:\n  claude-code:\n    orchestration:\n      mode: workflow\n" + base
        boot(agent("a"), text)

        val v = serverRows().single()
        assertEquals(text, v.yaml)
        assertEquals(emptyList(), v.warnings, "extensions are strict-legal, so nothing warns")
    }

    // ------------------------------------------------------------------------------------------------ S4 (startup outcome)

    private class Startup(
        val outcome: StartupOutcome,
        val dbFile: Path,
        val readiness: Path
    )

    private fun startup(
        name: String,
        text: String?
    ): Startup {
        val home = dir.resolve(name).also { Files.createDirectories(it) }
        val agent = home.resolve("agent").also { Files.createDirectories(it) }
        writeConfig(agent, text)
        val dbFile = home.resolve("boot.db")
        val readiness = home.resolve("ready")
        val env =
            mapOf(
                "DATABASE_PATH" to dbFile.toString(),
                "MCP_TRANSPORT" to "http",
                "MCP_HTTP_HOST" to "127.0.0.1",
                "MCP_HTTP_PORT" to "0",
                "READINESS_FILE" to readiness.toString(),
                "AGENT_CONFIG_DIR" to agent.toString()
            )
        val server =
            CurrentMcpServer(
                version = "test",
                shutdownCoordinator = ShutdownCoordinator(),
                appConfig = AppConfig.fromEnv { key -> env[key] },
                onBeforeTransportStart = { _: String -> throw IllegalStateException("stop-at-transport") },
                extraDataSteps = emptyList()
            )
        return Startup(server.run(), dbFile, readiness)
    }

    private fun Startup.versionRowCount(): Int =
        DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath().toString().replace('\\', '/')).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM config_versions").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun assertRejectedAtStartup(
        s: Startup,
        vararg detailParts: String
    ) {
        val outcome = s.outcome
        assertTrue(outcome is Failed, "expected Failed(DATA_STEPS), got $outcome")
        assertEquals(Reason.DATA_STEPS, outcome.reason, "detail: ${outcome.detail}")
        for (part in detailParts) assertTrue(part in outcome.detail, "detail must contain '$part': ${outcome.detail}")
        assertEquals(0, s.versionRowCount(), "a rejected file stores nothing")
        assertFalse(Files.exists(s.readiness), "a rejected file must never produce the readiness marker")
    }

    @Test
    fun `S4 control - the valid base file passes the import and startup proceeds to the transport stage`() {
        val s = startup("control", base)

        val outcome = s.outcome
        assertTrue(outcome is Failed, "the sentinel hook stops the run: $outcome")
        assertEquals(Reason.TRANSPORT_START, outcome.reason, "detail: ${outcome.detail}")
        assertTrue("stop-at-transport" in outcome.detail, outcome.detail)
        assertEquals(1, s.versionRowCount(), "the control file was imported")
    }

    @Test
    fun `S4 an unknown top-level key fails startup as DATA_STEPS naming the path`() {
        assertRejectedAtStartup(startup("bogus", "bogus: 1\n" + base), "/bogus")
    }

    @Test
    fun `S4 every defect is listed, not only the first`() {
        assertRejectedAtStartup(startup("two", "bogus: 1\nbogus-two: 2\n" + base), "/bogus", "/bogus-two")
    }

    @Test
    fun `S4 a malformed project rootId fails startup naming the rootId path`() {
        assertRejectedAtStartup(startup("bad-uuid", "project:\n  rootId: not-a-uuid\n" + base), "/project/rootId")
    }

    @Test
    fun `S4 a well-formed project rootId that is not an item in this database fails startup naming it`() {
        val unknown = "11111111-2222-4333-8444-555555555555"
        assertRejectedAtStartup(startup("unknown-root", "project:\n  rootId: \"$unknown\"\n" + base), unknown)
    }

    @Test
    fun `S4 a file over the size cap fails startup, one at the cap boots`() {
        val max = ConfigService.MAX_CONFIG_YAML_BYTES
        val head = base + "#"
        val atCap = head + "x".repeat(max - head.length)
        assertEquals(max, atCap.toByteArray(Charsets.UTF_8).size, "fixture: exactly at the cap")

        val boots = startup("at-cap", atCap)
        val bootsOutcome = boots.outcome
        assertTrue(bootsOutcome is Failed && bootsOutcome.reason == Reason.TRANSPORT_START, "a file at the cap boots: $bootsOutcome")
        assertEquals(1, boots.versionRowCount())

        assertRejectedAtStartup(startup("over-cap", atCap + "x"))
    }

    @Test
    fun `S4 a differently cased migration-only key gets no allowance`() {
        assertRejectedAtStartup(startup("cased", "Orchestration:\n  mode: workflow\n" + base), "/Orchestration")
    }

    @Test
    fun `S4 a nested migration-only key gets no allowance`() {
        assertRejectedAtStartup(startup("nested", "project:\n  orchestration:\n    mode: workflow\n" + base), "orchestration")
    }

    @Test
    fun `S4 an allowed legacy key does not excuse another defect in the same file`() {
        assertRejectedAtStartup(startup("mixed", "schema_resolution: legacy\nbogus: 1\n" + base), "/bogus")
    }

    @Test
    fun `S4 syntactically broken yaml still fails closed at the lenient load, before any import`() {
        val broken = "work_item_schemas: [\ninvalid yaml: :\n  - broken"

        val ex = assertFailsWith<IllegalArgumentException> { startup("syntax", broken) }

        assertTrue(ex.message?.contains("config.yaml") == true, "the message names the config file: ${ex.message}")
        assertFalse(Files.exists(dir.resolve("syntax").resolve("ready")), "no readiness marker")
    }

    // ---------------------------------------------------------------------------------------- S4 (root-targeted, composition level)

    private fun importFails(
        agent: Path,
        text: String
    ): String {
        writeConfig(agent, text)
        val composition = compose(agent)
        val failure = runCatching { runBlocking { composition.dataStepRunner.run() } }.exceptionOrNull()
        assertNotNull(failure, "the data steps must fail for this file")
        return generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }

    @Test
    fun `S4 a root-targeted file with resources is rejected at the resources path and stores nothing`() {
        seedRoot(dogfoodRoot)
        val withResources = "project:\n  rootId: \"$dogfoodRoot\"\nresources:\n  db-cred:\n    description: \"x\"\n" + base

        val message = importFails(agent("a"), withResources)

        assertTrue("/resources" in message, message)
        assertEquals(emptyList(), versionRows())

        // Control: the same root-targeted file without the registry boots.
        boot(agent("b"), "project:\n  rootId: \"$dogfoodRoot\"\n" + base)
        assertEquals(1, rootRows().size)
    }

    @Test
    fun `S4 a root-targeted file combining note_schemas with work_item_schemas is rejected`() {
        seedRoot(dogfoodRoot)
        val both = "project:\n  rootId: \"$dogfoodRoot\"\nwork_item_schemas: {}\nnote_schemas: {}\n"

        val message = importFails(agent("a"), both)

        assertTrue("/note_schemas" in message || "/work_item_schemas" in message, message)
        assertEquals(emptyList(), versionRows())
    }

    @Test
    fun `S4 a project rootId naming a depth-1 item is rejected and stores nothing`() {
        val root = seedRoot(dogfoodRoot)
        val child =
            runBlocking {
                db.repositoryProvider().workItemRepository().create(
                    WorkItem(title = "child", type = "feature-task", parentId = root.id, rootId = root.id, depth = 1, role = Role.QUEUE)
                )
            }

        val message = importFails(agent("a"), "project:\n  rootId: \"${child.id}\"\n" + base)

        assertTrue(child.id.toString() in message, message)
        assertEquals(emptyList(), versionRows())
    }

    // ------------------------------------------------------------------------------------------------ S7

    @Test
    fun `S7 a boot without any file imports nothing and the runtime has no server layer`() {
        val composition = boot(agent("a"), null)

        assertEquals(emptyList(), versionRows())
        assertNull(runBlocking { composition.serverConfig.layer() })
        assertNull(serverHeadVersion())
    }

    @Test
    fun `S7 a later boot without a file never resets the stored head`() {
        boot(agent("a"), base)
        val second = boot(agent("b"), null)

        assertEquals(1, serverRows().size, "no row appended, none removed")
        assertEquals(1, serverHeadVersion())
        assertEquals(configFingerprint(base), serverFingerprint(second), "the earlier head stands")
    }

    @Test
    fun `S7 a later boot with an empty file never resets the stored head`() {
        boot(agent("a"), base)
        val second = boot(agent("b"), "")

        assertEquals(1, serverRows().size)
        assertEquals(configFingerprint(base), serverFingerprint(second))
    }

    @Test
    fun `S7 a later boot with a comment-only file never resets the stored head`() {
        boot(agent("a"), base)
        val second = boot(agent("b"), "# nothing configured here\n")

        assertEquals(1, serverRows().size)
        assertEquals(configFingerprint(base), serverFingerprint(second))
    }

    @Test
    fun `S7 an empty first file imports nothing and boots`() {
        val composition = boot(agent("a"), "")

        assertEquals(emptyList(), versionRows())
        assertNull(runBlocking { composition.serverConfig.layer() })
    }

    // ------------------------------------------------------------------------------------------------ S8

    @Test
    fun `S8 two processes with different files alternate versions and the last boot wins on the shared volume`() {
        val processA = boot(agent("a"), base)
        assertEquals(configFingerprint(base), serverFingerprint(processA))

        val processB = boot(agent("b"), other)
        assertEquals(2, serverHeadVersion(), "B appends version 2 instead of being rejected as superseded")
        assertEquals(configFingerprint(other), serverFingerprint(processB))
        assertEquals(
            configFingerprint(other),
            serverFingerprint(processA),
            "process A, already running, follows the head to B's file without a restart"
        )

        val processA2 = boot(agent("a2"), base)
        assertEquals(3, serverHeadVersion(), "A booting again appends version 3, not a no-op")
        assertEquals(configFingerprint(base), serverFingerprint(processA2))
        assertEquals(configFingerprint(base), serverFingerprint(processB), "process B follows the head back to A's file")

        val rows = serverRows()
        assertEquals(listOf(1, 2, 3), rows.map { it.version })
        assertEquals(listOf("file-import", "file-import", "file-import"), rows.map { it.source })
        assertEquals(listOf(configFingerprint(base), configFingerprint(other), configFingerprint(base)), rows.map { it.fingerprint })
    }

    @Test
    fun `S8 the resolver of a running process answers the newest head's fingerprint`() {
        val processA = boot(agent("a"), base)
        boot(agent("b"), other)

        val resolved =
            runBlocking {
                processA.toolContext.configResolver
                    .layered(null)
                    .global
                    .fingerprint()
            }

        assertEquals(configFingerprint(other), resolved)
    }

    // ------------------------------------------------------------------------------------------------ S9

    private fun appendServerVersion(yaml: String) {
        val parsed = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false)
        val rawRoot = assertIs<ConfigDocumentParser.Outcome.Parsed>(parsed).rawRoot
        val store = db.repositoryProvider().configStore()
        val outcome =
            runBlocking {
                store.append(
                    scope = ConfigScope.SERVER,
                    rootId = null,
                    append =
                        ConfigAppend(
                            yaml = yaml,
                            fingerprint = store.computeFingerprint(yaml),
                            canonicalJson = CanonicalConfigJson.encode(rawRoot),
                            modelVersion = CanonicalConfigJson.MODEL_VERSION,
                            source = ConfigVersionSource.FILE_IMPORT,
                            principalId = null,
                            reqId = null,
                            warnings = emptyList()
                        ),
                    now = Instant.now()
                )
            }
        assertIs<ConfigAppendOutcome.Applied>(outcome)
    }

    @Test
    fun `S9 editing the file after boot changes nothing the runtime serves`() {
        val agent = agent("a")
        val composition = boot(agent, base)
        val before = serverFingerprint(composition)

        writeConfig(agent, other)

        assertEquals(configFingerprint(base), before)
        assertEquals(before, serverFingerprint(composition), "the file is read at boot only")
        assertEquals(
            before,
            runBlocking {
                composition.toolContext.configResolver
                    .layered(null)
                    .global
                    .fingerprint()
            }
        )
        assertEquals(1, serverRows().size, "and nothing was appended")
    }

    @Test
    fun `S9 a server version appended by another process is served on the next read`() {
        val composition = boot(agent("a"), base)
        val v2 = "traits:\n  v2-only:\n    notes: []\n" + base
        val service = strictConfigService(composition)
        val root = seedRoot()
        val usesV2Trait = "work_item_schemas:\n  x:\n    default_traits: [v2-only]\n    notes: []\n"

        val before = runBlocking { service.push(root.id, usesV2Trait) }
        assertIs<ConfigPushResult.Invalid>(before, "control: the trait is unknown to the stored server head: $before")

        appendServerVersion(v2)

        assertEquals(2, serverHeadVersion())
        assertEquals(configFingerprint(v2), serverFingerprint(composition))
        assertEquals(
            configFingerprint(v2),
            runBlocking {
                composition.toolContext.configResolver
                    .layered(null)
                    .global
                    .fingerprint()
            },
            "the resolver follows the head"
        )
        val after = runBlocking { service.push(root.id, usesV2Trait) }
        assertIs<ConfigPushResult.Success>(after, "push validation reads the new server layer: $after")
    }

    @Test
    fun `S9 the config routes follow the head - ETag, effective globalFingerprint and the empty case`() {
        val empty = boot(agent("none"), null)
        val root = seedRoot()
        val emptyService = strictConfigService(empty)

        testApplication {
            application { restApp(empty, emptyService) }
            val noConfig = client.get("/api/v1/config") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals("\"cfg-no-config\"", noConfig.headers[HttpHeaders.ETag], "no head means the no-config ETag")
        }

        val composition = boot(agent("a"), base)
        val service = strictConfigService(composition)
        testApplication {
            application { restApp(composition, service) }
            val v1 = client.get("/api/v1/config") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals("\"cfg-${configFingerprint(base)}\"", v1.headers[HttpHeaders.ETag])
        }

        val v2 = other + "# appended by another process\n"
        appendServerVersion(v2)

        testApplication {
            application { restApp(composition, service) }
            val etag = client.get("/api/v1/config") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals("\"cfg-${configFingerprint(v2)}\"", etag.headers[HttpHeaders.ETag], "the new head is served without a restart")

            val effective =
                client.get(
                    "/api/v1/roots/${root.id}/config/effective"
                ) { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, effective.status)
            val dto = McpJson.decodeFromString<EffectiveConfigDto>(effective.bodyAsText())
            assertEquals(configFingerprint(v2), dto.globalFingerprint)
        }
    }

    // ------------------------------------------------------------------------------------------------ S12

    @Test
    fun `S12 the first boot on a 3x volume reports the file's fingerprint and the global source`() {
        val volume = File(dir.toFile(), "volume").also { it.mkdirs() }
        val golden = GoldenV17.copyTo(File(volume, "golden.sqlite"))
        val url = UpgradeHarness.urlFor(golden)
        UpgradeHarness.migrate(url)
        val agent = agent("a")
        writeConfig(agent, legacy3x)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") agent.toString() else null }
        val manager = DatabaseManager(appConfig = appConfig)
        try {
            assertTrue(manager.initialize(url), "the upgraded 3.x volume must open")
            val composition =
                ServerComposition(appConfig = appConfig, databaseManager = manager, shutdownCoordinator = ShutdownCoordinator()).build()
            runBlocking { composition.dataStepRunner.run() }

            val result =
                runBlocking {
                    QueryItemsTool().execute(
                        buildJsonObject {
                            put("operation", JsonPrimitive("schema"))
                            put("type", JsonPrimitive("feature-task"))
                        },
                        composition.toolContext
                    )
                } as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "schema lookup must succeed: $result")
            val data = result["data"]!!.jsonObject
            assertEquals(configFingerprint(legacy3x), data["configFingerprint"]!!.jsonPrimitive.content, "the value 3.x reported")
            assertEquals("global", data["configSource"]!!.jsonPrimitive.content)
        } finally {
            manager.shutdown()
        }
    }

    // ------------------------------------------------------------------------------------------------ probes

    @Test
    fun `probe CRLF - the stored yaml is the raw bytes and the fingerprint is the normalized one`() {
        val crlf = base.replace("\n", "\r\n")
        boot(agent("a"), crlf)

        val v = serverRows().single()
        assertEquals(crlf, v.yaml, "stored byte for byte")
        assertEquals(configFingerprint(crlf), v.fingerprint)
        assertEquals(configFingerprint(base), v.fingerprint, "CRLF is normalized to LF before hashing")
        assertEquals(baseCanonical, v.canonical, "the canonical form does not depend on line endings")

        boot(agent("b"), base)
        assertEquals(1, serverRows().size, "the LF form of the same document is an identical re-import")
        assertEquals(crlf, serverRows().single().yaml, "the first writer's text is kept")
    }

    @Test
    fun `probe BOM - a byte order mark at the start of the file is kept in yaml and does not block the import`() {
        val withBom = "\uFEFF" + base
        boot(agent("a"), withBom)

        val v = serverRows().single()
        assertEquals(withBom, v.yaml, "the BOM stays in the stored text")
        assertEquals(configFingerprint(withBom), v.fingerprint)
        assertEquals(baseCanonical, v.canonical)
    }

    @Test
    fun `probe actor_authentication - a server file keeps it in yaml and out of the canonical form`() {
        val text = base + noopActorAuth
        boot(agent("a"), text)

        val v = serverRows().single()
        assertEquals(text, v.yaml, "audit text is verbatim")
        assertEquals(baseCanonical, v.canonical, "actor_authentication is stripped from the canonical form (D2)")
        assertEquals(emptyList(), v.warnings)
    }

    @Test
    fun `probe actor_authentication - a root-targeted file boots and its canonical form lacks the key`() {
        seedRoot(dogfoodRoot)
        val text = "project:\n  rootId: \"$dogfoodRoot\"\n" + base + noopActorAuth
        boot(agent("a"), text)

        val v = rootRows().single()
        assertEquals(text, v.yaml)
        val canonical = assertNotNull(v.canonical)
        assertFalse("actor_authentication" in canonical, "stripped before canonicalization: $canonical")
        assertTrue("work_item_schemas" in canonical)
    }

    // ------------------------------------------------------------------------------------------------ DocumentSchemaCatalog

    private fun layerOf(
        yaml: String,
        fingerprint: String?
    ): ConfigLayer {
        val parsed = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
        return ConfigLayer(assertIs<ConfigDocumentParser.Outcome.Parsed>(parsed).document, fingerprint, ConfigSource.GLOBAL)
    }

    @Test
    fun `catalog - no layer is an empty view with no fingerprint`() {
        val catalog = DocumentSchemaCatalog(null)

        assertEquals(emptyMap(), catalog.getAllSchemas())
        assertEquals(emptyMap(), catalog.getAllTraits())
        assertNull(catalog.getConfigFingerprint())
        assertEquals(emptyList(), catalog.getLoadWarnings())
        assertEquals(emptyList(), catalog.getTraitResources("anything"))
        assertEquals(emptyMap(), catalog.getTraitDispatch("anything"))
    }

    @Test
    fun `catalog - a layer is viewed through its document and its fingerprint`() {
        val yaml =
            base + "traits:\n  needs-db:\n    resources: [db-cred]\n  delegated:\n    dispatch:\n" +
                "      work: { agent: some-agent, effort: high }\n"
        val layer = layerOf(yaml, "fp-for-catalog")

        val catalog = DocumentSchemaCatalog(layer)

        assertEquals(setOf("feature-task"), catalog.getAllSchemas().keys)
        assertEquals(layer.document.workItemSchemas, catalog.getAllSchemas())
        assertEquals(setOf("needs-db", "delegated"), catalog.getAllTraits().keys)
        assertEquals("fp-for-catalog", catalog.getConfigFingerprint())
        assertEquals(layer.document.warnings, catalog.getLoadWarnings())
        assertEquals(layer.document.traitResources["needs-db"], catalog.getTraitResources("needs-db"))
        assertTrue(catalog.getTraitResources("needs-db").isNotEmpty(), "fixture: the trait declares a resource")
        assertEquals(emptyList(), catalog.getTraitResources("delegated"), "a trait without resources has none")
        assertEquals(setOf(Role.WORK), catalog.getTraitDispatch("delegated").keys)
        assertEquals(emptyMap(), catalog.getTraitDispatch("needs-db"))
        assertEquals(emptyList(), catalog.getTraitResources("undeclared-trait"))
    }

    @Test
    fun `catalog - a layer without a fingerprint reports none`() {
        assertNull(DocumentSchemaCatalog(layerOf(base, null)).getConfigFingerprint())
    }

    private fun strictConfigService(composition: CompositionResult) =
        ConfigService(
            db.repositoryProvider(),
            YamlConfigDocumentParser,
            db.db.unitOfWork(),
            ConfigValidator(NetworkntConfigStructuralValidator()),
            serverConfig = composition.serverConfig
        )

    private fun Application.restApp(
        composition: CompositionResult,
        service: ConfigService
    ) {
        configureTestApp(makeWriteAuthConfig()) {
            serviceRoutes(db.repositoryProvider(), "boot-import", "test", false, service)
            projectConfigRoutes(db.repositoryProvider(), service)
            configRoutes(composition.serverConfig)
            effectiveConfigRoutes(db.repositoryProvider(), composition.toolContext.configResolver, composition.serverConfig)
        }
    }
}

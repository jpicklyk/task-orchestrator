package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigTool
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureProjectConfigTestApp
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope` / `task-scope-addendum` notes on item
 * `79cd4f0c` (A1, stage A1c) -- S3 (two `enters: true` in one phase, fatal at global load AND
 * rejected on push) and S4 (F2/F3/F4 fatal like S3; W1/W2/W3/W4/W6 non-fatal warnings surfaced on
 * push, with hook-local sections producing none).
 *
 * Harness: the GLOBAL half mirrors [GlobalConfigStartupFailClosedTest] (a real
 * `.taskorchestrator/config.yaml` under a temp `AGENT_CONFIG_DIR`, loaded via a real
 * [ServerComposition.build]). The PUSH half mirrors [ManageProjectConfigToolSchemaWarningsTest]
 * (MCP `manage_project_config` push via a real SQLite-backed [ToolExecutionContext]) and
 * `ProjectConfigRoutesTest` (REST `PUT /api/v1/roots/{rootId}/config` via the real
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureProjectConfigTestApp] /
 * `projectConfigRoutes`) -- never a hand-built replica of either surface.
 */
class SeatConfigLoadFailClosedTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    // ─── Shared fixtures ───────────────────────────────────────────────────

    private fun writeGlobalConfig(
        dir: Path,
        content: String,
    ): Path {
        val configDir = dir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val file = configDir.resolve("config.yaml")
        Files.writeString(file, content)
        return file
    }

    private fun agentConfigDirAppConfig(dir: Path): AppConfig {
        val env = mapOf("AGENT_CONFIG_DIR" to dir.toString())
        return AppConfig.fromEnv { key -> env[key] }
    }

    private fun buildContext(): Pair<ToolExecutionContext, java.util.UUID> {
        val repo = db.repositoryProvider()
        val rootId =
            runBlocking {
                (repo.workItemRepository().create(WorkItem(title = "Push Root", type = "project")) as Result.Success).data.id
            }
        return ToolExecutionContext(repo) to rootId
    }

    private fun push(
        context: ToolExecutionContext,
        rootId: String,
        yaml: String,
    ): JsonElement =
        runBlocking {
            ManageProjectConfigTool(YamlConfigDocumentParser).execute(
                JsonObject(
                    mapOf(
                        "operation" to JsonPrimitive("push"),
                        "rootId" to JsonPrimitive(rootId),
                        "configYaml" to JsonPrimitive(yaml),
                    ),
                ),
                context,
            )
        }

    private fun get(
        context: ToolExecutionContext,
        rootId: String,
    ): JsonElement =
        runBlocking {
            ManageProjectConfigTool(YamlConfigDocumentParser).execute(
                JsonObject(
                    mapOf(
                        "operation" to JsonPrimitive("get"),
                        "rootId" to JsonPrimitive(rootId),
                    ),
                ),
                context,
            )
        }

    private fun isSuccess(result: JsonElement): Boolean = (result as JsonObject)["success"]!!.jsonPrimitive.boolean

    private fun errorOf(result: JsonElement): JsonObject = (result as JsonObject)["error"] as JsonObject

    private fun dataOf(result: JsonElement): JsonObject = (result as JsonObject)["data"] as JsonObject

    // ─── S3 -- two enters:true in one phase ─────────────────────────────────

    private val twoEntersOneList =
        """
        work_item_schemas:
          dup-enters-list:
            seats:
              - { name: a, phase: work, enters: true }
              - { name: b, phase: work, enters: true }
            notes:
              - key: n1
                role: work
                required: true
                seat: a
        """.trimIndent()

    private val twoEntersSchemaAndTrait =
        """
        work_item_schemas:
          dup-enters-trait:
            default_traits: [t1]
            seats:
              - { name: a, phase: work, enters: true }
            notes:
              - key: n1
                role: work
                required: true
                seat: a
        traits:
          t1:
            seats:
              - { name: b, phase: work, enters: true }
            notes:
              - key: n2
                role: work
                required: true
                seat: b
        """.trimIndent()

    @Test
    fun `S3 two enters true seats in one seats list fails global startup naming the config path and phase work`(
        @TempDir tempDir: Path,
    ) {
        val configPath = writeGlobalConfig(tempDir, twoEntersOneList)

        val ex =
            assertFailsWith<IllegalArgumentException> {
                ServerComposition(agentConfigDirAppConfig(tempDir), db.databaseManager, ShutdownCoordinator()).build()
            }
        assertTrue(ex.message?.contains(configPath.toString()) == true, "message must name the config path: ${ex.message}")
        assertTrue(ex.message?.contains("work") == true, "message must name phase 'work': ${ex.message}")
    }

    @Test
    fun `S3 two enters true from a schema seat and a same-document default_trait seat fails global startup naming phase work`(
        @TempDir tempDir: Path,
    ) {
        val configPath = writeGlobalConfig(tempDir, twoEntersSchemaAndTrait)

        val ex =
            assertFailsWith<IllegalArgumentException> {
                ServerComposition(agentConfigDirAppConfig(tempDir), db.databaseManager, ShutdownCoordinator()).build()
            }
        assertTrue(ex.message?.contains(configPath.toString()) == true, "message must name the config path: ${ex.message}")
        assertTrue(ex.message?.contains("work") == true, "message must name phase 'work': ${ex.message}")
    }

    @Test
    fun `S3 push of two enters true seats in one list is rejected as VALIDATION_ERROR via MCP and stores nothing`() {
        val (context, rootId) = buildContext()

        val result = push(context, rootId.toString(), twoEntersOneList)

        assertTrue(!isSuccess(result), "push must be rejected: $result")
        assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(result)["code"]!!.jsonPrimitive.content)
        val message = errorOf(result)["message"]!!.jsonPrimitive.content
        assertTrue(message.contains("work"), "N4: the rejection message must name phase 'work': $message")

        val getResult = get(context, rootId.toString())
        assertTrue(!isSuccess(getResult))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(getResult)["code"]!!.jsonPrimitive.content, "nothing must be stored")
    }

    @Test
    fun `S3 push of two enters true seats in one list is rejected as 422 parse_error via REST and stores nothing`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root =
                runBlocking {
                    (repo.workItemRepository().create(WorkItem(title = "REST Push Root", depth = 0)) as Result.Success).data
                }
            application { configureProjectConfigTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.parse("application/yaml"))
                    setBody(twoEntersOneList)
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertTrue(response.bodyAsText().contains("parse_error"), "body: ${response.bodyAsText()}")

            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "a two-enters:true config must never be stored")
        }

    // ─── S4 -- F2 dup seat name, F3 reserved 'unowned' name, F4 after-cycle: fatal like S3 ──

    private val dupSeatName =
        """
        work_item_schemas:
          dup-seat-name:
            seats:
              - { name: a, phase: work }
              - { name: a, phase: review }
            notes:
              - key: n1
                role: work
                required: true
                seat: a
        """.trimIndent()

    private val reservedUnownedName =
        """
        work_item_schemas:
          reserved-seat-name:
            seats:
              - { name: unowned, phase: work }
            notes:
              - key: n1
                role: work
                required: true
                seat: unowned
        """.trimIndent()

    private val afterCycle =
        """
        work_item_schemas:
          after-cycle:
            seats:
              - { name: a, phase: work, after: [b] }
              - { name: b, phase: work, after: [a] }
            notes:
              - key: n1
                role: work
                required: true
                seat: a
        """.trimIndent()

    @Test
    fun `S4 F2 duplicate seat name fails global startup naming the config path`(
        @TempDir tempDir: Path,
    ) {
        val configPath = writeGlobalConfig(tempDir, dupSeatName)

        val ex =
            assertFailsWith<IllegalArgumentException> {
                ServerComposition(agentConfigDirAppConfig(tempDir), db.databaseManager, ShutdownCoordinator()).build()
            }
        assertTrue(ex.message?.contains(configPath.toString()) == true, "message must name the config path: ${ex.message}")
    }

    @Test
    fun `S4 F2 duplicate seat name push is rejected via MCP (VALIDATION_ERROR) and REST (422 parse_error), storing nothing`(): Unit =
        testApplication {
            val (context, mcpRootId) = buildContext()
            val mcpResult = push(context, mcpRootId.toString(), dupSeatName)
            assertTrue(!isSuccess(mcpResult), "MCP push must be rejected: $mcpResult")
            assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(mcpResult)["code"]!!.jsonPrimitive.content)

            val repo = db.repositoryProvider()
            val root =
                runBlocking {
                    (repo.workItemRepository().create(WorkItem(title = "F2 REST Root", depth = 0)) as Result.Success).data
                }
            application { configureProjectConfigTestApp(repo) }
            val restResponse =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.parse("application/yaml"))
                    setBody(dupSeatName)
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, restResponse.status)
            assertTrue(restResponse.bodyAsText().contains("parse_error"), "body: ${restResponse.bodyAsText()}")
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "a duplicate seat name config must never be stored")
        }

    @Test
    fun `S4 F3 a seat named unowned fails global startup naming the config path`(
        @TempDir tempDir: Path,
    ) {
        val configPath = writeGlobalConfig(tempDir, reservedUnownedName)

        val ex =
            assertFailsWith<IllegalArgumentException> {
                ServerComposition(agentConfigDirAppConfig(tempDir), db.databaseManager, ShutdownCoordinator()).build()
            }
        assertTrue(ex.message?.contains(configPath.toString()) == true, "message must name the config path: ${ex.message}")
        assertTrue(ex.message?.contains("unowned") == true, "message must name the reserved seat name: ${ex.message}")
    }

    @Test
    fun `S4 F3 a seat named unowned push is rejected via MCP (VALIDATION_ERROR) and REST (422 parse_error), storing nothing`(): Unit =
        testApplication {
            val (context, mcpRootId) = buildContext()
            val mcpResult = push(context, mcpRootId.toString(), reservedUnownedName)
            assertTrue(!isSuccess(mcpResult), "MCP push must be rejected: $mcpResult")
            assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(mcpResult)["code"]!!.jsonPrimitive.content)

            val repo = db.repositoryProvider()
            val root =
                runBlocking {
                    (repo.workItemRepository().create(WorkItem(title = "F3 REST Root", depth = 0)) as Result.Success).data
                }
            application { configureProjectConfigTestApp(repo) }
            val restResponse =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.parse("application/yaml"))
                    setBody(reservedUnownedName)
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, restResponse.status)
            assertTrue(restResponse.bodyAsText().contains("parse_error"), "body: ${restResponse.bodyAsText()}")
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "a config with a seat named 'unowned' must never be stored")
        }

    @Test
    fun `S4 F4 an after cycle fails global startup naming the config path`(
        @TempDir tempDir: Path,
    ) {
        val configPath = writeGlobalConfig(tempDir, afterCycle)

        val ex =
            assertFailsWith<IllegalArgumentException> {
                ServerComposition(agentConfigDirAppConfig(tempDir), db.databaseManager, ShutdownCoordinator()).build()
            }
        assertTrue(ex.message?.contains(configPath.toString()) == true, "message must name the config path: ${ex.message}")
    }

    @Test
    fun `S4 F4 an after cycle push is rejected via MCP (VALIDATION_ERROR) and REST (422 parse_error), storing nothing`(): Unit =
        testApplication {
            val (context, mcpRootId) = buildContext()
            val mcpResult = push(context, mcpRootId.toString(), afterCycle)
            assertTrue(!isSuccess(mcpResult), "MCP push must be rejected: $mcpResult")
            assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(mcpResult)["code"]!!.jsonPrimitive.content)

            val repo = db.repositoryProvider()
            val root =
                runBlocking {
                    (repo.workItemRepository().create(WorkItem(title = "F4 REST Root", depth = 0)) as Result.Success).data
                }
            application { configureProjectConfigTestApp(repo) }
            val restResponse =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.parse("application/yaml"))
                    setBody(afterCycle)
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, restResponse.status)
            assertTrue(restResponse.bodyAsText().contains("parse_error"), "body: ${restResponse.bodyAsText()}")
            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertTrue((persisted as Result.Success).data == null, "an after-cycle config must never be stored")
        }

    // ─── S4 -- W1/W2/W3/W4/W6 warnings: load succeeds, push surfaces schemaWarnings ──

    private val warningsYaml =
        """
        work_item_schemas:
          warn-type:
            baz: nonsense
            seats:
              - { name: ok, phase: work }
              - { name: ok2, phase: work, zzseat: 1 }
            notes:
              - key: n1
                role: work
                required: true
                foo: unexpected
        traits:
          warn-trait:
            qux: nonsense
            dispatch:
              work:
                seats:
                  ok: { colour: red }
        bar: nonsense
        project:
          name: something
        retrospective:
          enabled: true
        actor_attribution:
          mode: x
        orchestration:
          mode: workflow
        """.trimIndent()

    @Test
    fun `S4 W1-W6 unknown-key warnings do not fail global startup`(
        @TempDir tempDir: Path,
    ) {
        writeGlobalConfig(tempDir, warningsYaml)

        // Must NOT throw -- warnings are non-fatal.
        val composition = ServerComposition(agentConfigDirAppConfig(tempDir), db.databaseManager, ShutdownCoordinator()).build()
        assertTrue(composition.noteSchemaService.getConfigFingerprint() != null, "the (warning-laden but valid) config must still load")
    }

    @Test
    fun `S4 W1-W6 push succeeds with schemaWarnings naming each offending key, and hook-local sections warn nothing`() {
        val (context, rootId) = buildContext()

        val result = push(context, rootId.toString(), warningsYaml)

        assertTrue(isSuccess(result), "a soft schema warning must never reject the push: $result")
        val schemaWarnings = dataOf(result)["schemaWarnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(schemaWarnings.size >= 5, "expected at least 5 warnings (W1,W2,W3x2,W4,W6): $schemaWarnings")

        val joined = schemaWarnings.joinToString("\n")
        assertTrue(joined.contains("foo"), "W1 unknown note-entry key 'foo' must be named: $schemaWarnings")
        assertTrue(joined.contains("bar"), "W2 unknown top-level section 'bar' must be named: $schemaWarnings")
        assertTrue(joined.contains("baz"), "W3 unknown schema-level key 'baz' must be named: $schemaWarnings")
        assertTrue(joined.contains("qux"), "W3 unknown trait-level key 'qux' must be named: $schemaWarnings")
        assertTrue(joined.contains("zzseat"), "W4 unknown seat-entry key 'zzseat' must be named: $schemaWarnings")
        assertTrue(joined.contains("colour"), "W6 unknown dispatch seat override field 'colour' must be named: $schemaWarnings")

        assertTrue(
            schemaWarnings.none {
                it.contains("project") || it.contains("retrospective") || it.contains("actor_attribution") || it.contains("orchestration")
            },
            "hook-local sections must produce NO warning: $schemaWarnings",
        )
    }

    // N4 -- push W4: a seat entry with a missing name warns at push time too (not just at global load).
    private val warnMissingSeatNameYaml =
        """
        work_item_schemas:
          warn-missing-seat-name:
            seats:
              - { phase: work }
              - { name: ok, phase: work }
            notes:
              - key: n1
                role: work
                required: true
                seat: ok
        """.trimIndent()

    @Test
    fun `N4 W4 push of a seat entry with a missing name succeeds with a schemaWarning naming the offending field`() {
        val (context, rootId) = buildContext()

        val result = push(context, rootId.toString(), warnMissingSeatNameYaml)

        assertTrue(isSuccess(result), "a missing seat name is a soft warning, never a push rejection: $result")
        val schemaWarnings = dataOf(result)["schemaWarnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            schemaWarnings.any { it.contains("name") },
            "N4: push must surface a schemaWarning naming the offending field 'name': $schemaWarnings",
        )
    }

    @Test
    fun `S4 an all-honored config omits schemaWarnings entirely`() {
        val (context, rootId) = buildContext()
        val cleanYaml =
            """
            work_item_schemas:
              clean-type:
                notes:
                  - key: spec
                    role: queue
                    required: true
            """.trimIndent()

        val result = push(context, rootId.toString(), cleanYaml)

        assertTrue(isSuccess(result))
        assertNull(dataOf(result)["schemaWarnings"], "schemaWarnings must be omitted entirely when empty, not an empty array")
    }
}

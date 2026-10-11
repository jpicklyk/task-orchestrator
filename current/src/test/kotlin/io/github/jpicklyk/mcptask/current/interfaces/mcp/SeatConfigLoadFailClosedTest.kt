package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
 * [ServerComposition.build]). The PUSH half pushes through the `config` tool (a real SQLite-backed
 * [ToolExecutionContext]); a rejected push is a `config_invalid` element error (the REST route halves of Q22 and earlier
 * moved to `POST /api/v1/ops/config`, which the production parity scenarios cover) -- never a hand-built replica.
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
                (repo.workItemRepository().create(WorkItem(title = "Push Root", type = "project"))!!).id
            }
        return ToolExecutionContext(repo, unitOfWork = db.unitOfWork()) to rootId
    }

    private fun configCall(op: JsonObject): JsonObject = buildJsonObject { put("ops", buildJsonArray { add(op) }) }

    private fun push(
        context: ToolExecutionContext,
        rootId: String,
        yaml: String,
    ): JsonObject =
        runBlocking {
            context.callTool(
                "config",
                configCall(
                    buildJsonObject {
                        put("op", "push")
                        put("rootId", rootId)
                        put("configYaml", yaml)
                    },
                ),
            )
        }

    private fun get(
        context: ToolExecutionContext,
        rootId: String,
    ): JsonObject =
        runBlocking {
            context.callTool(
                "config",
                configCall(
                    buildJsonObject {
                        put("op", "get")
                        put("rootId", rootId)
                    },
                ),
            )
        }

    private fun isSuccess(result: JsonObject): Boolean = result.results()[0]["ok"]!!.jsonPrimitive.content == "true"

    private fun errorOf(result: JsonObject): JsonObject = result.errorAt(0)

    /** The `detail.errors` violations of a `config_invalid` element error. */
    private fun violationsOf(result: JsonObject): List<JsonObject> =
        errorOf(result)["detail"]!!.jsonObject["errors"]!!.jsonArray.map { it.jsonObject }

    private fun dataOf(result: JsonObject): JsonObject = result.results()[0]

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
    fun `S3 push of two enters true seats in one list is rejected as config_invalid and stores nothing`() {
        val (context, rootId) = buildContext()

        val result = push(context, rootId.toString(), twoEntersOneList)

        assertTrue(!isSuccess(result), "push must be rejected: $result")
        assertEquals("config_invalid", errorOf(result)["code"]!!.jsonPrimitive.content)
        val reasons = violationsOf(result).joinToString("\n") { it["reason"]!!.jsonPrimitive.content }
        assertTrue(reasons.contains("work"), "N4: the rejection must name phase 'work': $reasons")

        val getResult = get(context, rootId.toString())
        assertTrue(!isSuccess(getResult))
        assertEquals("not_found", errorOf(getResult)["code"]!!.jsonPrimitive.content, "nothing must be stored")
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
    fun `S4 F2 duplicate seat name push is rejected as config_invalid and stores nothing`() {
        val (context, rootId) = buildContext()
        val result = push(context, rootId.toString(), dupSeatName)
        assertTrue(!isSuccess(result), "push must be rejected: $result")
        assertEquals("config_invalid", errorOf(result)["code"]!!.jsonPrimitive.content)
        val stored = get(context, rootId.toString())
        assertEquals("not_found", errorOf(stored)["code"]!!.jsonPrimitive.content, "a duplicate seat name config must never be stored")
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
    fun `S4 F3 a seat named unowned push is rejected as config_invalid and stores nothing`() {
        val (context, rootId) = buildContext()
        val result = push(context, rootId.toString(), reservedUnownedName)
        assertTrue(!isSuccess(result), "push must be rejected: $result")
        assertEquals("config_invalid", errorOf(result)["code"]!!.jsonPrimitive.content)
        val stored = get(context, rootId.toString())
        assertEquals(
            "not_found",
            errorOf(stored)["code"]!!.jsonPrimitive.content,
            "a config with a seat named 'unowned' must never be stored"
        )
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
    fun `S4 F4 an after cycle push is rejected as config_invalid and stores nothing`() {
        val (context, rootId) = buildContext()
        val result = push(context, rootId.toString(), afterCycle)
        assertTrue(!isSuccess(result), "push must be rejected: $result")
        assertEquals("config_invalid", errorOf(result)["code"]!!.jsonPrimitive.content)
        val stored = get(context, rootId.toString())
        assertEquals("not_found", errorOf(stored)["code"]!!.jsonPrimitive.content, "an after-cycle config must never be stored")
    }

    // --- S4 -- W1/W2/W3/W4/W6 warnings: load succeeds, push rejects them ---

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
        // Building never imports the file (the boot import is a data step); a lenient file load must not throw.
        assertNotNull(composition.serverConfig, "the (warning-laden but valid) config must still load")
    }

    @Test
    fun `S4 W1-W6 push is rejected with errors naming each offending key, and the 3x hook-local sections are errors too`() {
        val (context, rootId) = buildContext()

        val result = push(context, rootId.toString(), warningsYaml)

        assertTrue(!isSuccess(result), "former soft skips must reject the push: $result")
        val errors = violationsOf(result)
        val schemaWarnings = errors.map { it["reason"]!!.jsonPrimitive.content }
        assertTrue(schemaWarnings.size >= 5, "expected at least 5 errors (W1,W2,W3x2,W4,W6): $schemaWarnings")

        val joined = schemaWarnings.joinToString("\n")
        assertTrue(joined.contains("foo"), "W1 unknown note-entry key 'foo' must be named: $schemaWarnings")
        assertTrue(joined.contains("bar"), "W2 unknown top-level section 'bar' must be named: $schemaWarnings")
        assertTrue(joined.contains("baz"), "W3 unknown schema-level key 'baz' must be named: $schemaWarnings")
        assertTrue(joined.contains("qux"), "W3 unknown trait-level key 'qux' must be named: $schemaWarnings")
        assertTrue(joined.contains("zzseat"), "W4 unknown seat-entry key 'zzseat' must be named: $schemaWarnings")
        assertTrue(joined.contains("colour"), "W6 unknown dispatch seat override field 'colour' must be named: $schemaWarnings")

        val paths = errors.map { it["path"]!!.jsonPrimitive.content }
        for (section in listOf("/retrospective", "/actor_attribution", "/orchestration")) {
            assertTrue(section in paths, "the 3.x top-level section $section belongs under extensions and is an error now: $paths")
        }
        assertTrue("/project" !in paths && paths.none { it.startsWith("/project/") }, "project is a per-root section: $paths")
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
    fun `N4 W4 push of a seat entry with a missing name is rejected with an error naming the offending field`() {
        val (context, rootId) = buildContext()

        val result = push(context, rootId.toString(), warnMissingSeatNameYaml)

        assertTrue(!isSuccess(result), "a missing seat name is a former soft skip and rejects the push: $result")
        val errors = violationsOf(result)
        assertTrue(
            errors.any {
                it["path"]!!.jsonPrimitive.content == "/work_item_schemas/warn-missing-seat-name/seats/0" &&
                    it["reason"]!!.jsonPrimitive.content.contains("name")
            },
            "N4: push must name the seat entry and the offending field 'name': $errors",
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
        assertNull(dataOf(result)["warnings"], "warnings must be omitted entirely when empty, not an empty array")
    }
}

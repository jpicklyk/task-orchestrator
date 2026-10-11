package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for the MCP `manage_project_config` `push` operation as a
 * strict admission point, scenario S18.
 *
 * Oracle: task-scope build item 5 (manage_project_config -> VALIDATION_ERROR with the errors in `data`; the
 * `ignoredSections` wire field is removed, R15) and the harness evidence of [ManageProjectConfigToolTest]
 * (`error.code`, `data.errors[].path`). Execution goes through `tool.execute(params, context)`, which is the contract
 * the existing tool tests use.
 */
class ManageProjectConfigToolStrictTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var tool: ManageProjectConfigTool
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        tool = ManageProjectConfigTool(YamlConfigDocumentParser)
        context = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
    }

    private fun newRoot(title: String = "Project Root"): UUID =
        runBlocking {
            db
                .repositoryProvider()
                .workItemRepository()
                .create(WorkItem(title = title, type = "project"))
                .id
        }

    private fun call(vararg pairs: Pair<String, JsonElement>): JsonObject =
        runBlocking {
            tool.execute(JsonObject(mapOf(*pairs)), context) as JsonObject
        }

    private fun push(
        rootId: UUID,
        yaml: String
    ): JsonObject =
        call(
            "operation" to JsonPrimitive("push"),
            "rootId" to JsonPrimitive(rootId.toString()),
            "configYaml" to JsonPrimitive(yaml)
        )

    private fun get(rootId: UUID): JsonObject = call("operation" to JsonPrimitive("get"), "rootId" to JsonPrimitive(rootId.toString()))

    private fun JsonObject.success() = this["success"]!!.jsonPrimitive.boolean

    private fun JsonObject.errorCode() = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private fun JsonObject.errorPaths(): List<String> =
        this["data"]!!.jsonObject["errors"]!!.jsonArray.map {
            it.jsonObject["path"]!!.jsonPrimitive.content
        }

    @Test
    fun `S18 a push with a top-level plugin key is VALIDATION_ERROR listing the path in data errors`() {
        val root = newRoot()

        val result = push(root, "orchestration:\n  mode: workflow\n" + StrictConfigCases.VALID_ROOT_YAML)

        assertFalse(result.success(), "must be rejected: $result")
        assertEquals(ErrorCodes.VALIDATION_ERROR, result.errorCode())
        assertTrue("/orchestration" in result.errorPaths(), "errors: ${result.errorPaths()}")
    }

    @Test
    fun `S18 every error entry carries a path and a reason`() {
        val root = newRoot()

        val result = push(root, "schema_resolution: legacy\n" + StrictConfigCases.VALID_ROOT_YAML)

        val errors: JsonArray = result["data"]!!.jsonObject["errors"]!!.jsonArray
        assertTrue(errors.isNotEmpty())
        for (error in errors) {
            val entry = error.jsonObject
            assertTrue(entry["path"]!!.jsonPrimitive.content.startsWith("/"), "path is a JSON Pointer: $entry")
            assertTrue(entry["reason"]!!.jsonPrimitive.content.isNotBlank(), "reason is non-empty: $entry")
        }
    }

    @Test
    fun `S18 each defect is VALIDATION_ERROR at its pointer with nothing stored, and the repaired twin succeeds`() {
        val failures = mutableListOf<String>()
        for (case in StrictConfigCases.ROOT_CASES) {
            val root = newRoot("Root ${case.name}")

            val broken = push(root, case.yaml)

            if (broken.success()) {
                failures += "${case.name}: accepted"
            } else {
                if (broken.errorCode() != ErrorCodes.VALIDATION_ERROR) failures += "${case.name}: code ${broken.errorCode()}"
                val paths = broken.errorPaths()
                if (paths.none { it == case.errorPath || (case.withinEntry && it.startsWith(case.errorPath + "/")) }) {
                    failures +=
                        "${case.name}: paths $paths lack ${case.errorPath}"
                }
            }
            if (get(root).success()) failures += "${case.name}: a rejected push stored a config"

            val fixed = push(root, case.fixedYaml)
            if (!fixed.success()) failures += "${case.name}: repaired twin rejected: $fixed"
        }

        assertTrue(failures.isEmpty(), "failures:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `S18 no successful push response carries ignoredSections`() {
        val root = newRoot()

        val plain = push(root, StrictConfigCases.VALID_ROOT_YAML)
        val withExtensions = push(root, "extensions:\n  claude-code:\n    anything: true\n" + StrictConfigCases.VALID_ROOT_YAML)

        assertTrue(plain.success(), "$plain")
        assertTrue(withExtensions.success(), "extensions must be admitted: $withExtensions")
        assertNull(plain["data"]!!.jsonObject["ignoredSections"])
        assertNull(withExtensions["data"]!!.jsonObject["ignoredSections"])
    }

    @Test
    fun `S18 an advisory-only finding still succeeds and is reported as schemaWarnings`() {
        val root = newRoot()
        val yaml =
            "work_item_schemas:\n  feature-task:\n    seats:\n      - { name: impl, phase: work, enters: true }\n" +
                "    notes:\n      - key: spec\n        role: work\n        required: true\n"

        val result = push(root, yaml)

        assertTrue(result.success(), "$result")
        val warnings = result["data"]!!.jsonObject["schemaWarnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(warnings.any { it.contains("feature-task") && it.contains("spec") }, "warnings: $warnings")
    }
}

package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the MCP `manage_project_config` `push` operation's `data.schemaWarnings` field --
 * the additive array surfacing
 * [io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushResult.Success.schemaWarnings]
 * on the tool response, present only when non-empty.
 *
 * Independent test authorship per the `needs-test-author` trait: written against the item's
 * `test-plan` note oracles and [ManageProjectConfigTool]'s public `execute` contract, without
 * reading the implementer's own tests or notes. Mirrors [ManageProjectConfigToolTest]'s H2-backed
 * harness style; that file already covers the rest of the push/get contract, so this file focuses
 * solely on `schemaWarnings`.
 */
class ManageProjectConfigToolSchemaWarningsTest {
    private lateinit var tool: ManageProjectConfigTool
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var context: ToolExecutionContext
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            tool = ManageProjectConfigTool(YamlConfigDocumentParser)

            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository

            context = ToolExecutionContext(repositoryProvider)

            val root = (workItemRepository.create(WorkItem(title = "Project Root", type = "project")) as Result.Success).data
            rootId = root.id
        }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun isSuccess(result: JsonElement): Boolean = (result as JsonObject)["success"]!!.jsonPrimitive.boolean

    private fun dataOf(result: JsonElement): JsonObject = (result as JsonObject)["data"] as JsonObject

    private fun push(
        rootId: String,
        configYaml: String
    ): JsonElement =
        runBlocking {
            tool.execute(
                params(
                    "operation" to JsonPrimitive("push"),
                    "rootId" to JsonPrimitive(rootId),
                    "configYaml" to JsonPrimitive(configYaml)
                ),
                context
            )
        }

    // ──────────────────────────────────────────────
    // S12 — invalid role surfaces the warning text under data.schemaWarnings
    // ──────────────────────────────────────────────

    @Test
    fun `S12 push with an invalid role surfaces the warning text under data schemaWarnings`() {
        val yaml =
            """
            work_item_schemas:
              feature-task:
                notes:
                  - key: spec
                    role: not-a-role
            """.trimIndent()

        val result = push(rootId.toString(), yaml)

        assertTrue(isSuccess(result), "a soft schema warning must never reject the push")
        val schemaWarnings = dataOf(result)["schemaWarnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, schemaWarnings.size, "expected exactly one warning: $schemaWarnings")
        val warning = schemaWarnings.single()
        assertTrue(warning.contains("feature-task"), "warning should name the schema: $warning")
        assertTrue(warning.contains("spec"), "warning should name the entry's key: $warning")
        assertTrue(warning.contains("not-a-role"), "warning should name the bad role value: $warning")
    }

    // ──────────────────────────────────────────────
    // S1 — all-valid schema omits schemaWarnings entirely
    // ──────────────────────────────────────────────

    @Test
    fun `S1 push with an all-valid schema omits schemaWarnings entirely`() {
        val yaml =
            """
            work_item_schemas:
              feature-task:
                notes:
                  - key: spec
                    role: queue
                    required: true
            """.trimIndent()

        val result = push(rootId.toString(), yaml)

        assertTrue(isSuccess(result))
        assertNull(dataOf(result)["schemaWarnings"], "schemaWarnings must be omitted entirely when empty, not an empty array")
    }
}

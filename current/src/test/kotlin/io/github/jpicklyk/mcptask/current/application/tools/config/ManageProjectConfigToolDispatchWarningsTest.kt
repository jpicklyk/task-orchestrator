package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
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
import kotlin.test.assertTrue

/**
 * Exercises `manage_project_config` `push`'s `data.schemaWarnings` surface for the `dispatch:`
 * trait dimension (B1, S14, AC1) — a malformed dispatch phase key (`terminal`) must warn, not
 * reject the push. Mirrors [ManageProjectConfigToolSchemaWarningsTest]'s H2-backed harness; that
 * file already covers the general schemaWarnings contract (role-warnings, omit-when-empty), this
 * file is scoped to dispatch-specific warnings only.
 *
 * Independent test authorship per the `needs-test-author` trait: oracles come from the pinned
 * contract and AC1/P6 in the item's `task-scope` note — never from reading
 * ManageProjectConfigTool's source.
 */
class ManageProjectConfigToolDispatchWarningsTest {
    private lateinit var tool: ManageProjectConfigTool
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var context: ToolExecutionContext
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            tool = ManageProjectConfigTool()

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
    // S14 — dispatch phase key "terminal" is invalid: push still succeeds, warning names the
    // trait and "terminal" (AC1)
    // ──────────────────────────────────────────────

    @Test
    fun `S14 push with dispatch phase key terminal succeeds and warns naming the trait and terminal`() {
        val yaml =
            """
            traits:
              delegated:
                dispatch:
                  terminal:
                    agent: some-agent
            """.trimIndent()

        val result = push(rootId.toString(), yaml)

        assertTrue(isSuccess(result), "a soft schema warning must never reject the push")
        val schemaWarnings = dataOf(result)["schemaWarnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(schemaWarnings.isNotEmpty(), "expected at least one warning: $schemaWarnings")
        val warning = schemaWarnings.first { it.contains("delegated") && it.contains("terminal") }
        assertTrue(warning.contains("delegated"), "warning should name the trait: $warning")
        assertTrue(warning.contains("terminal"), "warning should name the invalid phase key: $warning")
    }
}

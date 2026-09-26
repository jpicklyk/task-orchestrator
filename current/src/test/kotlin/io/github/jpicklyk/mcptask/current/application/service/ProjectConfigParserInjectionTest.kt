package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigTool
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `f2c50e6d` —
 * scenarios S10 and S11 map to this file per the test-plan's file list. Oracle for S10:
 * `task-scope`'s Part B mapping in `parseAndValidateYaml`/`push` — a [ConfigDocumentParser.Outcome.Failed]
 * maps to `ProjectConfigPushResult.ParseError(detail)` carrying the SAME detail string, and a
 * [ConfigDocumentParser.Outcome.Parsed] maps to `ProjectConfigPushResult.Success` carrying the
 * parsed document's `warnings` as `schemaWarnings` and the raw root map's keys as `ignoredSections`
 * — confirmed verbatim in the declarations' `parseAndValidateYaml`/`push` excerpt. Oracle for S11:
 * the same Failed->ParseError mapping, surfaced through [ManageProjectConfigTool]'s push operation
 * response, mirroring [ManageProjectConfigToolTest]'s existing "push unparseable YAML returns error
 * containing parse detail" style but with an INJECTED fake parser rather than real YAML.
 *
 * NEW-SURFACE (both scenarios): the [ConfigDocumentParser] injection point on [ProjectConfigPushService]
 * and [ManageProjectConfigTool] is introduced by this feature. No source revert can yield behavioral
 * red; per the frozen plan, the substitute is "keep param, inline old parse" (an orchestrator-run
 * mutation).
 */
class ProjectConfigParserInjectionTest {
    private lateinit var databaseManager: DatabaseManager
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var repositoryProvider: RepositoryProvider
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)

            val provider = mockk<RepositoryProvider>(relaxed = true)
            every { provider.workItemRepository() } returns workItemRepository
            every { provider.projectConfigRepository() } returns projectConfigRepository
            repositoryProvider = provider

            val root = (workItemRepository.create(WorkItem(title = "PCPS Root", type = "project")) as Result.Success).data
            rootId = root.id
        }

    private class FakeConfigDocumentParser(
        private val outcome: ConfigDocumentParser.Outcome
    ) : ConfigDocumentParser {
        override fun parse(
            yaml: String,
            warnOnMissingSchemas: Boolean,
        ): ConfigDocumentParser.Outcome = outcome
    }

    // ──────────────────────────────────────────────
    // S10 — ProjectConfigPushService(p, fakeParser)
    // ──────────────────────────────────────────────

    @Test
    fun `S10 - a Failed outcome maps to ParseError carrying the same detail`(): Unit =
        runBlocking {
            val service =
                ProjectConfigPushService(repositoryProvider, FakeConfigDocumentParser(ConfigDocumentParser.Outcome.Failed("boom")))

            val result = service.push(rootId, "irrelevant: yaml")

            val parseError = assertIs<ProjectConfigPushResult.ParseError>(result)
            assertEquals("boom", parseError.detail)
        }

    @Test
    fun `S10 - a Parsed outcome maps to Success carrying the document warnings and the raw root's keys as ignoredSections`(): Unit =
        runBlocking {
            val doc = ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), warnings = listOf("w1"))
            val outcome = ConfigDocumentParser.Outcome.Parsed(doc, rawRoot = mapOf("x" to 1))
            val service = ProjectConfigPushService(repositoryProvider, FakeConfigDocumentParser(outcome))

            val result = service.push(rootId, "irrelevant: yaml")

            val success = assertIs<ProjectConfigPushResult.Success>(result)
            assertEquals(listOf("w1"), success.schemaWarnings)
            assertEquals(listOf("x"), success.ignoredSections)
        }

    // ──────────────────────────────────────────────
    // S11 — ManageProjectConfigTool(fake Failed("boom"))
    // ──────────────────────────────────────────────

    @Test
    fun `S11 - ManageProjectConfigTool push surfaces the injected parser's Failed detail in the error response`(): Unit =
        runBlocking {
            val tool = ManageProjectConfigTool(FakeConfigDocumentParser(ConfigDocumentParser.Outcome.Failed("boom")))
            val context = ToolExecutionContext(repositoryProvider)

            val params =
                JsonObject(
                    mapOf(
                        "operation" to JsonPrimitive("push"),
                        "rootId" to JsonPrimitive(rootId.toString()),
                        "configYaml" to JsonPrimitive("irrelevant: yaml"),
                    ),
                )

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "expected a failed push response: $result")
            val message = (result["error"] as JsonObject)["message"]!!.jsonPrimitive.content
            assertTrue(message.contains("boom"), "expected the injected parser's detail in the error message: $message")
        }
}

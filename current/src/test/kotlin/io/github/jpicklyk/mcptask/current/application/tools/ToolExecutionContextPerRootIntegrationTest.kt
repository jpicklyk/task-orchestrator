package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DB-backed integration test proving [ToolExecutionContext.resolveSchema] actually layers a
 * REAL [PerRootConfigService] (backed by a real `project_config` row via
 * [SQLiteProjectConfigRepository]) over the global [NoteSchemaService] mock — as opposed to the
 * sibling [ToolExecutionContextResolveSchemaTest], which mocks [PerRootConfigService] itself and
 * therefore never exercises the DB round-trip or [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser]
 * parsing path.
 *
 * Scenario: two project roots share one [ToolExecutionContext]. Root A has a per-root config
 * pushed for it; root B does not. An item under root A resolves the per-root schema; a sibling
 * item under root B (same type) resolves the global schema unchanged.
 */
class ToolExecutionContextPerRootIntegrationTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var perRootConfigService: PerRootConfigService
    private lateinit var noteSchemaService: NoteSchemaService
    private lateinit var context: ToolExecutionContext
    private lateinit var rootWithConfig: UUID
    private lateinit var rootWithoutConfig: UUID

    private val globalSchema =
        WorkItemSchema(
            type = "feature-task",
            notes = listOf(NoteSchemaEntry(key = "global-note", role = Role.WORK, required = false))
        )

    @BeforeEach
    fun setUp() =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            val repository = SQLiteProjectConfigRepository(databaseManager)
            perRootConfigService = PerRootConfigService(repository)

            // project_config.root_item_id has a real FK to work_items(id) — both roots must exist
            // as actual WorkItem rows, or upsert() below fails its FK check (caught internally by
            // suspendedTransaction and returned as a silent Result.Error, never an exception).
            val workItemRepository = SQLiteWorkItemRepository(databaseManager)
            val rootA = workItemRepository.create(WorkItem(title = "Root With Config"))
            val rootB = workItemRepository.create(WorkItem(title = "Root Without Config"))
            rootWithConfig = (rootA as Result.Success).data.id
            rootWithoutConfig = (rootB as Result.Success).data.id

            val perRootYaml =
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: per-root-note
                        role: work
                        required: false
                """.trimIndent()
            val upsertResult = repository.upsert(rootWithConfig, perRootYaml)
            assertTrue(
                upsertResult is Result.Success,
                "Test setup precondition: per-root config upsert must succeed, got $upsertResult"
            )
            // rootWithoutConfig intentionally has no pushed config row.

            noteSchemaService = mockk()
            every { noteSchemaService.getSchemaForType("feature-task") } returns globalSchema
            every { noteSchemaService.getSchemaForTags(any()) } returns null
            every { noteSchemaService.getDefaultTraits(any()) } returns emptyList()
            every { noteSchemaService.getTraitNotes(any()) } returns null

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            context =
                ToolExecutionContext(
                    repositoryProvider,
                    noteSchemaService,
                    perRootConfigService = perRootConfigService
                )
        }

    private fun makeItem(
        rootId: UUID?,
        type: String = "feature-task"
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "Test Item",
            type = type,
            rootId = rootId,
            depth = 0
        )

    @Test
    fun `item under a root with pushed config resolves the per-root schema`() =
        runBlocking {
            val item = makeItem(rootId = rootWithConfig)

            val result = context.resolveSchema(item)

            assertNotNull(result)
            assertEquals(1, result.notes.size)
            assertEquals("per-root-note", result.notes[0].key, "Must resolve the per-root note, not the global one")
        }

    @Test
    fun `sibling item under a root with no pushed config resolves the global schema unchanged`() =
        runBlocking {
            val item = makeItem(rootId = rootWithoutConfig)

            val result = context.resolveSchema(item)

            assertEquals(globalSchema, result, "No config row for this root — must fall through to the global schema untouched")
        }

    @Test
    fun `item with null rootId resolves the global schema unchanged`() =
        runBlocking {
            val item = makeItem(rootId = null)

            val result = context.resolveSchema(item)

            assertEquals(globalSchema, result)
        }
}

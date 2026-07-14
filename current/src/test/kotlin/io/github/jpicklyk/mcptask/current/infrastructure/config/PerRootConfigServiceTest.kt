package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DB-backed tests for [PerRootConfigService], adapted from [YamlNoteSchemaServiceTest]'s
 * temp-dir style — here the "file" is a `project_config` row instead of a temp
 * `.taskorchestrator/config.yaml`.
 */
class PerRootConfigServiceTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteProjectConfigRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var service: PerRootConfigService
    private lateinit var rootItemId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
            repository = SQLiteProjectConfigRepository(databaseManager)
            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            service = PerRootConfigService(repository)

            val root = WorkItem(title = "Project Root")
            workItemRepository.create(root)
            rootItemId = root.id
        }

    // --- No config row ---

    @Test
    fun `getSchemas returns null when no config row exists`() =
        runBlocking {
            assertNull(service.getSchemas(rootItemId))
        }

    @Test
    fun `getTraitNotes returns null when no config row exists`() =
        runBlocking {
            assertNull(service.getTraitNotes(rootItemId, "needs-migration-review"))
        }

    // --- Parse: work_item_schemas format ---

    @Test
    fun `getSchemas parses work_item_schemas format`() =
        runBlocking {
            repository.upsert(
                rootItemId,
                """
work_item_schemas:
  feature-task:
    lifecycle: auto
    notes:
      - key: acceptance-criteria
        role: queue
        required: true
        description: "Acceptance criteria"
                """.trimIndent()
            )

            val schemas = service.getSchemas(rootItemId)
            assertNotNull(schemas)
            val schema = schemas["feature-task"]
            assertNotNull(schema)
            assertEquals(1, schema.notes.size)
            assertEquals("acceptance-criteria", schema.notes[0].key)
            assertEquals(Role.QUEUE, schema.notes[0].role)
        }

    @Test
    fun `getSchemaForType returns the matching schema by type`() =
        runBlocking {
            repository.upsert(
                rootItemId,
                """
work_item_schemas:
  bug-fix:
    notes:
      - key: repro-steps
        role: queue
        required: true
        description: "Repro steps"
                """.trimIndent()
            )

            val schema = service.getSchemaForType(rootItemId, "bug-fix")
            assertNotNull(schema)
            assertEquals("repro-steps", schema.notes[0].key)

            assertNull(service.getSchemaForType(rootItemId, "unknown-type"))
        }

    @Test
    fun `getTraitNotes returns trait entries from traits block`() =
        runBlocking {
            repository.upsert(
                rootItemId,
                """
traits:
  needs-migration-review:
    notes:
      - key: migration-assessment
        role: queue
        required: true
        description: "Migration assessment"
        skill: "migration-review"
                """.trimIndent()
            )

            val traitNotes = service.getTraitNotes(rootItemId, "needs-migration-review")
            assertNotNull(traitNotes)
            assertEquals(1, traitNotes.size)
            assertEquals("migration-assessment", traitNotes[0].key)
            assertEquals("migration-review", traitNotes[0].skill)

            assertNull(service.getTraitNotes(rootItemId, "unknown-trait"))
        }

    // --- Unknown top-level keys ignored silently, no schemas section required ---

    @Test
    fun `config with only unrelated top-level keys parses to empty schemas without warning-driven failure`() =
        runBlocking {
            repository.upsert(
                rootItemId,
                """
project:
  name: "Some Project"
                """.trimIndent()
            )

            val schemas = service.getSchemas(rootItemId)
            assertNotNull(schemas, "A per-root config with no schema section is still a valid (empty) parse, not null")
            assertTrue(schemas.isEmpty())
        }

    // --- Parse failure -> null (fall through to global config) ---

    @Test
    fun `malformed YAML returns null rather than throwing`() =
        runBlocking {
            repository.upsert(rootItemId, "work_item_schemas: [\ninvalid yaml: :\n  - broken")

            assertNull(service.getSchemas(rootItemId))
        }

    // --- Cache hit: identical fingerprint reuses the cached parse ---

    @Test
    fun `repeated reads with unchanged content return equivalent parsed schemas`() =
        runBlocking {
            repository.upsert(
                rootItemId,
                """
work_item_schemas:
  default:
    notes:
      - key: implementation-notes
        role: work
        required: true
        description: "Implementation notes"
                """.trimIndent()
            )

            val first = service.getSchemas(rootItemId)
            val second = service.getSchemas(rootItemId)
            val third = service.getSchemas(rootItemId)

            assertNotNull(first)
            assertNotNull(second)
            assertNotNull(third)
            assertEquals(first.keys, second.keys)
            assertEquals(first.keys, third.keys)
            assertEquals(first["default"]?.notes, second["default"]?.notes)
        }

    // --- Hot reload: upsert new content is visible on next read, no restart / no invalidation call ---

    @Test
    fun `hot-reload — upserting new content is reflected on the very next read`() =
        runBlocking {
            repository.upsert(
                rootItemId,
                """
work_item_schemas:
  default:
    notes:
      - key: original-note
        role: work
        required: true
        description: "Original"
                """.trimIndent()
            )

            val before = service.getSchemas(rootItemId)
            assertEquals(listOf("original-note"), before?.get("default")?.notes?.map { it.key })

            repository.upsert(
                rootItemId,
                """
work_item_schemas:
  default:
    notes:
      - key: replaced-note
        role: work
        required: true
        description: "Replaced"
                """.trimIndent()
            )

            val after = service.getSchemas(rootItemId)
            assertEquals(
                listOf("replaced-note"),
                after?.get("default")?.notes?.map { it.key },
                "Expected the new config to be visible on the very next read after upsert, with no restart or explicit invalidation"
            )
        }

    // --- Config row deleted after being cached -> falls back to null ---

    @Test
    fun `getSchemas returns null after the config row is deleted, even if previously cached`() =
        runBlocking {
            repository.upsert(
                rootItemId,
                """
work_item_schemas:
  default:
    notes:
      - key: some-note
        role: work
        required: true
        description: "Some note"
                """.trimIndent()
            )
            assertNotNull(service.getSchemas(rootItemId), "Sanity check: cached once before delete")

            repository.delete(rootItemId)

            assertNull(service.getSchemas(rootItemId))
        }
}

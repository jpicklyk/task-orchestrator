package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteProjectConfigRepositoryTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var rootItemId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
            projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)
            workItemRepository = SQLiteWorkItemRepository(databaseManager)

            val root = WorkItem(title = "Project Root")
            workItemRepository.create(root)
            rootItemId = root.id
        }

    // --- get: no row ---

    @Test
    fun `get returns null when no config row exists`() =
        runBlocking {
            val result = projectConfigRepository.get(rootItemId)
            assertIs<Result.Success<*>>(result)
            assertNull((result as Result.Success).data)
        }

    @Test
    fun `getFingerprint returns null when no config row exists`() =
        runBlocking {
            val result = projectConfigRepository.getFingerprint(rootItemId)
            assertIs<Result.Success<*>>(result)
            assertNull((result as Result.Success).data)
        }

    // --- upsert: fresh insert ---

    @Test
    fun `upsert stores config and returns it with a computed fingerprint`() =
        runBlocking {
            val yaml = "work_item_schemas:\n  default: {}\n"
            val result = projectConfigRepository.upsert(rootItemId, yaml)
            assertIs<Result.Success<*>>(result)
            val stored = (result as Result.Success).data
            assertEquals(rootItemId, stored.rootItemId)
            assertEquals(yaml, stored.configYaml)
            assertTrue(stored.fingerprint.isNotBlank())

            val fetched = projectConfigRepository.get(rootItemId)
            assertIs<Result.Success<*>>(fetched)
            assertEquals(stored.configYaml, (fetched as Result.Success).data?.configYaml)
            assertEquals(stored.fingerprint, fetched.data?.fingerprint)
        }

    @Test
    fun `fingerprint is stable for identical content and changes when content changes`() =
        runBlocking {
            val yamlA = "work_item_schemas:\n  default: {}\n"
            val firstUpsert = (projectConfigRepository.upsert(rootItemId, yamlA) as Result.Success).data
            val secondUpsertSameContent = (projectConfigRepository.upsert(rootItemId, yamlA) as Result.Success).data
            assertEquals(
                firstUpsert.fingerprint,
                secondUpsertSameContent.fingerprint,
                "Fingerprint must be stable across upserts of identical content"
            )

            val yamlB = "work_item_schemas:\n  default: {}\n  other: {}\n"
            val thirdUpsertDifferentContent = (projectConfigRepository.upsert(rootItemId, yamlB) as Result.Success).data
            assertTrue(
                thirdUpsertDifferentContent.fingerprint != firstUpsert.fingerprint,
                "Fingerprint must change when content changes"
            )
        }

    // --- upsert: replaces existing row (one row per root) ---

    @Test
    fun `upsert replaces the existing row for the same root rather than inserting a second row`() =
        runBlocking {
            projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  default: {}\n")
            val second = projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  other: {}\n")
            assertIs<Result.Success<*>>(second)

            val fetched = (projectConfigRepository.get(rootItemId) as Result.Success).data
            assertEquals("work_item_schemas:\n  other: {}\n", fetched?.configYaml)
        }

    // --- delete ---

    @Test
    fun `delete removes the config row and returns true`() =
        runBlocking {
            projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  default: {}\n")

            val deleteResult = projectConfigRepository.delete(rootItemId)
            assertIs<Result.Success<*>>(deleteResult)
            assertTrue((deleteResult as Result.Success).data)

            val fetched = projectConfigRepository.get(rootItemId)
            assertNull((fetched as Result.Success).data)
        }

    @Test
    fun `delete returns false when no config row exists`() =
        runBlocking {
            val deleteResult = projectConfigRepository.delete(rootItemId)
            assertIs<Result.Success<*>>(deleteResult)
            assertTrue(!(deleteResult as Result.Success).data)
        }

    // --- FK cascade: deleting the root work item removes its config row ---

    @Test
    fun `deleting the root work item cascades to delete its project_config row`() =
        runBlocking {
            projectConfigRepository.upsert(rootItemId, "work_item_schemas:\n  default: {}\n")

            workItemRepository.delete(rootItemId)

            val fetched = projectConfigRepository.get(rootItemId)
            assertIs<Result.Success<*>>(fetched)
            assertNull((fetched as Result.Success).data, "Expected CASCADE delete to remove the project_config row")
        }
}

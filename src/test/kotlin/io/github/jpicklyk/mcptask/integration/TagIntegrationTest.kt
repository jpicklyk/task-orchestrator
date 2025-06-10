package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteFeatureRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteProjectRepository
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteTaskRepository
import io.github.jpicklyk.mcptask.infrastructure.database.schema.EntityTagsTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.FeaturesTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.ProjectsTable
import io.github.jpicklyk.mcptask.infrastructure.database.schema.TaskTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.time.Instant
import java.util.*

/**
 * Integration test that proves tag functionality is working end-to-end.
 * This test DOES NOT use workarounds or manual tag insertion.
 * It tests the actual repository functionality as it would be used in production.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TagIntegrationTest {

    private lateinit var db: Database
    private lateinit var dbManager: DatabaseManager
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var featureRepository: SQLiteFeatureRepository
    private lateinit var taskRepository: SQLiteTaskRepository

    @BeforeAll
    fun setup() {
        // Connect directly to H2 in-memory database
        db = Database.connect(
            url = "jdbc:h2:mem:tagintegrationtest;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )

        // Set transaction isolation level
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE


        // Initialize database manager and repositories
        dbManager = DatabaseManager(db)
        dbManager.updateSchema()
        projectRepository = SQLiteProjectRepository(dbManager)
        featureRepository = SQLiteFeatureRepository(dbManager)
        taskRepository = SQLiteTaskRepository(dbManager)
    }

    @AfterAll
    fun tearDown() {
        // Dump all tags for debugging
        dumpAllTags()
        // Note: No need to drop tables for in-memory database
    }

    /**
     * Debug utility to see what's actually in the database
     */
    private fun dumpAllTags() {
        transaction {
            println("==== TAG INTEGRATION TEST - DATABASE DUMP ====")
            val tags = EntityTagsTable.selectAll().toList()

            if (tags.isEmpty()) {
                println("NO TAGS FOUND IN DATABASE!")
            } else {
                println("Found ${tags.size} total tag entries:")
                tags.forEach { row ->
                    val entityId = row[EntityTagsTable.entityId]
                    val entityType = row[EntityTagsTable.entityType]
                    val tag = row[EntityTagsTable.tag]
                    val createdAt = row[EntityTagsTable.createdAt]

                    println("  - Entity Type: '$entityType', ID: $entityId, Tag: '$tag', Created: $createdAt")
                }
            }
            println("============================================")
        }
    }

    /**
     * Directly inserts a tag using raw SQL to bypass any potential repository issues.
     * This is for debugging purposes.
     */
    private fun insertTagDirectly(entityId: UUID, entityType: String, tag: String) {
        transaction {
            val now = Instant.now()
            val tagId = UUID.randomUUID()

            // Use direct SQL to insert
            exec(
                """
                INSERT INTO entity_tags (id, entity_id, entity_type, tag, created_at) 
                VALUES ('$tagId', '$entityId', '$entityType', '$tag', '$now')
            """.trimIndent()
            )

            println("Direct SQL: Inserted tag '$tag' for '$entityType' with ID: $entityId")
        }
    }

    /**
     * Test that verifies the complete flow of tag functionality without any workarounds.
     */
    @Test
    fun `test complete tag functionality without workarounds`() = runBlocking {
        // === PROJECT TESTING ===
        val projectId = UUID.randomUUID()
        val projectTags = listOf("project-tag", "integration-test", "kotlin")
        val project = Project(
            id = projectId,
            name = "Tag Integration Test Project",
            summary = "Testing tag functionality end-to-end",
            status = ProjectStatus.PLANNING,
            tags = projectTags
        )

        // 1. Create project with tags using repository
        println("\n=== CREATING PROJECT ===")
        val projectCreateResult = projectRepository.create(project)
        assertTrue(projectCreateResult is Result.Success, "Project creation should succeed")

        // Dump tags after creation
        println("\n=== TAGS AFTER PROJECT CREATION ===")
        dumpAllTags()

        // 2. Retrieve project and verify tags are present
        println("\n=== RETRIEVING PROJECT ===")
        val projectRetrieveResult = projectRepository.getById(projectId)
        assertTrue(projectRetrieveResult is Result.Success, "Project retrieval should succeed")

        val retrievedProject = (projectRetrieveResult as Result.Success).data
        println("Project tags - Expected: $projectTags")
        println("Project tags - Retrieved: ${retrievedProject.tags}")

        assertEquals(
            projectTags.size, retrievedProject.tags.size,
            "Retrieved project should have same number of tags as original"
        )
        assertTrue(
            retrievedProject.tags.containsAll(projectTags),
            "Retrieved project should contain all original tags"
        )

        // 3. Test direct SQL tagging approach for comparison
        val testEntityId = UUID.randomUUID()
        val testEntityType = "TEST_ENTITY"
        val testTags = listOf("tag1", "tag2", "tag3")

        // Directly insert tags with SQL to test database access
        println("\n=== DIRECT TAG INSERTION TEST ===")
        testTags.forEach { tag ->
            insertTagDirectly(testEntityId, testEntityType, tag)
        }

        // Verify tags are in the database
        val retrievedTags = transaction {
            EntityTagsTable
                .selectAll().where {
                    (EntityTagsTable.entityId eq testEntityId) and
                            (EntityTagsTable.entityType eq testEntityType)
                }
                .map { it[EntityTagsTable.tag] }
        }

        println("Direct SQL test - Tags inserted: $testTags")
        println("Direct SQL test - Tags retrieved: $retrievedTags")

        assertEquals(testTags.size, retrievedTags.size, "All directly inserted tags should be in database")
        assertTrue(retrievedTags.containsAll(testTags), "All tags should be retrievable")
    }
}
        
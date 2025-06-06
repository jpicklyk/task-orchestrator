package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.SQLiteProjectRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests focusing on project search and filtering operations
 */
class ProjectSearchFilterTest {
    private lateinit var database: Database
    private lateinit var repository: SQLiteProjectRepository

    @BeforeEach
    fun setUp() {
        // Create in-memory database for testing with unique name to ensure isolation
        val uniqueDbName = "test_${System.currentTimeMillis()}_${Math.random()}"
        database = Database.connect("jdbc:h2:mem:$uniqueDbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        val dbManager = DatabaseManager(database)
        dbManager.updateSchema()

        // Initialize repository
        repository = SQLiteProjectRepository(dbManager)

        // Populate test data with diverse content for comprehensive search testing
        runBlocking {
            // Create projects with different attributes
            val project1 = Project(
                name = "Mobile App Project",
                summary = "Project for developing a mobile application",
                status = ProjectStatus.PLANNING,
                tags = listOf("mobile", "app", "development")
            )
            val project2 = Project(
                name = "Website Redesign",
                summary = "Project for redesigning the company website",
                status = ProjectStatus.IN_DEVELOPMENT,
                tags = listOf("web", "design", "frontend")
            )
            val project3 = Project(
                name = "API Development",
                summary = "Project for creating RESTful APIs",
                status = ProjectStatus.COMPLETED,
                tags = listOf("api", "backend", "development")
            )
            val project4 = Project(
                name = "Database Migration",
                summary = "Migrating legacy database to PostgreSQL",
                status = ProjectStatus.PLANNING,
                tags = listOf("database", "migration", "postgresql")
            )
            val project5 = Project(
                name = "Security Audit",
                summary = "Comprehensive security audit of infrastructure",
                status = ProjectStatus.COMPLETED,
                tags = listOf("security", "audit", "infrastructure")
            )

            repository.create(project1)
            repository.create(project2)
            repository.create(project3)
            repository.create(project4)
            repository.create(project5)
        }
    }


    /**
     * Test searching and filtering projects by various criteria
     */
    @Test
    fun `should search and filter projects`() = runBlocking {
        // Test search by name
        val nameSearchResult = repository.search("Mobile")
        assertTrue(nameSearchResult is Result.Success)
        val nameResults = (nameSearchResult as Result.Success).data
        assertEquals(1, nameResults.size)
        assertEquals("Mobile App Project", nameResults[0].name)

        // Test search by summary content
        val summarySearchResult = repository.search("RESTful")
        assertTrue(summarySearchResult is Result.Success)
        val summaryResults = (summarySearchResult as Result.Success).data
        assertEquals(1, summaryResults.size)
        assertEquals("API Development", summaryResults[0].name)

        // Test filtering by status
        val statusFilterResult = repository.findByStatus(ProjectStatus.IN_DEVELOPMENT)
        assertTrue(statusFilterResult is Result.Success)
        val statusResults = (statusFilterResult as Result.Success).data
        assertEquals(1, statusResults.size)
        assertEquals("Website Redesign", statusResults[0].name)

        // Test filtering by tag
        val tagFilterResult = repository.findByTag("development")
        assertTrue(tagFilterResult is Result.Success)
        val tagResults = (tagFilterResult as Result.Success).data
        assertEquals(2, tagResults.size)
        val projectNames = tagResults.map { it.name }
        assertTrue(projectNames.contains("Mobile App Project"))
        assertTrue(projectNames.contains("API Development"))

        // Test combined search for multiple terms
        val combinedSearchResult = repository.search("development project")
        assertTrue(combinedSearchResult is Result.Success)
        val combinedResults = (combinedSearchResult as Result.Success<List<Project>>).data
        assertEquals(2, combinedResults.size)
        // Both "Mobile App Project" and "API Development" projects have both "development" and "project" terms
        val combinedProjectNames = combinedResults.map { it.name }
        assertTrue(combinedProjectNames.contains("Mobile App Project"))
        assertTrue(combinedProjectNames.contains("API Development"))
    }

    /**
     * Comprehensive test for search logic validation including negative cases
     */
    @Test
    fun `should properly handle search logic with positive and negative cases`() = runBlocking {
        // Test case insensitivity - should find results regardless of case
        val caseInsensitiveResult = repository.search("MOBILE")
        assertTrue(caseInsensitiveResult is Result.Success)
        val caseResults = (caseInsensitiveResult as Result.Success).data
        assertEquals(1, caseResults.size)
        assertEquals("Mobile App Project", caseResults[0].name)

        // Test mixed case search
        val mixedCaseResult = repository.search("ApI")
        assertTrue(mixedCaseResult is Result.Success)
        val mixedResults = (mixedCaseResult as Result.Success).data
        assertEquals(1, mixedResults.size)
        assertEquals("API Development", mixedResults[0].name)

        // Test AND logic for multi-word search - should only return results containing ALL terms
        val andLogicResult = repository.search("database migration")
        assertTrue(andLogicResult is Result.Success)
        val andResults = (andLogicResult as Result.Success).data
        assertEquals(1, andResults.size)
        assertEquals("Database Migration", andResults[0].name)

        // Test that search excludes results that don't match all terms
        val excludeResult = repository.search("mobile database")
        assertTrue(excludeResult is Result.Success)
        val excludeResults = (excludeResult as Result.Success).data
        assertEquals(0, excludeResults.size) // No project contains both "mobile" AND "database"

        // Test search term that should match nothing
        val noMatchResult = repository.search("nonexistent")
        assertTrue(noMatchResult is Result.Success)
        val noMatchResults = (noMatchResult as Result.Success).data
        assertEquals(0, noMatchResults.size)

        // Test partial word search - current implementation uses LIKE with wildcards, so partial matches work
        val partialResult = repository.search("develop") // Should match "development" because we use substring search
        assertTrue(partialResult is Result.Success)
        val partialResults = (partialResult as Result.Success).data
        assertEquals(2, partialResults.size) // Should match both "Mobile App Project" and "API Development"
        val partialNames = partialResults.map { it.name }
        assertTrue(partialNames.contains("Mobile App Project")) // Contains "development" tag
        assertTrue(partialNames.contains("API Development")) // Contains "development" tag

        // Test three-word search - should require ALL three terms
        val threeWordResult = repository.search("security audit infrastructure")
        assertTrue(threeWordResult is Result.Success)
        val threeWordResults = (threeWordResult as Result.Success).data

        // Debug output to understand the issue
        println("=== THREE-WORD SEARCH DEBUG ===")
        println("Query: 'security audit infrastructure'")
        println("Found ${threeWordResults.size} results:")
        threeWordResults.forEach { project ->
            println("  - ${project.name}")
            println("    Summary: ${project.summary}")
            println("    Tags: ${project.tags}")

            // Check if the project should match by examining the search vector
            val searchVector = "${project.name} ${project.summary} ${project.tags.joinToString(" ")}".lowercase()
            val hasAllTerms = listOf("security", "audit", "infrastructure").all { term ->
                searchVector.contains(term)
            }
            println("    Search vector contains all terms: $hasAllTerms")
            println("    Actual search vector: '$searchVector'")
            println()
        }

        assertEquals(1, threeWordResults.size)
        assertEquals("Security Audit", threeWordResults[0].name)

        // Test that two of three words doesn't match
        val twoOfThreeResult = repository.search("security audit mobile")
        assertTrue(twoOfThreeResult is Result.Success)
        val twoOfThreeResults = (twoOfThreeResult as Result.Success).data
        assertEquals(0, twoOfThreeResults.size) // No project contains all three terms

        // Test tag-based search
        val tagSearchResult = repository.search("postgresql")
        assertTrue(tagSearchResult is Result.Success)
        val tagSearchResults = (tagSearchResult as Result.Success).data
        assertEquals(1, tagSearchResults.size)
        assertEquals("Database Migration", tagSearchResults[0].name)

        // Test search across multiple fields (name + summary + tags)
        val multiFieldResult = repository.search("comprehensive")
        assertTrue(multiFieldResult is Result.Success)
        val multiFieldResults = (multiFieldResult as Result.Success).data
        assertEquals(1, multiFieldResults.size)
        assertEquals("Security Audit", multiFieldResults[0].name) // Found in summary
    }
}
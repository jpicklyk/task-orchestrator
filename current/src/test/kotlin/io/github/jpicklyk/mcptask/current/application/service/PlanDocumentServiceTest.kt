package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLitePlanDocumentRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises [PlanDocumentService] against a real H2-backed [SQLitePlanDocumentRepository] and
 * [SQLiteWorkItemRepository] — mirrors [ProjectConfigPushServiceTest]'s DB-backed style. Validation
 * (root existence, depth-0, size cap) depends on real WorkItem rows.
 */
class PlanDocumentServiceTest {
    private lateinit var service: PlanDocumentService
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var planDocumentRepository: SQLitePlanDocumentRepository
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            planDocumentRepository = SQLitePlanDocumentRepository(databaseManager)

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.planDocumentRepository() } returns planDocumentRepository

            service = PlanDocumentService(repositoryProvider)

            rootId = (workItemRepository.create(WorkItem(title = "Root", type = "project")) as Result.Success).data.id
        }

    @Test
    fun `stash succeeds for a valid depth-0 root`() =
        runBlocking {
            val result = service.stash(rootId, "plan-a", "# Plan A\n")
            assertIs<PlanDocumentStashResult.Success>(result)
            assertEquals("# Plan A\n", result.document.body)
            assertEquals(PlanDocumentStatus.PENDING, result.document.status)
        }

    @Test
    fun `stash against an unknown root returns NotFound and stores nothing`() =
        runBlocking {
            val unknownRoot = UUID.randomUUID()
            val result = service.stash(unknownRoot, "plan-a", "content")
            assertIs<PlanDocumentStashResult.NotFound>(result)

            val fetched = planDocumentRepository.get(unknownRoot, "plan-a")
            assertEquals(null, (fetched as Result.Success).data)
        }

    @Test
    fun `stash against a non-depth-0 root is rejected and stores nothing`() =
        runBlocking {
            val child =
                (
                    workItemRepository.create(WorkItem(title = "Child", parentId = rootId, depth = 1)) as Result.Success
                ).data

            val result = service.stash(child.id, "plan-a", "content")
            assertIs<PlanDocumentStashResult.NotDepthZero>(result)
            assertEquals(1, result.depth)

            val fetched = planDocumentRepository.get(child.id, "plan-a")
            assertEquals(null, (fetched as Result.Success).data)
        }

    @Test
    fun `stash over the 64KB cap is rejected and stores nothing`() =
        runBlocking {
            val oversized = "x".repeat(PlanDocumentService.MAX_BODY_BYTES + 1)
            val result = service.stash(rootId, "plan-a", oversized)
            assertIs<PlanDocumentStashResult.TooLarge>(result)
            assertEquals(PlanDocumentService.MAX_BODY_BYTES, result.maxBytes)

            val fetched = planDocumentRepository.get(rootId, "plan-a")
            assertEquals(null, (fetched as Result.Success).data)
        }

    @Test
    fun `stash exactly at the 64KB cap succeeds`() =
        runBlocking {
            val exact = "x".repeat(PlanDocumentService.MAX_BODY_BYTES)
            val result = service.stash(rootId, "plan-a", exact)
            assertIs<PlanDocumentStashResult.Success>(result)
        }

    @Test
    fun `re-stashing a PENDING slug overwrites it in place`() =
        runBlocking {
            service.stash(rootId, "plan-a", "v1")
            val result = service.stash(rootId, "plan-a", "v2")
            assertIs<PlanDocumentStashResult.Success>(result)
            assertEquals("v2", result.document.body)

            val summaries = (planDocumentRepository.list(rootId) as Result.Success).data
            assertEquals(1, summaries.size)
        }

    @Test
    fun `stashing against an ADOPTED slug is rejected with AdoptedConflict`() =
        runBlocking {
            service.stash(rootId, "plan-a", "v1")
            val adopter = (workItemRepository.create(WorkItem(title = "Adopter")) as Result.Success).data
            planDocumentRepository.markAdopted(rootId, "plan-a", adopter.id)

            val result = service.stash(rootId, "plan-a", "v2")
            assertIs<PlanDocumentStashResult.AdoptedConflict>(result)
            assertEquals(adopter.id, result.existing.adoptedByItemId)

            val fetched = (planDocumentRepository.get(rootId, "plan-a") as Result.Success).data
            assertEquals("v1", fetched?.body, "Rejected stash must not overwrite the adopted row")
        }

    @Test
    fun `get returns the stashed document including its body`() =
        runBlocking {
            service.stash(rootId, "plan-a", "# Plan A\n")
            val result = service.get(rootId, "plan-a")
            assertIs<Result.Success<*>>(result)
            assertEquals("# Plan A\n", (result as Result.Success).data?.body)
        }

    @Test
    fun `list omits body and reflects stashed documents`() =
        runBlocking {
            service.stash(rootId, "plan-a", "a")
            service.stash(rootId, "plan-b", "b")

            val result = service.list(rootId)
            assertIs<Result.Success<*>>(result)
            val slugs = (result as Result.Success).data.map { it.slug }
            assertEquals(listOf("plan-a", "plan-b"), slugs)
        }

    @Test
    fun `computeContentHash matches the hash stash actually stores`() =
        runBlocking {
            val body = "# Plan A\n"
            val computed = service.computeContentHash(body)
            val stashed = service.stash(rootId, "plan-a", body)
            assertIs<PlanDocumentStashResult.Success>(stashed)
            assertEquals(computed, stashed.document.contentHash)
        }

    @Test
    fun `dual ingestion parity — REST-style stash and MCP bodyFromFile-style stash of identical bytes land identical content hashes`() =
        runBlocking {
            // Simulates the two ingestion paths converging on the same PlanDocumentService.stash
            // call — REST PUT passes the raw request body; MCP bodyFromFile reads a file and
            // normalizes CRLF to LF before calling stash with the same resolved text. Both are
            // exercised here as identical String inputs to the shared service method.
            val bodyViaRest = "# Shared Plan\n\nSame bytes either way.\n"
            val bodyViaMcpFile = "# Shared Plan\n\nSame bytes either way.\n"
            assertTrue(bodyViaRest == bodyViaMcpFile, "Test setup sanity check")

            val restResult = service.stash(rootId, "plan-rest", bodyViaRest)
            val mcpResult = service.stash(rootId, "plan-mcp", bodyViaMcpFile)
            assertIs<PlanDocumentStashResult.Success>(restResult)
            assertIs<PlanDocumentStashResult.Success>(mcpResult)
            assertEquals(
                restResult.document.contentHash,
                mcpResult.document.contentHash,
                "Identical bytes stashed via either ingestion path must produce identical content hashes",
            )
        }
}

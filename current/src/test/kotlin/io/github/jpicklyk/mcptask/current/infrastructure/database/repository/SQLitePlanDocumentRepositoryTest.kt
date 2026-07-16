package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.PlanDocumentAdoptOutcome
import io.github.jpicklyk.mcptask.current.domain.repository.PlanDocumentStashOutcome
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLitePlanDocumentRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLitePlanDocumentRepositoryTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var planDocumentRepository: SQLitePlanDocumentRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var rootItemId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
            planDocumentRepository = SQLitePlanDocumentRepository(databaseManager)
            workItemRepository = SQLiteWorkItemRepository(databaseManager)

            val root = WorkItem(title = "Project Root")
            workItemRepository.create(root)
            rootItemId = root.id
        }

    // --- get: no row ---

    @Test
    fun `get returns null when no document row exists`() =
        runBlocking {
            val result = planDocumentRepository.get(rootItemId, "plan-a")
            assertIs<Result.Success<*>>(result)
            assertNull((result as Result.Success).data)
        }

    // --- stash: fresh insert ---

    @Test
    fun `stash stores a PENDING document and returns it with a computed content hash`() =
        runBlocking {
            val result = planDocumentRepository.stash(rootItemId, "plan-a", "# Plan A\n")
            assertIs<Result.Success<*>>(result)
            val outcome = (result as Result.Success).data
            assertIs<PlanDocumentStashOutcome.Stored>(outcome)
            val stored = outcome.document
            assertEquals(rootItemId, stored.rootItemId)
            assertEquals("plan-a", stored.slug)
            assertEquals("# Plan A\n", stored.body)
            assertEquals(PlanDocumentStatus.PENDING, stored.status)
            assertNull(stored.adoptedByItemId)
            assertTrue(stored.contentHash.isNotBlank())

            val fetched = planDocumentRepository.get(rootItemId, "plan-a")
            assertEquals(stored.body, (fetched as Result.Success).data?.body)
            assertEquals(stored.contentHash, fetched.data?.contentHash)
        }

    @Test
    fun `content hash is stable for identical content and changes when content changes`() =
        runBlocking {
            val hashA = planDocumentRepository.computeContentHash("# Plan A\n")
            assertEquals(hashA, planDocumentRepository.computeContentHash("# Plan A\n"))
            val hashB = planDocumentRepository.computeContentHash("# Plan B\n")
            assertTrue(hashA != hashB)
        }

    // --- stash: overwrites an existing PENDING row rather than inserting a second row ---

    @Test
    fun `stash overwrites the existing PENDING row for the same slug rather than inserting a second row`() =
        runBlocking {
            planDocumentRepository.stash(rootItemId, "plan-a", "v1")
            val second = planDocumentRepository.stash(rootItemId, "plan-a", "v2")
            assertIs<PlanDocumentStashOutcome.Stored>((second as Result.Success).data)

            val fetched = (planDocumentRepository.get(rootItemId, "plan-a") as Result.Success).data
            assertEquals("v2", fetched?.body)

            val summaries = (planDocumentRepository.list(rootItemId) as Result.Success).data
            assertEquals(1, summaries.size, "Overwrite must not create a second row for the same slug")
        }

    // --- stash: rejected against an ADOPTED slug ---

    @Test
    fun `stash against an ADOPTED slug is rejected and does not modify the row`() =
        runBlocking {
            planDocumentRepository.stash(rootItemId, "plan-a", "v1")
            val adopter = (workItemRepository.create(WorkItem(title = "Adopter")) as Result.Success).data
            planDocumentRepository.markAdopted(rootItemId, "plan-a", adopter.id)

            val result = planDocumentRepository.stash(rootItemId, "plan-a", "v2 — should not land")
            assertIs<Result.Success<*>>(result)
            val outcome = (result as Result.Success).data
            assertIs<PlanDocumentStashOutcome.AdoptedConflict>(outcome)
            assertEquals("v1", outcome.existing.body, "The adopted row's body must remain unchanged")

            val fetched = (planDocumentRepository.get(rootItemId, "plan-a") as Result.Success).data
            assertEquals("v1", fetched?.body)
            assertEquals(PlanDocumentStatus.ADOPTED, fetched?.status)
        }

    // --- markAdopted ---

    @Test
    fun `markAdopted transitions PENDING to ADOPTED and records the adopting item`() =
        runBlocking {
            planDocumentRepository.stash(rootItemId, "plan-a", "v1")
            val adopter = (workItemRepository.create(WorkItem(title = "Adopter")) as Result.Success).data

            val result = planDocumentRepository.markAdopted(rootItemId, "plan-a", adopter.id)
            assertIs<Result.Success<*>>(result)
            val outcome = (result as Result.Success).data
            assertIs<PlanDocumentAdoptOutcome.Adopted>(outcome)
            assertEquals(PlanDocumentStatus.ADOPTED, outcome.document.status)
            assertEquals(adopter.id, outcome.document.adoptedByItemId)
        }

    @Test
    fun `markAdopted on a missing slug returns NotFound`() =
        runBlocking {
            val adopter = (workItemRepository.create(WorkItem(title = "Adopter")) as Result.Success).data
            val result = planDocumentRepository.markAdopted(rootItemId, "no-such-slug", adopter.id)
            assertIs<Result.Success<*>>(result)
            assertIs<PlanDocumentAdoptOutcome.NotFound>((result as Result.Success).data)
        }

    @Test
    fun `markAdopted on an already-ADOPTED slug returns AlreadyAdopted without changing the adopter`() =
        runBlocking {
            planDocumentRepository.stash(rootItemId, "plan-a", "v1")
            val firstAdopter = (workItemRepository.create(WorkItem(title = "First Adopter")) as Result.Success).data
            val secondAdopter = (workItemRepository.create(WorkItem(title = "Second Adopter")) as Result.Success).data
            planDocumentRepository.markAdopted(rootItemId, "plan-a", firstAdopter.id)

            val result = planDocumentRepository.markAdopted(rootItemId, "plan-a", secondAdopter.id)
            assertIs<Result.Success<*>>(result)
            val outcome = (result as Result.Success).data
            assertIs<PlanDocumentAdoptOutcome.AlreadyAdopted>(outcome)
            assertEquals(firstAdopter.id, outcome.existing.adoptedByItemId, "Re-adoption must not change the recorded adopter")
        }

    // --- list ---

    @Test
    fun `list returns metadata-only summaries ordered by slug ascending`() =
        runBlocking {
            planDocumentRepository.stash(rootItemId, "zeta", "z")
            planDocumentRepository.stash(rootItemId, "alpha", "a")

            val result = planDocumentRepository.list(rootItemId)
            assertIs<Result.Success<*>>(result)
            val summaries = (result as Result.Success).data
            assertEquals(listOf("alpha", "zeta"), summaries.map { it.slug })
        }

    @Test
    fun `list filters by status when provided`() =
        runBlocking {
            planDocumentRepository.stash(rootItemId, "plan-a", "a")
            planDocumentRepository.stash(rootItemId, "plan-b", "b")
            val adopter = (workItemRepository.create(WorkItem(title = "Adopter")) as Result.Success).data
            planDocumentRepository.markAdopted(rootItemId, "plan-a", adopter.id)

            val pending = (planDocumentRepository.list(rootItemId, PlanDocumentStatus.PENDING) as Result.Success).data
            assertEquals(listOf("plan-b"), pending.map { it.slug })

            val adopted = (planDocumentRepository.list(rootItemId, PlanDocumentStatus.ADOPTED) as Result.Success).data
            assertEquals(listOf("plan-a"), adopted.map { it.slug })
        }

    @Test
    fun `list scopes to the given root only`() =
        runBlocking {
            val otherRoot = (workItemRepository.create(WorkItem(title = "Other Root")) as Result.Success).data
            planDocumentRepository.stash(rootItemId, "plan-a", "a")
            planDocumentRepository.stash(otherRoot.id, "plan-b", "b")

            val summaries = (planDocumentRepository.list(rootItemId) as Result.Success).data
            assertEquals(listOf("plan-a"), summaries.map { it.slug })
        }

    // --- FK cascade: deleting the root work item removes its plan_documents rows ---

    @Test
    fun `deleting the root work item cascades to delete its plan_documents rows`() =
        runBlocking {
            planDocumentRepository.stash(rootItemId, "plan-a", "a")

            workItemRepository.delete(rootItemId)

            val fetched = planDocumentRepository.get(rootItemId, "plan-a")
            assertIs<Result.Success<*>>(fetched)
            assertNull((fetched as Result.Success).data, "Expected CASCADE delete to remove the plan_documents row")
        }

    // --- FK set-null: deleting the adopting work item unlinks adoption without deleting the row ---

    @Test
    fun `deleting the adopting work item sets adoptedByItemId to null without deleting the document`() =
        runBlocking {
            planDocumentRepository.stash(rootItemId, "plan-a", "a")
            val adopter = (workItemRepository.create(WorkItem(title = "Adopter")) as Result.Success).data
            planDocumentRepository.markAdopted(rootItemId, "plan-a", adopter.id)

            workItemRepository.delete(adopter.id)

            val fetched = (planDocumentRepository.get(rootItemId, "plan-a") as Result.Success).data
            assertNotNull(fetched, "Document row must survive the adopter's deletion")
            assertNull(fetched.adoptedByItemId, "Expected ON DELETE SET NULL to unlink the adopter")
            assertEquals(
                PlanDocumentStatus.ADOPTED,
                fetched.status,
                "Deleting the adopter must not revert the document's own ADOPTED status"
            )
        }
}

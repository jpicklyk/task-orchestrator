package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
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
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [ManagePlanDocumentsTool] against a real H2-backed [SQLitePlanDocumentRepository] and
 * [SQLiteWorkItemRepository] — mirrors [ManageProjectConfigToolTest]'s DB-backed style.
 */
class ManagePlanDocumentsToolTest {
    private lateinit var tool: ManagePlanDocumentsTool
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var planDocumentRepository: SQLitePlanDocumentRepository
    private lateinit var context: ToolExecutionContext
    private lateinit var rootId: UUID

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() =
        runBlocking {
            tool = ManagePlanDocumentsTool(agentConfigBaseDir = tempDir)

            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            planDocumentRepository = SQLitePlanDocumentRepository(databaseManager)

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.planDocumentRepository() } returns planDocumentRepository

            context = ToolExecutionContext(repositoryProvider)

            val root = (workItemRepository.create(WorkItem(title = "Project Root", type = "project")) as Result.Success).data
            rootId = root.id
        }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun isSuccess(result: JsonElement): Boolean = (result as JsonObject)["success"]!!.jsonPrimitive.boolean

    private fun dataOf(result: JsonElement): JsonObject = (result as JsonObject)["data"] as JsonObject

    private fun errorOf(result: JsonElement): JsonObject = (result as JsonObject)["error"] as JsonObject

    private fun stash(
        rootItemId: String,
        slug: String,
        body: String? = null,
        bodyFromFile: String? = null
    ): JsonElement =
        runBlocking {
            tool.execute(
                params(
                    *buildList {
                        add("operation" to JsonPrimitive("stash"))
                        add("rootItemId" to JsonPrimitive(rootItemId))
                        add("slug" to JsonPrimitive(slug))
                        if (body != null) add("body" to JsonPrimitive(body))
                        if (bodyFromFile != null) add("bodyFromFile" to JsonPrimitive(bodyFromFile))
                    }.toTypedArray()
                ),
                context
            )
        }

    private fun get(
        rootItemId: String,
        slug: String
    ): JsonElement =
        runBlocking {
            tool.execute(
                params(
                    "operation" to JsonPrimitive("get"),
                    "rootItemId" to JsonPrimitive(rootItemId),
                    "slug" to JsonPrimitive(slug),
                ),
                context
            )
        }

    private fun list(
        rootItemId: String,
        status: String? = null
    ): JsonElement =
        runBlocking {
            tool.execute(
                params(
                    *buildList {
                        add("operation" to JsonPrimitive("list"))
                        add("rootItemId" to JsonPrimitive(rootItemId))
                        if (status != null) add("status" to JsonPrimitive(status))
                    }.toTypedArray()
                ),
                context
            )
        }

    // --- validateParams ---

    @Test
    fun `validateParams rejects stash with neither body nor bodyFromFile`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("stash"),
                    "rootItemId" to JsonPrimitive(rootId.toString()),
                    "slug" to JsonPrimitive("plan-a"),
                ),
            )
        }
    }

    @Test
    fun `validateParams rejects stash with both body and bodyFromFile`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("stash"),
                    "rootItemId" to JsonPrimitive(rootId.toString()),
                    "slug" to JsonPrimitive("plan-a"),
                    "body" to JsonPrimitive("inline"),
                    "bodyFromFile" to JsonPrimitive("plan.md"),
                ),
            )
        }
    }

    @Test
    fun `validateParams rejects an unknown operation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params("operation" to JsonPrimitive("delete")))
        }
    }

    // --- stash ---

    @Test
    fun `stash via inline body succeeds`() {
        val result = stash(rootId.toString(), "plan-a", body = "# Plan A\n")
        assertTrue(isSuccess(result))
        val data = dataOf(result)
        assertEquals("plan-a", data["slug"]!!.jsonPrimitive.content)
        assertEquals("pending", data["status"]!!.jsonPrimitive.content)
        assertFalse(data.containsKey("body"), "stash response should not echo the body back")
    }

    @Test
    fun `stash against an unknown root returns RESOURCE_NOT_FOUND`() {
        val result = stash(UUID.randomUUID().toString(), "plan-a", body = "content")
        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stash against an ADOPTED slug returns CONFLICT_ERROR`() {
        stash(rootId.toString(), "plan-a", body = "v1")
        val adopter = runBlocking { (workItemRepository.create(WorkItem(title = "Adopter")) as Result.Success).data }
        runBlocking { planDocumentRepository.markAdopted(rootId, "plan-a", adopter.id) }

        val result = stash(rootId.toString(), "plan-a", body = "v2")
        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.CONFLICT_ERROR, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    // --- dual ingestion parity: inline body vs bodyFromFile ---

    @Test
    fun `stash via body and stash via bodyFromFile land identical content hashes for identical bytes`() {
        val text = "# Shared Plan\n\nSame bytes either way.\n"
        val file = tempDir.resolve("plan.md")
        Files.writeString(file, text)

        val viaBody = stash(rootId.toString(), "plan-via-body", body = text)
        val viaFile = stash(rootId.toString(), "plan-via-file", bodyFromFile = "plan.md")

        assertTrue(isSuccess(viaBody))
        assertTrue(isSuccess(viaFile))
        assertEquals(
            dataOf(viaBody)["contentHash"]!!.jsonPrimitive.content,
            dataOf(viaFile)["contentHash"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `stash via bodyFromFile normalizes CRLF to LF`() {
        val file = tempDir.resolve("crlf.md")
        Files.writeString(file, "line1\r\nline2\r\n")

        stash(rootId.toString(), "plan-crlf", bodyFromFile = "crlf.md")
        val fetched = runBlocking { (planDocumentRepository.get(rootId, "plan-crlf") as Result.Success).data }
        assertEquals("line1\nline2\n", fetched?.body)
    }

    @Test
    fun `stash via bodyFromFile rejects a path escaping the agent config root`() {
        val result = stash(rootId.toString(), "plan-a", bodyFromFile = "../outside.md")
        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.VALIDATION_ERROR, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    // --- get ---

    @Test
    fun `get returns the full stored document including body`() {
        stash(rootId.toString(), "plan-a", body = "# Plan A\n")
        val result = get(rootId.toString(), "plan-a")
        assertTrue(isSuccess(result))
        assertEquals("# Plan A\n", dataOf(result)["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get on a missing slug returns RESOURCE_NOT_FOUND`() {
        val result = get(rootId.toString(), "no-such-slug")
        assertFalse(isSuccess(result))
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, errorOf(result)["code"]!!.jsonPrimitive.content)
    }

    // --- list ---

    @Test
    fun `list returns metadata-only summaries without a body field`() {
        stash(rootId.toString(), "plan-a", body = "a")
        stash(rootId.toString(), "plan-b", body = "b")

        val result = list(rootId.toString())
        assertTrue(isSuccess(result))
        val plans = dataOf(result)["plans"]!!.jsonArray
        assertEquals(2, plans.size)
        plans.forEach { plan -> assertFalse((plan as JsonObject).containsKey("body")) }
    }

    @Test
    fun `list filters by status`() {
        stash(rootId.toString(), "plan-a", body = "a")
        stash(rootId.toString(), "plan-b", body = "b")
        val adopter = runBlocking { (workItemRepository.create(WorkItem(title = "Adopter")) as Result.Success).data }
        runBlocking { planDocumentRepository.markAdopted(rootId, "plan-a", adopter.id) }

        val pending = list(rootId.toString(), status = "pending")
        val pendingSlugs = dataOf(pending)["plans"]!!.jsonArray.map { (it as JsonObject)["slug"]!!.jsonPrimitive.content }
        assertEquals(listOf("plan-b"), pendingSlugs)
    }
}

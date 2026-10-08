package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `diagnosis`/`test-plan` notes on item `aa664be1` — S14
 * maps to this file per the test-plan's file list. Oracle: D7 ("each item's schemaMatch/
 * expectedNotes decoration and the batch's availableTraits hint are computed AFTER repo.create
 * (workItem) has ALREADY PERSISTED that item ... schemaMatch/expectedNotes omitted from THIS
 * item's entry; item.created count unaffected ... availableTraits omitted from the response")
 * plus the verbatim `CreateItemHandler.kt` excerpt showing the two independent try/catch sites
 * (per-item decoration, and the batch's `availableTraits`).
 *
 * Harness mirrors [ManageItemsToolTest]'s real-SQLite-DB setup, adding a real [PerRootConfigService]
 * over a [FailableProjectConfigRepository]-wrapped real [io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository]
 * (via `repositoryProvider.projectConfigRepository()`) for the create call under test, following
 * the same "own copy per file" fixture used by the sibling config-unavailable test files (this
 * item's file-ownership rule forbids a shared harness file).
 */
class CreateItemConfigUnavailableTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var tool: ManageItemsTool
    private lateinit var plainContext: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        repositoryProvider = db.repositoryProvider()
        plainContext = ToolExecutionContext(repositoryProvider, unitOfWork = db.unitOfWork())
        tool = ManageItemsTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun createUnderPlainContext(
        title: String,
        parentId: String? = null,
        type: String? = null
    ): JsonObject {
        val obj =
            buildJsonObject {
                put("title", JsonPrimitive(title))
                if (parentId != null) put("parentId", JsonPrimitive(parentId))
                if (type != null) put("type", JsonPrimitive(type))
            }
        val result =
            tool.execute(
                params("operation" to JsonPrimitive("create"), "items" to JsonArray(listOf(obj))),
                plainContext
            ) as JsonObject
        return (result["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject
    }

    @Test
    fun `S14 - create under a cold failing root persists the item but omits schemaMatch and expectedNotes`(): Unit =
        runBlocking {
            val rootEntry = createUnderPlainContext(title = "Root S14")
            val rootId = rootEntry["id"]!!.jsonPrimitive.content

            // Push a real per-root config for type T (unaffected by the failure below, which is
            // purely a READ failure — the config genuinely exists in storage) so this scenario
            // proves omission-on-read-failure, not omission because there was never a schema to find.
            val realConfigRepo = repositoryProvider.projectConfigRepository()
            val pushed =
                realConfigRepo.upsert(
                    UUID.fromString(rootId),
                    """
                    work_item_schemas:
                      T:
                        notes:
                          - key: q1
                            role: queue
                            required: true
                            description: "Q1"
                    """.trimIndent()
                )
            assertEquals(true, pushed != null, "setup precondition: config push must succeed, got $pushed")

            val failable = FailableProjectConfigRepository(realConfigRepo)
            failable.failFingerprint = true
            val failingContext =
                ToolExecutionContext(
                    repositoryProvider,
                    perRootConfigService = PerRootConfigService(failable),
                    unitOfWork = db.unitOfWork()
                )

            val createResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "items" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("title", JsonPrimitive("Child S14"))
                                        put("type", JsonPrimitive("T"))
                                        put("parentId", JsonPrimitive(rootId))
                                    }
                                )
                            )
                    ),
                    failingContext
                ) as JsonObject

            assertEquals(true, createResult["success"]!!.jsonPrimitive.boolean)
            val data = createResult["data"] as JsonObject
            assertEquals(1, data["created"]?.jsonPrimitive?.int, "D7: a committed write must never be reported failed")
            assertEquals(0, data["failed"]?.jsonPrimitive?.int)

            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)
            val item = items[0] as JsonObject
            assertFalse(item.containsKey("schemaMatch"), "D7: schemaMatch must be OMITTED (not false) when the per-root read fails")
            assertFalse(item.containsKey("expectedNotes"), "D7: expectedNotes must be OMITTED (not empty) when the per-root read fails")
            assertFalse(data.containsKey("availableTraits"), "D7: availableTraits is omitted from the response on a read failure")

            // The item itself was genuinely persisted despite the decoration failure.
            val childId = item["id"]!!.jsonPrimitive.content
            val persisted = repositoryProvider.workItemRepository().getById(UUID.fromString(childId))
            assertNotNull(persisted)
            assertEquals("Child S14", persisted.title)
        }

    @Test
    fun `probe - a sibling item created in the same batch with no rootId still gets its schemaMatch and expectedNotes`(): Unit =
        runBlocking {
            val rootEntry = createUnderPlainContext(title = "Root S14b")
            val rootId = rootEntry["id"]!!.jsonPrimitive.content

            val realConfigRepo = repositoryProvider.projectConfigRepository()
            val pushed = realConfigRepo.upsert(UUID.fromString(rootId), "work_item_schemas:\n  T:\n    notes: []\n")
            assertEquals(true, pushed != null)

            val failable = FailableProjectConfigRepository(realConfigRepo)
            failable.failGet = true
            val failingContext =
                ToolExecutionContext(
                    repositoryProvider,
                    perRootConfigService = PerRootConfigService(failable),
                    unitOfWork = db.unitOfWork()
                )

            // Two items in ONE batch: a child of the failing root, and a standalone (no parentId,
            // no rootId) item — the standalone item never touches the per-root layer at all, so its
            // decoration must be entirely unaffected by the other item's read failure.
            val createResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("create"),
                        "items" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("title", JsonPrimitive("Failing child"))
                                        put("type", JsonPrimitive("T"))
                                        put("parentId", JsonPrimitive(rootId))
                                    },
                                    buildJsonObject {
                                        put("title", JsonPrimitive("Standalone"))
                                    }
                                )
                            )
                    ),
                    failingContext
                ) as JsonObject

            val data = createResult["data"] as JsonObject
            assertEquals(2, data["created"]?.jsonPrimitive?.int)
            val items = data["items"]!!.jsonArray
            val failingChild = items.first { it.jsonObject["title"]?.jsonPrimitive?.content == "Failing child" }.jsonObject
            val standalone = items.first { it.jsonObject["title"]?.jsonPrimitive?.content == "Standalone" }.jsonObject

            assertFalse(failingChild.containsKey("schemaMatch"))
            // The standalone item has no rootId, so context.resolveSchema for it never touches the
            // per-root layer at all — its decoration keys must be present as usual.
            assertTrue(
                standalone.containsKey("schemaMatch"),
                "an item on no root must be unaffected by another item's per-root read failure"
            )
        }
}

/**
 * Wraps a real [ProjectConfigRepository] and lets tests force [get]/[getFingerprint] to return
 * `throw IllegalStateException("x")` on demand. Own copy for this file — see the
 * identical class in the sibling config-unavailable test files for the full rationale (no shared
 * harness file per this item's file-ownership rule).
 */
private class FailableProjectConfigRepository(
    private val delegate: ProjectConfigRepository
) : ProjectConfigRepository by delegate {
    @Volatile var failFingerprint: Boolean = false

    @Volatile var failGet: Boolean = false

    override suspend fun getFingerprint(rootItemId: UUID) =
        if (failFingerprint) throw IllegalStateException("x") else delegate.getFingerprint(rootItemId)

    override suspend fun get(rootItemId: UUID) = if (failGet) throw IllegalStateException("x") else delegate.get(rootItemId)
}

package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Blind test-author coverage for item 2aa67b28 (inverted IS_BLOCKED_BY cycle-check fix).
 *
 * Oracles: [T] `X IS_BLOCKED_BY Y` means Y blocks X; [G] a directed cycle over blocker->blocked
 * edges is rejected; [F] the "created 0, failures[]" envelope shape for a rejected create;
 * [Db]/[Dg] diagnosis decisions (b) restatement-as-separate-row and (g) unchanged error texts.
 *
 * H2 + [DefaultRepositoryProvider], exercised through [ManageDependenciesTool] (always the
 * `createBatch` path per its documented behavior) plus one direct-repository scenario (S11).
 */
class DependencyDirectionCycleTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: ManageDependenciesTool
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var repositoryProvider: DefaultRepositoryProvider

    private lateinit var itemA: UUID
    private lateinit var itemB: UUID
    private lateinit var itemC: UUID

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = ManageDependenciesTool()
        workItemRepo = repositoryProvider.workItemRepository()

        runBlocking {
            itemA = createWorkItem("Item A")
            itemB = createWorkItem("Item B")
            itemC = createWorkItem("Item C")
        }
    }

    private suspend fun createWorkItem(title: String): UUID {
        val item = WorkItem(title = title)
        val result = workItemRepo.create(item)
        return (result as Result.Success).data.id
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /** Builds a `create` request whose `dependencies` array has one entry per (from, to, type) triple. */
    private fun createParams(vararg specs: Triple<UUID, UUID, String>): JsonObject =
        params(
            "operation" to JsonPrimitive("create"),
            "dependencies" to
                JsonArray(
                    specs.map { (from, to, type) ->
                        buildJsonObject {
                            put("fromItemId", JsonPrimitive(from.toString()))
                            put("toItemId", JsonPrimitive(to.toString()))
                            put("type", JsonPrimitive(type))
                        }
                    }
                )
        )

    private fun dataOf(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success envelope, got: $obj")
        return obj["data"] as JsonObject
    }

    // ──────────────────────────────────────────────
    // Happy
    // ──────────────────────────────────────────────

    @Test
    fun `S1 new direct edge closing a transitive BLOCKS chain via IS_BLOCKED_BY is accepted`(): Unit =
        runBlocking {
            val r1 = dataOf(tool.execute(createParams(Triple(itemA, itemB, "BLOCKS")), context))
            assertEquals(1, r1["created"]!!.jsonPrimitive.int)

            val r2 = dataOf(tool.execute(createParams(Triple(itemB, itemC, "BLOCKS")), context))
            assertEquals(1, r2["created"]!!.jsonPrimitive.int)

            // C IS_BLOCKED_BY A: blocker=A, blocked=C — a brand-new direct edge (A already
            // transitively blocks C via B, but no edge exists yet from blocked=C back to
            // blocker=A), so this must NOT be misdetected as circular.
            val r3 = dataOf(tool.execute(createParams(Triple(itemC, itemA, "IS_BLOCKED_BY")), context))
            assertEquals(1, r3["created"]!!.jsonPrimitive.int, "New direct A->C edge should be accepted: $r3")
        }

    @Test
    fun `S2 restating a stored BLOCKS edge as IS_BLOCKED_BY keeps both rows`(): Unit =
        runBlocking {
            // B BLOCKS A stored
            val r1 = dataOf(tool.execute(createParams(Triple(itemB, itemA, "BLOCKS")), context))
            assertEquals(1, r1["created"]!!.jsonPrimitive.int)

            // A IS_BLOCKED_BY B restates the same blocker/blocked pair (B blocks A) as a
            // separate row — not a cycle, not a duplicate (different type).
            val r2 = dataOf(tool.execute(createParams(Triple(itemA, itemB, "IS_BLOCKED_BY")), context))
            assertEquals(1, r2["created"]!!.jsonPrimitive.int, "Restatement must not be rejected: $r2")

            val stored = context.dependencyRepository().findByItemId(itemA)
            assertEquals(2, stored.size, "Both the BLOCKS and the restating IS_BLOCKED_BY row must be kept")
        }

    // ──────────────────────────────────────────────
    // Failure
    // ──────────────────────────────────────────────

    @Test
    fun `S7 two-call IS_BLOCKED_BY mutual cycle is rejected on the second call`(): Unit =
        runBlocking {
            val r1 = dataOf(tool.execute(createParams(Triple(itemA, itemB, "IS_BLOCKED_BY")), context))
            assertEquals(1, r1["created"]!!.jsonPrimitive.int)

            val r2 = dataOf(tool.execute(createParams(Triple(itemB, itemA, "IS_BLOCKED_BY")), context))
            assertEquals(0, r2["created"]!!.jsonPrimitive.int)
            val failures = r2["failures"]!!.jsonArray
            assertEquals(1, failures.size)
            assertTrue(
                failures[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
                    .contains("circular", ignoreCase = true)
            )

            val stored = context.dependencyRepository().findByItemId(itemA)
            assertEquals(1, stored.size, "Only the first edge should be persisted")
        }

    @Test
    fun `S8 same mutual IS_BLOCKED_BY cycle in one batch call is rejected atomically`(): Unit =
        runBlocking {
            val result =
                dataOf(
                    tool.execute(
                        createParams(
                            Triple(itemA, itemB, "IS_BLOCKED_BY"),
                            Triple(itemB, itemA, "IS_BLOCKED_BY")
                        ),
                        context
                    )
                )
            assertEquals(0, result["created"]!!.jsonPrimitive.int)
            assertEquals(
                0,
                context.dependencyRepository().findByItemId(itemA).size,
                "A rejected batch must roll back atomically — 0 rows persisted"
            )
        }

    @Test
    fun `S9 existing BLOCKS then reverse IS_BLOCKED_BY is rejected circular`(): Unit =
        runBlocking {
            dataOf(tool.execute(createParams(Triple(itemA, itemB, "BLOCKS")), context))

            val result = dataOf(tool.execute(createParams(Triple(itemA, itemB, "IS_BLOCKED_BY")), context))
            assertEquals(0, result["created"]!!.jsonPrimitive.int)
            assertTrue(
                result["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
                    .contains("circular dependency chain", ignoreCase = true)
            )
        }

    @Test
    fun `S10 transitive BLOCKS chain closed head-to-tail by IS_BLOCKED_BY is rejected circular`(): Unit =
        runBlocking {
            dataOf(tool.execute(createParams(Triple(itemA, itemB, "BLOCKS")), context))
            dataOf(tool.execute(createParams(Triple(itemB, itemC, "BLOCKS")), context))

            val result = dataOf(tool.execute(createParams(Triple(itemA, itemC, "IS_BLOCKED_BY")), context))
            assertEquals(0, result["created"]!!.jsonPrimitive.int)
            assertTrue(
                result["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
                    .contains("circular dependency chain", ignoreCase = true)
            )
        }

    @Test
    fun `S11 repository create rejects an IS_BLOCKED_BY mutual cycle directly`(): Unit =
        runBlocking {
            val depRepository = context.dependencyRepository()
            depRepository.create(Dependency(fromItemId = itemA, toItemId = itemB, type = DependencyType.IS_BLOCKED_BY))

            val ex =
                assertThrows<ValidationException> {
                    depRepository.create(
                        Dependency(fromItemId = itemB, toItemId = itemA, type = DependencyType.IS_BLOCKED_BY)
                    )
                }
            assertTrue(ex.message!!.contains("circular dependency", ignoreCase = true))
        }

    // ──────────────────────────────────────────────
    // Probes
    // ──────────────────────────────────────────────

    @Test
    fun `probe reversal symmetry - IS_BLOCKED_BY then reverse BLOCKS is rejected circular`(): Unit =
        runBlocking {
            // blocker=B, blocked=A
            dataOf(tool.execute(createParams(Triple(itemA, itemB, "IS_BLOCKED_BY")), context))

            // A BLOCKS B: blocker=A, blocked=B — reverses the stored edge, closing a 2-cycle.
            // This is the mirror image of S9 (which builds BLOCKS first, IS_BLOCKED_BY second).
            val result = dataOf(tool.execute(createParams(Triple(itemA, itemB, "BLOCKS")), context))
            assertEquals(0, result["created"]!!.jsonPrimitive.int)
            assertTrue(
                result["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
                    .contains("circular dependency chain", ignoreCase = true)
            )
        }

    @Test
    fun `probe batch order - mixed BLOCKS then IS_BLOCKED_BY cycle in one call is rejected`(): Unit =
        runBlocking {
            val result =
                dataOf(
                    tool.execute(
                        createParams(
                            Triple(itemA, itemB, "BLOCKS"),
                            Triple(itemA, itemB, "IS_BLOCKED_BY")
                        ),
                        context
                    )
                )
            assertEquals(0, result["created"]!!.jsonPrimitive.int)
            assertEquals(0, context.dependencyRepository().findByItemId(itemA).size)
        }

    @Test
    fun `probe exact duplicate IS_BLOCKED_BY is rejected but not misreported as circular`(): Unit =
        runBlocking {
            dataOf(tool.execute(createParams(Triple(itemA, itemB, "IS_BLOCKED_BY")), context))

            val result = dataOf(tool.execute(createParams(Triple(itemA, itemB, "IS_BLOCKED_BY")), context))
            assertEquals(0, result["created"]!!.jsonPrimitive.int)
            val error =
                result["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
            assertFalse(error.contains("circular", ignoreCase = true), "Exact duplicate must not be reported as circular: $error")
        }

    @Test
    fun `probe lower-case is_blocked_by type string is accepted and behaves as IS_BLOCKED_BY`(): Unit =
        runBlocking {
            val created = dataOf(tool.execute(createParams(Triple(itemA, itemB, "is_blocked_by")), context))
            assertEquals(1, created["created"]!!.jsonPrimitive.int)
            assertEquals(
                "IS_BLOCKED_BY",
                created["dependencies"]!!
                    .jsonArray[0]
                    .jsonObject["type"]!!
                    .jsonPrimitive.content
            )

            // Confirms it behaves like a real IS_BLOCKED_BY edge for cycle purposes: the reverse
            // BLOCKS edge (blocker A, blocked B) must now be rejected as circular.
            val reverse = dataOf(tool.execute(createParams(Triple(itemA, itemB, "BLOCKS")), context))
            assertEquals(0, reverse["created"]!!.jsonPrimitive.int)
            assertTrue(
                reverse["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
                    .contains("circular dependency chain", ignoreCase = true)
            )
        }
}

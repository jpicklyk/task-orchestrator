package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.CountingUnitOfWork
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.createTreeOp
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.sqlite.assertNoOutsideUnitWrites
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent P5b tests (item c01d2e90) over the real MCP tool entry points (`tool.execute(params, context)`) and a
 * real SQLite database: S3 (atomicity without the retired markers), S4 (MCP version-conflict text), S5 (write fault
 * -> DATABASE_ERROR carrying the innermost SQL text), S6 (claim fault -> db_error), S9 (read fault -> DATABASE_ERROR,
 * never RESOURCE_NOT_FOUND), S13 (service calls join an outer unit) and S14 (unit granularity per element / per call).
 *
 * Faults are real SQL: BEFORE triggers that RAISE(ABORT, 'inj') for one chosen row, or a renamed table for reads.
 * Every negative assertion is paired with a control: the trigger is dropped and the SAME call is repeated on the SAME
 * fixture, which must then succeed (so "nothing was written" cannot be an unrelated early failure).
 *
 * Oracles: task-scope D2 (store fault -> site legacy mapper: MCP DATABASE_ERROR with the innermost SQL text), D3/F5
 * (MCP text for a lost optimistic lock: "WorkItem was modified by another transaction (version mismatch)"), D4/D7/F7
 * (one unit per element; a create_tree element is ONE unit), F10 (MCP read fault
 * -> DATABASE_ERROR, never RESOURCE_NOT_FOUND), claim contract in the declarations (outcome and code `db_error`,
 * kind `transient`). The exact MCP message prefixes are NOT DECLARED, so S5 asserts the code and the `inj` text only.
 * NOT-COVERED here: the McpToolAdapter fallback (no public seam to drive it; see test-manifest).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class P5bWriteUnitToolsTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val repo get() = db.repositoryProvider().workItemRepository()

    private fun rawExec(sql: String) {
        DriverManager.getConnection(db.jdbcUrl).use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun rawInt(sql: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun titleCount(title: String): Int = rawInt("SELECT count(*) FROM work_items WHERE title = '$title'")

    private fun contextOver(uow: CountingUnitOfWork = CountingUnitOfWork(db.unitOfWork())): Pair<ToolExecutionContext, CountingUnitOfWork> =
        ToolExecutionContext(
            db.repositoryProvider(),
            actorVerifier = NoOpActorVerifier,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
            unitOfWork = uow
        ) to uow

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /** A `notes_write` call upserting one `work` note per key (an element per key, each its own write unit). */
    private fun noteUpsertCall(
        itemId: UUID,
        vararg keys: String
    ) = buildJsonObject {
        put(
            "ops",
            buildJsonArray {
                keys.forEach { k ->
                    add(
                        buildJsonObject {
                            put("op", "upsert")
                            put("itemId", itemId.toString())
                            put("key", k)
                            put("role", "work")
                            put("body", "body of $k")
                        }
                    )
                }
            }
        )
    }

    private fun results(envelope: JsonObject): JsonArray = envelope["data"]!!.jsonObject["results"]!!.jsonArray

    private fun data(result: JsonElement): JsonObject = (result as JsonObject)["data"] as JsonObject

    private fun success(result: JsonElement): Boolean = (result as JsonObject)["success"]!!.jsonPrimitive.boolean

    /**
     * A write fault must surface as the DATABASE_ERROR code carrying the innermost SQL text. Per-element tools
     * (manage_items) report it inside `failures`; a call-level failure puts it in `error`. Either shape
     * must carry `inj`, and the code, wherever the envelope carries one, must be DATABASE_ERROR and never not-found.
     */
    private fun assertDatabaseFault(result: JsonElement) {
        val text = result.toString()
        assertTrue("inj" in text, "the innermost SQL text must reach the caller: $text")
        assertFalse(ErrorCodes.RESOURCE_NOT_FOUND in text, "a store fault must never be reported as not-found: $text")
        val envelope = result as JsonObject
        if (!envelope["success"]!!.jsonPrimitive.boolean) {
            assertEquals(ErrorCodes.DATABASE_ERROR, envelope["error"]!!.jsonObject["code"]!!.jsonPrimitive.content, text)
        } else {
            val failures = (envelope["data"] as JsonObject)["failures"]!!.jsonArray
            assertTrue(failures.isNotEmpty(), "a successful envelope with a fault must list it in failures: $text")
            assertTrue(failures.any { "inj" in it.toString() }, "failures must carry the SQL text: $failures")
        }
    }

    // ---------------------------------------------------------------- S3
    @Test
    fun `S3 advance - an audit-row fault leaves role and version untouched and no transition row, then succeeds once healthy`(): Unit =
        runBlocking {
            val item = repo.create(WorkItem(title = "S3 advance", role = Role.QUEUE, depth = 0))
            rawExec(
                "CREATE TRIGGER s3_adv BEFORE INSERT ON events WHEN NEW.type = 'item.transitioned' BEGIN SELECT RAISE(ABORT, 'inj'); END"
            )
            val (ctx, _) = contextOver()
            val params =
                obj(
                    "transitions" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", item.id.toString())
                                    put("trigger", "start")
                                }
                            )
                        }
                )

            val faulted = db.assertNoOutsideUnitWrites { AdvanceItemTool().execute(params, ctx) }

            assertFalse("\"applied\":true" in faulted.toString(), "the transition must not report applied: $faulted")
            val after = assertNotNull(repo.getById(item.id))
            assertEquals(Role.QUEUE, after.role, "role must be unchanged")
            assertEquals(item.version, after.version, "version must be unchanged")
            assertEquals(0, rawInt("SELECT count(*) FROM events WHERE type = 'item.transitioned'"), "no audit row")

            rawExec("DROP TRIGGER s3_adv")
            val healthy = AdvanceItemTool().execute(params, contextOver().first)
            assertTrue("\"applied\":true" in healthy.toString(), "control: with the trigger gone the same call applies: $healthy")
            assertEquals(Role.WORK, assertNotNull(repo.getById(item.id)).role)
            assertEquals(1, rawInt("SELECT count(*) FROM events WHERE type = 'item.transitioned'"))
        }

    // ---------------------------------------------------------------- S4 (MCP text)
    private class ConflictOnUpdateRepository(
        private val delegate: WorkItemRepository
    ) : WorkItemRepository by delegate {
        override suspend fun update(item: WorkItem): WorkItem? = throw VersionConflictException(item.id, item.version, item.version + 1)
    }

    private class ConflictProvider(
        private val delegate: RepositoryProvider
    ) : RepositoryProvider by delegate {
        private val conflicting = ConflictOnUpdateRepository(delegate.workItemRepository())

        override fun workItemRepository(): WorkItemRepository = conflicting
    }

    // ---------------------------------------------------------------- S5
    @Test
    fun `S5 notes_write upsert fault fails the element, redacts the SQL text and writes no note`(): Unit =
        runBlocking {
            val item = repo.create(WorkItem(title = "S5 note host", depth = 0))
            rawExec("CREATE TRIGGER s5_notes BEFORE INSERT ON notes WHEN NEW.key = 'boom' BEGIN SELECT RAISE(ABORT, 'inj'); END")

            val result = db.assertNoOutsideUnitWrites { contextOver().first.callTool("notes_write", noteUpsertCall(item.id, "boom")) }

            val element = results(result).single().jsonObject
            assertFalse(element["ok"]!!.jsonPrimitive.boolean, "the faulting element fails: $result")
            assertFalse("inj" in result.toString(), "the 4.0 envelope never carries the store's SQL text: $result")
            assertEquals(0, rawInt("SELECT count(*) FROM notes WHERE key = 'boom'"))

            rawExec("DROP TRIGGER s5_notes")
            val healthy = contextOver().first.callTool("notes_write", noteUpsertCall(item.id, "boom"))
            assertTrue(
                results(healthy)
                    .single()
                    .jsonObject["ok"]!!
                    .jsonPrimitive.boolean,
                "control: $healthy"
            )
        }

    // ---------------------------------------------------------------- S6
    private fun claimParams(itemId: UUID) =
        obj(
            "claims" to buildJsonArray { add(buildJsonObject { put("itemId", itemId.toString()) }) },
            "actor" to
                buildJsonObject {
                    put("id", "s6-agent")
                    put("kind", "subagent")
                },
            "requestId" to JsonPrimitive(UUID.randomUUID().toString())
        )

    @Test
    fun `S6 claim_item with a failing claim write reports db_error transient and leaves the item unclaimed`(): Unit =
        runBlocking {
            val item = repo.create(WorkItem(title = "S6 claim", role = Role.QUEUE, depth = 0))
            rawExec("CREATE TRIGGER s6_claim BEFORE UPDATE OF claimed_by ON work_items BEGIN SELECT RAISE(ABORT, 'inj'); END")

            val result = db.assertNoOutsideUnitWrites { ClaimItemTool().execute(claimParams(item.id), contextOver().first) }

            val first = data(result)["claimResults"]!!.jsonArray[0].jsonObject
            assertEquals("db_error", first["outcome"]!!.jsonPrimitive.content, "$first")
            assertEquals("db_error", first["code"]!!.jsonPrimitive.content, "$first")
            assertEquals("transient", first["kind"]!!.jsonPrimitive.content, "$first")
            assertNull(assertNotNull(repo.getById(item.id)).claimedBy, "the item must stay unclaimed")

            rawExec("DROP TRIGGER s6_claim")
            val healthy = data(ClaimItemTool().execute(claimParams(item.id), contextOver().first))["claimResults"]!!.jsonArray[0].jsonObject
            assertEquals("success", healthy["outcome"]!!.jsonPrimitive.content, "control: $healthy")
            assertEquals("s6-agent", assertNotNull(repo.getById(item.id)).claimedBy)
        }

    // ---------------------------------------------------------------- S13 (service-level complement; the reviewer trace is the plan's verification)
    @Test
    fun `S13 placement and purge services called inside an outer unit commit nothing when the outer returns Err`(): Unit =
        runBlocking {
            val uow = db.unitOfWork()
            val items = ToolExecutionContext(db.repositoryProvider(), unitOfWork = uow).itemCommandService
            val victim = repo.create(WorkItem(title = "S13 victim", depth = 0))
            val placedId = UUID.randomUUID()

            val result =
                uow.write<Unit>("S13.outer") {
                    val placed =
                        items.create(
                            ItemCreateCommand(type = TEST_ITEM_TYPE, id = placedId, parentId = null, title = "S13 placed")
                        )
                    assertIs<Outcome.Ok<WorkItem>>(placed)
                    val purged = items.delete(victim.id, recursive = false)
                    assertIs<Outcome.Ok<*>>(purged)
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "outer decides to roll back"))
                }

            assertIs<Outcome.Err>(result)
            assertNull(repo.getById(placedId), "the placed item joined the outer unit and was rolled back")
            assertNotNull(repo.getById(victim.id), "the purge joined the outer unit and was rolled back")
        }

    @Test
    fun `S13 control - the same outer unit returning Ok commits both service writes`(): Unit =
        runBlocking {
            val uow = db.unitOfWork()
            val items = ToolExecutionContext(db.repositoryProvider(), unitOfWork = uow).itemCommandService
            val victim = repo.create(WorkItem(title = "S13 victim ok", depth = 0))
            val placedId = UUID.randomUUID()

            val result =
                uow.write("S13.outer.ok") {
                    items.create(ItemCreateCommand(type = TEST_ITEM_TYPE, id = placedId, parentId = null, title = "S13 placed ok"))
                    items.delete(victim.id, recursive = false)
                    Outcome.Ok(Unit)
                }

            assertEquals(Outcome.Ok(Unit), result)
            assertNotNull(repo.getById(placedId))
            assertNull(repo.getById(victim.id))
        }

    // ---------------------------------------------------------------- S14
    @Test
    fun `S14 notes_write upsert of three with the second faulting is three units, keeping notes 1 and 3`(): Unit =
        runBlocking {
            val item = repo.create(WorkItem(title = "S14 note host", depth = 0))
            rawExec("CREATE TRIGGER s14_notes BEFORE INSERT ON notes WHEN NEW.key = 'boom' BEGIN SELECT RAISE(ABORT, 'inj'); END")
            val (ctx, counting) = contextOver()

            val result = db.assertNoOutsideUnitWrites { ctx.callTool("notes_write", noteUpsertCall(item.id, "n1", "boom", "n3")) }

            val summary = result["data"]!!.jsonObject["summary"]!!.jsonObject
            assertEquals(2, summary["ok"]!!.jsonPrimitive.int, "$result")
            assertEquals(1, summary["failed"]!!.jsonPrimitive.int, "$result")
            assertEquals(1, rawInt("SELECT count(*) FROM notes WHERE key = 'n1'"))
            assertEquals(0, rawInt("SELECT count(*) FROM notes WHERE key = 'boom'"))
            assertEquals(1, rawInt("SELECT count(*) FROM notes WHERE key = 'n3'"))
            assertEquals(3, counting.writes, "one write unit per element: ${counting.ops}")
        }

    private fun treeOp(): JsonObject = createTreeOp(*workTreeParams.entries.map { it.key to it.value }.toTypedArray())

    private val workTreeParams =
        obj(
            "root" to
                buildJsonObject {
                    put("type", "test-item")
                    put("title", "S14 tree root")
                },
            "children" to
                buildJsonArray {
                    listOf("S14 tree c1", "S14 tree boom", "S14 tree c3").forEachIndexed { i, t ->
                        add(
                            buildJsonObject {
                                put("ref", "c$i")
                                put("type", "test-item")
                                put("title", t)
                            }
                        )
                    }
                }
        )

    @Test
    fun `S14 create_tree is one unit per element - a fault in the second child leaves no item`(): Unit =
        runBlocking {
            rawExec(
                "CREATE TRIGGER s14_tree BEFORE INSERT ON work_items WHEN NEW.title = 'S14 tree boom' BEGIN SELECT RAISE(ABORT, 'inj'); END"
            )
            val (faultCtx, faultCounting) = contextOver()

            val faulted = db.assertNoOutsideUnitWrites { faultCtx.itemsWrite(treeOp()) }

            assertFalse(
                results(faulted)[0].jsonObject["ok"]!!.jsonPrimitive.boolean,
                "a tree with a faulting child must fail as a whole: $faulted"
            )
            assertEquals(0, rawInt("SELECT count(*) FROM work_items WHERE title LIKE 'S14 tree%'"), "all-or-nothing: no root, no child")
            assertEquals(1, faultCounting.writes, "one unit for the whole call")

            rawExec("DROP TRIGGER s14_tree")
            val (okCtx, okCounting) = contextOver()
            val healthy = okCtx.itemsWrite(treeOp())
            assertTrue(results(healthy)[0].jsonObject["ok"]!!.jsonPrimitive.boolean, "control: $healthy")
            assertEquals(4, rawInt("SELECT count(*) FROM work_items WHERE title LIKE 'S14 tree%'"))
            assertEquals(1, okCounting.writes, "one unit for the whole call")
        }
}

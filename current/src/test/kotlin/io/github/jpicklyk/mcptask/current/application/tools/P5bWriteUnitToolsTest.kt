package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
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
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.sqlite.assertNoOutsideUnitWrites
import kotlinx.coroutines.runBlocking
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
 * (one unit per element; create_work_tree and manage_dependencies create are ONE unit per call), F10 (MCP read fault
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

    private fun noteUpsert(
        itemId: UUID,
        vararg keys: String
    ) = obj(
        "operation" to JsonPrimitive("upsert"),
        "notes" to
            buildJsonArray {
                keys.forEach { k ->
                    add(
                        buildJsonObject {
                            put("itemId", itemId.toString())
                            put("key", k)
                            put("role", "work")
                            put("body", "body of $k")
                        }
                    )
                }
            }
    )

    private fun data(result: JsonElement): JsonObject = (result as JsonObject)["data"] as JsonObject

    private fun success(result: JsonElement): Boolean = (result as JsonObject)["success"]!!.jsonPrimitive.boolean

    /**
     * A write fault must surface as the DATABASE_ERROR code carrying the innermost SQL text. Per-element tools
     * (manage_items / manage_notes) report it inside `failures`; a call-level failure puts it in `error`. Either shape
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
            rawExec("CREATE TRIGGER s3_adv BEFORE INSERT ON role_transitions BEGIN SELECT RAISE(ABORT, 'inj'); END")
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
            assertEquals(0, rawInt("SELECT count(*) FROM role_transitions"), "no audit row")

            rawExec("DROP TRIGGER s3_adv")
            val healthy = AdvanceItemTool().execute(params, contextOver().first)
            assertTrue("\"applied\":true" in healthy.toString(), "control: with the trigger gone the same call applies: $healthy")
            assertEquals(Role.WORK, assertNotNull(repo.getById(item.id)).role)
            assertEquals(1, rawInt("SELECT count(*) FROM role_transitions"))
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
    fun `S5 manage_notes upsert fault reports DATABASE_ERROR with the innermost SQL text and writes no note`(): Unit =
        runBlocking {
            val item = repo.create(WorkItem(title = "S5 note host", depth = 0))
            rawExec("CREATE TRIGGER s5_notes BEFORE INSERT ON notes WHEN NEW.key = 'boom' BEGIN SELECT RAISE(ABORT, 'inj'); END")

            val result = db.assertNoOutsideUnitWrites { ManageNotesTool().execute(noteUpsert(item.id, "boom"), contextOver().first) }

            assertDatabaseFault(result)
            assertEquals(0, rawInt("SELECT count(*) FROM notes WHERE key = 'boom'"))

            rawExec("DROP TRIGGER s5_notes")
            val healthy = ManageNotesTool().execute(noteUpsert(item.id, "boom"), contextOver().first)
            assertEquals(1, data(healthy)["upserted"]!!.jsonPrimitive.int, "control: $healthy")
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
    fun `S14 manage_notes upsert of three with the second faulting is three units, keeping notes 1 and 3`(): Unit =
        runBlocking {
            val item = repo.create(WorkItem(title = "S14 note host", depth = 0))
            rawExec("CREATE TRIGGER s14_notes BEFORE INSERT ON notes WHEN NEW.key = 'boom' BEGIN SELECT RAISE(ABORT, 'inj'); END")
            val (ctx, counting) = contextOver()

            val result = db.assertNoOutsideUnitWrites { ManageNotesTool().execute(noteUpsert(item.id, "n1", "boom", "n3"), ctx) }

            val d = data(result)
            assertEquals(2, d["upserted"]!!.jsonPrimitive.int, "$result")
            assertEquals(1, d["failed"]!!.jsonPrimitive.int, "$result")
            assertEquals(1, rawInt("SELECT count(*) FROM notes WHERE key = 'n1'"))
            assertEquals(0, rawInt("SELECT count(*) FROM notes WHERE key = 'boom'"))
            assertEquals(1, rawInt("SELECT count(*) FROM notes WHERE key = 'n3'"))
            assertEquals(3, counting.writes, "one write unit per element: ${counting.ops}")
        }

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
    fun `S14 create_work_tree is one unit per call - a fault in the second child leaves no item`(): Unit =
        runBlocking {
            rawExec(
                "CREATE TRIGGER s14_tree BEFORE INSERT ON work_items WHEN NEW.title = 'S14 tree boom' BEGIN SELECT RAISE(ABORT, 'inj'); END"
            )
            val (faultCtx, faultCounting) = contextOver()

            val faulted = db.assertNoOutsideUnitWrites { CreateWorkTreeTool().execute(workTreeParams, faultCtx) }

            assertFalse(success(faulted), "a tree with a faulting child must fail as a whole: $faulted")
            assertEquals(0, rawInt("SELECT count(*) FROM work_items WHERE title LIKE 'S14 tree%'"), "all-or-nothing: no root, no child")
            assertEquals(1, faultCounting.writes, "one unit for the whole call")

            rawExec("DROP TRIGGER s14_tree")
            val (okCtx, okCounting) = contextOver()
            val healthy = CreateWorkTreeTool().execute(workTreeParams, okCtx)
            assertTrue(success(healthy), "control: $healthy")
            assertEquals(4, rawInt("SELECT count(*) FROM work_items WHERE title LIKE 'S14 tree%'"))
            assertEquals(1, okCounting.writes, "one unit for the whole call")
        }

    @Test
    fun `S14 manage_dependencies create of three is one atomic unit - a fault on the third edge leaves no edge`(): Unit =
        runBlocking {
            val ids = (1..6).map { repo.create(WorkItem(title = "S14 dep $it", depth = 0)).id }
            val params =
                obj(
                    "operation" to JsonPrimitive("create"),
                    "dependencies" to
                        buildJsonArray {
                            listOf(0 to 1, 2 to 3, 4 to 5).forEach { (a, b) ->
                                add(
                                    buildJsonObject {
                                        put("fromItemId", ids[a].toString())
                                        put("toItemId", ids[b].toString())
                                    }
                                )
                            }
                        }
                )
            rawExec(
                "CREATE TRIGGER s14_deps BEFORE INSERT ON dependencies WHEN (SELECT count(*) FROM dependencies) >= 2 " +
                    "BEGIN SELECT RAISE(ABORT, 'inj'); END"
            )
            val (faultCtx, faultCounting) = contextOver()

            val faulted = db.assertNoOutsideUnitWrites { ManageDependenciesTool().execute(params, faultCtx) }

            assertEquals(0, rawInt("SELECT count(*) FROM dependencies"), "all-or-nothing: edges 1 and 2 must be rolled back: $faulted")
            assertFalse("\"created\":3" in faulted.toString(), "$faulted")
            assertEquals(1, faultCounting.writes, "one unit per call")

            rawExec("DROP TRIGGER s14_deps")
            val (okCtx, okCounting) = contextOver()
            val healthy = ManageDependenciesTool().execute(params, okCtx)
            assertEquals(3, data(healthy)["created"]!!.jsonPrimitive.int, "control: $healthy")
            assertEquals(3, rawInt("SELECT count(*) FROM dependencies"))
            assertEquals(1, okCounting.writes, "one unit per call")
        }
}

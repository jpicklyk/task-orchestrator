package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test authorship for item a19fcf0a (P12, needs-test-author): `manage_dependencies` and
 * `query_dependencies` after dependency direction normalization. Scenarios S1, S2, S4, S5, S7, S8, S11, S18 of the
 * frozen test-plan, plus probes.
 *
 * Oracles (frozen plan 3.4 and task-scope, never the code): `a IS_BLOCKED_BY b` means b blocks a and is stored as
 * `b BLOCKS a` with the same unblockAt; two edges are duplicates when they share the normalized (from, to, type)
 * whatever their unblockAt; a restatement is a duplicate and never a cycle; a cycle over the blocker-to-blocked graph
 * is rejected; a rejected create stores nothing; `query_dependencies type=IS_BLOCKED_BY` is the blocked-side view
 * (BLOCKS rows whose toItemId is the queried item, read back as BLOCKS); delete-by-relationship with
 * type=IS_BLOCKED_BY removes the BLOCKS row from toItemId to fromItemId and with no type matches only stored rows from
 * fromItemId to toItemId; the topological chain lists a blocker before what it blocks.
 *
 * Response-shape evidence (public, not implementation): `ManageDependenciesToolTest` (created, failed, failures[].error,
 * dependencies[]), `QueryDependenciesToolTest` / `QueryDependenciesToolBacklinksTest` / `QueryDependenciesToolBoundsTest`
 * (dependencies[], backlinks[], counts, total), and `current/docs/api-reference.md` (delete response, write-policy order).
 *
 * EXISTING-SURFACE: every scenario goes through tool.execute with real SQLite, so a revert of the fix gives behavioral
 * red (an IS_BLOCKED_BY row stored, or an accepted restatement), not a compile failure.
 */
class DependencyNormalizationToolsTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var context: ToolExecutionContext
    private lateinit var manage: ManageDependenciesTool
    private lateinit var query: QueryDependenciesTool

    private lateinit var a: UUID
    private lateinit var b: UUID
    private lateinit var c: UUID
    private lateinit var d: UUID
    private lateinit var e: UUID

    @BeforeEach
    fun setUp() {
        val repositoryProvider = db.repositoryProvider()
        context = ToolExecutionContext(repositoryProvider, unitOfWork = db.unitOfWork())
        manage = ManageDependenciesTool()
        query = QueryDependenciesTool()
        runBlocking {
            a = item("A")
            b = item("B")
            c = item("C")
            d = item("D")
            e = item("E")
        }
    }

    private suspend fun item(title: String): UUID = context.workItemRepository().create(WorkItem(title = title)).id

    private fun p(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun depSpec(
        from: UUID,
        to: UUID,
        type: String,
        unblockAt: String? = null
    ): JsonObject =
        buildJsonObject {
            put("fromItemId", JsonPrimitive(from.toString()))
            put("toItemId", JsonPrimitive(to.toString()))
            put("type", JsonPrimitive(type))
            if (unblockAt != null) put("unblockAt", JsonPrimitive(unblockAt))
        }

    private suspend fun create(vararg specs: JsonObject): JsonObject {
        val result =
            manage.execute(
                p("operation" to JsonPrimitive("create"), "dependencies" to JsonArray(specs.toList())),
                context
            ) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected a success envelope, got: $result")
        return result["data"] as JsonObject
    }

    private suspend fun createPattern(vararg pairs: Pair<String, JsonElement>): JsonObject {
        val result = manage.execute(p("operation" to JsonPrimitive("create"), *pairs), context) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected a success envelope, got: $result")
        return result["data"] as JsonObject
    }

    private fun ids(vararg ids: UUID) = JsonArray(ids.map { JsonPrimitive(it.toString()) })

    private fun firstFailure(data: JsonObject): String =
        data["failures"]!!
            .jsonArray[0]
            .jsonObject["error"]!!
            .jsonPrimitive.content

    private suspend fun stored(vararg items: UUID): List<Dependency> =
        items
            .flatMap { context.dependencyRepository().findByItemId(it) }
            .distinctBy { it.id }

    private fun edge(dep: Dependency) = Triple(dep.fromItemId, dep.toItemId, dep.type)

    // ------------------------------------------------------------------
    // S1: IS_BLOCKED_BY input is stored as one swapped BLOCKS row
    // ------------------------------------------------------------------

    @Test
    fun `S1 IS_BLOCKED_BY input stores exactly one swapped BLOCKS row with the given unblockAt`(): Unit =
        runBlocking {
            val data = create(depSpec(a, b, "IS_BLOCKED_BY", unblockAt = "work"))

            assertEquals(1, data["created"]!!.jsonPrimitive.int)
            val resp = data["dependencies"]!!.jsonArray.single().jsonObject
            assertEquals(b.toString(), resp["fromItemId"]!!.jsonPrimitive.content, "b blocks a, so the stored from is b")
            assertEquals(a.toString(), resp["toItemId"]!!.jsonPrimitive.content)
            assertEquals("BLOCKS", resp["type"]!!.jsonPrimitive.content)
            assertEquals("work", resp["unblockAt"]!!.jsonPrimitive.content)

            val rows = stored(a, b)
            assertEquals(1, rows.size, "exactly one row, no twin: $rows")
            val row = rows.single()
            assertEquals(Triple(b, a, DependencyType.BLOCKS), edge(row))
            assertEquals("work", row.unblockAt)
            assertEquals(resp["id"]!!.jsonPrimitive.content, row.id.toString(), "the response lists the stored row")
        }

    @Test
    fun `S1 probe lower-case is_blocked_by type is normalized the same way`(): Unit =
        runBlocking {
            create(depSpec(a, b, "is_blocked_by"))

            val rows = stored(a, b)
            assertEquals(listOf(Triple(b, a, DependencyType.BLOCKS)), rows.map(::edge))
        }

    @Test
    fun `S1 probe unblockAt is kept absent when not given`(): Unit =
        runBlocking {
            val data = create(depSpec(a, b, "IS_BLOCKED_BY"))

            val resp = data["dependencies"]!!.jsonArray.single().jsonObject
            assertNull(resp["unblockAt"], "an unset threshold is omitted from the response: $resp")
            assertNull(stored(a, b).single().unblockAt)
        }

    // ------------------------------------------------------------------
    // S2: every create pattern stores swapped BLOCKS rows for IS_BLOCKED_BY
    // ------------------------------------------------------------------

    @Test
    fun `S2 fan-in pattern with IS_BLOCKED_BY swaps every row`(): Unit =
        runBlocking {
            // each of b, c, d "is blocked by" e, so e blocks each of them
            val data =
                createPattern(
                    "pattern" to JsonPrimitive("fan-in"),
                    "type" to JsonPrimitive("IS_BLOCKED_BY"),
                    "fromItemIds" to ids(b, c, d),
                    "toItemId" to JsonPrimitive(e.toString())
                )

            assertEquals(3, data["created"]!!.jsonPrimitive.int)
            val expected =
                setOf(
                    Triple(e, b, DependencyType.BLOCKS),
                    Triple(e, c, DependencyType.BLOCKS),
                    Triple(e, d, DependencyType.BLOCKS)
                )
            assertEquals(expected, stored(e).map(::edge).toSet())
            assertEquals(3, stored(e).size)
            val respEdges =
                data["dependencies"]!!
                    .jsonArray
                    .map {
                        Triple(
                            UUID.fromString(it.jsonObject["fromItemId"]!!.jsonPrimitive.content),
                            UUID.fromString(it.jsonObject["toItemId"]!!.jsonPrimitive.content),
                            DependencyType.valueOf(it.jsonObject["type"]!!.jsonPrimitive.content)
                        )
                    }.toSet()
            assertEquals(expected, respEdges, "the response lists the stored (normalized) rows")
        }

    @Test
    fun `S2 fan-out pattern with IS_BLOCKED_BY swaps every row`(): Unit =
        runBlocking {
            // a "is blocked by" each of b, c, so b and c each block a
            createPattern(
                "pattern" to JsonPrimitive("fan-out"),
                "type" to JsonPrimitive("IS_BLOCKED_BY"),
                "fromItemId" to JsonPrimitive(a.toString()),
                "toItemIds" to ids(b, c)
            )

            assertEquals(
                setOf(Triple(b, a, DependencyType.BLOCKS), Triple(c, a, DependencyType.BLOCKS)),
                stored(a).map(::edge).toSet()
            )
            assertEquals(2, stored(a).size)
        }

    @Test
    fun `S2 linear pattern with IS_BLOCKED_BY swaps every row`(): Unit =
        runBlocking {
            // a is blocked by b, b is blocked by c, so b blocks a and c blocks b
            createPattern(
                "pattern" to JsonPrimitive("linear"),
                "type" to JsonPrimitive("IS_BLOCKED_BY"),
                "itemIds" to ids(a, b, c)
            )

            assertEquals(
                setOf(Triple(b, a, DependencyType.BLOCKS), Triple(c, b, DependencyType.BLOCKS)),
                stored(a, b, c).map(::edge).toSet()
            )
            assertEquals(2, stored(a, b, c).size)
        }

    // ------------------------------------------------------------------
    // S7: a restatement is a duplicate, never a cycle, and stores nothing
    // ------------------------------------------------------------------

    @Test
    fun `S7 IS_BLOCKED_BY restating a stored BLOCKS row fails as a duplicate and keeps one row`(): Unit =
        runBlocking {
            create(depSpec(b, a, "BLOCKS"))

            val data = create(depSpec(a, b, "IS_BLOCKED_BY"))

            assertEquals(0, data["created"]!!.jsonPrimitive.int)
            val msg = firstFailure(data)
            assertTrue(msg.contains("already exists", ignoreCase = true), "expected the duplicate text, got: $msg")
            assertFalse(msg.contains("circular", ignoreCase = true), "a restatement is never a cycle: $msg")
            assertEquals(1, stored(a, b).size)
        }

    @Test
    fun `S7 BLOCKS restating a stored IS_BLOCKED_BY input fails as a duplicate`(): Unit =
        runBlocking {
            create(depSpec(a, b, "IS_BLOCKED_BY"))

            val data = create(depSpec(b, a, "BLOCKS"))

            assertEquals(0, data["created"]!!.jsonPrimitive.int)
            assertFalse(firstFailure(data).contains("circular", ignoreCase = true), "a restatement is not a cycle")
            assertEquals(1, stored(a, b).size)
        }

    @Test
    fun `S7 restatement within one batch is rejected in both orders and stores nothing`(): Unit =
        runBlocking {
            val blocksFirst = create(depSpec(b, a, "BLOCKS"), depSpec(a, b, "IS_BLOCKED_BY"))
            assertEquals(0, blocksFirst["created"]!!.jsonPrimitive.int)
            assertTrue(
                firstFailure(blocksFirst).contains("Duplicate dependency within batch", ignoreCase = true),
                "expected the within-batch duplicate text, got: ${firstFailure(blocksFirst)}"
            )
            assertEquals(0, stored(a, b).size, "a rejected batch stores nothing")

            val aliasFirst = create(depSpec(a, b, "IS_BLOCKED_BY"), depSpec(b, a, "BLOCKS"))
            assertEquals(0, aliasFirst["created"]!!.jsonPrimitive.int)
            assertTrue(
                firstFailure(aliasFirst).contains("Duplicate dependency within batch", ignoreCase = true),
                "expected the within-batch duplicate text, got: ${firstFailure(aliasFirst)}"
            )
            assertEquals(0, stored(a, b).size, "a rejected batch stores nothing")
        }

    @Test
    fun `S7 duplicates are decided without regard to unblockAt and the stored threshold is not upgraded`(): Unit =
        runBlocking {
            create(depSpec(b, a, "BLOCKS"))

            val data = create(depSpec(a, b, "IS_BLOCKED_BY", unblockAt = "work"))

            assertEquals(0, data["created"]!!.jsonPrimitive.int)
            val row = stored(a, b).single()
            assertNull(row.unblockAt, "the stored row keeps its original (unset) threshold")
        }

    @Test
    fun `S7 probe replaying the identical IS_BLOCKED_BY create is a duplicate`(): Unit =
        runBlocking {
            create(depSpec(a, b, "IS_BLOCKED_BY"))

            val replay = create(depSpec(a, b, "IS_BLOCKED_BY"))

            assertEquals(0, replay["created"]!!.jsonPrimitive.int)
            assertTrue(firstFailure(replay).contains("already exists", ignoreCase = true), firstFailure(replay))
            assertEquals(1, stored(a, b).size)
        }

    @Test
    fun `S7 a batch carrying a stored duplicate stores none of its other edges`(): Unit =
        runBlocking {
            create(depSpec(b, a, "BLOCKS"))

            val data = create(depSpec(c, d, "BLOCKS"), depSpec(a, b, "IS_BLOCKED_BY"))

            assertEquals(0, data["created"]!!.jsonPrimitive.int)
            assertEquals(0, stored(c, d).size, "the valid sibling edge must not be written")
        }

    // ------------------------------------------------------------------
    // S8: one cycle rule over normalized edges, mixed types included
    // ------------------------------------------------------------------

    @Test
    fun `S8 IS_BLOCKED_BY closing a BLOCKS chain is a cycle not a duplicate and C IS_BLOCKED_BY A is accepted`(): Unit =
        runBlocking {
            create(depSpec(a, b, "BLOCKS"))
            create(depSpec(b, c, "BLOCKS"))

            // a IS_BLOCKED_BY c means c blocks a, closing a -> b -> c -> a
            val closing = create(depSpec(a, c, "IS_BLOCKED_BY"))
            assertEquals(0, closing["created"]!!.jsonPrimitive.int)
            val msg = firstFailure(closing)
            assertTrue(msg.contains("circular dependency chain", ignoreCase = true), "expected the cycle text, got: $msg")
            assertFalse(msg.contains("already exists", ignoreCase = true), "a cycle is not a duplicate: $msg")
            assertEquals(2, stored(a, b, c).size, "nothing was added")

            // c IS_BLOCKED_BY a means a blocks c: a second path from a to c, no cycle
            val parallel = create(depSpec(c, a, "IS_BLOCKED_BY"))
            assertEquals(1, parallel["created"]!!.jsonPrimitive.int, "no cycle: $parallel")
            assertTrue(Triple(a, c, DependencyType.BLOCKS) in stored(a, b, c).map(::edge))
        }

    @Test
    fun `S8 cycle closed across the edges of one mixed-type batch is rejected atomically`(): Unit =
        runBlocking {
            // a blocks b (BLOCKS), b blocks c (as c IS_BLOCKED_BY b), c blocks a (BLOCKS): a three-edge cycle
            val data = create(depSpec(a, b, "BLOCKS"), depSpec(c, b, "IS_BLOCKED_BY"), depSpec(c, a, "BLOCKS"))

            assertEquals(0, data["created"]!!.jsonPrimitive.int)
            assertTrue(firstFailure(data).contains("circular", ignoreCase = true), firstFailure(data))
            assertEquals(0, stored(a, b, c).size)
        }

    @Test
    fun `S8 RELATES_TO never forms a cycle with a blocking edge`(): Unit =
        runBlocking {
            create(depSpec(a, b, "BLOCKS"))

            val data = create(depSpec(b, a, "RELATES_TO"))

            assertEquals(1, data["created"]!!.jsonPrimitive.int, "RELATES_TO has no blocking semantics: $data")
        }

    // ------------------------------------------------------------------
    // S4: query type=IS_BLOCKED_BY is the blocked-side view
    // ------------------------------------------------------------------

    /** d blocks a (incoming to a), a blocks b (outgoing from a), c relates to a. */
    private suspend fun seedBlockedSideGraph() {
        create(depSpec(d, a, "BLOCKS"))
        create(depSpec(a, b, "BLOCKS"))
        create(depSpec(c, a, "RELATES_TO"))
    }

    private suspend fun getDeps(vararg extra: Pair<String, JsonElement>): JsonObject {
        val result =
            query.execute(
                p("operation" to JsonPrimitive("get"), "itemId" to JsonPrimitive(a.toString()), *extra),
                context
            ) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected a success envelope, got: $result")
        return result["data"] as JsonObject
    }

    private fun JsonObject.depEdges(): List<Triple<String, String, String>> =
        this["dependencies"]!!.jsonArray.map {
            Triple(
                it.jsonObject["fromItemId"]!!.jsonPrimitive.content,
                it.jsonObject["toItemId"]!!.jsonPrimitive.content,
                it.jsonObject["type"]!!.jsonPrimitive.content
            )
        }

    @Test
    fun `S4 get with type IS_BLOCKED_BY and direction all returns only the BLOCKS rows into the item`(): Unit =
        runBlocking {
            seedBlockedSideGraph()

            val data = getDeps("type" to JsonPrimitive("IS_BLOCKED_BY"), "direction" to JsonPrimitive("all"))

            assertEquals(
                listOf(Triple(d.toString(), a.toString(), "BLOCKS")),
                data.depEdges(),
                "only the BLOCKS row whose toItemId is the queried item, read back as BLOCKS"
            )
        }

    @Test
    fun `S4 get with type IS_BLOCKED_BY defaults direction to all and matches direction incoming`(): Unit =
        runBlocking {
            seedBlockedSideGraph()

            val byDefault = getDeps("type" to JsonPrimitive("IS_BLOCKED_BY")).depEdges()
            val incoming = getDeps("type" to JsonPrimitive("IS_BLOCKED_BY"), "direction" to JsonPrimitive("incoming")).depEdges()

            assertEquals(1, byDefault.size)
            assertEquals(byDefault, incoming)
        }

    @Test
    fun `S4 backlinks with type IS_BLOCKED_BY returns the same rows as type BLOCKS`(): Unit =
        runBlocking {
            seedBlockedSideGraph()

            suspend fun backlinks(type: String): List<Pair<String, String>> {
                val result =
                    query.execute(
                        p(
                            "operation" to JsonPrimitive("backlinks"),
                            "itemId" to JsonPrimitive(a.toString()),
                            "type" to JsonPrimitive(type)
                        ),
                        context
                    ) as JsonObject
                assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected success, got: $result")
                return (result["data"] as JsonObject)["backlinks"]!!.jsonArray.map {
                    it.jsonObject["fromItemId"]!!.jsonPrimitive.content to it.jsonObject["type"]!!.jsonPrimitive.content
                }
            }

            val viaBlocked = backlinks("IS_BLOCKED_BY")
            val viaBlocks = backlinks("BLOCKS")

            assertEquals(listOf(d.toString() to "BLOCKS"), viaBlocks)
            assertEquals(viaBlocks, viaBlocked, "IS_BLOCKED_BY backlinks are the BLOCKS backlinks")
        }

    @Test
    fun `S4 probe paging with the IS_BLOCKED_BY filter counts the filtered set`(): Unit =
        runBlocking {
            // a has three incoming BLOCKS rows and one outgoing BLOCKS row
            create(depSpec(b, a, "BLOCKS"), depSpec(c, a, "BLOCKS"), depSpec(d, a, "BLOCKS"), depSpec(a, e, "BLOCKS"))

            val data =
                getDeps(
                    "type" to JsonPrimitive("IS_BLOCKED_BY"),
                    "limit" to JsonPrimitive(1),
                    "offset" to JsonPrimitive(0)
                )

            assertEquals(1, data["dependencies"]!!.jsonArray.size)
            assertEquals(3, data["total"]!!.jsonPrimitive.int, "total is the post-filter, pre-page count of blocked-side rows")
            assertEquals("BLOCKS", data.depEdges().single().third)
        }

    // ------------------------------------------------------------------
    // S11: type=IS_BLOCKED_BY with direction=outgoing is a validation error
    // ------------------------------------------------------------------

    /**
     * Runs the call the way the MCP adapter does (validateParams, then execute) and returns the error text, or null when
     * the call succeeded. A validation error surfaces either as a ToolValidationException or as a failure envelope; both
     * are the documented "validation error", success is not.
     */
    private suspend fun errorOf(params: JsonObject): String? =
        try {
            query.validateParams(params)
            val result = query.execute(params, context) as JsonObject
            if (result["success"]!!.jsonPrimitive.boolean) null else result["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        } catch (ex: ToolValidationException) {
            ex.message ?: ""
        }

    @Test
    fun `S11 get with type IS_BLOCKED_BY and direction outgoing is a validation error that suggests incoming`(): Unit =
        runBlocking {
            seedBlockedSideGraph()

            val error =
                errorOf(
                    p(
                        "operation" to JsonPrimitive("get"),
                        "itemId" to JsonPrimitive(a.toString()),
                        "type" to JsonPrimitive("IS_BLOCKED_BY"),
                        "direction" to JsonPrimitive("outgoing")
                    )
                )

            assertNotNull(error, "outgoing plus IS_BLOCKED_BY must not succeed")
            assertTrue(error.contains("incoming", ignoreCase = true), "the message suggests incoming, got: $error")
        }

    @Test
    fun `S11 probe direction outgoing with type BLOCKS is still valid`(): Unit =
        runBlocking {
            seedBlockedSideGraph()

            val data = getDeps("type" to JsonPrimitive("BLOCKS"), "direction" to JsonPrimitive("outgoing"))

            assertEquals(listOf(Triple(a.toString(), b.toString(), "BLOCKS")), data.depEdges())
        }

    // ------------------------------------------------------------------
    // S5: delete-by-relationship direction
    // ------------------------------------------------------------------

    private suspend fun delete(
        from: UUID,
        to: UUID,
        type: String? = null
    ): JsonObject {
        val args =
            buildList<Pair<String, JsonElement>> {
                add("operation" to JsonPrimitive("delete"))
                add("fromItemId" to JsonPrimitive(from.toString()))
                add("toItemId" to JsonPrimitive(to.toString()))
                if (type != null) add("type" to JsonPrimitive(type))
            }
        val result = manage.execute(p(*args.toTypedArray()), context) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected success, got: $result")
        return result["data"] as JsonObject
    }

    @Test
    fun `S5 delete with type IS_BLOCKED_BY removes the BLOCKS row from toItemId to fromItemId`(): Unit =
        runBlocking {
            create(depSpec(a, b, "IS_BLOCKED_BY")) // stored as b BLOCKS a

            val data = delete(from = a, to = b, type = "IS_BLOCKED_BY")

            assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(a.toString(), data["fromItemId"]!!.jsonPrimitive.content, "the response echoes the ids the caller gave")
            assertEquals(b.toString(), data["toItemId"]!!.jsonPrimitive.content)
            assertEquals(0, stored(a, b).size)
        }

    @Test
    fun `S5 delete with no type does not match the former IS_BLOCKED_BY row stored in the other direction`(): Unit =
        runBlocking {
            create(depSpec(a, b, "IS_BLOCKED_BY")) // stored as b BLOCKS a

            val data = delete(from = a, to = b)

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int, "no stored row runs from a to b")
            assertEquals(listOf(Triple(b, a, DependencyType.BLOCKS)), stored(a, b).map(::edge))
        }

    @Test
    fun `S5 delete with no type matches stored rows from fromItemId to toItemId of any type`(): Unit =
        runBlocking {
            create(depSpec(a, b, "BLOCKS"), depSpec(a, b, "RELATES_TO"))

            val data = delete(from = a, to = b)

            assertEquals(2, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(0, stored(a, b).size)
        }

    @Test
    fun `S5 probe delete with type RELATES_TO leaves the BLOCKS row`(): Unit =
        runBlocking {
            create(depSpec(a, b, "BLOCKS"), depSpec(a, b, "RELATES_TO"))

            val data = delete(from = a, to = b, type = "RELATES_TO")

            assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(listOf(Triple(a, b, DependencyType.BLOCKS)), stored(a, b).map(::edge))
        }

    @Test
    fun `S5 probe delete with type IS_BLOCKED_BY when only the same-direction BLOCKS row exists deletes nothing`(): Unit =
        runBlocking {
            create(depSpec(a, b, "BLOCKS"))

            val data = delete(from = a, to = b, type = "IS_BLOCKED_BY") // would remove b BLOCKS a, which is absent

            assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(listOf(Triple(a, b, DependencyType.BLOCKS)), stored(a, b).map(::edge))
        }

    // ------------------------------------------------------------------
    // S18: the query graph lists blockers before what they block
    // ------------------------------------------------------------------

    @Test
    fun `S18 neighborsOnly false over an IS_BLOCKED_BY chain lists every blocker before its blocked item`(): Unit =
        runBlocking {
            // a is blocked by b, b is blocked by c: c blocks b blocks a
            createPattern(
                "pattern" to JsonPrimitive("linear"),
                "type" to JsonPrimitive("IS_BLOCKED_BY"),
                "itemIds" to ids(a, b, c)
            )

            val result =
                query.execute(
                    p(
                        "operation" to JsonPrimitive("get"),
                        "itemId" to JsonPrimitive(b.toString()),
                        "neighborsOnly" to JsonPrimitive(false)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected success, got: $result")
            val graph = (result["data"] as JsonObject)["graph"] as JsonObject
            val chain = graph["chain"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf(c.toString(), b.toString(), a.toString()), chain, "blockers first: c, then b, then a")
            assertEquals(2, graph["depth"]!!.jsonPrimitive.int)
        }

    @Test
    fun `S18 probe a mixed BLOCKS and IS_BLOCKED_BY diamond keeps every blocker ahead of its blocked item`(): Unit =
        runBlocking {
            // a blocks b (BLOCKS), a blocks c (c IS_BLOCKED_BY a), b blocks d (BLOCKS), c blocks d (d IS_BLOCKED_BY c)
            create(
                depSpec(a, b, "BLOCKS"),
                depSpec(c, a, "IS_BLOCKED_BY"),
                depSpec(b, d, "BLOCKS"),
                depSpec(d, c, "IS_BLOCKED_BY")
            )

            val result =
                query.execute(
                    p(
                        "operation" to JsonPrimitive("get"),
                        "itemId" to JsonPrimitive(a.toString()),
                        "neighborsOnly" to JsonPrimitive(false)
                    ),
                    context
                ) as JsonObject

            val chain = ((result["data"] as JsonObject)["graph"] as JsonObject)["chain"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(setOf(a, b, c, d).map { it.toString() }.toSet(), chain.toSet())

            fun before(
                first: UUID,
                second: UUID
            ) = assertTrue(chain.indexOf(first.toString()) < chain.indexOf(second.toString()), "$first must precede $second in $chain")
            before(a, b)
            before(a, c)
            before(b, d)
            before(c, d)
        }

    // ------------------------------------------------------------------
    // Probes: self edge and unblockAt validation survive normalization
    // ------------------------------------------------------------------

    @Test
    fun `probe IS_BLOCKED_BY self-edge is rejected and nothing is stored`(): Unit =
        runBlocking {
            val data = create(depSpec(a, a, "IS_BLOCKED_BY"))

            assertEquals(0, data["created"]!!.jsonPrimitive.int)
            assertTrue(data["failures"]!!.jsonArray.isNotEmpty())
            assertEquals(0, stored(a).size)
        }

    @Test
    fun `probe IS_BLOCKED_BY with an invalid unblockAt is rejected and nothing is stored`(): Unit =
        runBlocking {
            val data = create(depSpec(a, b, "IS_BLOCKED_BY", unblockAt = "someday"))

            assertEquals(0, data["created"]!!.jsonPrimitive.int)
            assertEquals(0, stored(a, b).size)
        }
}

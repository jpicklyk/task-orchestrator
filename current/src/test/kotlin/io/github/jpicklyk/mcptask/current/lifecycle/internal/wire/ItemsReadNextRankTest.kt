package io.github.jpicklyk.mcptask.current.lifecycle.internal.wire

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.irApp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.titles
import io.github.jpicklyk.mcptask.current.model.item.Priority
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import io.github.jpicklyk.mcptask.current.test.dispatchAsTestActor
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.testToolDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 7ceedf39) for the sibling rank in `items_read` select `next` and in claim by
 * selector: scenarios S1, S2, S4, S5, S6, S7 and S13 of the frozen test-plan, plus the probes that apply to the order.
 *
 * Oracles: decision 0013 Decision (an integer rank orders `next` among items of equal priority, smaller first, unranked last,
 * ties allowed, never changes eligibility) and task-scope D1, D4 and D7 (priority, then rank ascending with unranked last,
 * then complexity ascending with nulls last, then `createdAt` ascending, then `id` ascending; `oldest` and `newest` ignore
 * rank; claim by selector takes the item `next` lists first; `features` carries `profileSets`, `rank`, `createTreeExistingDeps` in that order).
 * Every expected order below is written from those clauses, one key at a time, and never copied from an answer.
 *
 * Ambiguities: none left open. The plan's S6 phrase "blocked lists r1 as unranked" is read as: the `blocked` select still lists
 * the dependency-blocked ranked item and its compact entry carries no `rank` member (D3, the compact view never carries it).
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ItemsReadNextRankTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val registry = ToolRegistry(buildToolSpecs())

    private val t0: Instant = Instant.parse("2026-02-01T00:00:00Z")

    private val actor = """"actor":{"id":"agent-1","kind":"subagent"}"""

    private fun next(extra: String = "") = """{"select":"next","limit":200$extra}"""

    /** A UUID whose text sorts by [n]. */
    private fun uid(n: Int): UUID = UUID.fromString("%08x-0000-4000-8000-%012x".format(n, n))

    /**
     * Seeds a queue item through the undecorated repository. Depth and root derive from [parent] and the claim triple from one
     * expiry, so the domain invariants hold by construction.
     */
    private suspend fun EventLogRig.rk(
        title: String,
        priority: Priority = Priority.MEDIUM,
        rank: Int? = null,
        complexity: Int? = null,
        at: Instant = t0,
        id: UUID = UUID.randomUUID(),
        parent: WorkItem? = null,
        role: Role = Role.QUEUE,
        claimedBy: String? = null
    ): WorkItem {
        val claimExpiresAt = if (claimedBy != null) Instant.now().plusSeconds(3600) else null
        val claimedAt = claimExpiresAt?.minusSeconds(60)
        return raw.workItemRepository().create(
            WorkItem(
                id = id,
                title = title,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                rootId = parent?.let { it.rootId ?: it.id } ?: id,
                role = role,
                priority = priority,
                complexity = complexity,
                rank = rank,
                type = "test-item",
                createdAt = at,
                modifiedAt = at,
                roleChangedAt = at,
                claimedBy = claimedBy,
                claimedAt = claimedAt,
                claimExpiresAt = claimExpiresAt,
                originalClaimedAt = claimedAt
            )
        )
    }

    private suspend fun EventLogRig.advance(body: String): JsonObject {
        val spec = checkNotNull(registry["advance"]) { "advance is not registered" }
        return testToolDispatcher(ctx)
            .dispatchAsTestActor(spec, ToolProfile.ORCHESTRATION, Json.parseToJsonElement(body).jsonObject, "k7f3q9ab", "mcp")
            .envelope.body
    }

    private suspend fun EventLogRig.claimSelect(select: String): JsonObject =
        advance("""{$actor,"ops":[{"op":"claim","select":{$select}}]}""").okResult()

    private suspend fun EventLogRig.title(id: String): String = checkNotNull(raw.workItemRepository().getById(UUID.fromString(id))).title

    // ------------------------------------------------------------------ S1 / S2 / S4 / S5 order (AC6)

    @Test
    fun `S1 next orders by priority, then rank with unranked last, then complexity, by default and with orderBy priority`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.rk("A", Priority.HIGH, rank = 20, complexity = 1)
            rig.rk("B", Priority.HIGH, rank = 10, complexity = 9)
            rig.rk("C", Priority.HIGH, rank = null, complexity = 1)
            rig.rk("D", Priority.HIGH, rank = 10, complexity = 2)
            rig.rk("E", Priority.MEDIUM, rank = 1, complexity = 1)

            val default = select(next())
            val explicit = select(next(""","orderBy":"priority""""))

            assertEquals(200, default.status, default.raw)
            // High first: rank 10 (D before B by complexity 2 < 9), rank 20 (A), unranked (C); then the medium E whose rank 1
            // does not lift it over a higher priority.
            assertEquals(listOf("D", "B", "A", "C", "E"), default.items.titles(), "default order")
            assertEquals(listOf("D", "B", "A", "C", "E"), explicit.items.titles(), "orderBy priority is the default order")
            assertEquals(5, default.total)
        }

    @Test
    fun `S2 unranked items of equal priority and complexity come oldest first, not in id order`(): Unit =
        irApp(db.db, dir) { rig ->
            // Ids ascend opposite to creation: the latest-created item has the smallest id.
            rig.rk("late", complexity = 3, at = t0.plusSeconds(20), id = uid(1))
            rig.rk("early", complexity = 3, at = t0, id = uid(3))
            rig.rk("mid", complexity = 3, at = t0.plusSeconds(10), id = uid(2))

            val r = select(next())

            assertEquals(200, r.status, r.raw)
            assertEquals(listOf("early", "mid", "late"), r.items.titles(), "createdAt ascending, before id")
        }

    @Test
    fun `S4 equal priority orders ranks ascending across negative, zero and positive`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.rk("ten", rank = 10)
            rig.rk("minus5", rank = -5)
            rig.rk("zero", rank = 0)

            val r = select(next())

            assertEquals(listOf("minus5", "zero", "ten"), r.items.titles())
        }

    @Test
    fun `probe rank 0 is a rank, so it precedes an unranked item and a ranked one follows by value`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.rk("unranked", complexity = 1)
            rig.rk("zero", rank = 0, complexity = 10)
            rig.rk("one", rank = 1, complexity = 1)

            val r = select(next())

            assertEquals(listOf("zero", "one", "unranked"), r.items.titles(), "0 is not read as unranked")
        }

    @Test
    fun `probe the largest and smallest 32-bit ranks order by value and stay ahead of unranked`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.rk("max", rank = Int.MAX_VALUE)
            rig.rk("min", rank = Int.MIN_VALUE)
            rig.rk("none")

            val r = select(next())

            assertEquals(listOf("min", "max", "none"), r.items.titles())
        }

    @Test
    fun `probe equal ranks fall to complexity ascending with nulls last, then to creation order`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.rk("c5", rank = 7, complexity = 5, at = t0)
            rig.rk("cNull", rank = 7, complexity = null, at = t0)
            rig.rk("c2late", rank = 7, complexity = 2, at = t0.plusSeconds(5), id = uid(1))
            rig.rk("c2early", rank = 7, complexity = 2, at = t0, id = uid(2))

            val r = select(next())

            assertEquals(listOf("c2early", "c2late", "c5", "cNull"), r.items.titles())
        }

    @Test
    fun `S5 oldest and newest ignore rank`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.rk("unranked-old", at = t0)
            rig.rk("rank5-mid", rank = 5, at = t0.plusSeconds(10))
            rig.rk("rank1-new", rank = 1, at = t0.plusSeconds(20))

            val oldest = select(next(""","orderBy":"oldest""""))
            val newest = select(next(""","orderBy":"newest""""))

            assertEquals(listOf("unranked-old", "rank5-mid", "rank1-new"), oldest.items.titles(), "oldest first, rank 1 last")
            assertEquals(listOf("rank1-new", "rank5-mid", "unranked-old"), newest.items.titles(), "newest first, rank 1 first")
        }

    // ------------------------------------------------------------------ S6 rank never changes eligibility (AC7)

    @Test
    fun `S6 a ranked blocked item and a ranked claimed item stay out of next while the unranked low item is listed`(): Unit =
        irApp(db.db, dir) { rig ->
            val blocker = rig.rk("blocker", Priority.HIGH, role = Role.WORK)
            val blocked = rig.rk("r1-blocked", Priority.HIGH, rank = 1)
            rig.vwBlocks(blocker, blocked)
            rig.rk("r2-claimed", Priority.HIGH, rank = 2, claimedBy = "agent-x")
            rig.rk("low-unranked", Priority.LOW)

            val r = select(next())
            val blockedRead = select("""{"select":"blocked","limit":200}""")

            assertEquals(200, r.status, r.raw)
            assertEquals(listOf("low-unranked"), r.items.titles(), "rank adds no way past a dependency or a claim")
            assertEquals(1, r.total, "the total counts eligible items only")
            assertTrue("r1-blocked" in blockedRead.items.titles(), "blocked still lists it: ${blockedRead.items.titles()}")
            for (entry in blockedRead.items) assertFalse("rank" in entry, "the compact view never carries rank: $entry")
        }

    @Test
    fun `S6 control a ranked eligible item is listed ahead of the unranked low one and counted`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.rk("r3-free", Priority.HIGH, rank = 3)
            rig.rk("low-unranked", Priority.LOW)

            val r = select(next())

            assertEquals(listOf("r3-free", "low-unranked"), r.items.titles())
            assertEquals(2, r.total)
        }

    @Test
    fun `probe includeClaimed keeps rank order with the claimed item in its place`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.rk("unranked", Priority.HIGH)
            rig.rk("r2-claimed", Priority.HIGH, rank = 2, claimedBy = "agent-x")
            rig.rk("r1", Priority.HIGH, rank = 1)

            val r = select(next(""","includeClaimed":true"""))
            val without = select(next())

            assertEquals(listOf("r1", "r2-claimed", "unranked"), r.items.titles())
            assertEquals(listOf("r1", "unranked"), without.items.titles(), "control: the claimed item is hidden by default")
        }

    @Test
    fun `probe next entries never carry a rank member`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.rk("ranked", rank = 4)

            val r = select(next())

            assertEquals(listOf("ranked"), r.items.titles())
            for (entry in r.items) assertFalse("rank" in entry, "compact entries carry no rank: $entry")
        }

    // ------------------------------------------------------------------ S7 claim by selector (AC8)

    private suspend fun EventLogRig.seedSiblings(): WorkItem {
        val parent = rk("parent", Priority.MEDIUM)
        rk("rank30", Priority.MEDIUM, rank = 30, parent = parent)
        rk("rank10", Priority.MEDIUM, rank = 10, parent = parent)
        rk("rank20", Priority.MEDIUM, rank = 20, parent = parent)
        return parent
    }

    @Test
    fun `S7 claim by selector with a parent and the default order claims the lowest rank, then the next lowest`(): Unit =
        irApp(db.db, dir) { rig ->
            val parent = rig.seedSiblings()

            val first = rig.claimSelect(""""parentId":"${parent.id}"""")
            val second = rig.claimSelect(""""parentId":"${parent.id}"""")

            assertEquals(true, first["claimed"]?.jsonPrimitive?.content?.toBoolean(), "$first")
            assertEquals("rank10", rig.title(first.getValue("itemId").jsonPrimitive.content), "$first")
            assertEquals(
                "rank20",
                rig.title(second.getValue("itemId").jsonPrimitive.content),
                "the next claim continues the order: $second"
            )
        }

    @Test
    fun `S7 claim by selector with orderBy priority claims the lowest rank`(): Unit =
        irApp(db.db, dir) { rig ->
            val parent = rig.seedSiblings()

            val element = rig.claimSelect(""""parentId":"${parent.id}","orderBy":"priority"""")

            assertEquals(true, element["claimed"]?.jsonPrimitive?.content?.toBoolean(), "$element")
            assertEquals("rank10", rig.title(element.getValue("itemId").jsonPrimitive.content), "$element")
        }

    @Test
    fun `S7 claim by selector takes the item next lists first`(): Unit =
        irApp(db.db, dir) { rig ->
            val parent = rig.seedSiblings()
            val listed = select(next(""","parentId":"${parent.id}""""))

            val element = rig.claimSelect(""""parentId":"${parent.id}"""")

            assertEquals("rank10", listed.items.titles().first(), "fixture: next lists the lowest rank first")
            assertEquals(listed.items.titles().first(), rig.title(element.getValue("itemId").jsonPrimitive.content))
        }

    // ------------------------------------------------------------------ S13 features (AC9)

    private val declaredTypeYaml = "work_item_schemas:\n  test-item:\n    notes: []\n"

    private val expectedTail = listOf("profileSets", "rank", "createTreeExistingDeps")

    private fun List<String>.assertAdvertisesRank(where: String) {
        val at = indexOf("profileSets")
        assertTrue(at >= 0 && at + 3 <= size, "$where carries profileSets: $this")
        assertEquals(expectedTail, subList(at, at + 3), "$where carries profileSets, rank, createTreeExistingDeps in order: $this")
        assertEquals(size, distinct().size, "$where has no duplicate feature: $this")
    }

    @Test
    fun `S13 items_read schema and GET info advertise rank and createTreeExistingDeps in that order`(): Unit =
        // A declared type, so the schema answer is not the schema-free one (which carries no features).
        irApp(db.db, dir, yaml = declaredTypeYaml) { rig ->
            val item = rig.rk("anchor")

            val schema = select("""{"select":"schema","itemId":"${item.id}"}""")
            val info = get("/info")

            assertEquals(200, schema.status, schema.raw)
            assertNotNull(schema.data["features"], "items_read schema carries features: ${schema.raw}")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .assertAdvertisesRank("items_read schema")
            assertEquals(200, info.status, info.raw)
            val infoFeatures = assertNotNull(info.data["features"], "GET /api/v1/info carries features: ${info.raw}")
            infoFeatures.jsonArray.map { it.jsonPrimitive.content }.assertAdvertisesRank("GET /api/v1/info")
        }
}

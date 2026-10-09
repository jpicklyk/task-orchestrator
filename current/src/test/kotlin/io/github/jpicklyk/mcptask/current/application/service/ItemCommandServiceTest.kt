package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.MAX_TRAVERSAL_DEPTH
import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.str
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureWriteTestApp
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.P11_LEASE_KEY
import io.github.jpicklyk.mcptask.current.test.rawCount
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests for the item command service and the structural rules it carries (item 947f0230): placement on create
 * and reparent, one-statement subtree restamp, create-in-queue-only, the closed-parent rule, old-parent cascade
 * re-evaluation, event rows, and the work-tree unit. Everything runs through the real production composition
 * (`ServerComposition` via `EventLogRig`, config from the P11 schema fixture) over a real SQLite file.
 *
 * Oracles (frozen in the planning phase, none read from the implementation): [P] plan 3.3 / 3.5 / 3.7; [D] the task-scope
 * decisions D1-D11; [E] the event row catalog `EventCoverageTest` pins at the base commit; [W] the P3 child-completion
 * cascade warrant (completes under auto when every remaining child is terminal and the gate passes, suppressed with
 * gateBlocked when notes are missing, silent when no child remains or the lifecycle is manual / permanent, roleBlocked
 * for a BLOCKED parent, chains upward); [AC] the task-scope acceptance criteria. The MCP wire shape of `cascadeEvents`
 * (update: `items[].cascadeEvents`; delete: top-level `cascadeEvents`; omitted when empty) is taken from the public API
 * reference (`current/docs/api-reference.md`, manage_items), the only public statement of that shape.
 *
 * Scenario ids are the test-plan's: S1 S2 S3 S4 S5 S6 S7 S8 S9 S10 S11 S12 S13 (S14 is PlacementInvariantTest, S15 is
 * StoreWriteConfinementTest). Fixtures are created by the services under test where placement matters, so no row carries a
 * hand-set depth or root; fixtures that need a role other than queue set it directly on the stored row.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ItemCommandServiceTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun driver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))

    private val P11Driver.svc: ItemCommandService get() = rig.ctx.itemCommandService

    private val P11Driver.items get() = raw.workItemRepository()

    private suspend fun P11Driver.make(
        title: String,
        parent: WorkItem? = null,
        type: String? = null,
    ): WorkItem = svc.create(ItemCreateCommand(parentId = parent?.id, title = title, type = type, tags = type)).orFail()

    private fun <T> Outcome<T>.errorOrFail(): DomainError =
        when (this) {
            is Outcome.Err -> error
            is Outcome.Ok -> throw AssertionError("expected a rejection, got $value")
        }

    private fun List<EventRecord>.types() = map { it.type }

    private fun List<EventRecord>.ofType(type: String) = filter { it.type == type }

    private fun List<EventRecord>.assertContiguous(label: String) {
        assertTrue(isNotEmpty(), "$label: expected rows")
        val seqs = map { it.seq }
        assertEquals((seqs.first()..seqs.last()).toList(), seqs, "$label: one unit's rows must be contiguous and ascending")
    }

    private fun EventRecord.changed(): Set<String> = payload()["changedFields"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    private suspend fun P11Driver.count(table: String): Int = rawCount(jdbcUrl, "SELECT COUNT(*) FROM $table")

    /** The standard fixture. Depths: ra 0, a 1, a2 1, x 2, s 2, c1 3, c2 4; rb 0, b 1, b2 2. */
    private class Forest(
        val ra: WorkItem,
        val a: WorkItem,
        val a2: WorkItem,
        val x: WorkItem,
        val s: WorkItem,
        val c1: WorkItem,
        val c2: WorkItem,
        val rb: WorkItem,
        val b: WorkItem,
        val b2: WorkItem,
    ) {
        val all get() = listOf(ra, a, a2, x, s, c1, c2, rb, b, b2)
        val subtree get() = listOf(x, c1, c2)
        val others get() = all - subtree.toSet()
    }

    private suspend fun P11Driver.forest(): Forest {
        val ra = make("RA")
        val a = make("A", ra)
        val a2 = make("A2", ra)
        val x = make("X", a)
        val s = make("S", a)
        val c1 = make("c1", x)
        val c2 = make("c2", c1)
        val rb = make("RB")
        val b = make("B", rb)
        val b2 = make("B2", b)
        return Forest(ra, a, a2, x, s, c1, c2, rb, b, b2)
    }

    private suspend fun P11Driver.snapshot(items: List<WorkItem>): Map<UUID, WorkItem> = items.associate { it.id to reload(it) }

    // ---------------------------------------------------------------------------------------------
    // S1 -- placement on create, on every surface
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 service create under a depth 2 parent stamps depth 3 the parent root and queue and records one created row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val r = d.make("R")
            val a = d.make("A", r)
            val p = d.make("P", a)
            assertEquals(0, r.depth, "fixture: a root is depth 0")
            assertEquals(r.id, r.rootId, "fixture: a root is its own root")
            assertEquals(2, p.depth, "fixture: p sits at depth 2")
            assertEquals(r.id, p.rootId, "fixture: p belongs to root r")

            val (created, rows) = d.rig.written { d.svc.create(ItemCreateCommand(parentId = p.id, title = "C")).orFail() }

            assertEquals(3, created.depth)
            assertEquals(r.id, created.rootId)
            assertEquals(p.id, created.parentId)
            assertEquals(Role.QUEUE, created.role)
            val stored = d.reload(created)
            assertEquals(created.depth, stored.depth, "the returned item is the stored item")
            assertEquals(created.rootId, stored.rootId)
            assertEquals(created.parentId, stored.parentId)
            assertEquals(created.role, stored.role)
            val row = rows.single()
            assertEquals("item.created", row.type)
            assertEquals(created.id, row.entityId)
            assertEquals(r.id, row.rootId, "the created row is recorded under the item's root")
            assertEquals(p.id.toString(), row.str("parentId"))
        }

    @Test
    fun `S1 service create without a parent makes a depth 0 root that is its own root`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)

            val root = d.svc.create(ItemCreateCommand(parentId = null, title = "lone root")).orFail()

            assertEquals(0, root.depth)
            assertEquals(root.id, root.rootId)
            assertNull(root.parentId)
            assertEquals(Role.QUEUE, root.role)
        }

    @Test
    fun `S1 manage_items create and a hex prefix parent stamp the same placement`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val r = d.make("R")
            val p = d.make("P", d.make("A", r))

            val result =
                d.rig.callOk(
                    ManageItemsTool(),
                    "operation" to JsonPrimitive("create"),
                    "items" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("title", "by full id")
                                    put("parentId", p.id.toString())
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("title", "by prefix")
                                    put("parentId", p.id.toString().substring(0, 8))
                                }
                            )
                        },
                )

            val data = result["data"]!!.jsonObject
            assertEquals(2, data["created"]!!.jsonPrimitive.int, "$result")
            for (element in data["items"]!!.jsonArray) {
                val stored = d.reload(WorkItem(id = UUID.fromString(element.jsonObject["id"]!!.jsonPrimitive.content), title = "x"))
                assertEquals(3, stored.depth)
                assertEquals(r.id, stored.rootId)
                assertEquals(p.id, stored.parentId)
                assertEquals(Role.QUEUE, stored.role)
                assertEquals("queue", element.jsonObject["role"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun `S1 create_work_tree stamps placement in both modes`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val r = d.make("R")
            val p = d.make("P", d.make("A", r))

            val created =
                d.rig.callOk(
                    CreateWorkTreeTool(),
                    "root" to buildJsonObject { put("title", "tree root") },
                    "parentId" to JsonPrimitive(p.id.toString()),
                    "children" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", "k")
                                    put("title", "tree kid")
                                }
                            )
                        },
                )
            val createData = created["data"]!!.jsonObject
            val treeRoot =
                d.reload(
                    WorkItem(id = UUID.fromString(createData["root"]!!.jsonObject["id"]!!.jsonPrimitive.content), title = "x")
                )
            val kid =
                d.reload(
                    WorkItem(
                        id =
                            UUID.fromString(
                                createData["children"]!!
                                    .jsonArray[0]
                                    .jsonObject["id"]!!
                                    .jsonPrimitive.content
                            ),
                        title = "x"
                    )
                )
            assertEquals(3, treeRoot.depth, "create mode: the new root sits under p")
            assertEquals(r.id, treeRoot.rootId)
            assertEquals(4, kid.depth)
            assertEquals(r.id, kid.rootId)
            assertEquals(Role.QUEUE, treeRoot.role)
            assertEquals(Role.QUEUE, kid.role)

            val attached =
                d.rig.callOk(
                    CreateWorkTreeTool(),
                    "root" to buildJsonObject { put("id", p.id.toString()) },
                    "children" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", "k2")
                                    put("title", "attached kid")
                                }
                            )
                        },
                )
            val attachedKid =
                d.reload(
                    WorkItem(
                        id =
                            UUID.fromString(
                                attached["data"]!!
                                    .jsonObject["children"]!!
                                    .jsonArray[0]
                                    .jsonObject["id"]!!
                                    .jsonPrimitive.content
                            ),
                        title = "x",
                    ),
                )
            assertEquals(3, attachedKid.depth, "attach mode: the kid sits under the existing depth 2 root")
            assertEquals(r.id, attachedKid.rootId)
            assertEquals(p.id, attachedKid.parentId)
            assertEquals(
                2,
                attached["data"]!!
                    .jsonObject["root"]!!
                    .jsonObject["depth"]!!
                    .jsonPrimitive.int,
                "the reported root depth is the stored one"
            )
        }

    @Test
    fun `S1 AC2 a create command has no role depth or rootId to set`() {
        val fields =
            ItemCreateCommand::class.java.declaredFields
                .map { it.name }
                .toSet()
        assertFalse("role" in fields, "ItemCreateCommand must not carry a role: $fields")
        assertFalse("depth" in fields, "ItemCreateCommand must not carry a depth: $fields")
        assertFalse("rootId" in fields, "ItemCreateCommand must not carry a rootId: $fields")
        assertTrue(setOf("id", "parentId", "title").all { it in fields }, "fixture: the reflection sees the command's fields: $fields")
        val patchFields =
            ItemPatchCommand::class.java.declaredFields
                .map { it.name }
                .toSet()
        listOf("role", "depth", "rootId", "claimedBy").forEach {
            assertFalse(it in patchFields, "ItemPatchCommand must not carry $it: $patchFields")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // S2 / S3 / S13 -- reparent restamps the subtree in one statement
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 S3 reparent to another root restamps depth root version and time of every descendant and leaves every other row alone`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()
            val before = d.snapshot(f.all)
            delay(25)

            val (result, rows) = d.rig.written { d.svc.patch(patchOf(f.x, parent = ParentChange.MoveUnder(f.b2.id))).orFail() }

            assertTrue(result.reparented)
            assertEquals(2, result.descendantsRestamped)
            assertEquals(f.b2.depth + 1, result.item.depth)
            assertEquals(f.rb.id, result.item.rootId)
            assertEquals(f.b2.id, result.item.parentId)
            val x = d.reload(f.x)
            val c1 = d.reload(f.c1)
            val c2 = d.reload(f.c2)
            assertEquals(f.b2.depth + 1, x.depth)
            assertEquals(x.depth + 1, c1.depth)
            assertEquals(c1.depth + 1, c2.depth)
            listOf(x, c1, c2).forEach { assertEquals(f.rb.id, it.rootId, "${it.title} follows the new root") }
            listOf(f.c1 to c1, f.c2 to c2).forEach { (original, new) ->
                val old = before.getValue(original.id)
                assertEquals(old.version + 1, new.version, "${old.title}: a restamp bumps the version by one")
                assertTrue(new.modifiedAt > old.modifiedAt, "${old.title}: a restamp moves modifiedAt")
            }
            assertEquals(c1.modifiedAt, c2.modifiedAt, "every restamped row carries the one unit instant")
            val after = d.snapshot(f.others)
            f.others.forEach {
                assertEquals(before.getValue(it.id), after.getValue(it.id), "${it.title} is outside the moved subtree and must not change")
            }

            // S3: exactly two reparent rows plus one updated row per descendant, in one contiguous run.
            assertEquals(4, rows.size, "2 reparented + 2 descendant updated, nothing else: ${rows.types()}")
            val moved = rows.ofType("item.reparented")
            assertEquals(2, moved.size)
            val left = moved.single { it.str("side") == "left" }
            val entered = moved.single { it.str("side") == "entered" }
            assertEquals(f.ra.id, left.rootId)
            assertEquals(f.rb.id, entered.rootId)
            moved.forEach {
                assertEquals(f.x.id, it.entityId)
                assertEquals(f.a.id.toString(), it.str("fromParentId"))
                assertEquals(f.b2.id.toString(), it.str("toParentId"))
            }
            val updated = rows.ofType("item.updated")
            assertEquals(
                setOf(f.c1.id, f.c2.id),
                updated.map { it.entityId }.toSet(),
                "one updated row per descendant, none for the moved item"
            )
            updated.forEach {
                assertEquals(setOf("rootId", "depth"), it.changed(), "root and depth both changed for ${it.entityId}")
                assertTrue(it.rootId in setOf(f.ra.id, f.rb.id), "an updated row is recorded under the old or the new root, never another")
            }
            rows.assertContiguous("S3")
        }

    @Test
    fun `S2 move to root stamps depth 0 and the item as its own root across the subtree`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()

            val result = d.svc.patch(patchOf(f.x, parent = ParentChange.MoveToRoot)).orFail()

            assertTrue(result.reparented)
            assertEquals(2, result.descendantsRestamped)
            val x = d.reload(f.x)
            assertEquals(0, x.depth)
            assertEquals(x.id, x.rootId)
            assertNull(x.parentId)
            val c1 = d.reload(f.c1)
            val c2 = d.reload(f.c2)
            assertEquals(1, c1.depth)
            assertEquals(2, c2.depth)
            assertEquals(x.id, c1.rootId)
            assertEquals(x.id, c2.rootId)
        }

    @Test
    fun `S2 S3 a move to a shallower node in the same root changes depth only`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()

            val (result, rows) = d.rig.written { d.svc.patch(patchOf(f.x, parent = ParentChange.MoveUnder(f.ra.id))).orFail() }

            assertEquals(2, result.descendantsRestamped)
            assertEquals(1, d.reload(f.x).depth)
            assertEquals(2, d.reload(f.c1).depth)
            assertEquals(3, d.reload(f.c2).depth)
            listOf(f.x, f.c1, f.c2).forEach { assertEquals(f.ra.id, d.reload(it).rootId, "${it.title} stays in the same root") }
            val updated = rows.ofType("item.updated")
            assertEquals(setOf(f.c1.id, f.c2.id), updated.map { it.entityId }.toSet())
            updated.forEach { assertEquals(setOf("depth"), it.changed(), "only depth changed") }
            moved(rows).forEach { assertEquals(f.ra.id, it.rootId, "same root on both sides") }
        }

    private fun moved(rows: List<EventRecord>) = rows.ofType("item.reparented").also { assertEquals(2, it.size, "$it") }

    @Test
    fun `S3 a move to the same depth in another root changes root only`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()
            assertEquals(f.x.depth, f.b.depth + 1, "fixture: x under b keeps x's depth, so only the root differs")

            val (result, rows) = d.rig.written { d.svc.patch(patchOf(f.x, parent = ParentChange.MoveUnder(f.b.id))).orFail() }

            assertEquals(2, result.descendantsRestamped)
            listOf(f.c1, f.c2).forEach {
                val moved = d.reload(it)
                assertEquals(f.rb.id, moved.rootId)
                assertEquals(it.depth, moved.depth, "${it.title}: the depth is unchanged")
            }
            val updated = rows.ofType("item.updated")
            assertEquals(2, updated.size)
            updated.forEach { assertEquals(setOf("rootId"), it.changed()) }
        }

    @Test
    fun `S13 a reparent with no depth or root change restamps nothing and records no descendant rows`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()
            val before = d.snapshot(f.subtree.drop(1))
            delay(25)

            val (result, rows) = d.rig.written { d.svc.patch(patchOf(f.x, parent = ParentChange.MoveUnder(f.a2.id))).orFail() }

            assertTrue(result.reparented)
            assertEquals(0, result.descendantsRestamped, "same depth and same root: the restamp is skipped entirely")
            assertEquals(before, d.snapshot(f.subtree.drop(1)), "descendants keep version, modifiedAt, depth and root")
            assertEquals(listOf("item.reparented", "item.reparented"), rows.types(), "only the two reparent rows")
            assertEquals(f.a2.id, d.reload(f.x).parentId)
        }

    @Test
    fun `S13 a patch that keeps the parent restamps nothing and records one updated row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()
            val before = d.snapshot(f.subtree.drop(1))

            val (result, rows) = d.rig.written { d.svc.patch(patchOf(f.x, title = "X renamed")).orFail() }

            assertFalse(result.reparented)
            assertEquals(0, result.descendantsRestamped)
            assertEquals("X renamed", result.item.title)
            assertEquals(f.a.id, d.reload(f.x).parentId, "Keep leaves the parent alone")
            assertEquals(before, d.snapshot(f.subtree.drop(1)))
            val row = rows.single()
            assertEquals("item.updated", row.type)
            assertEquals(setOf("title"), row.changed())
        }

    @Test
    fun `S13 a patch that changes nothing records no row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()

            val (_, rows) = d.rig.written { d.svc.patch(patchOf(f.x)).orFail() }

            assertEquals(emptyList(), rows, "an unchanged patch records nothing")
        }

    // ---------------------------------------------------------------------------------------------
    // S12 -- patch failures: texts and codes, nothing written
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S12 a self parent a descendant cycle and a missing parent are rejected and nothing changes`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()
            val before = d.snapshot(f.all)
            val marker = d.rig.maxSeq()

            val self = d.svc.patch(patchOf(f.x, parent = ParentChange.MoveUnder(f.x.id))).errorOrFail()
            assertEquals(ErrorCode.INVALID_REQUEST, self.code)
            assertTrue(ItemCommandErrors.isSelfParent(self), "the error is classified as a self parent: $self")

            val cycle = d.svc.patch(patchOf(f.x, parent = ParentChange.MoveUnder(f.c2.id))).errorOrFail()
            assertEquals(ErrorCode.CYCLE_DETECTED, cycle.code)

            val missing = d.svc.patch(patchOf(f.x, parent = ParentChange.MoveUnder(UUID.randomUUID()))).errorOrFail()
            assertEquals(ErrorCode.NOT_FOUND, missing.code)

            val gone = d.svc.patch(patchOf(f.x).copy(itemId = UUID.randomUUID())).errorOrFail()
            assertEquals(ErrorCode.NOT_FOUND, gone.code)

            assertEquals(before, d.snapshot(f.all), "every rejection leaves every row untouched")
            assertEquals(emptyList(), d.rig.rowsAfter(marker).filter { it.type.startsWith("item.") }, "no item row for a rejected patch")
        }

    @Test
    fun `S12 expectedVersion mismatch is a version conflict and a matching or absent version applies`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()
            val current = d.reload(f.x)

            val stale = d.svc.patch(patchOf(current, title = "stale write").copy(expectedVersion = current.version + 5)).errorOrFail()
            assertEquals(ErrorCode.VERSION_CONFLICT, stale.code)
            assertEquals("X", d.reload(f.x).title, "a conflict writes nothing")

            val exact = d.svc.patch(patchOf(current, title = "exact write").copy(expectedVersion = current.version)).orFail()
            assertEquals("exact write", exact.item.title)

            val absent = d.svc.patch(patchOf(d.reload(f.x), title = "absent version write")).orFail()
            assertEquals("absent version write", absent.item.title, "a null expectedVersion skips the check")
        }

    @Test
    fun `S12 a subtree deeper than the traversal bound is refused and rolled back`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()
            val head = d.make("chain head", f.a)
            var tail = head
            repeat(MAX_TRAVERSAL_DEPTH) { i ->
                tail =
                    d.items.create(WorkItem(title = "chain $i", parentId = tail.id, depth = tail.depth + 1, rootId = f.ra.id))
            }
            val tailBefore = d.reload(tail)
            val headBefore = d.reload(head)
            val marker = d.rig.maxSeq()

            val refused = d.svc.patch(patchOf(headBefore, parent = ParentChange.MoveToRoot))

            assertIs<Outcome.Err>(refused, "moving a subtree beyond the traversal bound must fail closed")
            assertEquals(headBefore, d.reload(head), "the moved item is unchanged")
            assertEquals(tailBefore, d.reload(tail), "no descendant was restamped")
            assertEquals(emptyList(), d.rig.rowsAfter(marker).filter { it.type.startsWith("item.") })
        }

    // ---------------------------------------------------------------------------------------------
    // S5 -- recursive delete: rows, order, leases
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 recursive delete records cascaded note and dependency rows once each then an item deleted row per item`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val r = d.make("S5 root")
            val child = d.make("S5 child", r)
            val grand = d.make("S5 grand", child)
            val outsider = d.make("S5 outsider")
            d.raw.noteRepository().upsert(Note(itemId = child.id, key = "n-child", role = "work", body = "c"))
            d.raw.noteRepository().upsert(Note(itemId = r.id, key = "n-root", role = "work", body = "r"))
            d.raw.dependencyRepository().create(Dependency(fromItemId = child.id, toItemId = grand.id, type = DependencyType.BLOCKS))
            d.raw.dependencyRepository().create(Dependency(fromItemId = outsider.id, toItemId = child.id, type = DependencyType.BLOCKS))
            d.raw.dependencyRepository().create(Dependency(fromItemId = r.id, toItemId = outsider.id, type = DependencyType.BLOCKS))

            val (result, rows) = d.rig.written { d.svc.delete(r.id, recursive = true).orFail() }

            assertEquals(r.id, result.id)
            assertEquals(2, result.descendantsDeleted, "child and grandchild, not the root itself")
            assertEquals(emptyList(), result.cascadeEvents, "a parentless root has no parent to re-evaluate")
            val deleted = rows.ofType("item.deleted")
            assertEquals(setOf(r.id, child.id, grand.id), deleted.map { it.entityId }.toSet())
            assertEquals(3, deleted.size, "one item deleted row per item: ${rows.types()}")
            deleted.forEach { assertEquals(r.id, it.rootId, "an item deleted row is recorded under the item's pre-delete root") }
            val notes = rows.ofType("note.deleted")
            assertEquals(setOf("n-child", "n-root"), notes.mapNotNull { it.str("key") }.toSet())
            assertEquals(2, notes.size)
            notes.forEach {
                assertEquals("cascade", it.str("cause"))
                assertEquals(r.id, it.rootId)
                val owner = deleted.single { row -> row.entityId.toString() == it.str("itemId") }
                assertTrue(it.seq < owner.seq, "a cascaded note row precedes its item's deleted row")
            }
            val edges = rows.ofType("dependency.removed")
            assertEquals(
                setOf(
                    child.id.toString() to grand.id.toString(),
                    outsider.id.toString() to child.id.toString(),
                    r.id.toString() to outsider.id.toString(),
                ),
                edges.map { it.str("fromItemId") to it.str("toItemId") }.toSet(),
            )
            assertEquals(3, edges.size, "each edge is recorded exactly once, even one between two deleted items: ${rows.types()}")
            edges.forEach { assertEquals("cascade", it.str("cause")) }
            assertEquals(
                r.id,
                edges.single { it.str("fromItemId") == child.id.toString() }.rootId,
                "an edge between two deleted items is recorded under the subtree root",
            )
            assertEquals(8, rows.size, "3 item + 2 note + 3 dependency rows and nothing else")
            rows.assertContiguous("S5")
            listOf(r, child, grand).forEach { assertNull(d.items.getById(it.id), "${it.title} is gone") }
            assertNotNull(d.items.getById(outsider.id), "control: an item outside the subtree survives")
            assertEquals(
                emptyList(),
                d.raw.dependencyRepository().findByItemId(outsider.id),
                "the outsider's edges to deleted items are gone"
            )
        }

    @Test
    fun `S5 a non recursive delete of a parent with children is refused with the direct child count and writes nothing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val p = d.make("parent")
            val k1 = d.make("kid 1", p)
            d.make("kid 2", p)
            d.make("grandkid", k1)
            d.holdLease(p)
            val marker = d.rig.maxSeq()

            val refused = d.svc.delete(p.id, recursive = false).errorOrFail()

            assertEquals(ErrorCode.INVALID_REQUEST, refused.code)
            assertEquals(2, ItemCommandErrors.childCount(refused), "the count is of direct children only: $refused")
            assertEquals(4, d.count("work_items"), "nothing was deleted")
            assertEquals(emptyList(), d.rig.rowsAfter(marker).filter { it.type.startsWith("item.") }, "no item row")
            val interval =
                d.raw
                    .resourceLeaseRepository()
                    .findRecentIntervals(P11_LEASE_KEY, 10)
                    .single()
            assertNull(interval.releasedAt, "the refusal precedes any lease release")
            assertEquals(
                1,
                d.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(p.id)
                    .size,
                "the parent's lease is still active"
            )
        }

    @Test
    fun `S5 a recursive delete releases the leases of every deleted item in the same unit`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val p = d.make("parent")
            val kid = d.make("leased kid", p)
            d.holdLease(kid)
            assertEquals(
                1,
                d.raw
                    .resourceLeaseRepository()
                    .findActiveForItem(kid.id)
                    .size,
                "fixture: the kid holds the lease"
            )

            d.svc.delete(p.id, recursive = true).orFail()

            val interval =
                d.raw
                    .resourceLeaseRepository()
                    .findRecentIntervals(P11_LEASE_KEY, 10)
                    .single()
            assertNotNull(interval.releasedAt, "the lease interval is closed")
            assertEquals(emptyList(), d.raw.resourceLeaseRepository().findActiveForItem(kid.id))
        }

    @Test
    fun `S5 deleting a missing item is not found and a deleted parent cannot be created under`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val parent = d.make("short lived")
            d.svc.delete(parent.id, recursive = false).orFail()
            val marker = d.rig.maxSeq()

            assertEquals(
                ErrorCode.NOT_FOUND,
                d.svc
                    .delete(parent.id, recursive = false)
                    .errorOrFail()
                    .code
            )
            val orphaned = d.svc.create(ItemCreateCommand(parentId = parent.id, title = "late child")).errorOrFail()

            assertEquals(ErrorCode.NOT_FOUND, orphaned.code, "a parent deleted before the unit is not found")
            assertEquals(0, d.count("work_items"))
            assertEquals(emptyList(), d.rig.rowsAfter(marker).filter { it.type.startsWith("item.") })
        }

    // ---------------------------------------------------------------------------------------------
    // S6 / S9 -- old parent re-evaluation (reparent out, delete)
    // ---------------------------------------------------------------------------------------------

    private class Family(
        val parent: WorkItem,
        val done: WorkItem,
        val open: WorkItem,
    )

    /** A parent in WORK with one terminal child and one queue child. */
    private suspend fun P11Driver.family(
        type: String? = null,
        parent: WorkItem? = null,
    ): Family {
        val p = make("P", parent, type)
        setRole(p, Role.WORK)
        val done = make("done kid", p)
        setRole(done, Role.TERMINAL)
        val open = make("open kid", p)
        return Family(reload(p), reload(done), reload(open))
    }

    @Test
    fun `S6 reparenting the last open child away completes the old parent in the same unit`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val fam = d.family()

            val (result, rows) = d.rig.written { d.svc.patch(patchOf(fam.open, parent = ParentChange.MoveToRoot)).orFail() }

            val cascade = result.cascadeEvents.single()
            assertEquals(fam.parent.id, cascade.itemId)
            assertTrue(cascade.applied)
            assertEquals(Role.WORK, cascade.previousRole)
            assertEquals(Role.TERMINAL, cascade.targetRole)
            assertEquals(Role.TERMINAL, d.role(fam.parent))
            val transition = d.transitions(fam.parent).single()
            assertEquals("cascade", transition.trigger)
            assertEquals("work", transition.fromRole)
            assertEquals("terminal", transition.toRole)
            assertEquals(Role.QUEUE, d.role(fam.open), "the moved item's own role never changes")
            val cascadeRow = rows.ofType("item.transitioned").single()
            assertEquals(fam.parent.id, cascadeRow.entityId)
            assertEquals("cascade", cascadeRow.str("origin"))
            assertEquals(listOf("item.reparented", "item.reparented", "item.transitioned"), rows.types().sorted(), "${rows.types()}")
            rows.assertContiguous("S6 reparent")
        }

    @Test
    fun `S6 deleting the last open child completes the old parent and deleting the only child does not`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val fam = d.family()

            val (result, rows) = d.rig.written { d.svc.delete(fam.open.id, recursive = false).orFail() }

            val cascade = result.cascadeEvents.single()
            assertEquals(fam.parent.id, cascade.itemId)
            assertTrue(cascade.applied)
            assertEquals(Role.TERMINAL, d.role(fam.parent))
            assertEquals("cascade", d.transitions(fam.parent).single().trigger)
            assertEquals(listOf("item.deleted", "item.transitioned"), rows.types())
            rows.assertContiguous("S6 delete")

            // D5: deleting the last remaining child never completes the parent (no child remains).
            val lone = d.make("lone parent")
            d.setRole(lone, Role.WORK)
            val only = d.make("only kid", lone)

            val loneResult = d.svc.delete(only.id, recursive = false).orFail()

            assertEquals(emptyList(), loneResult.cascadeEvents)
            assertEquals(Role.WORK, d.role(lone), "no remaining child is not a completed set of children")
            assertEquals(0, d.transitions(lone).size)
        }

    @Test
    fun `S6 the new parent of a move is never cascaded and an old parent with an open sibling is left alone`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val q = d.make("Q")
            d.setRole(q, Role.WORK)
            val z = d.make("Z", q)
            d.setRole(z, Role.TERMINAL)
            val fam = d.family()

            val result = d.svc.patch(patchOf(fam.done, parent = ParentChange.MoveUnder(q.id))).orFail()

            assertEquals(emptyList(), result.cascadeEvents, "the old parent still has an open child, so nothing cascades")
            assertEquals(Role.WORK, d.role(fam.parent))
            assertEquals(Role.WORK, d.role(q), "the new parent Q has only terminal children now but receives no cascade")
            assertEquals(0, d.transitions(q).size)
        }

    @Test
    fun `S6 a completed old parent chains upward through its own parent`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val grand = d.make("grand")
            d.setRole(grand, Role.WORK)
            val fam = d.family(parent = grand)

            val (result, rows) = d.rig.written { d.svc.delete(fam.open.id, recursive = false).orFail() }

            assertEquals(listOf(fam.parent.id, grand.id), result.cascadeEvents.map { it.itemId })
            assertTrue(result.cascadeEvents.all { it.applied })
            assertEquals(Role.TERMINAL, d.role(fam.parent))
            assertEquals(Role.TERMINAL, d.role(grand))
            assertEquals(2, rows.ofType("item.transitioned").size)
            rows.assertContiguous("S6 chain")
        }

    @Test
    fun `S6 manual and permanent parents are not cascaded by a delete or a move`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            for (type in listOf("p11-manual", "p11-permanent")) {
                val onDelete = d.family(type)
                val deleted = d.svc.delete(onDelete.open.id, recursive = false).orFail()
                assertEquals(emptyList(), deleted.cascadeEvents, "$type: delete is silent")
                assertEquals(Role.WORK, d.role(onDelete.parent), "$type: the parent stays in WORK after a delete")
                assertEquals(0, d.transitions(onDelete.parent).size)

                val onMove = d.family(type)
                val moved = d.svc.patch(patchOf(onMove.open, parent = ParentChange.MoveToRoot)).orFail()
                assertEquals(emptyList(), moved.cascadeEvents, "$type: reparent-out is silent")
                assertEquals(Role.WORK, d.role(onMove.parent), "$type: the parent stays in WORK after a move")
            }
            // control: the same shape under an auto parent does complete (so the silence above is the lifecycle, not the fixture).
            val auto = d.family()
            assertEquals(
                1,
                d.svc
                    .delete(auto.open.id, recursive = false)
                    .orFail()
                    .cascadeEvents.size
            )
        }

    @Test
    fun `S9 a parent missing a required note is not completed and the suppressed cascade is reported with gateBlocked`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val fam = d.family("p11-gated")

            val (result, rows) = d.rig.written { d.svc.delete(fam.open.id, recursive = false).orFail() }

            val event = result.cascadeEvents.single()
            assertEquals(fam.parent.id, event.itemId)
            assertFalse(event.applied)
            assertTrue(event.gateBlocked)
            assertEquals(listOf("spec"), event.gateMissingNotes.map { it.key })
            assertEquals(Role.WORK, d.role(fam.parent))
            assertEquals(0, d.transitions(fam.parent).size)
            assertEquals(listOf("item.deleted"), rows.types(), "a suppressed cascade writes no transition row")
        }

    @Test
    fun `S9 control the same parent completes once its required note exists`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val fam = d.family("p11-gated")
            d.note(fam.parent, "spec", "queue")

            val result = d.svc.delete(fam.open.id, recursive = false).orFail()

            assertTrue(result.cascadeEvents.single().applied)
            assertEquals(Role.TERMINAL, d.role(fam.parent))
        }

    @Test
    fun `S6 a blocked parent is reported with roleBlocked and not completed`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val p = d.item("blocked parent", Role.BLOCKED, previousRole = Role.WORK)
            val done = d.make("done kid", p)
            d.setRole(done, Role.TERMINAL)
            val open = d.make("open kid", p)

            val result = d.svc.delete(open.id, recursive = false).orFail()

            val event = result.cascadeEvents.single()
            assertEquals(p.id, event.itemId)
            assertFalse(event.applied)
            assertTrue(event.roleBlocked)
            assertEquals(Role.BLOCKED, d.role(p))
        }

    @Test
    fun `S6 S11 a structural write and its cascade roll back together when the outer unit fails`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val fam = d.family()
            val marker = d.rig.maxSeq()

            val outcome =
                d.rig.composition.unitOfWork.write<Unit>("S6.outer") {
                    d.svc.delete(fam.open.id, recursive = false).orFail()
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "boom"))
                }

            assertIs<Outcome.Err>(outcome)
            assertNotNull(d.items.getById(fam.open.id), "the delete rolled back")
            assertEquals(Role.WORK, d.role(fam.parent), "the cascade rolled back with it")
            assertEquals(0, d.transitions(fam.parent).size, "no cascade transition row survives")
            assertEquals(emptyList(), d.rig.rowsAfter(marker), "no event row survives a rolled back unit")
        }

    @Test
    fun `S11 a reparent and its restamp roll back together when the outer unit fails`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()
            val before = d.snapshot(f.all)
            val marker = d.rig.maxSeq()

            val outcome =
                d.rig.composition.unitOfWork.write<Unit>("S11.outer") {
                    d.svc.patch(patchOf(f.x, parent = ParentChange.MoveUnder(f.b2.id))).orFail()
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "boom"))
                }

            assertIs<Outcome.Err>(outcome)
            assertEquals(before, d.snapshot(f.all), "the moved item and every restamped descendant are as before")
            assertEquals(emptyList(), d.rig.rowsAfter(marker))
        }

    // ---------------------------------------------------------------------------------------------
    // S6 / S9 on the MCP wire (shape from the public API reference)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 manage_items update reports the old parent cascade on the element and omits cascadeEvents when nothing cascaded`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val fam = d.family()
            val other = d.family()

            val moved =
                d.rig.callOk(
                    ManageItemsTool(),
                    "operation" to JsonPrimitive("update"),
                    "items" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", fam.open.id.toString())
                                    put("parentId", JsonNull)
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("itemId", other.done.id.toString())
                                    put("title", "renamed only")
                                }
                            )
                        },
                )

            val data = moved["data"]!!.jsonObject
            assertEquals(2, data["updated"]!!.jsonPrimitive.int, "$moved")
            val elements = data["items"]!!.jsonArray.map { it.jsonObject }
            val movedElement = elements.single { it["id"]!!.jsonPrimitive.content == fam.open.id.toString() }
            val cascade = movedElement["cascadeEvents"]!!.jsonArray.single().jsonObject
            assertEquals(fam.parent.id.toString(), cascade["itemId"]!!.jsonPrimitive.content)
            assertEquals("work", cascade["previousRole"]!!.jsonPrimitive.content)
            assertEquals("terminal", cascade["targetRole"]!!.jsonPrimitive.content)
            assertTrue(cascade["applied"]!!.jsonPrimitive.boolean)
            val plain = elements.single { it["id"]!!.jsonPrimitive.content == other.done.id.toString() }
            assertFalse("cascadeEvents" in plain, "an update with nothing to cascade omits the key: $plain")
            assertEquals(
                other.parent.id,
                d.reload(other.done).parentId,
                "an absent parentId keeps the parent, only an explicit null moves to root"
            )
            assertNull(d.reload(fam.open).parentId, "an explicit null parentId moves the item to root")
            assertEquals(Role.TERMINAL, d.role(fam.parent))
            assertEquals(Role.WORK, d.role(other.parent))
        }

    @Test
    fun `S6 S9 manage_items delete reports cascades at the top level and a suppressed one with gateBlocked`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val fam = d.family()
            val gated = d.family("p11-gated")
            val lone = d.make("lone parent")
            d.setRole(lone, Role.WORK)
            val only = d.make("only kid", lone)

            fun deleteParams(vararg ids: UUID) =
                arrayOf<Pair<String, JsonElement>>(
                    "operation" to JsonPrimitive("delete"),
                    "itemIds" to buildJsonArray { ids.forEach { add(JsonPrimitive(it.toString())) } },
                )

            val completed = d.rig.callOk(ManageItemsTool(), *deleteParams(fam.open.id))["data"]!!.jsonObject
            val completedEvent = completed["cascadeEvents"]!!.jsonArray.single().jsonObject
            assertEquals(fam.parent.id.toString(), completedEvent["itemId"]!!.jsonPrimitive.content)
            assertTrue(completedEvent["applied"]!!.jsonPrimitive.boolean)

            val blocked = d.rig.callOk(ManageItemsTool(), *deleteParams(gated.open.id))["data"]!!.jsonObject
            val blockedEvent = blocked["cascadeEvents"]!!.jsonArray.single().jsonObject
            assertFalse(blockedEvent["applied"]!!.jsonPrimitive.boolean)
            assertTrue(blockedEvent["gateBlocked"]!!.jsonPrimitive.boolean)
            assertTrue(blockedEvent["missingNotes"].toString().contains("spec"), "the missing note is named: $blockedEvent")

            val quiet = d.rig.callOk(ManageItemsTool(), *deleteParams(only.id))["data"]!!.jsonObject
            assertEquals(1, quiet["deleted"]!!.jsonPrimitive.int)
            assertFalse("cascadeEvents" in quiet, "no cascade, no key: $quiet")
        }

    // ---------------------------------------------------------------------------------------------
    // S7 -- the closed-parent rule
    // ---------------------------------------------------------------------------------------------

    private suspend fun P11Driver.terminal(type: String? = null): WorkItem {
        val t = make("terminal parent", type = type)
        setRole(t, Role.TERMINAL)
        return reload(t)
    }

    @Test
    fun `S7 creating under a terminal auto parent is rejected and writes nothing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val closed = d.terminal()
            val before = d.count("work_items")
            val marker = d.rig.maxSeq()

            val rejected = d.svc.create(ItemCreateCommand(parentId = closed.id, title = "late child")).errorOrFail()

            assertEquals(ErrorCode.INVALID_TRANSITION, rejected.code)
            assertTrue(ItemCommandErrors.isClosedParent(rejected), "classified as a closed parent: $rejected")
            assertEquals(before, d.count("work_items"), "nothing was created")
            assertEquals(emptyList(), d.rig.rowsAfter(marker).filter { it.type.startsWith("item.") }, "no item row")
            assertEquals(Role.TERMINAL, d.role(closed), "the closed parent is not reopened")
            // control: the identical create under the parent while it is open succeeds
            d.setRole(closed, Role.WORK)
            assertEquals(
                closed.id,
                d.svc
                    .create(ItemCreateCommand(parentId = closed.id, title = "timely child"))
                    .orFail()
                    .parentId
            )
        }

    @Test
    fun `S7 a terminal manual or permanent parent accepts children by create and by reparent`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            for (type in listOf("p11-manual", "p11-permanent")) {
                val closed = d.terminal(type)

                val child = d.svc.create(ItemCreateCommand(parentId = closed.id, title = "$type child")).orFail()

                assertEquals(closed.id, child.parentId, "$type: create is accepted")
                assertEquals(closed.depth + 1, child.depth)
                val stray = d.make("$type stray")
                val moved = d.svc.patch(patchOf(stray, parent = ParentChange.MoveUnder(closed.id))).orFail()
                assertTrue(moved.reparented, "$type: reparent under it is accepted")
                assertEquals(closed.id, d.reload(stray).parentId)
            }
        }

    @Test
    fun `S7 reparenting under a terminal auto parent is rejected and leaves the subtree untouched`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val f = d.forest()
            val closed = d.terminal()
            val before = d.snapshot(f.all)
            val marker = d.rig.maxSeq()

            val rejected = d.svc.patch(patchOf(f.x, parent = ParentChange.MoveUnder(closed.id))).errorOrFail()

            assertEquals(ErrorCode.INVALID_TRANSITION, rejected.code)
            assertTrue(ItemCommandErrors.isClosedParent(rejected))
            assertEquals(before, d.snapshot(f.all), "no row changed, including the descendants")
            assertEquals(emptyList(), d.rig.rowsAfter(marker).filter { it.type.startsWith("item.") })
        }

    @Test
    fun `S7 manage_items names the reopen fix for create and update and still creates under an open parent`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val closed = d.terminal()
            val stray = d.make("stray")

            val create =
                d.rig
                    .callOk(
                        ManageItemsTool(),
                        "operation" to JsonPrimitive("create"),
                        "items" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("title", "late")
                                        put("parentId", closed.id.toString())
                                    }
                                )
                            },
                    )["data"]!!
                    .jsonObject
            assertEquals(0, create["created"]!!.jsonPrimitive.int)
            assertEquals(
                "Item at index 0: parent '${closed.id}' is terminal under auto lifecycle; reopen it before adding children",
                create["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content,
            )

            val update =
                d.rig
                    .callOk(
                        ManageItemsTool(),
                        "operation" to JsonPrimitive("update"),
                        "items" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", stray.id.toString())
                                        put("parentId", closed.id.toString())
                                    }
                                )
                            },
                    )["data"]!!
                    .jsonObject
            assertEquals(0, update["updated"]!!.jsonPrimitive.int)
            assertEquals(
                "Item '${stray.id}': parent '${closed.id}' is terminal under auto lifecycle; reopen it before moving items under it",
                update["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content,
            )
            assertNull(d.reload(stray).parentId, "the stray item did not move")
            assertEquals(2, d.count("work_items"), "nothing was created under the closed parent")
        }

    @Test
    fun `S7 create_work_tree rejects a closed parent and a closed existing root with a validation error and writes nothing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val closed = d.terminal()
            val before = d.count("work_items")

            val underClosed =
                d.rig.call(
                    CreateWorkTreeTool(),
                    "root" to buildJsonObject { put("title", "tree under closed") },
                    "parentId" to JsonPrimitive(closed.id.toString()),
                    "children" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", "k")
                                    put("title", "kid")
                                }
                            )
                        },
                )
            assertFalse(underClosed["success"]!!.jsonPrimitive.boolean, "$underClosed")
            assertEquals(ErrorCodes.VALIDATION_ERROR, underClosed["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

            val attachClosed =
                d.rig.call(
                    CreateWorkTreeTool(),
                    "root" to buildJsonObject { put("id", closed.id.toString()) },
                    "children" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", "k")
                                    put("title", "kid")
                                }
                            )
                        },
                )
            assertFalse(attachClosed["success"]!!.jsonPrimitive.boolean, "$attachClosed")
            assertEquals(ErrorCodes.VALIDATION_ERROR, attachClosed["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

            assertEquals(before, d.count("work_items"), "zero items created by either rejected tree")

            // manual lifecycle accepts the same trees
            val manual = d.terminal("p11-manual")
            val accepted =
                d.rig.callOk(
                    CreateWorkTreeTool(),
                    "root" to buildJsonObject { put("id", manual.id.toString()) },
                    "children" to
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", "k")
                                    put("title", "kid")
                                }
                            )
                        },
                )
            assertTrue(accepted["success"]!!.jsonPrimitive.boolean)
        }

    // ---------------------------------------------------------------------------------------------
    // S8 -- create always lands in queue; role is rejected
    // ---------------------------------------------------------------------------------------------

    private suspend fun P11Driver.mcpCreate(vararg specs: JsonObject): JsonObject =
        rig
            .call(
                ManageItemsTool(),
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(specs.toList()),
            )["data"]!!
            .jsonObject

    private fun spec(
        title: String,
        role: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("title", title)
            if (role != null) put("role", role)
        }

    @Test
    fun `S8 a role on create is rejected for work queue and a bogus value and nothing is created`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            for (role in listOf("work", "queue", "bogus")) {
                val data = d.mcpCreate(spec("with role $role", role))

                assertEquals(0, data["created"]!!.jsonPrimitive.int, "role=$role: $data")
                assertEquals(1, data["failed"]!!.jsonPrimitive.int)
                assertEquals(
                    "Item at index 0: 'role' is not accepted on create; items are created in queue (use advance_item to move them)",
                    data["failures"]!!
                        .jsonArray[0]
                        .jsonObject["error"]!!
                        .jsonPrimitive.content,
                    "role=$role",
                )
            }
            assertEquals(0, d.count("work_items"), "no rejected spec created a row")
        }

    @Test
    fun `S8 a batch applies the valid spec and rejects only the one with a role at its own index`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)

            val data = d.mcpCreate(spec("plain"), spec("rejected", "work"))

            assertEquals(1, data["created"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            assertTrue(
                data["failures"]!!
                    .jsonArray[0]
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
                    .startsWith("Item at index 1: 'role' is not accepted on create"),
                "$data",
            )
            val created = data["items"]!!.jsonArray.single().jsonObject
            assertEquals("queue", created["role"]!!.jsonPrimitive.content)
            assertEquals(Role.QUEUE, d.role(WorkItem(id = UUID.fromString(created["id"]!!.jsonPrimitive.content), title = "x")))
            assertEquals(1, d.count("work_items"))
        }

    @Test
    fun `S8 a replayed keyed create applies once and records one created row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val requestId = UUID.randomUUID().toString()
            val params =
                arrayOf<Pair<String, JsonElement>>(
                    "operation" to JsonPrimitive("create"),
                    "items" to buildJsonArray { add(buildJsonObject { put("title", "replayed") }) },
                    "actor" to
                        buildJsonObject {
                            put("id", "agent-replay")
                            put("kind", "subagent")
                        },
                    "requestId" to JsonPrimitive(requestId),
                )

            d.rig.callOk(ManageItemsTool(), *params)
            d.rig.callOk(ManageItemsTool(), *params)

            assertEquals(1, rawCount(d.jdbcUrl, "SELECT COUNT(*) FROM work_items WHERE title = 'replayed'"), "the replay creates nothing")
            assertEquals(
                1,
                d.rig
                    .rows()
                    .ofType("item.created")
                    .size,
                "one row, not two"
            )
        }

    @Test
    fun `S8 the service rejects a blank title and an out of range complexity as invalid requests`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)

            val blank = d.svc.create(ItemCreateCommand(parentId = null, title = "")).errorOrFail()
            val complex = d.svc.create(ItemCreateCommand(parentId = null, title = "too complex", complexity = 11)).errorOrFail()

            assertEquals(ErrorCode.INVALID_REQUEST, blank.code)
            assertEquals(ErrorCode.INVALID_REQUEST, complex.code)
            assertEquals(0, d.count("work_items"))
            assertEquals(emptyList(), d.rig.rows())
        }

    // ---------------------------------------------------------------------------------------------
    // S4 / S10 -- create_work_tree is one unit over the services
    // ---------------------------------------------------------------------------------------------

    private val planBody =
        """
        # Overview
        Feature overview text.
        # Task 1
        Task 1 detail text.
        """.trimIndent()

    private fun treeParams(
        parent: WorkItem,
        deps: JsonArray? = null,
        docSlug: String? = "my-plan",
    ): Array<Pair<String, JsonElement>> {
        val params = mutableListOf<Pair<String, JsonElement>>()
        params +=
            "root" to
            buildJsonObject {
                put("title", "Feature X")
                put(
                    "noteAnchors",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("noteKey", "requirements")
                                put("role", "queue")
                                put("anchor", "overview")
                            }
                        )
                    },
                )
            }
        params += "parentId" to JsonPrimitive(parent.id.toString())
        if (docSlug != null) params += "docRef" to buildJsonObject { put("slug", docSlug) }
        params +=
            "children" to
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("ref", "t1")
                        put("title", "Task 1")
                        put(
                            "noteAnchors",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("noteKey", "task-scope")
                                        put("role", "queue")
                                        put("anchor", "task-1")
                                    }
                                )
                            },
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("ref", "t2")
                        put("title", "Task 2")
                    }
                )
            }
        if (deps != null) params += "deps" to deps
        params +=
            "notes" to
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("itemRef", "t2")
                        put("key", "approach")
                        put("role", "work")
                        put("body", "Explicit approach body")
                    },
                )
            }
        return params.toTypedArray()
    }

    private fun dep(
        from: String,
        to: String,
    ): JsonObject =
        buildJsonObject {
            put("from", from)
            put("to", to)
            put("type", "BLOCKS")
        }

    @Test
    fun `S4 a work tree records items dependency notes and the document adoption as one contiguous run in service order`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val project = d.make("Project")
            d.raw.planDocumentRepository().stash(project.id, "my-plan", planBody)

            val (result, rows) =
                d.rig.written { d.rig.callOk(CreateWorkTreeTool(), *treeParams(project, buildJsonArray { add(dep("root", "t1")) })) }

            val data = result["data"]!!.jsonObject
            val rootId = UUID.fromString(data["root"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(
                listOf(
                    "item.created",
                    "item.created",
                    "item.created",
                    "dependency.added",
                    "note.upserted",
                    "note.upserted",
                    "note.upserted",
                    "plan_document.adopted",
                ),
                rows.types(),
                "items root first, then dependencies, then notes, then the adoption, nothing twice",
            )
            rows.assertContiguous("S4")
            assertEquals(rootId, rows.first().entityId, "the root is created first")
            rows.ofType("item.created").forEach { assertEquals(project.id, it.rootId, "every created row is under the project root") }
            assertEquals(project.id.toString(), rows.first().str("parentId"))
            val doc = d.raw.planDocumentRepository().get(project.id, "my-plan")!!
            assertEquals(PlanDocumentStatus.ADOPTED, doc.status)
            assertEquals(rootId, doc.adoptedByItemId)
        }

    @Test
    fun `S10 AC7 a cyclic or duplicate dependency leaves zero items notes dependencies and a pending document`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val project = d.make("Project")
            d.raw.planDocumentRepository().stash(project.id, "my-plan", planBody)
            val items = d.count("work_items")
            val notes = d.count("notes")
            val deps = d.count("dependencies")
            val marker = d.rig.maxSeq()

            val cyclic =
                d.rig.call(
                    CreateWorkTreeTool(),
                    *treeParams(
                        project,
                        buildJsonArray {
                            add(dep("t1", "t2"))
                            add(dep("t2", "t1"))
                        }
                    )
                )
            val duplicate =
                d.rig.call(
                    CreateWorkTreeTool(),
                    *treeParams(
                        project,
                        buildJsonArray {
                            add(dep("t1", "t2"))
                            add(dep("t1", "t2"))
                        }
                    )
                )

            assertFalse(cyclic["success"]!!.jsonPrimitive.boolean, "$cyclic")
            assertFalse(duplicate["success"]!!.jsonPrimitive.boolean, "$duplicate")
            assertEquals(items, d.count("work_items"))
            assertEquals(notes, d.count("notes"))
            assertEquals(deps, d.count("dependencies"))
            assertEquals(
                PlanDocumentStatus.PENDING,
                d.raw
                    .planDocumentRepository()
                    .get(project.id, "my-plan")!!
                    .status
            )
            val written = d.rig.rowsAfter(marker).map { it.type }
            assertEquals(
                emptyList(),
                written.filter {
                    it in
                        setOf("item.created", "dependency.added", "note.upserted", "plan_document.adopted")
                }
            )
            // control: the same tree without the bad dependencies applies, so the rejections above were the dependencies
            val ok = d.rig.call(CreateWorkTreeTool(), *treeParams(project, buildJsonArray { add(dep("t1", "t2")) }))
            assertTrue(ok["success"]!!.jsonPrimitive.boolean, "$ok")
            assertEquals(items + 3, d.count("work_items"))
        }

    @Test
    fun `S10 AC7 a document that is already adopted fails the whole tree after nothing was kept`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = driver(dir)
            val project = d.make("Project")
            val earlier = d.make("Earlier adopter")
            d.raw.planDocumentRepository().stash(project.id, "my-plan", planBody)
            assertNotNull(d.raw.planDocumentRepository().markAdopted(project.id, "my-plan", earlier.id), "fixture: the document is adopted")
            val items = d.count("work_items")
            val notes = d.count("notes")
            val deps = d.count("dependencies")
            val marker = d.rig.maxSeq()

            val result = d.rig.call(CreateWorkTreeTool(), *treeParams(project, buildJsonArray { add(dep("t1", "t2")) }))

            assertFalse(result["success"]!!.jsonPrimitive.boolean, "$result")
            assertEquals(items, d.count("work_items"), "zero items")
            assertEquals(notes, d.count("notes"), "zero notes")
            assertEquals(deps, d.count("dependencies"), "zero dependencies")
            val doc = d.raw.planDocumentRepository().get(project.id, "my-plan")!!
            assertEquals(PlanDocumentStatus.ADOPTED, doc.status)
            assertEquals(earlier.id, doc.adoptedByItemId, "the earlier adoption is untouched")
            assertEquals(
                emptyList(),
                d.rig.rowsAfter(marker).map { it.type }.filter {
                    it in
                        setOf("item.created", "dependency.added", "note.upserted", "plan_document.adopted")
                },
            )
        }
}

/**
 * REST half of the structural rules (3.x wire shapes kept): the item routes run the same command service, so closed
 * parents map to 409 invalid_transition with the parent id, and a delete or a move out re-evaluates the old parent while
 * the REST bodies stay unchanged (no cascadeEvents, D7).
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ItemCommandServiceRestTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val repo get() = db.repositoryProvider()

    private suspend fun create(
        title: String,
        parent: WorkItem? = null,
        role: Role = Role.QUEUE,
    ): WorkItem =
        repo.workItemRepository().create(
            WorkItem(
                title = title,
                role = role,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                rootId = parent?.let { it.rootId ?: it.id },
            ),
        )

    private fun etag(item: WorkItem) = "\"v1-${item.modifiedAt.toEpochMilli()}\""

    private fun jsonOf(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `S7 POST under a terminal auto parent is 409 invalid_transition naming the parent and writes nothing`(): Unit =
        testApplication {
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val closed = create("closed", role = Role.TERMINAL)

            val response =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"late child","parentId":"${closed.id}"}""")
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.Conflict, response.status, body)
            val json = jsonOf(body)
            assertEquals("invalid_transition", json["error"]!!.jsonPrimitive.content)
            assertEquals(closed.id.toString(), json["details"]!!.jsonObject["parentId"]!!.jsonPrimitive.content)
            assertEquals(
                1,
                repo
                    .workItemRepository()
                    .findByFilters(limit = 100)
                    .items.size,
                "only the fixture parent exists"
            )
        }

    @Test
    fun `S7 PATCH reparent under a terminal auto parent is 409 invalid_transition and the item stays put`(): Unit =
        testApplication {
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val closed = create("closed", role = Role.TERMINAL)
            val home = create("home")
            val x = create("x", home)

            val response =
                client.patch("/api/v1/items/${x.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag(x))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":"${closed.id}"}""")
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.Conflict, response.status, body)
            val json = jsonOf(body)
            assertEquals("invalid_transition", json["error"]!!.jsonPrimitive.content)
            assertEquals(closed.id.toString(), json["details"]!!.jsonObject["parentId"]!!.jsonPrimitive.content)
            assertEquals(home.id, repo.workItemRepository().getById(x.id)!!.parentId)
        }

    @Test
    fun `S6 REST DELETE of the last open child completes the old parent and the body carries no cascadeEvents`(): Unit =
        testApplication {
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val parent = create("parent", role = Role.WORK)
            create("done kid", parent, Role.TERMINAL)
            val open = create("open kid", parent)

            val response =
                client.delete("/api/v1/items/${open.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertTrue(response.status.value in 200..299, "${response.status} ${response.bodyAsText()}")
            assertFalse(response.bodyAsText().contains("cascadeEvents"), "REST bodies are unchanged (D7)")
            assertEquals(Role.TERMINAL, repo.workItemRepository().getById(parent.id)!!.role)
            assertEquals(
                "cascade",
                repo
                    .roleTransitionRepository()
                    .findByItemId(parent.id, limit = 50)
                    .single()
                    .trigger
            )
        }

    @Test
    fun `S6 REST PATCH moving the last open child out completes the old parent`(): Unit =
        testApplication {
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val parent = create("parent", role = Role.WORK)
            create("done kid", parent, Role.TERMINAL)
            val open = create("open kid", parent)

            val response =
                client.patch("/api/v1/items/${open.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag(open))
                    contentType(ContentType.Application.Json)
                    setBody("""{"parentId":null}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertFalse(response.bodyAsText().contains("cascadeEvents"), "REST bodies are unchanged (D7)")
            assertEquals(Role.TERMINAL, repo.workItemRepository().getById(parent.id)!!.role)
            val moved = repo.workItemRepository().getById(open.id)!!
            assertNull(moved.parentId)
            assertEquals(0, moved.depth)
            assertEquals(moved.id, moved.rootId)
        }
}

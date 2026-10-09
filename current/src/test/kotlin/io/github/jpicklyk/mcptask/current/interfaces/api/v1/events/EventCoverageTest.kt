package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.EventStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.config.ManagePlanDocumentsTool
import io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildMcpTools
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent P8 coverage of the durable event log (item ea2b9b63): every mutating surface, driven end to end through
 * the REAL production composition ([EventLogRig], API off), must leave the documented rows in the `events` table.
 *
 * Oracles: P = plan section 3.7 / 8 (a row per mutating store method in the writing unit; rejections recorded; FK
 * cascades recorded; root uses its own id; installed always, API on or off); TS = the item's task-scope decisions
 * (item 2 type catalog and payload keys, item 6 decorator rules); CI = carry-in F1-F8; row payload key names are the
 * declared `payload()` keys. No expected value is read from the implementation.
 *
 * Test-plan scenarios here: S2, S3, S4, S5 (rows), S6, S9, S10, S15, S16, S17 (coverage half), plus the probes
 * listed in the test-manifest. Fixtures are seeded through the undecorated provider, so every asserted row was
 * written by the operation under test (a vacuity control accompanies each absence assertion).
 */
class EventCoverageTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun rig(
        dir: Path,
        yaml: String = "work_item_schemas: {}\n",
    ) = EventLogRig.build(db.db, dir, yaml)

    private fun List<EventRecord>.types() = map { it.type }

    private fun List<EventRecord>.ofType(type: String) = filter { it.type == type }

    private fun List<EventRecord>.assertContiguous(label: String) {
        assertTrue(isNotEmpty(), "$label: expected rows")
        val seqs = map { it.seq }
        assertEquals((seqs.first()..seqs.last()).toList(), seqs, "$label: one unit's rows must be contiguous and ascending")
        assertTrue(seqs.first() > EventStore.SEQ_FLOOR, "$label: seq must be above the floor")
    }

    private fun itemsCreate(
        title: String,
        parentId: UUID? = null,
        actorId: String? = null,
    ): Array<Pair<String, JsonElement>> {
        val base =
            mutableListOf<Pair<String, JsonElement>>(
                "operation" to JsonPrimitive("create"),
                "items" to
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("title", title)
                                if (parentId != null) put("parentId", parentId.toString())
                            },
                        )
                    },
            )
        if (actorId != null) {
            base.add(
                "actor" to
                    buildJsonObject {
                        put("id", actorId)
                        put("kind", "subagent")
                    },
            )
            base.add("requestId" to JsonPrimitive(UUID.randomUUID().toString()))
        }
        return base.toTypedArray()
    }

    private fun advance(
        id: UUID,
        trigger: String,
    ): Array<Pair<String, JsonElement>> =
        arrayOf(
            "transitions" to
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", id.toString())
                            put("trigger", trigger)
                        },
                    )
                },
        )

    // ---------------------------------------------------------------------------------------------
    // S2 -- API-off composition records rows; root uses its own id
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 API-off composition records one item created row whose root_id is the item id`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            assertNull(rig.composition.apiWiring.eventBus, "fixture: this composition must be the API-off one")

            val (_, rows) = rig.written { rig.callOk(ManageItemsTool(), *itemsCreate("S2 root")) }

            assertEquals(listOf("item.created"), rows.types())
            val row = rows.single()
            assertEquals("item", row.entityKind)
            assertEquals(row.entityId, row.rootId, "a root item uses its own id as root_id")
            assertNull(row.str("parentId"))
            assertEquals(
                "S2 root",
                rig.raw
                    .workItemRepository()
                    .getById(row.entityId)
                    ?.title
            )
            assertTrue(row.seq > EventStore.SEQ_FLOOR)
            assertNull(row.principalId, "an actorless write has null principal columns (F6)")
            assertNull(row.principalKind)
        }

    @Test
    fun `S2b a child created through manage_items records parentId and the parent as root_id`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val root = rig.seed("S2b root")

            val (_, rows) = rig.written { rig.callOk(ManageItemsTool(), *itemsCreate("S2b child", parentId = root.id)) }

            val row = rows.single()
            assertEquals("item.created", row.type)
            assertEquals(root.id, row.rootId)
            assertEquals(root.id.toString(), row.str("parentId"))
            assertTrue(row.entityId != root.id)
        }

    @Test
    fun `an actor on the call is recorded as the principal columns of the row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)

            val (_, rows) = rig.written { rig.callOk(ManageItemsTool(), *itemsCreate("actor root", actorId = "agent-p8")) }

            val row = rows.single()
            assertEquals("agent-p8", row.principalId)
            assertEquals("subagent", row.principalKind)
        }

    // ---------------------------------------------------------------------------------------------
    // S3 -- transitions: a transition row, no item.updated; cascade origin
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S3 start records item transitioned for the item and a cascade-origin row for the started parent`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val parent = rig.seed("S3 parent")
            val child = rig.seed("S3 child", parent)

            val (_, rows) = rig.written { rig.callOk(AdvanceItemTool(), *advance(child.id, "start")) }

            assertEquals(listOf("item.transitioned", "item.transitioned"), rows.types(), "no item.updated for a role change: $rows")
            val own = rows.single { it.entityId == child.id }
            assertEquals("start", own.str("trigger"))
            assertEquals("queue", own.str("fromRole"))
            assertEquals("work", own.str("toRole"))
            assertEquals("user", own.str("origin"))
            assertEquals(parent.id, own.rootId)
            val cascaded = rows.single { it.entityId == parent.id }
            assertEquals("cascade", cascaded.str("origin"))
            assertEquals("work", cascaded.str("toRole"))
            assertEquals(
                Role.WORK,
                rig.raw
                    .workItemRepository()
                    .getById(parent.id)
                    ?.role,
                "control: the cascade really happened"
            )
            rows.assertContiguous("S3")
        }

    // ---------------------------------------------------------------------------------------------
    // S4 -- recursive delete: FK cascade rows
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S4 recursive delete records item note and dependency rows for the whole subtree under the root`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val root = rig.seed("S4 root")
            val child = rig.seed("S4 child", root)
            rig.raw.noteRepository().upsert(Note(itemId = child.id, key = "n-child", role = "work", body = "c"))
            rig.raw.noteRepository().upsert(Note(itemId = root.id, key = "n-root", role = "work", body = "r"))
            rig.raw.dependencyRepository().create(Dependency(fromItemId = child.id, toItemId = root.id, type = DependencyType.BLOCKS))
            assertTrue(rig.rows().isEmpty(), "fixture: seeding through the undecorated provider writes no rows")

            val (_, rows) =
                rig.written {
                    rig.callOk(
                        ManageItemsTool(),
                        "operation" to JsonPrimitive("delete"),
                        "itemIds" to buildJsonArray { add(JsonPrimitive(root.id.toString())) },
                        "recursive" to JsonPrimitive(true),
                    )
                }

            assertEquals(2, rows.ofType("item.deleted").size, "$rows")
            assertEquals(setOf(root.id, child.id), rows.ofType("item.deleted").map { it.entityId }.toSet())
            val notes = rows.ofType("note.deleted")
            assertEquals(2, notes.size, "$rows")
            assertEquals(setOf("n-child", "n-root"), notes.mapNotNull { it.str("key") }.toSet())
            assertTrue(notes.all { it.str("cause") == "cascade" }, "FK-cascaded notes carry cause=cascade: $notes")
            assertEquals(1, rows.ofType("dependency.removed").size, "an edge between two deleted items is recorded once: $rows")
            val edge = rows.ofType("dependency.removed").single()
            assertEquals(child.id.toString(), edge.str("fromItemId"))
            assertEquals(root.id.toString(), edge.str("toItemId"))
            assertEquals(5, rows.size, "exactly 2 item + 2 note + 1 dependency rows: $rows")
            assertTrue(rows.all { it.rootId == root.id }, "every row of the subtree delete is under the root: $rows")
            for (note in notes) {
                val owner = note.str("itemId")
                val ownerDeleted = rows.ofType("item.deleted").single { it.entityId.toString() == owner }
                assertTrue(note.seq < ownerDeleted.seq, "cascade rows precede the item.deleted of their item")
            }
            rows.assertContiguous("S4")
            assertNull(rig.raw.workItemRepository().getById(root.id), "control: the subtree is really gone")
        }

    // ---------------------------------------------------------------------------------------------
    // S5 -- reparent: two rows, left under the old root, entered under the new
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 reparent records a left row under the old root and an entered row under the new root`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val r1 = rig.seed("S5 R1")
            val r2 = rig.seed("S5 R2")
            val child = rig.seed("S5 child", r1)

            val (_, rows) =
                rig.written {
                    rig.callOk(
                        ManageItemsTool(),
                        "operation" to JsonPrimitive("update"),
                        "items" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", child.id.toString())
                                        put("parentId", r2.id.toString())
                                    },
                                )
                            },
                    )
                }

            val moved = rows.ofType("item.reparented")
            assertEquals(2, moved.size, "$rows")
            assertEquals(0, rows.ofType("item.updated").size, "a pure parent change yields reparented rows only: $rows")
            val left = moved.single { it.str("side") == "left" }
            val entered = moved.single { it.str("side") == "entered" }
            assertEquals(r1.id, left.rootId)
            assertEquals(r2.id, entered.rootId)
            for (row in moved) {
                assertEquals(child.id, row.entityId)
                assertEquals(r1.id.toString(), row.str("fromParentId"))
                assertEquals(r2.id.toString(), row.str("toParentId"))
            }
            assertEquals(
                r2.id,
                rig.raw
                    .workItemRepository()
                    .getById(child.id)
                    ?.parentId,
                "control: the move happened"
            )

            val bus = ApiEventBus(source = rig.raw.eventStore())
            assertEquals(
                listOf(ApiEventType.SCOPE_LEFT),
                bus.projectedEvents(0L, setOf(r1.id)).map { it.event },
                "a stream scoped to the old root sees scope.left",
            )
            assertEquals(
                listOf(ApiEventType.SCOPE_ENTERED),
                bus.projectedEvents(0L, setOf(r2.id)).map { it.event },
                "a stream scoped to the new root sees scope.entered",
            )
        }

    // ---------------------------------------------------------------------------------------------
    // manage_items update / no-op / empty delete probes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `manage_items update of a title records item updated with the changed field and a no-op update records nothing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val item = rig.seed("upd before")

            val (_, rows) =
                rig.written {
                    rig.callOk(
                        ManageItemsTool(),
                        "operation" to JsonPrimitive("update"),
                        "items" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", item.id.toString())
                                        put("title", "upd after")
                                    },
                                )
                            },
                    )
                }

            val row = rows.single()
            assertEquals("item.updated", row.type)
            assertEquals(item.id, row.entityId)
            assertEquals(listOf("title"), row.payload()["changedFields"]!!.jsonArray.map { it.jsonPrimitive.content })

            val current = rig.raw.workItemRepository().getById(item.id)!!
            val (_, noop) = rig.written { rig.inUnit { rig.provider.workItemRepository().update(current) } }
            assertEquals(emptyList(), noop, "an update that changes nothing records no row: $noop")
            val (_, emptyDelete) = rig.written { rig.inUnit { rig.provider.workItemRepository().deleteAll(emptySet()) } }
            assertEquals(emptyList(), emptyDelete, "deleteAll of nothing records no row")
        }

    // ---------------------------------------------------------------------------------------------
    // S15 -- orphan items keep a non-null root_id
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S15 an item with a null stored root_id still records a non-null root`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val root = rig.seed("S15 root")
            assertNull(root.rootId, "fixture: a depth-0 seed has no stored root id")
            val orphanChild =
                rig.raw.workItemRepository().create(
                    WorkItem(title = "S15 orphan child", parentId = root.id, depth = 1, rootId = null),
                )

            val (_, rows) =
                rig.written {
                    rig.callOk(
                        ManageItemsTool(),
                        "operation" to JsonPrimitive("update"),
                        "items" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", root.id.toString())
                                        put("title", "S15 root renamed")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("itemId", orphanChild.id.toString())
                                        put("title", "S15 child renamed")
                                    },
                                )
                            },
                    )
                }

            assertEquals(2, rows.ofType("item.updated").size, "$rows")
            assertEquals(root.id, rows.single { it.entityId == root.id }.rootId, "a parentless orphan roots at its own id")
            assertEquals(root.id, rows.single { it.entityId == orphanChild.id }.rootId, "an orphan child roots at its ancestor")
        }

    // ---------------------------------------------------------------------------------------------
    // manage_notes / S16
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `manage_notes upsert and delete record note upserted with the body length in characters and note deleted explicit`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val item = rig.seed("note host")
            val body =
                buildString {
                    append('h')
                    append(233.toChar())
                    append("llo")
                    append(10003.toChar())
                }
            assertEquals(6, body.length)
            assertTrue(body.toByteArray(Charsets.UTF_8).size > body.length, "fixture: bytes differ from characters")

            val (_, upserted) =
                rig.written {
                    rig.callOk(
                        ManageNotesTool(),
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", item.id.toString())
                                        put("key", "k1")
                                        put("role", "work")
                                        put("body", body)
                                    },
                                )
                            },
                    )
                }
            val up = upserted.single()
            assertEquals("note.upserted", up.type)
            assertEquals("note", up.entityKind)
            assertEquals(item.id.toString(), up.str("itemId"))
            assertEquals("k1", up.str("key"))
            assertEquals("work", up.str("role"))
            assertEquals(
                6,
                up
                    .payload()["bodyLength"]!!
                    .jsonPrimitive.content
                    .toInt(),
                "bodyLength counts characters"
            )
            assertEquals(item.id, up.rootId)

            val (_, deleted) =
                rig.written {
                    rig.callOk(
                        ManageNotesTool(),
                        "operation" to JsonPrimitive("delete"),
                        "itemId" to JsonPrimitive(item.id.toString()),
                        "key" to JsonPrimitive("k1"),
                    )
                }
            val down = deleted.single()
            assertEquals("note.deleted", down.type)
            assertEquals("explicit", down.str("cause"))
            assertEquals("k1", down.str("key"))
            assertEquals(up.entityId, down.entityId, "the delete row is about the same note id")
        }

    @Test
    fun `S16 deleting all notes of an item records one note deleted row per note with its key`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val item = rig.seed("S16 host")
            listOf("a", "b", "c").forEach { rig.raw.noteRepository().upsert(Note(itemId = item.id, key = it, role = "work", body = it)) }

            val (count, rows) = rig.written { rig.inUnit { rig.provider.noteRepository().deleteByItemId(item.id) } }

            assertEquals(3, count)
            assertEquals(List(3) { "note.deleted" }, rows.types())
            assertEquals(setOf("a", "b", "c"), rows.mapNotNull { it.str("key") }.toSet())
            assertEquals(3, rows.map { it.entityId }.toSet().size, "each row names its own note")
            assertTrue(rows.all { it.rootId == item.id })
            rows.assertContiguous("S16")
        }

    // ---------------------------------------------------------------------------------------------
    // manage_dependencies
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `manage_dependencies create and delete record dependency added and removed`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val root = rig.seed("dep root")
            val a = rig.seed("dep A", root)
            val b = rig.seed("dep B", root)

            val (_, added) =
                rig.written {
                    rig.callOk(
                        ManageDependenciesTool(),
                        "operation" to JsonPrimitive("create"),
                        "dependencies" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("fromItemId", a.id.toString())
                                        put("toItemId", b.id.toString())
                                    },
                                )
                            },
                    )
                }
            val add = added.single()
            assertEquals("dependency.added", add.type)
            assertEquals("dependency", add.entityKind)
            assertEquals(a.id.toString(), add.str("fromItemId"))
            assertEquals(b.id.toString(), add.str("toItemId"))
            assertEquals(root.id, add.rootId)

            val (_, removed) =
                rig.written {
                    rig.callOk(
                        ManageDependenciesTool(),
                        "operation" to JsonPrimitive("delete"),
                        "fromItemId" to JsonPrimitive(a.id.toString()),
                        "toItemId" to JsonPrimitive(b.id.toString()),
                    )
                }
            val rem = removed.single()
            assertEquals("dependency.removed", rem.type)
            assertEquals(add.entityId, rem.entityId, "the same edge id")
            assertEquals("explicit", rem.str("cause"))
        }

    // ---------------------------------------------------------------------------------------------
    // S6 / S10 -- claims, leases, config, plan documents
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S6 claim_item claim and release record claim acquired and claim released`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val item = rig.seed("S6 claim item")

            fun params(key: String): Array<Pair<String, JsonElement>> =
                arrayOf(
                    key to buildJsonArray { add(buildJsonObject { put("itemId", item.id.toString()) }) },
                    "actor" to
                        buildJsonObject {
                            put("id", "s6-agent")
                            put("kind", "subagent")
                        },
                    "requestId" to JsonPrimitive(UUID.randomUUID().toString()),
                )

            val (_, claimed) = rig.written { rig.callOk(ClaimItemTool(), *params("claims")) }
            val acquired = claimed.single { it.type == "claim.acquired" }
            assertEquals(item.id, acquired.entityId)
            assertEquals("s6-agent", acquired.str("holder"))
            assertTrue(
                acquired
                    .payload()["ttlSeconds"]!!
                    .jsonPrimitive.content
                    .toInt() > 0
            )

            val (_, released) = rig.written { rig.callOk(ClaimItemTool(), *params("releases")) }
            val rel = released.single { it.type == "claim.released" }
            assertEquals(item.id, rel.entityId)
            assertEquals("released", rel.str("reason"))
        }

    @Test
    fun `S6 and S10 claim store records acquired with its ttl cleared and a rejection when another agent holds the item`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val item = rig.seed("S10 claim item")

            val (first, acquiredRows) =
                rig.written {
                    rig.ctx.claimService
                        .claim(item.id, "agent-a", 120)
                        .getOrNull()
                }
            assertIs<ClaimResult.Success>(first)
            val acquired = acquiredRows.single()
            assertEquals("claim.acquired", acquired.type)
            assertEquals(
                120,
                acquired
                    .payload()["ttlSeconds"]!!
                    .jsonPrimitive.content
                    .toInt()
            )
            assertEquals("agent-a", acquired.str("holder"))

            val (second, rejectedRows) =
                rig.written {
                    rig.ctx.claimService
                        .claim(item.id, "agent-b", 120)
                        .getOrNull()
                }
            assertIs<ClaimResult.AlreadyClaimed>(second)
            assertEquals(
                listOf("claim.rejected"),
                rejectedRows.types(),
                "contention commits its unit and records exactly one rejection row"
            )
            assertEquals(item.id, rejectedRows.single().entityId)
            assertEquals(
                "agent-a",
                rig.raw
                    .workItemRepository()
                    .getById(item.id)
                    ?.claimedBy,
                "control: agent-a still holds the item"
            )

            val (cleared, clearedRows) =
                rig.written {
                    rig.ctx.claimService
                        .clearClaim(item.id)
                        .getOrNull()
                }
            assertTrue(cleared == true)
            assertEquals(listOf("claim.released"), clearedRows.types())
            assertEquals("cleared", clearedRows.single().str("reason"))
        }

    @Test
    fun `S6 and S10 lease store records acquired released forced and a rejection for a contended key`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val holder = rig.seed("lease holder")
            val other = rig.seed("lease other")
            val claims = rig.ctx.claimService

            val (acq, acquired) = rig.written { claims.acquireLeases(holder.id, "actor-1", listOf("res-a" to 600)).getOrNull() }
            assertIs<LeaseAcquireResult.Success>(acq)
            val a = acquired.single()
            assertEquals("lease.acquired", a.type)
            assertEquals(holder.id, a.entityId)
            assertEquals("res-a", a.str("key"))
            assertEquals(
                600,
                a
                    .payload()["ttlSeconds"]!!
                    .jsonPrimitive.content
                    .toInt()
            )

            val (contended, rejected) = rig.written { claims.acquireLeases(other.id, "actor-2", listOf("res-a" to 600)).getOrNull() }
            assertIs<LeaseAcquireResult.Contended>(contended)
            assertEquals(listOf("lease.rejected"), rejected.types(), "contention commits its unit and records exactly one rejection row")
            val rej = rejected.single()
            assertEquals(other.id, rej.entityId)
            assertEquals(listOf("res-a"), rej.payload()["contendedKeys"]!!.jsonArray.map { it.jsonPrimitive.content })

            val (_, released) = rig.written { claims.releaseLeases(setOf(holder.id)).getOrNull() }
            val rel = released.single()
            assertEquals("lease.released", rel.type)
            assertEquals("res-a", rel.str("key"))
            assertFalse(rel.payload()["forced"]!!.jsonPrimitive.boolean)
            assertEquals(
                1,
                rel
                    .payload()["count"]!!
                    .jsonPrimitive.content
                    .toInt()
            )

            claims.acquireLeases(holder.id, "actor-1", listOf("res-b" to 600))
            val (_, forced) = rig.written { claims.forceReleaseLease("res-b", "operator").getOrNull() }
            val f = forced.single()
            assertEquals("lease.released", f.type)
            assertEquals("res-b", f.str("key"))
            assertTrue(f.payload()["forced"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `S6 manage_project_config push and manage_plan_documents stash record config and plan document rows`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val root = rig.seed("cfg root")

            val (_, cfg) =
                rig.written {
                    rig.callOk(
                        ManageProjectConfigTool(YamlConfigDocumentParser),
                        "operation" to JsonPrimitive("push"),
                        "rootId" to JsonPrimitive(root.id.toString()),
                        "configYaml" to JsonPrimitive(EventLogRig.GATED_CONFIG),
                    )
                }
            val pushed = cfg.single()
            assertEquals("project_config.upserted", pushed.type)
            assertEquals("project_config", pushed.entityKind)
            assertEquals(root.id, pushed.entityId, "a project config row is about its root")
            assertEquals(root.id, pushed.rootId)
            assertFalse(pushed.str("fingerprint").isNullOrBlank())

            val (_, plans) =
                rig.written {
                    rig.callOk(
                        ManagePlanDocumentsTool(),
                        "operation" to JsonPrimitive("stash"),
                        "rootId" to JsonPrimitive(root.id.toString()),
                        "slug" to JsonPrimitive("p8-plan"),
                        "body" to JsonPrimitive("A plan body."),
                    )
                }
            val stashed = plans.single()
            assertEquals("plan_document.stashed", stashed.type)
            assertEquals("plan_document", stashed.entityKind)
            assertEquals("p8-plan", stashed.str("slug"))
            assertEquals(root.id, stashed.rootId)

            val adopter = rig.seed("adopter", root)
            val (_, adopted) =
                rig.written {
                    rig.inUnit {
                        rig.provider.planDocumentRepository().markAdopted(
                            root.id,
                            "p8-plan",
                            adopter.id
                        )
                    }
                }
            val ad = adopted.single()
            assertEquals("plan_document.adopted", ad.type)
            assertEquals("p8-plan", ad.str("slug"))
            assertEquals(adopter.id.toString(), ad.str("adoptedByItemId"))
            assertEquals(stashed.entityId, ad.entityId, "the adoption row is about the same document id")
        }

    @Test
    fun `create_work_tree records created rows for every item plus note and dependency rows in one contiguous run`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)

            val (result, rows) =
                rig.written {
                    rig.callOk(
                        CreateWorkTreeTool(),
                        "root" to buildJsonObject { put("title", "tree root") },
                        "children" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("ref", "c1")
                                        put("title", "tree c1")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("ref", "c2")
                                        put("title", "tree c2")
                                    },
                                )
                            },
                        "deps" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("from", "c1")
                                        put("to", "c2")
                                        put("type", "BLOCKS")
                                    },
                                )
                            },
                        "notes" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemRef", "c1")
                                        put("key", "approach")
                                        put("role", "work")
                                        put("body", "b")
                                    },
                                )
                            },
                    )
                }

            val rootId =
                UUID.fromString(
                    result["data"]!!
                        .jsonObject["root"]!!
                        .jsonObject["id"]!!
                        .jsonPrimitive.content
                )
            assertEquals(3, rows.ofType("item.created").size, "$rows")
            assertEquals(1, rows.ofType("note.upserted").size)
            assertEquals(1, rows.ofType("dependency.added").size)
            assertEquals(5, rows.size, "$rows")
            assertTrue(rows.all { it.rootId == rootId }, "every row of the tree is under the new root: $rows")
            rows.assertContiguous("create_work_tree")
        }

    // ---------------------------------------------------------------------------------------------
    // S9 -- gate and dependency rejections
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S9 a gate-blocked start leaves the response unchanged and records exactly one transition rejected with the missing key`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir, EventLogRig.GATED_CONFIG)
            val item = rig.seed("S9 gated", tags = EventLogRig.GATED_TAG)

            val (blocked, rows) = rig.written { rig.call(AdvanceItemTool(), *advance(item.id, "start")) }

            val result =
                blocked["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertFalse(result["applied"]!!.jsonPrimitive.boolean)
            assertEquals("gate_blocked", result["errorCode"]!!.jsonPrimitive.content, "the tool response is unchanged: $blocked")
            assertEquals(listOf("transition.rejected"), rows.types(), "exactly one rejection row and no transition row: $rows")
            val row = rows.single()
            assertEquals(item.id, row.entityId)
            assertEquals("start", row.str("trigger"))
            assertEquals("gate_blocked", row.str("code"))
            assertEquals(listOf("spec"), row.payload()["missingKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                Role.QUEUE,
                rig.raw
                    .workItemRepository()
                    .getById(item.id)
                    ?.role
            )

            // Control: with the required note present the same start succeeds and records no rejection.
            rig.raw.noteRepository().upsert(Note(itemId = item.id, key = "spec", role = "queue", body = "filled"))
            val (_, after) = rig.written { rig.callOk(AdvanceItemTool(), *advance(item.id, "start")) }
            assertEquals(listOf("item.transitioned"), after.types(), "$after")
        }

    @Test
    fun `S9 a start blocked by an unfinished blocker records transition rejected with the blocker id`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val blocker = rig.seed("S9 blocker")
            val blocked = rig.seed("S9 blocked")
            rig.raw.dependencyRepository().create(Dependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS))

            val (response, rows) = rig.written { rig.call(AdvanceItemTool(), *advance(blocked.id, "start")) }
            assertFalse(
                response["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray
                    .single()
                    .jsonObject["applied"]!!
                    .jsonPrimitive.boolean,
                "fixture: the start must be refused: $response",
            )
            assertEquals(listOf("transition.rejected"), rows.types(), "$rows")
            val row = rows.single()
            assertEquals(blocked.id, row.entityId)
            assertEquals("dependency_unmet", row.str("code"))
            assertEquals(listOf(blocker.id.toString()), row.payload()["blockerIds"]!!.jsonArray.map { it.jsonPrimitive.content })

            // Control: finish the blocker; the same start then succeeds and adds no further rejection.
            rig.callOk(AdvanceItemTool(), *advance(blocker.id, "start"))
            rig.callOk(AdvanceItemTool(), *advance(blocker.id, "complete"))
            val (_, after) = rig.written { rig.callOk(AdvanceItemTool(), *advance(blocked.id, "start")) }
            assertTrue(after.ofType("item.transitioned").any { it.entityId == blocked.id }, "$after")
            assertEquals(1, rig.rows().ofType("transition.rejected").size, "no second rejection row")
        }

    @Test
    fun `S9 complete_tree records a transition rejected for a gate-blocked item`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir, EventLogRig.GATED_CONFIG)
            val root = rig.seed("tree root")
            val child = rig.seed("tree gated child", root, tags = EventLogRig.GATED_TAG)

            val (_, rows) =
                rig.written {
                    rig.call(CompleteTreeTool(), "rootId" to JsonPrimitive(root.id.toString()), "trigger" to JsonPrimitive("complete"))
                }

            val rejected = rows.ofType("transition.rejected").filter { it.entityId == child.id }
            assertEquals(1, rejected.size, "$rows")
            assertEquals("gate_blocked", rejected.single().str("code"))
            assertTrue(
                rejected
                    .single()
                    .payload()["missingKeys"]!!
                    .jsonArray
                    .any { it.jsonPrimitive.content == "spec" }
            )
            assertEquals(
                Role.QUEUE,
                rig.raw
                    .workItemRepository()
                    .getById(child.id)
                    ?.role,
                "control: the gated child did not complete"
            )
        }

    // ---------------------------------------------------------------------------------------------
    // Round 2 -- plan-document adoption through create_work_tree; rejection rows under idempotency keys
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `create_work_tree with a docRef records exactly one plan document adopted row plus the item created rows`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val project = rig.seed("adoption project root")
            val (_, stashRows) =
                rig.written {
                    rig.callOk(
                        ManagePlanDocumentsTool(),
                        "operation" to JsonPrimitive("stash"),
                        "rootId" to JsonPrimitive(project.id.toString()),
                        "slug" to JsonPrimitive("r2-plan"),
                        "body" to JsonPrimitive("# Overview - a plan body."),
                    )
                }
            val stashed = stashRows.single { it.type == "plan_document.stashed" }

            val (result, rows) =
                rig.written {
                    rig.callOk(
                        CreateWorkTreeTool(),
                        "root" to buildJsonObject { put("title", "adopting root") },
                        "parentId" to JsonPrimitive(project.id.toString()),
                        "children" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("ref", "c1")
                                        put("title", "adopting child")
                                    },
                                )
                            },
                        "docRef" to
                            buildJsonObject {
                                put("rootId", project.id.toString())
                                put("slug", "r2-plan")
                            },
                    )
                }
            val createdRootId =
                result["data"]!!
                    .jsonObject["root"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.content

            val adopted = rows.ofType("plan_document.adopted")
            assertEquals(1, adopted.size, "exactly one adoption row: $rows")
            assertEquals(2, rows.ofType("item.created").size, "root and child created rows: $rows")
            assertEquals(3, rows.size, "nothing else is recorded for the adoption call: $rows")
            val row = adopted.single()
            assertEquals("r2-plan", row.str("slug"))
            assertEquals(createdRootId, row.str("adoptedByItemId"))
            assertEquals(stashed.entityId, row.entityId, "the adoption row is about the document that was stashed")
            assertEquals(project.id, row.rootId)
            rows.assertContiguous("create_work_tree with docRef")
        }

    @Test
    fun `a gate-blocked advance sent with a requestId and a trusted actor still records exactly one transition rejected row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir, EventLogRig.GATED_CONFIG)
            val item = rig.seed("keyed gated", tags = EventLogRig.GATED_TAG)

            val (response, rows) =
                rig.written {
                    rig.call(
                        AdvanceItemTool(),
                        "transitions" to
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("itemId", item.id.toString())
                                        put("trigger", "start")
                                        put(
                                            "actor",
                                            buildJsonObject {
                                                put("id", "keyed-agent")
                                                put("kind", "subagent")
                                            },
                                        )
                                    },
                                )
                            },
                        "requestId" to JsonPrimitive(UUID.randomUUID().toString()),
                    )
                }

            val result =
                response["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(
                "gate_blocked",
                result["errorCode"]!!.jsonPrimitive.content,
                "fixture: the keyed start must be gate blocked: $response"
            )
            assertEquals(
                listOf("transition.rejected"),
                rows.types(),
                "per plan 3.9 a rejection row is recorded even though the keyed element fails: $rows"
            )
            assertEquals("gate_blocked", rows.single().str("code"))
            assertEquals(item.id, rows.single().entityId)
        }

    @Test
    fun `an AlreadyClaimed claim_item sent with a requestId records exactly one claim rejected row`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val item = rig.seed("keyed claim contention")
            assertIs<ClaimResult.Success>(rig.raw.workItemRepository().claim(item.id, "holder-agent", 600))

            val (response, rows) =
                rig.written {
                    rig.call(
                        ClaimItemTool(),
                        "claims" to buildJsonArray { add(buildJsonObject { put("itemId", item.id.toString()) }) },
                        "actor" to
                            buildJsonObject {
                                put("id", "challenger-agent")
                                put("kind", "subagent")
                            },
                        "requestId" to JsonPrimitive(UUID.randomUUID().toString()),
                    )
                }

            assertTrue(
                response.toString().contains("already_claimed"),
                "fixture: the keyed claim must lose to the live holder: $response"
            )
            assertEquals(
                "holder-agent",
                rig.raw
                    .workItemRepository()
                    .getById(item.id)
                    ?.claimedBy,
                "control: the original holder keeps the item"
            )
            assertEquals(
                listOf("claim.rejected"),
                rows.types(),
                "per plan 3.9 a rejection row is recorded even though the keyed element fails: $rows"
            )
            assertEquals(item.id, rows.single().entityId)
        }

    // ---------------------------------------------------------------------------------------------
    // S17 -- every MCP write tool is covered or read-only allow-listed
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S17 every tool the server registers is classified as a covered write tool or an allow-listed read-only tool`() {
        val registered = buildMcpTools().map { it.name }.toSet()
        val covered = WRITE_TOOLS_COVERED.keys
        assertTrue(covered.intersect(READ_ONLY_TOOLS).isEmpty(), "a tool is either a covered write tool or read-only, not both")
        assertEquals(
            registered,
            covered + READ_ONLY_TOOLS,
            "an MCP tool was added or removed without classifying it for event coverage: unclassified=" +
                "${registered - covered - READ_ONLY_TOOLS}, stale=${(covered + READ_ONLY_TOOLS) - registered}",
        )
    }

    @Test
    fun `S17 each covered write tool yields at least one row and the read-only tools yield none`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val rig = rig(dir)
            val tools = buildMcpTools().associateBy { it.name }
            val root = rig.seed("S17 root")
            val child = rig.seed("S17 child", root)
            val other = rig.seed("S17 other", root)
            val treeRoot = rig.seed("S17 tree root")
            val depTarget = rig.seed("S17 dep target", root)
            val actor =
                buildJsonObject {
                    put("id", "s17-agent")
                    put("kind", "subagent")
                }
            val perTool: Map<String, Array<Pair<String, JsonElement>>> =
                mapOf(
                    "manage_items" to itemsCreate("S17 created"),
                    "manage_notes" to
                        arrayOf(
                            "operation" to JsonPrimitive("upsert"),
                            "notes" to
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("itemId", child.id.toString())
                                            put("key", "k")
                                            put("role", "work")
                                            put("body", "b")
                                        },
                                    )
                                },
                        ),
                    "manage_dependencies" to
                        arrayOf(
                            "operation" to JsonPrimitive("create"),
                            "dependencies" to
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("fromItemId", child.id.toString())
                                            put("toItemId", depTarget.id.toString())
                                        },
                                    )
                                },
                        ),
                    "advance_item" to advance(other.id, "start"),
                    "claim_item" to
                        arrayOf(
                            "claims" to buildJsonArray { add(buildJsonObject { put("itemId", child.id.toString()) }) },
                            "actor" to actor,
                            "requestId" to JsonPrimitive(UUID.randomUUID().toString()),
                        ),
                    "complete_tree" to arrayOf("rootId" to JsonPrimitive(treeRoot.id.toString()), "trigger" to JsonPrimitive("complete")),
                    "create_work_tree" to arrayOf("root" to buildJsonObject { put("title", "S17 tree") }),
                    "manage_project_config" to
                        arrayOf(
                            "operation" to JsonPrimitive("push"),
                            "rootId" to JsonPrimitive(root.id.toString()),
                            "configYaml" to JsonPrimitive(EventLogRig.GATED_CONFIG),
                        ),
                    "manage_plan_documents" to
                        arrayOf(
                            "operation" to JsonPrimitive("stash"),
                            "rootId" to JsonPrimitive(root.id.toString()),
                            "slug" to JsonPrimitive("s17"),
                            "body" to JsonPrimitive("s17 body"),
                        ),
                )
            assertEquals(WRITE_TOOLS_COVERED.keys, perTool.keys, "the scenario table must cover exactly the covered write tools")
            for ((name, params) in perTool) {
                val (result, rows) = rig.written { rig.call(tools.getValue(name), *params) }
                assertTrue(result["success"]!!.jsonPrimitive.boolean, "$name must succeed: $result")
                assertTrue(rows.isNotEmpty(), "write tool $name left no event row")
                assertTrue(
                    rows.any { it.type in WRITE_TOOLS_COVERED.getValue(name) },
                    "$name rows ${rows.types()} do not include any of ${WRITE_TOOLS_COVERED[name]}",
                )
            }

            val mark = rig.maxSeq()
            val reads: Map<String, Array<Pair<String, JsonElement>>> =
                mapOf(
                    "query_items" to arrayOf("operation" to JsonPrimitive("get"), "itemId" to JsonPrimitive(root.id.toString())),
                    "query_notes" to arrayOf("operation" to JsonPrimitive("list"), "itemId" to JsonPrimitive(child.id.toString())),
                    "get_context" to arrayOf("itemId" to JsonPrimitive(root.id.toString())),
                    "query_dependencies" to arrayOf("operation" to JsonPrimitive("get"), "itemId" to JsonPrimitive(child.id.toString())),
                    "get_next_status" to arrayOf("itemId" to JsonPrimitive(root.id.toString())),
                    "get_next_item" to emptyArray(),
                    "get_blocked_items" to emptyArray(),
                    "query_rules" to arrayOf("operation" to JsonPrimitive("list"), "rootId" to JsonPrimitive(root.id.toString())),
                )
            assertEquals(READ_ONLY_TOOLS, reads.keys, "every read-only tool is exercised with VALID arguments, never a bare call")
            for ((name, params) in reads) {
                val result = rig.call(tools.getValue(name), *params)
                assertTrue(
                    result["success"]!!.jsonPrimitive.boolean,
                    "read tool $name must succeed so its zero rows mean something: $result"
                )
            }
            assertEquals(mark, rig.maxSeq(), "read-only tools must append zero rows (maxSeq unchanged)")
            assertEquals(emptyList(), rig.rowsAfter(mark), "read-only tools must record no event rows")
            // Control: a write on the same rig does append a row, so the zero-row assertions above can fail.
            val (_, control) = rig.written { rig.callOk(ManageItemsTool(), *itemsCreate("S17 read control")) }
            assertEquals(listOf("item.created"), control.types(), "control: the same rig does append a row for a write")
        }

    @Test
    fun `the typed catalog reports its documented type strings entity kinds and root`() {
        val id = UUID.randomUUID()
        val sample: List<DomainEvent> =
            listOf(
                DomainEvent.ItemCreated(id, id, null),
                DomainEvent.ItemDeleted(id, id),
                DomainEvent.ClaimExpired(id, id),
                DomainEvent.LeaseExpired(id, id, "k"),
                DomainEvent.ProjectConfigDeleted(id),
            )
        assertEquals(
            listOf("item.created", "item.deleted", "claim.expired", "lease.expired", "project_config.deleted"),
            sample.map { it.type },
        )
        assertEquals(listOf("item", "item", "item", "item", "project_config"), sample.map { it.entityKind })
        assertEquals(sample.last().rootId, sample.last().entityId, "a project config event is about its root")
    }

    companion object {
        /** Write tool name -> the event types a successful call must include at least one of. */
        val WRITE_TOOLS_COVERED: Map<String, Set<String>> =
            mapOf(
                "manage_items" to setOf("item.created"),
                "manage_notes" to setOf("note.upserted"),
                "manage_dependencies" to setOf("dependency.added"),
                "advance_item" to setOf("item.transitioned"),
                "claim_item" to setOf("claim.acquired"),
                "complete_tree" to setOf("item.transitioned"),
                "create_work_tree" to setOf("item.created"),
                "manage_project_config" to setOf("project_config.upserted"),
                "manage_plan_documents" to setOf("plan_document.stashed"),
            )

        /** Tools that never mutate store state; each is exercised and must leave the log untouched. */
        val READ_ONLY_TOOLS: Set<String> =
            setOf(
                "query_items",
                "query_notes",
                "query_dependencies",
                "get_next_status",
                "get_next_item",
                "get_blocked_items",
                "get_context",
                "query_rules",
            )
    }
}

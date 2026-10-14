package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.service.ok
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.blocksDep
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.configFaultFixture
import io.github.jpicklyk.mcptask.current.test.makeNote
import io.github.jpicklyk.mcptask.current.test.relatesDep
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import io.github.jpicklyk.mcptask.current.test.tools.ToolSurfaceChecks
import io.github.jpicklyk.mcptask.current.test.upsert
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------------------------------------------------
// Shared fixtures of the three orchestration-select test classes (context / next / blocked). All internal so the sibling
// classes reuse them; every fixture builds a valid WorkItem by construction (depth and root derived from the parent, the
// claim triple derived from one expiry, previousRole present exactly for a BLOCKED item).
// ---------------------------------------------------------------------------------------------------------------------

/** Seeds an item through the undecorated repository with every field the orchestration selects order or filter on. */
internal suspend fun EventLogRig.vwMake(
    title: String,
    parent: WorkItem? = null,
    role: Role = Role.QUEUE,
    id: UUID = UUID.randomUUID(),
    tags: String? = null,
    type: String? = "test-item",
    priority: Priority = Priority.MEDIUM,
    complexity: Int? = null,
    previousRole: Role? = if (role == Role.BLOCKED) Role.WORK else null,
    at: Instant = Instant.now(),
    claimedBy: String? = null,
    summary: String = ""
): WorkItem {
    val claimExpiresAt = if (claimedBy != null) Instant.now().plusSeconds(3600) else null
    val claimedAt = claimExpiresAt?.minusSeconds(60)
    return raw.workItemRepository().create(
        WorkItem(
            id = id,
            title = title,
            summary = summary,
            parentId = parent?.id,
            depth = (parent?.depth ?: -1) + 1,
            rootId = parent?.let { it.rootId ?: it.id } ?: id,
            role = role,
            previousRole = previousRole,
            priority = priority,
            complexity = complexity,
            tags = tags,
            type = type,
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

/** [blocker] BLOCKS [blocked]; [unblockAt] is the wire role the blocker must reach (null = the default, terminal). */
internal suspend fun EventLogRig.vwBlocks(
    blocker: WorkItem,
    blocked: WorkItem,
    unblockAt: String? = null
) {
    raw.dependencyRepository().create(blocksDep(blocker.id, blocked.id, unblockAt))
}

internal suspend fun EventLogRig.vwNote(
    item: WorkItem,
    key: String,
    role: String,
    body: String = "filled $key"
) {
    raw.noteRepository().upsert(makeNote(item.id, key, role, body))
}

internal fun JsonObject.boolOf(name: String): Boolean = getValue(name).jsonPrimitive.boolean

internal fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

internal fun JsonObject.intOf(name: String): Int = getValue(name).jsonPrimitive.int

/** The ids of a paged select's `data.items` (8-hex short ids). */
internal fun Reply.ids(): Set<String> = items.map { it.str("id") }.toSet()

/** The ids in answer order. */
internal fun Reply.idList(): List<String> = items.map { it.str("id") }

/** The ids of an `{items, page}` block (a health-mode `active` / `blocked` block). */
internal fun JsonObject.blockIds(): List<String> = arr("items").map { it.jsonObject.str("id") }

internal fun JsonObject.blockTotal(): Int = obj("page").intOf("total")

internal fun JsonObject.blockNext(): String? = obj("page")["next"]?.jsonPrimitive?.contentOrNull

/** True when [wire] names [id] in the compact 8-hex form or as the full UUID. */
internal fun sameItem(
    wire: String,
    id: UUID
): Boolean = wire == short(id) || wire == id.toString()

private const val CTX_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "  ctx-type:\n" +
        "    notes:\n" +
        "      - key: note-a\n" +
        "        role: queue\n" +
        "        required: true\n" +
        "      - key: note-b\n" +
        "        role: queue\n" +
        "        required: true\n" +
        "        guidance: \"GUIDE-B\"\n" +
        "        skill: \"skill-b\"\n" +
        "  ctx-stall:\n" +
        "    notes:\n" +
        "      - key: work-note\n" +
        "        role: work\n" +
        "        required: true\n" +
        "      - key: review-note\n" +
        "        role: review\n" +
        "        required: true\n"

/**
 * Independent tests (test-author seat, item f35528b3) for `items_read` select `context` (item mode and health mode),
 * scenarios S1-S6, S8-S10, S16 (context part), S17 and S18 of the frozen test-plan (NEW-SURFACE except S18), through the
 * production REST topology with a real bearer principal (`POST /api/v1/ops/items_read`).
 *
 * Oracles: task-scope (select `context` shape, D-a, D-b, D-d, D-e, D-f, D-g and acceptance criteria 1 and 5), the
 * pre-existing `GatePredicate` rule (a note is filled when its body is non-blank), the status-graph goldens under
 * `golden/p11` (the queue row of an auto type without a review phase, the terminal row `reopen -> queue`, the blocked row
 * `resume -> <previousRole>` resolved to the item's previous role), the `findByRoles` KDoc (role order queue, work, review,
 * blocked, terminal, then `role_changed_at` ascending, then id), the `ListPage` / envelope "List data" page shape, and the
 * Q19 scope rules (a target id outside a restricted scope is `forbidden` before anything else; lists, totals and counts are
 * visible-only).
 *
 * Wire-shape evidence for members the declarations do not list: the task-scope member names, and the re-recorded
 * `golden/a1-seatless/context_*.json` captures for the key/exists/filled schema entry and the string list in
 * `gateStatus.missing`. The id of a blocker is accepted in the compact 8-hex form or as the full UUID ([sameItem]); the
 * location of the health-mode `next` cursor is not declared, so [healthNext] reads `active.page.next`, then
 * `blocked.page.next`, then `page.next`.
 *
 * Ambiguities (not resolved from the implementation): the wire location of the health cursor; whether `claimSummary.unclaimed`
 * counts only active-role items (so only the claimed-active count is asserted numerically).
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ItemsReadContextTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun ctx(
        itemId: Any,
        extra: String = ""
    ) = """{"select":"context","itemId":"$itemId"$extra}"""

    private fun health(extra: String = "") = """{"select":"context"$extra}"""

    private fun gateOf(r: Reply): JsonObject = r.data.obj("gateStatus")

    private fun missingKeys(gate: JsonObject): List<String> =
        gate.arr("missing").map {
            if (it is JsonObject) it.str("key") else it.jsonPrimitive.content
        }

    private fun transitionsOf(r: Reply): Map<String, String> = r.data.obj("transitions").mapValues { it.value.jsonPrimitive.content }

    private val plainQueueRow =
        mapOf("block" to "blocked", "cancel" to "terminal", "complete" to "terminal", "hold" to "blocked", "start" to "work")

    // ------------------------------------------------------------------ S1, S2, S3

    @Test
    fun `S1 an unfilled required note blocks the gate and names its guidance and skill`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val item = rig.vwMake("Gated", type = "ctx-type")
            rig.vwNote(item, "note-a", "queue")

            val r = select(ctx(item.id))

            assertEquals(200, r.status, r.raw)
            val gate = gateOf(r)
            assertEquals(false, gate.boolOf("canAdvance"))
            assertEquals("queue", gate.str("phase"))
            assertEquals(listOf("note-b"), missingKeys(gate))
            assertEquals("note", gate.str("blockedBy"))
            assertEquals("note-b", r.data.str("guidanceKey"))
            assertEquals("skill-b", r.data.str("skillPointer"))
            assertFalse("next" in r.data, "no next step while the gate is closed: ${r.raw}")
            val entries = r.data.arr("schema").map { it.jsonObject }
            val a = entries.single { it.str("key") == "note-a" }
            assertEquals(true, a.boolOf("exists"))
            assertEquals(true, a.boolOf("filled"))
            val b = entries.single { it.str("key") == "note-b" }
            assertEquals(false, b.boolOf("exists"))
            assertEquals(false, b.boolOf("filled"))
            assertEquals("Gated", r.data.obj("item").str("title"))
        }

    @Test
    fun `S2 a whitespace-only or CRLF-only body is still missing and a one-character body is filled`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            for (blank in listOf("   ", "\r\n", "\r\n \t \r\n")) {
                val item = rig.vwMake("Blank-${blank.length}", type = "ctx-type")
                rig.vwNote(item, "note-a", "queue")
                rig.vwNote(item, "note-b", "queue", blank)

                val r = select(ctx(item.id))

                assertEquals(200, r.status, r.raw)
                assertEquals(false, gateOf(r).boolOf("canAdvance"), "blank body ${blank.length} chars: ${r.raw}")
                assertEquals(listOf("note-b"), missingKeys(gateOf(r)), "blank body ${blank.length} chars")
                val b =
                    r.data
                        .arr("schema")
                        .map { it.jsonObject }
                        .single { it.str("key") == "note-b" }
                assertEquals(true, b.boolOf("exists"), "the row exists")
                assertEquals(false, b.boolOf("filled"), "but a blank body is not filled")
            }
            val one = rig.vwMake("OneChar", type = "ctx-type")
            rig.vwNote(one, "note-a", "queue")
            rig.vwNote(one, "note-b", "queue", "x")
            val control = select(ctx(one.id))
            assertEquals(true, gateOf(control).boolOf("canAdvance"), "control: a one-character body fills the note: ${control.raw}")
        }

    @Test
    fun `S3 every required note filled and no dependency opens the gate with the next step and the queue row`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val item = rig.vwMake("Ready", type = "ctx-type")
            rig.vwNote(item, "note-a", "queue")
            rig.vwNote(item, "note-b", "queue")

            val r = select(ctx(item.id))

            assertEquals(200, r.status, r.raw)
            val gate = gateOf(r)
            assertEquals(true, gate.boolOf("canAdvance"))
            assertEquals("queue", gate.str("phase"))
            assertEquals(emptyList(), missingKeys(gate))
            assertFalse("blockedBy" in gate, "an open gate names no blocker: $gate")
            val next = r.data.obj("next")
            assertEquals("start", next.str("trigger"))
            assertEquals("work", next.str("role"))
            assertEquals(plainQueueRow, transitionsOf(r))
        }

    @Test
    fun `S3 a type with no notes is open at once and a hex prefix resolves the same item`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val item = rig.vwMake("Plain")

            for (ref in listOf(item.id.toString(), short(item.id))) {
                val r = select(ctx(ref))
                assertEquals(200, r.status, "$ref: ${r.raw}")
                assertEquals(true, gateOf(r).boolOf("canAdvance"), ref)
                assertEquals("Plain", r.data.obj("item").str("title"), ref)
            }
        }

    // ------------------------------------------------------------------ S4 dependency gate

    @Test
    fun `S4 an unmet BLOCKS dependency closes the gate and lists the blocker with its role and threshold`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val blocker = rig.vwMake("Blocker")
            val item = rig.vwMake("Waiting")
            rig.vwBlocks(blocker, item)

            val r = select(ctx(item.id))

            assertEquals(200, r.status, r.raw)
            val gate = gateOf(r)
            assertEquals(false, gate.boolOf("canAdvance"))
            assertEquals("dependency", gate.str("blockedBy"))
            val blockers = gate.arr("blockers").map { it.jsonObject }
            assertEquals(1, blockers.size, "$gate")
            assertTrue(sameItem(blockers.single().str("id"), blocker.id), "blocker id: ${blockers.single()}")
            assertEquals("queue", blockers.single().str("role"))
            assertEquals("terminal", blockers.single().str("threshold"), "an unset unblock_at is terminal")
            assertFalse("next" in r.data, "no next step: ${r.raw}")
        }

    @Test
    fun `S4 the unblock threshold decides satisfaction, a BLOCKED blocker never satisfies and RELATES_TO is ignored`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            // (blocker role, unblockAt wire, expected canAdvance)
            val cases =
                listOf(
                    Triple(Role.QUEUE, "work", false),
                    Triple(Role.WORK, "work", true),
                    Triple(Role.REVIEW, "work", true),
                    Triple(Role.WORK, "review", false),
                    Triple(Role.REVIEW, "review", true),
                    Triple(Role.WORK, "terminal", false),
                    Triple(Role.REVIEW, "terminal", false),
                    Triple(Role.TERMINAL, "terminal", true),
                    Triple(Role.TERMINAL, "queue", true),
                    Triple(Role.BLOCKED, "queue", false)
                )
            for ((role, threshold, expected) in cases) {
                val blocker = rig.vwMake("Blocker-$role-$threshold", role = role)
                val item = rig.vwMake("Item-$role-$threshold")
                rig.vwBlocks(blocker, item, threshold)

                val r = select(ctx(item.id))

                assertEquals(200, r.status, "$role/$threshold: ${r.raw}")
                assertEquals(expected, gateOf(r).boolOf("canAdvance"), "blocker $role, unblockAt $threshold: ${r.raw}")
                if (!expected) {
                    assertEquals("dependency", gateOf(r).str("blockedBy"), "$role/$threshold")
                    assertEquals(
                        threshold,
                        gateOf(r)
                            .arr("blockers")
                            .first()
                            .jsonObject
                            .str("threshold"),
                        "$role/$threshold"
                    )
                }
            }
            val relatedFrom = rig.vwMake("Related-from")
            val related = rig.vwMake("Related-to")
            rig.raw.dependencyRepository().create(relatesDep(relatedFrom.id, related.id))
            assertEquals(true, gateOf(select(ctx(related.id))).boolOf("canAdvance"), "RELATES_TO never blocks")
        }

    // ------------------------------------------------------------------ S5, S6 transitions

    @Test
    fun `S5 a terminal item cannot advance, reports no blocker or next step and offers only reopen`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val blocker = rig.vwMake("Pending blocker")
            val item = rig.vwMake("Done", role = Role.TERMINAL, type = "ctx-type")
            rig.vwBlocks(blocker, item)

            val r = select(ctx(item.id))

            assertEquals(200, r.status, r.raw)
            val gate = gateOf(r)
            assertEquals(false, gate.boolOf("canAdvance"))
            assertEquals("terminal", gate.str("phase"))
            for (absent in listOf("blockedBy", "missingBySeat", "violations")) {
                assertFalse(absent in gate, "a terminal gate carries no $absent: $gate")
            }
            assertFalse("next" in r.data, "no next step for a terminal item: ${r.raw}")
            assertEquals(mapOf("reopen" to "queue"), transitionsOf(r))
        }

    @Test
    fun `S6 a blocked item offers resume resolved to its previous role`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            for (previous in listOf(Role.WORK, Role.REVIEW, Role.QUEUE)) {
                val item = rig.vwMake("Held-$previous", role = Role.BLOCKED, previousRole = previous)

                val r = select(ctx(item.id))

                assertEquals(200, r.status, "$previous: ${r.raw}")
                assertEquals(
                    mapOf("cancel" to "terminal", "resume" to previous.name.lowercase()),
                    transitionsOf(r),
                    "blocked from $previous"
                )
                assertEquals(false, gateOf(r).boolOf("canAdvance"), "a start from BLOCKED is not allowed")
            }
        }

    // ------------------------------------------------------------------ S8 config fault

    private val rootYaml =
        "work_item_schemas:\n" +
            "  p11-gated:\n" +
            "    notes:\n" +
            "      - key: spec\n        role: queue\n        required: true\n"

    @Test
    fun `S8 a per-root config read fault after a warm read answers unavailable and no gate`() =
        irApp(db.db, dir, yaml = P11_BASE_YAML) { rig ->
            val d = P11Driver(rig)
            val fx = d.configFaultFixture()
            d.breakConfigReads()

            val r = select(ctx(fx.item.id))

            assertEquals(ErrorCode.UNAVAILABLE.httpStatus, r.status, r.raw)
            assertEquals("unavailable", r.code)
            assertFalse("gateStatus" in r.raw || "canAdvance" in r.raw, "no fallback gate is served: ${r.raw}")
        }

    @Test
    fun `S8 a per-root config read fault before any warm read also answers unavailable`() =
        irApp(db.db, dir, yaml = P11_BASE_YAML) { rig ->
            val d = P11Driver(rig)
            val root = d.item("cold root", Role.WORK)
            val child = d.item("cold item", Role.QUEUE, type = "p11-gated", parent = root)
            d.note(child, "spec", "queue")
            rig.raw.configStore().upsert(root.id, rootYaml)
            d.breakConfigReads()

            val r = select(ctx(child.id))

            assertEquals(ErrorCode.UNAVAILABLE.httpStatus, r.status, r.raw)
            assertEquals("unavailable", r.code)
        }

    // ------------------------------------------------------------------ item-mode errors and strictness

    @Test
    fun `S1 an unknown full id is not_found and a malformed id is invalid_request on itemId`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            rig.vwMake("Anything")

            val missing = select(ctx("99999999-9999-4999-8999-999999999999"))
            assertEquals(404, missing.status, missing.raw)
            assertEquals("not_found", missing.code)

            val bad = select(ctx("xyz"))
            assertEquals("invalid_request", bad.code, bad.raw)
            assertTrue("/itemId" in bad.fields(), bad.fields().toString())
        }

    @Test
    fun `S1 fields ancestors adds the visible ancestors root-first to the item and an unknown fields name is rejected`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val top = rig.vwMake("Top")
            val mid = rig.vwMake("Mid", top)
            val leaf = rig.vwMake("Leaf", mid)

            val plain = select(ctx(leaf.id))
            assertFalse("ancestors" in plain.data.obj("item"), "ancestors are opt-in: ${plain.raw}")

            val with = select(ctx(leaf.id, ""","fields":["ancestors"]"""))
            assertEquals(200, with.status, with.raw)
            assertEquals(
                listOf(short(top.id), short(mid.id)),
                with.data
                    .obj("item")
                    .arr("ancestors")
                    .map { it.jsonObject.str("id") }
            )

            for (bad in listOf("summary", "guidance", "bogus")) {
                val r = select(ctx(leaf.id, ""","fields":["$bad"]"""))
                assertEquals("invalid_request", r.code, "fields [$bad]: ${r.raw}")
                assertTrue(r.fields().any { it.startsWith("/fields") }, "fields [$bad]: ${r.fields()}")
                assertTrue("ancestors" in r.reasons().joinToString(" "), "the allowed name is listed: ${r.reasons()}")
            }
        }

    @Test
    fun `D-g the dropped 3x parameters are unknown_parameter`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val item = rig.vwMake("Strict")
            val dropped =
                listOf(
                    "mode" to "\"item\"",
                    "includeAncestors" to "true",
                    "includeDetails" to "true",
                    "since" to "\"2026-01-01T00:00:00Z\""
                )

            for ((member, value) in dropped) {
                val r = select(ctx(item.id, ""","$member":$value"""))
                assertEquals("unknown_parameter", r.code, "$member: ${r.raw}")
                assertEquals(listOf("/$member"), r.parameters(), member)
            }
        }

    // ------------------------------------------------------------------ S9 health mode

    private class Stall(
        val w1: WorkItem,
        val w2: WorkItem,
        val w3: WorkItem,
        val r1: WorkItem,
        val r2: WorkItem
    )

    /** 3 WORK + 2 REVIEW items, role-changed in the order w1 < r1 < w2 < r2 < w3; w2 and r2 lack their current-role note. */
    private suspend fun seedStall(rig: EventLogRig): Stall {
        val t0 = Instant.parse("2026-02-01T00:00:00Z")
        val w1 = rig.vwMake("w1", role = Role.WORK, type = "ctx-stall", at = t0)
        val w2 = rig.vwMake("w2", role = Role.WORK, type = "ctx-stall", at = t0.plusSeconds(10))
        val w3 = rig.vwMake("w3", role = Role.WORK, type = "ctx-stall", at = t0.plusSeconds(20))
        val r1 = rig.vwMake("r1", role = Role.REVIEW, type = "ctx-stall", at = t0.plusSeconds(5))
        val r2 = rig.vwMake("r2", role = Role.REVIEW, type = "ctx-stall", at = t0.plusSeconds(15))
        rig.vwNote(w1, "work-note", "work")
        rig.vwNote(w3, "work-note", "work")
        rig.vwNote(r1, "review-note", "review")
        return Stall(w1, w2, w3, r1, r2)
    }

    private fun healthNext(data: JsonObject): String? =
        data.obj("active").blockNext()
            ?: data.obj("blocked").blockNext()
            ?: data["page"]
                ?.jsonObject
                ?.get("next")
                ?.jsonPrimitive
                ?.contentOrNull

    private fun stalledIds(data: JsonObject): Set<String> = data.arr("stalled").map { it.jsonObject.str("id") }.toSet()

    @Test
    fun `S9 one limit and cursor page the active items in role then role-changed order with exact totals and per-page stalled`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val s = seedStall(rig)
            val expectedOrder = listOf(s.w1, s.w2, s.w3, s.r1, s.r2).map { short(it.id) }
            val seen = mutableListOf<String>()
            val stalledPerPage = mutableListOf<Set<String>>()
            var cursor: String? = null
            var pages = 0

            do {
                val extra = ""","limit":2""" + (cursor?.let { ""","cursor":"$it"""" } ?: "")
                val r = select(health(extra))
                assertEquals(200, r.status, r.raw)
                val active = r.data.obj("active")
                assertEquals(5, active.blockTotal(), "total is exact on every page: ${r.raw}")
                assertTrue(active.blockIds().size <= 2, "page size: ${r.raw}")
                assertEquals(0, r.data.obj("blocked").blockTotal(), "nothing is blocked")
                seen += active.blockIds()
                stalledPerPage += stalledIds(r.data)
                cursor = healthNext(r.data)
                pages++
                if (cursor != null) {
                    assertTrue(active.obj("page").boolOf("truncated"), "a page with a cursor is truncated: ${r.raw}")
                }
            } while (cursor != null && pages < 10)

            assertEquals(3, pages)
            assertEquals(expectedOrder, seen, "WORK before REVIEW, then role_changed_at ascending; five distinct ids")
            assertEquals(5, seen.distinct().size)
            assertEquals(
                listOf(setOf(short(s.w2.id)), emptySet(), setOf(short(s.r2.id))),
                stalledPerPage,
                "stalled = the page's items missing a required note of their CURRENT role"
            )
        }

    @Test
    fun `S9 a stalled entry names the item, its role and the missing keys`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val s = seedStall(rig)

            val r = select(health())

            assertEquals(200, r.status, r.raw)
            val stalled = r.data.arr("stalled").map { it.jsonObject }
            assertEquals(setOf(short(s.w2.id), short(s.r2.id)), stalled.map { it.str("id") }.toSet())
            val w2 = stalled.single { it.str("id") == short(s.w2.id) }
            assertEquals("w2", w2.str("title"))
            assertEquals("work", w2.str("role"))
            assertEquals(listOf("work-note"), w2.arr("missing").map { it.jsonPrimitive.content })
            val r2 = stalled.single { it.str("id") == short(s.r2.id) }
            assertEquals("review", r2.str("role"))
            assertEquals(listOf("review-note"), r2.arr("missing").map { it.jsonPrimitive.content })
        }

    @Test
    fun `S9 the default page is 50 and the limit bounds are 1 and 200`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            repeat(55) { rig.vwMake("busy-$it", role = Role.WORK) }

            val default = select(health())
            assertEquals(
                50,
                default.data
                    .obj("active")
                    .blockIds()
                    .size
            )
            assertEquals(55, default.data.obj("active").blockTotal())
            assertNotNull(healthNext(default.data), "a truncated default page carries a cursor")

            val all = select(health(""","limit":200"""))
            assertEquals(
                55,
                all.data
                    .obj("active")
                    .blockIds()
                    .size
            )
            assertNull(healthNext(all.data), "everything fits in one page")

            for (bad in listOf("0", "201", "-1")) {
                val r = select(health(""","limit":$bad"""))
                assertEquals("invalid_request", r.code, "limit $bad: ${r.raw}")
                assertTrue("/limit" in r.fields(), "limit $bad: ${r.fields()}")
            }
            assertEquals(
                1,
                select(health(""","limit":1"""))
                    .data
                    .obj("active")
                    .blockIds()
                    .size
            )
            val garbage = select(health(""","cursor":"not-a-cursor""""))
            assertEquals("invalid_cursor", garbage.code, garbage.raw)
            assertEquals(400, garbage.status)
        }

    @Test
    fun `S9 ancestorId scopes the health lists to the subtree including its root`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val tree = rig.vwMake("Tree", role = Role.WORK)
            val inside = rig.vwMake("Inside", tree, role = Role.WORK)
            rig.vwMake("Outside", role = Role.WORK)
            val held = rig.vwMake("Held", tree, role = Role.BLOCKED)
            rig.vwMake("OutsideHeld", role = Role.BLOCKED)

            val r = select(health(""","ancestorId":"${tree.id}""""))

            assertEquals(200, r.status, r.raw)
            assertEquals(
                setOf(short(tree.id), short(inside.id)),
                r.data
                    .obj("active")
                    .blockIds()
                    .toSet()
            )
            assertEquals(2, r.data.obj("active").blockTotal())
            assertEquals(listOf(short(held.id)), r.data.obj("blocked").blockIds())
            assertEquals(1, r.data.obj("blocked").blockTotal())
        }

    @Test
    fun `S9 an empty database answers empty lists, an empty stalled list and zero claim counts`() =
        irApp(db.db, dir, yaml = CTX_YAML) { _ ->
            val r = select(health())

            assertEquals(200, r.status, r.raw)
            assertEquals(0, r.data.obj("active").blockTotal())
            assertEquals(emptyList(), r.data.obj("active").blockIds())
            assertEquals(0, r.data.obj("blocked").blockTotal())
            assertEquals(0, r.data.arr("stalled").size)
            val claims = r.data.obj("claimSummary")
            for (name in listOf("active", "expired", "unclaimed")) assertEquals(0, claims.intOf(name), name)
        }

    // ------------------------------------------------------------------ S10 define blocked once

    private class BlockedSet(
        val explicit: WorkItem,
        val queueDep: WorkItem,
        val workDep: WorkItem,
        val satisfied: WorkItem
    )

    private suspend fun seedBlockedSet(
        rig: EventLogRig,
        tags: String?
    ): BlockedSet {
        val explicit = rig.vwMake("explicit", role = Role.BLOCKED, tags = tags)
        val queueDep = rig.vwMake("queueDep", role = Role.QUEUE, tags = tags)
        val workDep = rig.vwMake("workDep", role = Role.WORK, tags = tags)
        val satisfied = rig.vwMake("satisfied", role = Role.QUEUE, tags = tags)
        val blocker = rig.vwMake("blocker", role = Role.QUEUE, tags = tags)
        val done = rig.vwMake("done", role = Role.TERMINAL, tags = tags)
        rig.vwBlocks(blocker, queueDep)
        rig.vwBlocks(blocker, workDep)
        rig.vwBlocks(done, satisfied)
        return BlockedSet(explicit, queueDep, workDep, satisfied)
    }

    @Test
    fun `S10 the health blocked list is the blocked select's set, with a blockType per entry and no blockers detail`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val b = seedBlockedSet(rig, tags = null)

            val healthReply = select(health())
            val blockedReply = select("""{"select":"blocked"}""")

            assertEquals(200, healthReply.status, healthReply.raw)
            val healthBlocked = healthReply.data.obj("blocked")
            assertEquals(blockedReply.ids(), healthBlocked.blockIds().toSet())
            assertEquals(blockedReply.total, healthBlocked.blockTotal())
            assertEquals(setOf(short(b.explicit.id), short(b.queueDep.id), short(b.workDep.id)), blockedReply.ids())
            assertFalse(short(b.satisfied.id) in blockedReply.ids(), "an item whose only blocker is satisfied is not blocked")
            val types = healthBlocked.arr("items").map { it.jsonObject }.associate { it.str("id") to it.str("blockType") }
            assertEquals("explicit", types.getValue(short(b.explicit.id)))
            assertEquals("dependency", types.getValue(short(b.queueDep.id)))
            assertEquals("dependency", types.getValue(short(b.workDep.id)))
            for (entry in healthBlocked.arr("items")) {
                assertFalse("blockers" in entry.jsonObject, "health mode carries no blockers detail: $entry")
            }
        }

    @Test
    fun `S10 the two definitions agree under a tag scope too`() =
        irApp(db.db, dir, tagsInclude = setOf("api"), yaml = CTX_YAML) { rig ->
            seedBlockedSet(rig, tags = "api")
            seedBlockedSet(rig, tags = null)

            val healthReply = select(health(), TEST_TOKEN)
            val blockedReply = select("""{"select":"blocked"}""", TEST_TOKEN)

            assertEquals(
                blockedReply.ids(),
                healthReply.data
                    .obj("blocked")
                    .blockIds()
                    .toSet()
            )
            assertEquals(3, blockedReply.total, "only the three visible blocked items: ${blockedReply.raw}")
            assertEquals(3, healthReply.data.obj("blocked").blockTotal())
        }

    // ------------------------------------------------------------------ S16 scope

    @Test
    fun `S16 a tag scope lists and counts only visible items in health mode and hides claim counts of invisible items`() =
        irApp(db.db, dir, tagsInclude = setOf("api"), yaml = CTX_YAML) { rig ->
            val vis = rig.vwMake("Vis-claimed", role = Role.WORK, tags = "api", claimedBy = "agent-visible")
            val visPlain = rig.vwMake("Vis-plain", role = Role.WORK, tags = "api,backend")
            val visHeld = rig.vwMake("Vis-held", role = Role.BLOCKED, tags = "api")
            val hid = rig.vwMake("Hid-claimed", role = Role.WORK, claimedBy = "agent-hidden")
            rig.vwMake("Hid-two", role = Role.WORK, tags = "apix")
            rig.vwMake("Hid-three", role = Role.WORK)
            rig.vwMake("Hid-held", role = Role.BLOCKED)

            val scoped = select(health(), TEST_TOKEN)
            val open = select(health())

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(
                setOf(short(vis.id), short(visPlain.id)),
                scoped.data
                    .obj("active")
                    .blockIds()
                    .toSet()
            )
            assertEquals(2, scoped.data.obj("active").blockTotal())
            assertEquals(listOf(short(visHeld.id)), scoped.data.obj("blocked").blockIds())
            assertEquals(1, scoped.data.obj("blocked").blockTotal())
            assertEquals(1, scoped.data.obj("claimSummary").intOf("active"), "only the visible claim is counted")
            for (secret in listOf("Hid-", "agent-hidden", "agent-visible", short(hid.id))) {
                assertFalse(secret in scoped.raw, "no trace of '$secret': ${scoped.raw}")
            }
            assertEquals(5, open.data.obj("active").blockTotal(), "control: the unrestricted caller sees all five")
            assertEquals(2, open.data.obj("claimSummary").intOf("active"))
            assertTrue(
                scoped.data.obj("claimSummary").intOf("unclaimed") < open.data.obj("claimSummary").intOf("unclaimed"),
                "invisible unclaimed items are not counted"
            )
        }

    @Test
    fun `S16 a root scope lists only the subtree of the listed root in health mode`() {
        val r1 = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a")
        val r2 = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b")
        irApp(db.db, dir, scopeRootIds = setOf(r1), yaml = CTX_YAML) { rig ->
            val root1 = rig.vwMake("Mine", id = r1, role = Role.WORK)
            val own = rig.vwMake("Mine-child", root1, role = Role.WORK)
            val root2 = rig.vwMake("Theirs", id = r2, role = Role.WORK)
            rig.vwMake("Theirs-child", root2, role = Role.WORK)

            val scoped = select(health(), TEST_TOKEN)

            assertEquals(
                setOf(short(root1.id), short(own.id)),
                scoped.data
                    .obj("active")
                    .blockIds()
                    .toSet()
            )
            assertEquals(2, scoped.data.obj("active").blockTotal())
            assertFalse("Theirs" in scoped.raw, scoped.raw)
        }
    }

    @Test
    fun `S16 an item outside the scope is forbidden, never not_found, for a UUID, a prefix and a missing id`() =
        irApp(db.db, dir, tagsInclude = setOf("api"), yaml = CTX_YAML) { rig ->
            val visible = rig.vwMake("Visible", tags = "api")
            val secret = rig.vwMake("Secret-title")
            val child = rig.vwMake("Untagged-child", visible)

            assertEquals(200, select(ctx(visible.id), TEST_TOKEN).status)
            for (ref in listOf(secret.id.toString(), short(secret.id), child.id.toString(), "99999999-9999-4999-8999-999999999999")) {
                val r = select(ctx(ref), TEST_TOKEN)
                assertEquals(403, r.status, "$ref: ${r.raw}")
                assertEquals("forbidden", r.code, ref)
                assertEquals("tag", r.detail.str("scope"), ref)
                assertEquals("api", r.detail.str("required"), ref)
                assertFalse("Secret-title" in r.raw, "no trace of the hidden item: ${r.raw}")
            }
            assertEquals(404, select(ctx("99999999-9999-4999-8999-999999999999")).status, "control: unrestricted sees not_found")
        }

    @Test
    fun `S16 a prefix shared by an admitted and a hidden item resolves to the admitted one and is ambiguous unrestricted`() =
        irApp(db.db, dir, tagsInclude = setOf("api"), yaml = CTX_YAML) { rig ->
            val inside = rig.vwMake("Inside-twin", tags = "api", id = UUID.fromString("abcd1234-0000-4000-8000-000000000001"))
            rig.vwMake("Outside-twin", id = UUID.fromString("abcd1234-7777-4777-8777-777777777777"))

            val scoped = select(ctx("abcd1234"), TEST_TOKEN)
            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(inside.title, scoped.data.obj("item").str("title"))
            assertFalse("Outside-twin" in scoped.raw, scoped.raw)

            val open = select(ctx("abcd1234"))
            assertEquals("ambiguous_id", open.code, open.raw)
        }

    @Test
    fun `S16 a blocker outside the scope is omitted from blockers while the item stays blocked`() =
        irApp(db.db, dir, tagsInclude = setOf("api"), yaml = CTX_YAML) { rig ->
            val hiddenBlocker = rig.vwMake("Hidden-blocker")
            val visibleBlocker = rig.vwMake("Visible-blocker", tags = "api")
            val item = rig.vwMake("Waiting", tags = "api")
            rig.vwBlocks(hiddenBlocker, item)
            rig.vwBlocks(visibleBlocker, item)

            val scoped = select(ctx(item.id), TEST_TOKEN)
            val open = select(ctx(item.id))

            assertEquals(false, gateOf(scoped).boolOf("canAdvance"), "satisfaction is computed on the true roles: ${scoped.raw}")
            assertEquals("dependency", gateOf(scoped).str("blockedBy"))
            val visibleOnly = gateOf(scoped).arr("blockers").map { it.jsonObject.str("id") }
            assertEquals(1, visibleOnly.size, scoped.raw)
            assertTrue(sameItem(visibleOnly.single(), visibleBlocker.id))
            assertFalse(short(hiddenBlocker.id) in scoped.raw || "Hidden-blocker" in scoped.raw, "no topology leak: ${scoped.raw}")
            assertEquals(2, gateOf(open).arr("blockers").size, "control: the unrestricted caller sees both blockers")
        }

    @Test
    fun `S16 when the only blocker is hidden the item still cannot advance and no blocker is named`() =
        irApp(db.db, dir, tagsInclude = setOf("api"), yaml = CTX_YAML) { rig ->
            val hiddenBlocker = rig.vwMake("Hidden-blocker")
            val item = rig.vwMake("Waiting", tags = "api")
            rig.vwBlocks(hiddenBlocker, item)

            val scoped = select(ctx(item.id), TEST_TOKEN)

            assertEquals(false, gateOf(scoped).boolOf("canAdvance"), scoped.raw)
            assertEquals("dependency", gateOf(scoped).str("blockedBy"), scoped.raw)
            assertFalse(short(hiddenBlocker.id) in scoped.raw, scoped.raw)
        }

    // ------------------------------------------------------------------ S17 profile visibility

    private val registry = ToolRegistry(buildToolSpecs())

    private fun selectEnum(profile: ToolProfile): List<String> {
        val spec = checkNotNull(registry[ItemsReadTool.NAME]) { "items_read is not registered" }
        val schema = checkNotNull(ToolSchemaRenderer.inputSchema(spec, profile)) { "items_read has no schema in $profile" }
        return schema
            .obj("properties")
            .obj("select")
            .arr("enum")
            .map { it.jsonPrimitive.content }
    }

    @Test
    fun `S17 the orchestration profile offers context next blocked and history and the knowledge profile does not`() {
        assertEquals(
            listOf("get", "list", "schema", "search", "overview", "related", "context", "next", "blocked", "history"),
            selectEnum(ToolProfile.ORCHESTRATION)
        )
        assertEquals(listOf("get", "list", "schema", "search", "overview", "related"), selectEnum(ToolProfile.KNOWLEDGE))
    }

    @Test
    fun `S17 a knowledge-profile call of a hidden select is refused while get still answers`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val item = rig.vwMake("Knowledge probe")
            val spec = checkNotNull(registry[ItemsReadTool.NAME])
            val dispatcher = buildToolDispatcher(rig.ctx)

            suspend fun knowledge(args: JsonObject) =
                dispatcher
                    .dispatch(spec, ToolProfile.KNOWLEDGE, args, "probe-req", CallLogRecord.SURFACE_MCP, CallAccess.LOCAL)
                    .envelope.body

            for (hidden in listOf("context", "next", "blocked")) {
                val body = knowledge(buildJsonObject { put("select", hidden) })
                assertEquals("false", (body["ok"] as JsonPrimitive).content, "select $hidden must be refused in KNOWLEDGE: $body")
                assertNull(body["data"], "no data for a hidden select: $body")
            }
            val control =
                knowledge(
                    buildJsonObject {
                        put("select", "get")
                        put("itemId", item.id.toString())
                    }
                )
            assertEquals("true", (control["ok"] as JsonPrimitive).content, "control: get is offered in KNOWLEDGE: $control")
        }

    @Test
    fun `S17 the compact items_read orchestration entry stays within the per-tool ceiling`() {
        val size = ToolSurfaceChecks.sizes(registry, ToolProfile.ORCHESTRATION).getValue(ItemsReadTool.NAME)

        assertTrue(size <= ToolSurfaceChecks.PER_TOOL_CEILING, "items_read orchestration entry is $size chars")
        val knowledge = ToolSurfaceChecks.sizes(registry, ToolProfile.KNOWLEDGE).getValue(ItemsReadTool.NAME)
        assertTrue(knowledge < size, "the knowledge entry ($knowledge) is smaller than the orchestration entry ($size)")
    }

    // ------------------------------------------------------------------ S18 the removed gate route

    @Test
    fun `S18 GET items id gate answers 404 and the ops context select answers the gate state`() =
        irApp(db.db, dir, yaml = CTX_YAML) { rig ->
            val item = rig.vwMake("Gate probe", type = "ctx-type")

            val gone = get("/items/${item.id}/gate")
            assertEquals(404, gone.status, gone.raw)
            assertFalse("gateStatus" in gone.raw || "canAdvance" in gone.raw, "the route serves no gate: ${gone.raw}")
            val byPrefix = get("/items/${short(item.id)}/gate")
            assertEquals(404, byPrefix.status, "a prefix form is gone too: ${byPrefix.raw}")

            val replacement = select(ctx(item.id))
            assertEquals(200, replacement.status, "the replacement answers: ${replacement.raw}")
            assertEquals(false, gateOf(replacement).boolOf("canAdvance"))
            assertEquals(listOf("note-a", "note-b"), missingKeys(gateOf(replacement)).sorted())
        }
}

/**
 * Independent tests (test-author seat, item f35528b3) for scenario S7 of the frozen test-plan: the claim detail of `context`
 * item mode, with the clock moved instead of sleeping.
 *
 * Oracle: a claim is active while its expiry is strictly after the unit instant, so an instant equal to the expiry is
 * already expired (the `findEligible` KDoc and `ClaimState.isActive` contract); `claimDetail` is the sole sanctioned
 * claim-identity disclosure (task-scope). The clock is frozen at T; a claim of ttl 60 expires at E = T + 60 s.
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadContextClaimTest {
    private val clock = SettableClock(T)

    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod(clock = clock)

    private val probe = ItemContextProbe()

    private suspend fun detail(
        rig: EventLogRig,
        item: WorkItem
    ): JsonObject? {
        val body = probe.envelope(rig.ctx, buildJsonObject { put("itemId", item.id.toString()) })
        assertEquals("true", (body["ok"] as JsonPrimitive).content, "$body")
        return body.obj("data")["claimDetail"]?.jsonObject
    }

    @Test
    fun `S7 a claimed item reports the holder and isExpired flips from false to true at exactly the expiry`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(sqlite.db, dir, P11_BASE_YAML, clock)
            val driver = P11Driver(rig)
            val item = driver.item("Claimed", Role.QUEUE, type = "p11-optional")
            val expiry = T.plusSeconds(TTL.toLong())
            rig.ctx.claimService
                .claim(item.id, "agent-a", TTL)
                .ok()

            val live = detail(rig, item)
            assertNotNull(live, "a claimed item carries claimDetail")
            assertEquals("agent-a", live.str("claimedBy"))
            assertEquals(false, live.boolOf("isExpired"))

            clock.set(expiry.minusMillis(1))
            assertEquals(false, detail(rig, item)!!.boolOf("isExpired"), "one millisecond before the expiry the claim still holds")

            clock.set(expiry)
            assertEquals(true, detail(rig, item)!!.boolOf("isExpired"), "at exactly the expiry the claim is expired")

            clock.set(expiry.plusSeconds(1))
            assertEquals(true, detail(rig, item)!!.boolOf("isExpired"), "and stays expired afterwards")
        }

    @Test
    fun `S7 an unclaimed item carries no claim detail`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(sqlite.db, dir, P11_BASE_YAML, clock)
            val item = P11Driver(rig).item("Free", Role.QUEUE, type = "p11-optional")

            assertNull(detail(rig, item), "no claim, no claimDetail")
        }

    private companion object {
        val T: Instant = Instant.parse("2026-03-01T10:00:00Z")
        const val TTL = 60
    }
}

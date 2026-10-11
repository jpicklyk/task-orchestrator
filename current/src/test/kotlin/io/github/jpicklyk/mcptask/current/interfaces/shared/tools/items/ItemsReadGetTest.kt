package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 450aa813) for `items_read` select `get`: scenarios S1, S2 and the get part of
 * S13 of the frozen test-plan (all NEW-SURFACE), through the production REST topology (`POST /api/v1/ops/items_read`).
 *
 * Oracles (task-scope item numbers): 2 (ItemView: lean omits null / empty string / empty array / requiresVerification=false;
 * `full` members; `version` always), 3 (`fields` is an additive opt-in validated per select, unknown name is
 * `invalid_request` on `/fields` listing the allowed names), 4 (`get {itemId, fields?, ifVersion?}`: `ifVersion == version`
 * gives `{unchanged: true, version}`, else the full view), D2 (ids resolve as 8..32 hex or a UUID, any case), D5 (`fields`
 * is additive), the dispatcher's strictness (unknown member is `unknown_parameter`, a bad value is `invalid_request`), and the
 * envelope (ok/error bodies, statuses). Expected values are literals or are read from the stored row, never from a run.
 *
 * Not asserted (no oracle that fixes the wire form): the JSON type of `properties` / `metadata` ("as stored"), and the
 * content of `diagnostics`. ifVersion = -1 is unspecified and not probed. Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadGetTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun getArgs(
        id: String,
        extra: String = ""
    ) = """{"select":"get","itemId":"$id"$extra}"""

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 get answers the lean full view - full ids, enums, tags and traits as arrays, version, nothing null or empty`() =
        irApp(db.db, dir) { rig ->
            val top = rig.make("Top")
            val mid = rig.make("Mid", top)
            val leaf =
                rig.make(
                    "Leaf",
                    mid,
                    tags = "alpha, beta ,,gamma",
                    traits = listOf("t1", "t2"),
                    summary = "leaf summary",
                    priority = Priority.HIGH
                )
            val stored = rig.raw.workItemRepository().getById(leaf.id)!!

            val r = select(getArgs(leaf.id.toString()))

            assertEquals(200, r.status)
            assertTrue(r.ok, r.raw)
            val d = r.data
            assertEquals(leaf.id.toString(), d.str("id"))
            assertEquals(mid.id.toString(), d.str("parentId"))
            assertEquals(top.id.toString(), d.str("rootId"))
            assertEquals("test-item", d.str("type"))
            assertEquals("Leaf", d.str("title"))
            assertEquals("leaf summary", d.str("summary"))
            assertEquals("queue", d.str("role"))
            assertEquals("high", d.str("priority"))
            assertEquals(
                2,
                d
                    .getValue("depth")
                    .jsonPrimitive.long
                    .toInt()
            )
            assertEquals(listOf("alpha", "beta", "gamma"), d.strings("tags"), "CSV split, trimmed, empties dropped, as an array")
            assertEquals(listOf("t1", "t2"), d.strings("traits"))
            assertEquals(stored.version, d.getValue("version").jsonPrimitive.long)
            for (absent in listOf("description", "previousRole", "complexity", "requiresVerification", "createdAt", "modifiedAt")) {
                assertFalse(absent in d, "lean view omits $absent (null / false): ${r.raw}")
            }
            for (absent in listOf("roleChangedAt", "ancestors", "claimedBy", "claimExpiresAt")) {
                assertFalse(absent in d, "$absent is not part of the default view: ${r.raw}")
            }
        }

    @Test
    fun `S1 a bare root omits parent, root, empty summary, empty tags and empty traits`() =
        irApp(db.db, dir) { rig ->
            val bare = rig.make("Bare")

            val d = select(getArgs(bare.id.toString())).data

            assertEquals("Bare", d.str("title"))
            for (absent in listOf("parentId", "summary", "tags", "traits")) {
                assertFalse(absent in d, "$absent is null / empty and must be omitted: $d")
            }
            assertTrue("version" in d, "version is always present")
        }

    @Test
    fun `S1 fields createdAt modifiedAt roleChangedAt add exactly those timestamps`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Timed")

            val d = select(getArgs(item.id.toString(), ""","fields":["createdAt","modifiedAt","roleChangedAt"]""")).data

            for (name in listOf("createdAt", "modifiedAt", "roleChangedAt")) {
                Instant.parse(d.str(name))
            }
            assertFalse("ancestors" in d)
        }

    @Test
    fun `S1 fields createdAt alone adds createdAt and not the other timestamps`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("OnlyCreated")

            val d = select(getArgs(item.id.toString(), ""","fields":["createdAt"]""")).data

            Instant.parse(d.str("createdAt"))
            assertFalse("modifiedAt" in d)
            assertFalse("roleChangedAt" in d)
        }

    @Test
    fun `S1 fields ancestors adds compact ancestors root-first with 8-hex short ids`() =
        irApp(db.db, dir) { rig ->
            val top = rig.make("Top")
            val mid = rig.make("Mid", top, summary = "mid summary")
            val leaf = rig.make("Leaf", mid)

            val d = select(getArgs(leaf.id.toString(), ""","fields":["createdAt","ancestors"]""")).data

            Instant.parse(d.str("createdAt"))
            val ancestors = d.arr("ancestors").map { it as kotlinx.serialization.json.JsonObject }
            assertEquals(listOf(short(top.id), short(mid.id)), ancestors.map { it.str("id") }, "root-first compact views")
            assertEquals(listOf("Top", "Mid"), ancestors.titles())
            assertFalse("parentId" in ancestors[0], "the root has no parent")
            assertEquals(short(top.id), ancestors[1].str("parentId"))
            assertEquals(
                0,
                ancestors[0]
                    .getValue("depth")
                    .jsonPrimitive.long
                    .toInt()
            )
            assertEquals(
                1,
                ancestors[1]
                    .getValue("depth")
                    .jsonPrimitive.long
                    .toInt()
            )
            assertTrue("version" in ancestors[1])
            assertFalse("summary" in ancestors[1], "compact views carry no summary: ${ancestors[1]}")
        }

    @Test
    fun `S1 ids resolve as UUID in any case, 32-hex, and 8-hex prefix, in any case`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Resolvable", id = UUID.fromString("5ae0c3d1-0000-4000-8000-0000000000aa"))
            val full = item.id.toString()

            for (form in listOf(full, full.uppercase(), full.replace("-", ""), full.substring(0, 8), full.substring(0, 8).uppercase())) {
                val r = select(getArgs(form))
                assertEquals(200, r.status, "form $form: ${r.raw}")
                assertEquals(full, r.data.str("id"), "form $form")
            }
        }

    @Test
    fun `S1 replaying the same get answers the same data`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Replayed", tags = "x")

            val first = select(getArgs(item.id.toString())).data
            val second = select(getArgs(item.id.toString())).data

            assertEquals(first, second)
        }

    // ------------------------------------------------------------------ S2

    @Test
    fun `S2 ifVersion equal to the version answers only unchanged and version`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Versioned")
            val v =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version

            val r = select(getArgs(item.id.toString(), ""","ifVersion":$v"""))

            assertEquals(200, r.status)
            assertEquals(setOf("unchanged", "version"), r.data.keys)
            assertTrue(
                r.data
                    .getValue("unchanged")
                    .jsonPrimitive.boolean
            )
            assertEquals(
                v,
                r.data
                    .getValue("version")
                    .jsonPrimitive.long
            )
        }

    @Test
    fun `S2 ifVersion that differs answers the full view, for lower and higher values`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Versioned", tags = "k")
            val v =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version

            for (other in listOf(v + 5, v + 1, 0L).filter { it != v }) {
                val d = select(getArgs(item.id.toString(), ""","ifVersion":$other""")).data
                assertFalse("unchanged" in d, "ifVersion $other != $v must not answer unchanged: $d")
                assertEquals("Versioned", d.str("title"))
                assertEquals(v, d.getValue("version").jsonPrimitive.long)
            }
        }

    @Test
    fun `S2 after a write the old ifVersion answers the full view with the new version`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Before")
            val old =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version
            assertTrue(
                select(getArgs(item.id.toString(), ""","ifVersion":$old"""))
                    .data
                    .getValue("unchanged")
                    .jsonPrimitive.boolean,
                "control"
            )

            rig.ctx.itemCommandService
                .patch(patchOf(rig.raw.workItemRepository().getById(item.id)!!, title = "After"))
                .orFail()

            val d = select(getArgs(item.id.toString(), ""","ifVersion":$old""")).data
            assertFalse("unchanged" in d, "the version moved, so the old ifVersion is stale: $d")
            assertEquals("After", d.str("title"))
            assertNotEquals(old, d.getValue("version").jsonPrimitive.long)
        }

    // ------------------------------------------------------------------ S13 (get)

    @Test
    fun `S13 an unknown member is unknown_parameter and a member of another select too`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Strict")
            val id = item.id.toString()

            val unknown = select(getArgs(id, ""","includeTimestamps":true"""))
            assertEquals("unknown_parameter", unknown.code)
            assertEquals(400, unknown.status)
            assertEquals(listOf("/includeTimestamps"), unknown.parameters())

            val foreign = select(getArgs(id, ""","limit":5"""))
            assertEquals("unknown_parameter", foreign.code)
            assertEquals(listOf("/limit"), foreign.parameters())

            assertEquals(200, select(getArgs(id)).status, "control: the same call without the stray member succeeds")
        }

    @Test
    fun `S13 an unknown fields name is invalid_request on fields and lists the four allowed names`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Strict")

            val r = select(getArgs(item.id.toString(), ""","fields":["bogus"]"""))

            assertEquals("invalid_request", r.code)
            assertEquals(400, r.status)
            assertTrue(r.fields().any { it.startsWith("/fields") }, "pointer on /fields: ${r.fields()}")
            val reasons = r.reasons().joinToString(" ")
            for (allowed in listOf("createdAt", "modifiedAt", "roleChangedAt", "ancestors")) {
                assertTrue(allowed in reasons, "the allowed name $allowed is listed: $reasons")
            }
        }

    @Test
    fun `S13 a fields name that only list accepts (summary, guidance) is rejected by get`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Strict")

            for (name in listOf("summary", "guidance")) {
                val r = select(getArgs(item.id.toString(), ""","fields":["$name"]"""))
                assertEquals("invalid_request", r.code, name)
                assertTrue(r.fields().any { it.startsWith("/fields") }, "$name: ${r.fields()}")
            }
        }

    @Test
    fun `S13 a missing, too short, too long or non-hex itemId is invalid_request on itemId`() =
        irApp(db.db, dir) { rig ->
            rig.make("Anything")

            val missing = select("""{"select":"get"}""")
            assertEquals("invalid_request", missing.code)
            assertTrue("/itemId" in missing.fields(), missing.raw)

            val bad = listOf("abcdef1", "a".repeat(33), "zzzzzzzz", "abcd-123", "")
            for (raw in bad) {
                val r = select(getArgs(raw))
                assertEquals("invalid_request", r.code, "'$raw': ${r.raw}")
                assertEquals(400, r.status, "'$raw'")
                assertTrue("/itemId" in r.fields(), "'$raw': ${r.fields()}")
            }
        }

    @Test
    fun `S13 a well-formed id that names nothing is not_found for the caller with unrestricted scope`() =
        irApp(db.db, dir) { rig ->
            rig.make("Present", id = UUID.fromString("11111111-aaaa-4aaa-8aaa-111111111111"))

            val byUuid = select(getArgs("99999999-9999-4999-8999-999999999999"))
            assertEquals(404, byUuid.status)
            assertEquals("not_found", byUuid.code)

            val byPrefix = select(getArgs("00000000"))
            assertEquals(404, byPrefix.status)
            assertEquals("not_found", byPrefix.code)
        }

    @Test
    fun `S13 a string ifVersion is a type fault on ifVersion`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Typed")

            val typed = select(getArgs(item.id.toString(), ""","ifVersion":"abc""""))
            assertEquals("invalid_request", typed.code)
            assertTrue("/ifVersion" in typed.fields(), typed.raw)

            assertEquals(JsonPrimitive("Typed"), select(getArgs(item.id.toString())).data.getValue("title"), "control")
        }
}

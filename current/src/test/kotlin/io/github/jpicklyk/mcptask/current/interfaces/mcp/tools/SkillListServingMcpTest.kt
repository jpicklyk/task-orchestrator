package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.CfgKit
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.effectiveOp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.list
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.obj
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.pushOp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.ref
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.rulesOp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.violations
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.short
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.onlyResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.transitionOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
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
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 79a61aba / U1) for how the tool surface SERVES a note entry that names several
 * skills and a skill mode: scenarios S2, S3, S4, S5, S7 and S10 and their probes of the frozen test-plan. Every call runs
 * through the real tool dispatcher over the real composition ([EventLogRig], the GLOBAL layer carries the fixture config).
 *
 * Oracle: the frozen task-scope "Decision" clause, applied by hand to this file's own YAML: `skill` stays a string and is the
 * entry's FIRST skill on every output; `skills` is emitted only when the entry has two or more skills; `skillMode` (wire
 * form) only when the mode is method-on-diff; a single string, a one-element list and an explicit `skill_mode: invoke` serve
 * output with neither member (AC2). The rules op follows the FIRST skill, and its `resolvedFrom` gains `skills` when two or
 * more. The context answers name the first MISSING required note's skills. A push is checked in STRICT mode, so a bad
 * `skill_mode` or `skill` is refused with a `config_invalid` violation at the entry path.
 *
 * Ambiguity, not resolved from the implementation: the wire location of the health-mode stalled entry's skill members is read
 * on the stalled entry itself (task-scope: the emit site is "health stalled", right after `skillPointer`).
 *
 * Fixture condition for each absence assertion: the paired multi-skill type in the same fixture does carry the member, so an
 * implementation that always omitted it fails the paired assertion, and one that always emitted it fails the absence one.
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class SkillListServingMcpTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val global =
        """
        work_item_schemas:
          multi:
            notes:
              - key: ws
                role: work
                required: true
                skill: [rule-a, rule-b]
                skill_mode: method-on-diff
          multi-rev:
            notes:
              - key: ws
                role: work
                required: true
                skill: [rule-b, rule-a]
          pair-invoke:
            notes:
              - key: ws
                role: work
                required: true
                skill: [rule-a, rule-b]
                skill_mode: invoke
          mono-str:
            notes:
              - key: ws
                role: work
                required: true
                skill: rule-a
          mono-list:
            notes:
              - key: ws
                role: work
                required: true
                skill: [rule-a]
          mono-invoke:
            notes:
              - key: ws
                role: work
                required: true
                skill: rule-a
                skill_mode: invoke
          traited:
            notes: []
        traits:
          t-multi:
            notes:
              - key: tws
                role: work
                required: true
                skill: [rule-a, rule-b]
                skill_mode: method-on-diff
        """.trimIndent() + "\n"

    private val listTypes = listOf("multi", "multi-rev", "pair-invoke")
    private val singleTypes = listOf("mono-str", "mono-list", "mono-invoke")

    private class Fixture(
        val rig: EventLogRig,
        val k: CfgKit,
        val root: WorkItem
    ) {
        suspend fun item(
            type: String,
            role: Role,
            traits: List<String> = emptyList()
        ): WorkItem = rig.make("item-$type-${UUID.randomUUID()}", parent = root, role = role, type = type, traits = traits)
    }

    private suspend fun fixture(): Fixture {
        val rig = EventLogRig.build(db.db, dir, global)
        val k = CfgKit(rig)
        return Fixture(rig, k, k.root("R", UUID.fromString("a2000001-0000-4000-8000-000000000001")))
    }

    private suspend fun Fixture.stash(key: String) {
        rig.raw.planDocumentRepository().stash(root.id, "rule/$key", "body of $key")
    }

    private fun JsonObject.strings(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }

    private suspend fun Fixture.read(args: JsonObject): JsonObject {
        val body = rig.ctx.callTool("items_read", args)
        assertTrue(body["ok"]!!.jsonPrimitive.boolean, "items_read failed: $body")
        return body["data"]!!.jsonObject
    }

    private suspend fun Fixture.schemaEntry(
        item: WorkItem,
        key: String
    ): JsonObject =
        read(
            buildJsonObject {
                put("select", "schema")
                put("itemId", item.id.toString())
            }
        ).getValue("notes").jsonArray.map { it.jsonObject }.single { it.str("key") == key }

    private suspend fun Fixture.context(item: WorkItem): JsonObject =
        read(
            buildJsonObject {
                put("select", "context")
                put("itemId", item.id.toString())
            }
        )

    private suspend fun Fixture.stalledEntry(item: WorkItem): JsonObject =
        read(buildJsonObject { put("select", "context") })
            .getValue("stalled")
            .jsonArray
            .map { it.jsonObject }
            .single { it.str("id") == short(item.id) }

    private suspend fun Fixture.advanceStart(item: WorkItem): JsonObject {
        val element = rig.ctx.advanceTool(transitionOp(item.id, "start")).onlyResult()
        assertTrue(element["ok"]!!.jsonPrimitive.boolean, "start failed: $element")
        return element
    }

    private suspend fun Fixture.effectiveEntry(
        type: String,
        key: String
    ): JsonObject =
        k
            .call(effectiveOp())
            .okResult()
            .list("schemas")
            .first { it.str("type") == type }
            .list("notes")
            .single { it.str("key") == key }

    /** The members that carry the skill facts, as the wire object holds them (absent stays absent). */
    private fun JsonObject.skillFacts(): Map<String, Any?> =
        mapOf(
            "skill" to this["skill"]?.jsonPrimitive?.content,
            "skills" to this["skills"]?.jsonArray?.map { it.jsonPrimitive.content },
            "skillMode" to this["skillMode"]?.jsonPrimitive?.content
        )

    private val multiFacts =
        mapOf<String, Any?>("skill" to "rule-a", "skills" to listOf("rule-a", "rule-b"), "skillMode" to "method-on-diff")
    private val singleFacts = mapOf<String, Any?>("skill" to "rule-a", "skills" to null, "skillMode" to null)

    // ------------------------------------------------------------------ S2 items_read schema

    @Test
    fun `S2 items_read schema serves skill as the first skill, the skills list and the wire mode`(): Unit =
        runBlocking {
            val f = fixture()
            val entry = f.schemaEntry(f.item("multi", Role.WORK), "ws")

            assertEquals(multiFacts, entry.skillFacts(), "entry: $entry")
        }

    @Test
    fun `S2 a trait entry is served whole with its skills and mode`(): Unit =
        runBlocking {
            val f = fixture()
            val entry = f.schemaEntry(f.item("traited", Role.WORK, traits = listOf("t-multi")), "tws")

            assertEquals(multiFacts, entry.skillFacts(), "entry: $entry")
        }

    @Test
    fun `S2 probe - list order decides the first skill`(): Unit =
        runBlocking {
            val f = fixture()
            val entry = f.schemaEntry(f.item("multi-rev", Role.WORK), "ws")

            assertEquals(
                mapOf<String, Any?>("skill" to "rule-b", "skills" to listOf("rule-b", "rule-a"), "skillMode" to null),
                entry.skillFacts()
            )
        }

    // ------------------------------------------------------------------ S3 context, advance, health

    @Test
    fun `S3 context names the first missing note's skill, its skills and the mode`(): Unit =
        runBlocking {
            val f = fixture()
            val data = f.context(f.item("multi", Role.WORK))

            assertEquals("rule-a", data.str("skillPointer"))
            assertEquals(listOf("rule-a", "rule-b"), data.strings("skillPointers"))
            assertEquals("method-on-diff", data.str("skillMode"))
        }

    @Test
    fun `S3 advance start into the work phase names the skills and the mode`(): Unit =
        runBlocking {
            val f = fixture()
            val element = f.advanceStart(f.item("multi", Role.QUEUE))

            assertEquals("work", element.str("newRole"))
            assertEquals("rule-a", element.str("skillPointer"))
            assertEquals(listOf("rule-a", "rule-b"), element.strings("skillPointers"))
            assertEquals("method-on-diff", element.str("skillMode"))
        }

    @Test
    fun `S3 health stalled entry names the skills and the mode`(): Unit =
        runBlocking {
            val f = fixture()
            val entry = f.stalledEntry(f.item("multi", Role.WORK))

            assertEquals("rule-a", entry.str("skillPointer"))
            assertEquals(listOf("rule-a", "rule-b"), entry.strings("skillPointers"))
            assertEquals("method-on-diff", entry.str("skillMode"))
        }

    @Test
    fun `S3 a trait note that is the first missing note supplies the skills`(): Unit =
        runBlocking {
            val f = fixture()
            val data = f.context(f.item("traited", Role.WORK, traits = listOf("t-multi")))

            assertEquals("rule-a", data.str("skillPointer"))
            assertEquals(listOf("rule-a", "rule-b"), data.strings("skillPointers"))
            assertEquals("method-on-diff", data.str("skillMode"))
        }

    @Test
    fun `S3 a list in invoke mode serves skillPointers and no skillMode`(): Unit =
        runBlocking {
            val f = fixture()
            val item = f.item("pair-invoke", Role.WORK)
            val data = f.context(item)

            assertEquals("rule-a", data.str("skillPointer"))
            assertEquals(listOf("rule-a", "rule-b"), data.strings("skillPointers"))
            assertFalse("skillMode" in data, "invoke is the default and is never emitted: $data")
        }

    // ------------------------------------------------------------------ S4 effective and rules

    @Test
    fun `S4 config effective serves the same skill facts as the items_read schema`(): Unit =
        runBlocking {
            val f = fixture()
            val effective = f.effectiveEntry("multi", "ws")
            val schema = f.schemaEntry(f.item("multi", Role.WORK), "ws")

            assertEquals(multiFacts, effective.skillFacts(), "effective entry: $effective")
            assertEquals(schema.skillFacts(), effective.skillFacts(), "the two views of one entry must agree")
        }

    @Test
    fun `S4 rules by noteKey follows the first skill and resolvedFrom names the list`(): Unit =
        runBlocking {
            val f = fixture()
            f.stash("rule-a")
            f.stash("rule-b")
            val item = f.item("multi", Role.WORK)

            val got = f.k.call(rulesOp("itemId" to ref(item.id), "noteKey" to ref("ws"))).okResult()

            assertEquals("rule-a", got.str("key"))
            assertEquals("body of rule-a", got.str("body"))
            val from = got.obj("resolvedFrom")
            assertEquals("rule-a", from.str("skill"))
            assertEquals(listOf("rule-a", "rule-b"), from.strings("skills"))
        }

    @Test
    fun `S4 probe - rules follows the first skill of a reordered list`(): Unit =
        runBlocking {
            val f = fixture()
            f.stash("rule-a")
            f.stash("rule-b")
            val item = f.item("multi-rev", Role.WORK)

            val got = f.k.call(rulesOp("itemId" to ref(item.id), "noteKey" to ref("ws"))).okResult()

            assertEquals("rule-b", got.str("key"))
            assertEquals(listOf("rule-b", "rule-a"), got.obj("resolvedFrom").strings("skills"))
        }

    // ------------------------------------------------------------------ S5 single skill serves unchanged

    @Test
    fun `S5 a string and a one-element list serve equal entries with no skills or skillMode member`(): Unit =
        runBlocking {
            val f = fixture()
            val str = f.schemaEntry(f.item("mono-str", Role.WORK), "ws")
            val list = f.schemaEntry(f.item("mono-list", Role.WORK), "ws")

            assertEquals(str, list, "schema entries must be byte-equal")
            assertEquals(singleFacts, list.skillFacts(), "entry: $list")
            assertFalse("skills" in list || "skillMode" in list, "no new member for a single skill: $list")
        }

    @Test
    fun `S5 context and advance answers carry no skillPointers or skillMode for a single skill`(): Unit =
        runBlocking {
            val f = fixture()
            for (type in listOf("mono-str", "mono-list")) {
                val ctx = f.context(f.item(type, Role.WORK))
                assertEquals("rule-a", ctx.str("skillPointer"), type)
                assertFalse("skillPointers" in ctx || "skillMode" in ctx, "$type context: $ctx")

                val start = f.advanceStart(f.item(type, Role.QUEUE))
                assertEquals("rule-a", start.str("skillPointer"), type)
                assertFalse("skillPointers" in start || "skillMode" in start, "$type start: $start")
            }
        }

    @Test
    fun `S5 health stalled entry and effective view carry no new member for a single skill`(): Unit =
        runBlocking {
            val f = fixture()
            val stalled = f.stalledEntry(f.item("mono-list", Role.WORK))
            assertEquals("rule-a", stalled.str("skillPointer"))
            assertFalse("skillPointers" in stalled || "skillMode" in stalled, "stalled: $stalled")

            assertEquals(f.effectiveEntry("mono-str", "ws"), f.effectiveEntry("mono-list", "ws"))
            assertEquals(singleFacts, f.effectiveEntry("mono-list", "ws").skillFacts())
        }

    @Test
    fun `S5 rules resolvedFrom carries no skills member for a single skill`(): Unit =
        runBlocking {
            val f = fixture()
            f.stash("rule-a")
            for (type in listOf("mono-str", "mono-list")) {
                val item = f.item(type, Role.WORK)
                val got = f.k.call(rulesOp("itemId" to ref(item.id), "noteKey" to ref("ws"))).okResult()

                assertEquals("rule-a", got.obj("resolvedFrom").str("skill"), type)
                assertFalse("skills" in got.obj("resolvedFrom"), "$type: ${got.obj("resolvedFrom")}")
            }
        }

    // ------------------------------------------------------------------ S7 explicit invoke

    @Test
    fun `S7 an explicit invoke mode is never emitted and the single-skill output is unchanged`(): Unit =
        runBlocking {
            val f = fixture()
            val explicit = f.schemaEntry(f.item("mono-invoke", Role.WORK), "ws")

            assertEquals(f.schemaEntry(f.item("mono-str", Role.WORK), "ws"), explicit)
            assertFalse("skillMode" in explicit, "entry: $explicit")
            assertFalse("skillMode" in f.effectiveEntry("mono-invoke", "ws"))
            val ctx = f.context(f.item("mono-invoke", Role.WORK))
            assertFalse("skillMode" in ctx, "context: $ctx")
        }

    @Test
    fun `S7 a list with an explicit invoke mode serves skills and no skillMode`(): Unit =
        runBlocking {
            val f = fixture()
            val entry = f.schemaEntry(f.item("pair-invoke", Role.WORK), "ws")

            assertEquals(
                mapOf<String, Any?>("skill" to "rule-a", "skills" to listOf("rule-a", "rule-b"), "skillMode" to null),
                entry.skillFacts()
            )
        }

    // ------------------------------------------------------------------ S10 push validation

    private fun pushYaml(vararg extra: String): String =
        "work_item_schemas:\n  pushed:\n    notes:\n      - key: ws\n        role: work\n        required: true\n" +
            extra.joinToString("") { "        $it\n" }

    private val entryPath = "/work_item_schemas/pushed/notes/0"

    @Test
    fun `S10 a push with a skill list and a method-on-diff mode is accepted and served per root`(): Unit =
        runBlocking {
            val f = fixture()
            val yaml =
                pushYaml("skill: [rule-a, rule-b]", "skill_mode: method-on-diff") +
                    "traits:\n  tp:\n    notes:\n      - key: tn\n        role: work\n        required: true\n" +
                    "        skill: [rule-a, rule-b]\n        skill_mode: method-on-diff\n"

            f.k.call(pushOp(f.root.id, yaml)).okResult()

            val view = f.k.call(effectiveOp(f.root.id)).okResult()
            val entry =
                view
                    .list("schemas")
                    .first { it.str("type") == "pushed" }
                    .list("notes")
                    .single { it.str("key") == "ws" }
            assertEquals(multiFacts, entry.skillFacts(), "entry: $entry")
        }

    @Test
    fun `S10 a push with an unknown skill_mode is refused at skill_mode`(): Unit =
        runBlocking {
            val f = fixture()
            val before = f.k.versions(f.root.id)

            val err = f.k.call(pushOp(f.root.id, pushYaml("skill: rule-a", "skill_mode: diff"))).errorAt()

            assertEquals("config_invalid", err.str("code"))
            assertTrue(f.violationPaths(err).any { it.startsWith("$entryPath/skill_mode") }, "violations: ${err.violations()}")
            assertEquals(before, f.k.versions(f.root.id), "a refused push appends no version")
        }

    @Test
    fun `S10 a push with an empty skill list is refused at skill`(): Unit =
        runBlocking {
            val f = fixture()

            val err = f.k.call(pushOp(f.root.id, pushYaml("skill: []"))).errorAt()

            assertEquals("config_invalid", err.str("code"))
            assertTrue(f.violationPaths(err).any { it.startsWith("$entryPath/skill") }, "violations: ${err.violations()}")
        }

    @Test
    fun `S10 a push with a duplicate skill name is refused at skill 1`(): Unit =
        runBlocking {
            val f = fixture()

            val err = f.k.call(pushOp(f.root.id, pushYaml("skill: [rule-a, rule-a]"))).errorAt()

            assertEquals("config_invalid", err.str("code"))
            assertTrue(f.violationPaths(err).any { it.startsWith("$entryPath/skill") }, "violations: ${err.violations()}")
        }

    @Test
    fun `S10 a push with skill_mode and no skill is refused at skill_mode`(): Unit =
        runBlocking {
            val f = fixture()

            val err = f.k.call(pushOp(f.root.id, pushYaml("skill_mode: method-on-diff"))).errorAt()

            assertEquals("config_invalid", err.str("code"))
            assertTrue(f.violationPaths(err).any { it.startsWith("$entryPath/skill_mode") }, "violations: ${err.violations()}")
        }

    private fun Fixture.violationPaths(error: JsonObject): List<String> = error.violations().map { it.first }
}

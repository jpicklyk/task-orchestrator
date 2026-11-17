package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.CfgKit
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.effectiveOp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.list
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.pushOp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.violations
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.test.callTool
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 775f55e6 / U4) for how the tool surface SERVES and ADMITS a review lane:
 * scenarios S3, S9 and S14 of the frozen test-plan, with the decision D3 absence checks. Every call runs through the real
 * tool dispatcher over the real composition ([EventLogRig]; the GLOBAL layer carries the fixture config).
 *
 * Oracle (frozen task-scope, applied by hand to this file's own YAML): `items_read` `schema` serves a declared lane as
 * `lane: {output: <wire>}` plus `after: [...]` only when non-empty, on the type path and on the item path (a trait entry is
 * served whole on the item path); an entry with no lane serves no `lane` member (AC3); `lane: {}` serves output verdict and no
 * after; the lane is not served on `config effective` (D3). A push is checked in STRICT mode: a bogus output is refused as
 * `config_invalid` at `<entry>/lane/output`, a lane on a work entry at `<entry>/lane`; a valid lane is admitted and then served
 * per root.
 *
 * Fixture condition for each absence assertion: the paired entries in the same fixture do carry the member.
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ReviewLaneServingMcpTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val global =
        """
        work_item_schemas:
          lanes:
            notes:
              - key: ra
                role: review
                required: true
                lane:
                  output: findings
                  after: [rb]
              - key: rb
                role: review
                required: true
                lane:
                  output: verdict
              - key: rc
                role: review
                required: true
              - key: rd
                role: review
                required: true
                lane: {}
              - key: re
                role: review
                required: true
                lane:
                  after: [ra, rb]
          traited:
            notes: []
        traits:
          t-lane:
            notes:
              - key: tl
                role: review
                required: true
                lane:
                  output: findings
                  after: [rb]
        """.trimIndent() + "\n"

    private class Fixture(
        val rig: EventLogRig,
        val k: CfgKit,
        val root: WorkItem
    ) {
        suspend fun item(
            type: String,
            traits: List<String> = emptyList()
        ): WorkItem = rig.make("item-$type-${UUID.randomUUID()}", parent = root, role = Role.WORK, type = type, traits = traits)
    }

    private suspend fun fixture(): Fixture {
        val rig = EventLogRig.build(db.db, dir, global)
        val k = CfgKit(rig)
        return Fixture(rig, k, k.root("R", UUID.fromString("a4000001-0000-4000-8000-000000000001")))
    }

    private suspend fun Fixture.read(args: JsonObject): JsonObject {
        val body = rig.ctx.callTool("items_read", args)
        assertTrue(body["ok"]!!.jsonPrimitive.boolean, "items_read failed: $body")
        return body["data"]!!.jsonObject
    }

    private suspend fun Fixture.itemEntries(item: WorkItem): List<JsonObject> =
        read(
            buildJsonObject {
                put("select", "schema")
                put("itemId", item.id.toString())
            }
        ).getValue("notes").jsonArray.map { it.jsonObject }

    private suspend fun Fixture.typeEntries(type: String): List<JsonObject> =
        read(
            buildJsonObject {
                put("select", "schema")
                put("type", type)
                put("rootId", root.id.toString())
            }
        ).getValue("notes").jsonArray.map { it.jsonObject }

    private fun List<JsonObject>.entry(key: String): JsonObject = single { it.str("key") == key }

    /** The served lane as (member names, output, after), or null when the entry carries no lane member. */
    private fun JsonObject.laneFacts(): Triple<Set<String>, String?, List<String>?>? {
        val lane = this["lane"]?.jsonObject ?: return null
        return Triple(
            lane.keys,
            lane["output"]?.jsonPrimitive?.content,
            lane["after"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
    }

    private val findingsAfterRb = Triple(setOf("output", "after"), "findings", listOf("rb"))
    private val verdictOnly = Triple(setOf("output"), "verdict", null)

    // ------------------------------------------------------------------ S3 serving

    @Test
    fun `S3 the type path serves each declared lane and no lane for an entry that declares none`(): Unit =
        runBlocking {
            val entries = fixture().typeEntries("lanes")

            assertEquals(findingsAfterRb, entries.entry("ra").laneFacts())
            assertEquals(verdictOnly, entries.entry("rb").laneFacts())
            assertNull(entries.entry("rc").laneFacts(), "rc declares no lane: ${entries.entry("rc")}")
            assertFalse("lane" in entries.entry("rc"))
        }

    @Test
    fun `S3 the item path serves the same lanes as the type path`(): Unit =
        runBlocking {
            val f = fixture()
            val byItem = f.itemEntries(f.item("lanes"))
            val byType = f.typeEntries("lanes")

            for (key in listOf("ra", "rb", "rc", "rd", "re")) {
                assertEquals(byType.entry(key).laneFacts(), byItem.entry(key).laneFacts(), key)
            }
            assertEquals(Triple(setOf("output", "after"), "verdict", listOf("ra", "rb")), byItem.entry("re").laneFacts())
        }

    @Test
    fun `S3 an empty lane map serves output verdict and no after member`(): Unit =
        runBlocking {
            val entries = fixture().typeEntries("lanes")

            assertEquals(verdictOnly, entries.entry("rd").laneFacts())
        }

    @Test
    fun `S3 a trait entry is served whole with its lane on the item path`(): Unit =
        runBlocking {
            val f = fixture()
            val entries = f.itemEntries(f.item("traited", traits = listOf("t-lane")))

            assertEquals(findingsAfterRb, entries.entry("tl").laneFacts())
        }

    @Test
    fun `S3 config effective does not serve the lane`(): Unit =
        runBlocking {
            val f = fixture()
            val notes =
                f.k
                    .call(effectiveOp())
                    .okResult()
                    .list("schemas")
                    .first { it.str("type") == "lanes" }
                    .list("notes")

            assertEquals(listOf("ra", "rb", "rc", "rd", "re"), notes.map { it.str("key") })
            assertTrue(notes.none { "lane" in it }, "effective notes: $notes")
        }

    // ------------------------------------------------------------------ S14 control

    @Test
    fun `S14 a schema with no lane serves no lane member on either path`(): Unit =
        runBlocking {
            val f = fixture()
            val plain = f.itemEntries(f.item("traited"))

            assertTrue(plain.isEmpty() || plain.none { "lane" in it }, "entries: $plain")
            assertNull(f.typeEntries("lanes").entry("rc").laneFacts())
        }

    // ------------------------------------------------------------------ S9 push

    private fun pushYaml(
        role: String,
        vararg laneLines: String
    ): String =
        "work_item_schemas:\n  pushed:\n    notes:\n      - key: rv\n        role: $role\n        required: true\n" +
            laneLines.joinToString("") { "        $it\n" }

    private val entryPath = "/work_item_schemas/pushed/notes/0"

    private fun violationPaths(error: JsonObject): List<String> = error.violations().map { it.first }

    @Test
    fun `S9 a push with a bogus lane output is refused at the output path and appends no version`(): Unit =
        runBlocking {
            val f = fixture()
            val before = f.k.versions(f.root.id)

            val err = f.k.call(pushOp(f.root.id, pushYaml("review", "lane:", "  output: bogus"))).errorAt()

            assertEquals("config_invalid", err.str("code"))
            assertTrue(violationPaths(err).any { it == "$entryPath/lane/output" }, "violations: ${err.violations()}")
            assertEquals(before, f.k.versions(f.root.id))
        }

    @Test
    fun `S9 a push with a lane on a work entry is refused at the lane path`(): Unit =
        runBlocking {
            val f = fixture()

            val err = f.k.call(pushOp(f.root.id, pushYaml("work", "lane:", "  output: findings"))).errorAt()

            assertEquals("config_invalid", err.str("code"))
            assertTrue(violationPaths(err).any { it == "$entryPath/lane" }, "violations: ${err.violations()}")
        }

    @Test
    fun `S9 a push with a valid findings lane on a schema entry and a trait entry is admitted and served per root`(): Unit =
        runBlocking {
            val f = fixture()
            val yaml =
                pushYaml("review", "lane:", "  output: findings", "  after: [other]") +
                    "traits:\n  tp:\n    notes:\n      - key: tn\n        role: review\n        required: true\n" +
                    "        lane:\n          output: findings\n          after: other\n"

            f.k.call(pushOp(f.root.id, yaml)).okResult()

            val entries = f.itemEntries(f.item("pushed", traits = listOf("tp")))
            assertEquals(Triple(setOf("output", "after"), "findings", listOf("other")), entries.entry("rv").laneFacts())
            assertEquals(Triple(setOf("output", "after"), "findings", listOf("other")), entries.entry("tn").laneFacts())
        }
}

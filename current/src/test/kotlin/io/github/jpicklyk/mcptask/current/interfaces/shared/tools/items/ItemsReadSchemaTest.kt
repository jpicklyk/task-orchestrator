package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
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
 * Independent tests (test-author seat, item 450aa813) for `items_read` select `schema`: scenario S4, the schema part of S9 /
 * S13 and the schema probes of the frozen test-plan (NEW-SURFACE), through the production REST topology.
 *
 * Oracles: task-scope item 6 (`schema {type? XOR itemId?, rootId? (type only), fields?}`: neither or both is
 * `invalid_request`; the body carries the schema notes with `description`, `skill`, `maxLength` per entry and the per-note
 * `guidance` only with `fields: ["guidance"]`; no schema is an ok `{schemaFree: true}`), D6 (an unknown type is never an
 * error), D2 (`itemId` and `rootId` are TARGET ids: scope-guarded, `forbidden` before anything else), and the
 * dispatcher's strictness. The exact member names of the schema view are not declared, so the tests locate the schema text
 * the config was written with (its description, guidance, skill and maxLength literals) anywhere in the answer body.
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadSchemaTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001")
    private val r2 = UUID.fromString("b2b2b2b2-0000-4000-8000-000000000002")

    private val yaml =
        "work_item_schemas:\n" +
            "  test-item:\n" +
            "    notes: []\n" +
            "  feature-task:\n" +
            "    notes:\n" +
            "      - key: spec\n" +
            "        role: queue\n" +
            "        required: true\n" +
            "        description: \"SPEC-DESC-QQ\"\n" +
            "        guidance: \"SPEC-GUIDANCE-QQ\"\n" +
            "        skill: \"spec-quality\"\n" +
            "        maxLength: 777\n" +
            "      - key: impl\n" +
            "        role: work\n" +
            "        required: false\n" +
            "        description: \"IMPL-DESC-QQ\"\n" +
            "        guidance: \"IMPL-GUIDANCE-QQ\"\n"

    private fun schema(extra: String) = """{"select":"schema"$extra}"""

    // ------------------------------------------------------------------ S4

    @Test
    fun `S4 schema by type returns the notes without guidance by default`(): Unit =
        irApp(db.db, dir, yaml = yaml) { _ ->
            val r = select(schema(""","type":"feature-task""""))

            assertEquals(200, r.status, r.raw)
            assertTrue(r.ok)
            for (text in listOf("\"spec\"", "\"impl\"", "SPEC-DESC-QQ", "IMPL-DESC-QQ", "spec-quality", "777")) {
                assertTrue(text in r.raw, "schema answer carries $text: ${r.raw}")
            }
            assertFalse("SPEC-GUIDANCE-QQ" in r.raw, "guidance is opt-in: ${r.raw}")
            assertFalse("IMPL-GUIDANCE-QQ" in r.raw, "guidance is opt-in: ${r.raw}")
            assertFalse("schemaFree" in r.raw, "a declared type is not schema-free")
        }

    @Test
    fun `S4 fields guidance adds the per-note guidance`(): Unit =
        irApp(db.db, dir, yaml = yaml) { _ ->
            val r = select(schema(""","type":"feature-task","fields":["guidance"]"""))

            assertEquals(200, r.status, r.raw)
            for (text in listOf("SPEC-GUIDANCE-QQ", "IMPL-GUIDANCE-QQ", "SPEC-DESC-QQ", "spec-quality", "777")) {
                assertTrue(text in r.raw, "schema answer with guidance carries $text: ${r.raw}")
            }
        }

    @Test
    fun `S4 schema by itemId resolves the item's type and a prefix works`(): Unit =
        irApp(db.db, dir, yaml = yaml) { rig ->
            val typed = rig.make("Typed", type = "feature-task")

            for (ref in listOf(typed.id.toString(), short(typed.id))) {
                val r = select(schema(""","itemId":"$ref""""))
                assertEquals(200, r.status, "$ref: ${r.raw}")
                assertTrue("SPEC-DESC-QQ" in r.raw, "$ref: ${r.raw}")
                assertFalse("SPEC-GUIDANCE-QQ" in r.raw, ref)
            }
            val withGuidance = select(schema(""","itemId":"${typed.id}","fields":["guidance"]"""))
            assertTrue("SPEC-GUIDANCE-QQ" in withGuidance.raw, withGuidance.raw)
        }

    @Test
    fun `S4 an unknown type and an item of an unknown type are ok and schemaFree`(): Unit =
        irApp(db.db, dir, yaml = yaml) { rig ->
            val mystery = rig.make("Mystery", type = "no-such-type")

            val byType = select(schema(""","type":"no-such-type""""))
            assertEquals(200, byType.status, byType.raw)
            assertTrue(byType.ok)
            assertTrue(
                byType.data
                    .getValue("schemaFree")
                    .jsonPrimitive.boolean
            )

            val byItem = select(schema(""","itemId":"${mystery.id}""""))
            assertEquals(200, byItem.status, byItem.raw)
            assertTrue(
                byItem.data
                    .getValue("schemaFree")
                    .jsonPrimitive.boolean
            )
        }

    @Test
    fun `S4 with rootId a type resolves over that root and still answers the global schema`(): Unit =
        irApp(db.db, dir, yaml = yaml) { rig ->
            val root = rig.make("Project root", id = r1)

            val r = select(schema(""","type":"feature-task","rootId":"${root.id}""""))

            assertEquals(200, r.status, r.raw)
            assertTrue("SPEC-DESC-QQ" in r.raw, "no per-root config is pushed, so the global schema applies: ${r.raw}")
        }

    // ------------------------------------------------------------------ S9 / S13 (schema)

    @Test
    fun `S13 type and itemId are exclusive and one of them is required`(): Unit =
        irApp(db.db, dir, yaml = yaml) { rig ->
            val typed = rig.make("Typed", type = "feature-task")

            val neither = select(schema(""))
            assertEquals("invalid_request", neither.code, neither.raw)
            assertEquals(400, neither.status)

            val both = select(schema(""","type":"feature-task","itemId":"${typed.id}""""))
            assertEquals("invalid_request", both.code, both.raw)
            assertEquals(400, both.status)

            assertEquals(200, select(schema(""","type":"feature-task"""")).status, "control: either one alone is fine")
        }

    @Test
    fun `S13 fields accepts only guidance and unknown members are unknown_parameter`(): Unit =
        irApp(db.db, dir, yaml = yaml) { _ ->
            for (bad in listOf("bogus", "summary", "createdAt")) {
                val r = select(schema(""","type":"feature-task","fields":["$bad"]"""))
                assertEquals("invalid_request", r.code, bad)
                assertTrue(r.fields().any { it.startsWith("/fields") }, "$bad: ${r.fields()}")
                assertTrue("guidance" in r.reasons().joinToString(" "), "the allowed name guidance is listed: ${r.reasons()}")
            }
            for (member in listOf("limit", "anchorId", "ifVersion")) {
                val r = select(schema(""","type":"feature-task","$member":1"""))
                assertEquals("unknown_parameter", r.code, member)
                assertEquals(listOf("/$member"), r.parameters())
            }
        }

    @Test
    fun `S9 under a root scope a foreign or missing itemId and a foreign rootId are forbidden`(): Unit =
        irApp(db.db, dir, scopeRootIds = setOf(r1), yaml = yaml) { rig ->
            val mine = rig.make("Mine", id = r1, type = "feature-task")
            val foreign = rig.make("Foreign", id = r2, type = "feature-task")

            val ok = select(schema(""","itemId":"${mine.id}""""), TEST_TOKEN)
            assertEquals(200, ok.status, ok.raw)
            assertTrue("SPEC-DESC-QQ" in ok.raw)

            val denied = select(schema(""","itemId":"${foreign.id}""""), TEST_TOKEN)
            assertEquals(403, denied.status, denied.raw)
            assertEquals("forbidden", denied.code)
            assertEquals("root", denied.detail.str("scope"))
            assertEquals(r1.toString(), denied.detail.str("required"))
            assertFalse("SPEC-DESC-QQ" in denied.raw, "nothing of the schema leaks with the denial")

            val missing = select(schema(""","itemId":"99999999-9999-4999-8999-999999999999""""), TEST_TOKEN)
            assertEquals(403, missing.status, "a missing id under a restricted scope is forbidden, never not_found: ${missing.raw}")
            assertEquals("forbidden", missing.code)

            val foreignRoot = select(schema(""","type":"feature-task","rootId":"${foreign.id}""""), TEST_TOKEN)
            assertEquals(403, foreignRoot.status, foreignRoot.raw)
            assertEquals("forbidden", foreignRoot.code)

            val ownRoot = select(schema(""","type":"feature-task","rootId":"${mine.id}""""), TEST_TOKEN)
            assertEquals(200, ownRoot.status, ownRoot.raw)

            val typeOnly = select(schema(""","type":"feature-task""""), TEST_TOKEN)
            assertEquals(200, typeOnly.status, "control: a type with no item or root names no scoped target")
        }
}

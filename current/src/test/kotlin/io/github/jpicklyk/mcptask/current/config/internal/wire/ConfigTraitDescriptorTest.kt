package io.github.jpicklyk.mcptask.current.config.internal.wire

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
 * Independent tests (test-author seat, item 3b7a9ed5 / U2) for the `description` and `applies_when` trait declarations as seen
 * through the `config` tool: scenarios S1 to S6 of the frozen test-plan.
 *
 * Oracles: task-scope Behaviour D1 (the YAML keys `description` and `applies_when` on a trait surface as `description` and
 * `appliesWhen` on the effective trait), D3 (a value must be a string; a blank string is absent; the value is trimmed; a
 * non-string is rejected at `/traits/<name>/<key>` on admission), AC2 (a trait declaring neither gains no member, absent not
 * null), and the LayeredConfig precedence table row "Trait (every dimension)" (a per-root trait entry replaces every dimension
 * of the server entry of the same name).
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigTraitDescriptorTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-0000000000d1")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-0000000000d2")

    private val base = "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n"

    private fun traitYaml(
        body: String,
        notes: Boolean = true
    ): String =
        base + "traits:\n  t1:\n" + body +
            (if (notes) "    notes:\n      - key: n1\n        role: work\n        required: false\n" else "")

    private suspend fun seeded(globalYaml: String): CfgKit {
        val k = CfgKit(EventLogRig.build(db.db, dir, globalYaml))
        k.root("R1", r1)
        k.root("R2", r2)
        return k
    }

    private fun JsonObject.trait(name: String): JsonObject = list("traits").first { it.str("name") == name }

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 a server trait with description and applies_when serves both on the effective view`(): Unit =
        runBlocking {
            val k = seeded(traitYaml("    description: \"One line.\"\n    applies_when: \"When X.\"\n"))
            val t1 = k.call(effectiveOp()).okResult().trait("t1")
            assertEquals("One line.", t1.str("description"))
            assertEquals("When X.", t1.str("appliesWhen"))
        }

    // ------------------------------------------------------------------ S2

    @Test
    fun `S2 a trait declaring neither field has no description or appliesWhen member`(): Unit =
        runBlocking {
            val k = seeded(traitYaml(""))
            val t1 = k.call(effectiveOp()).okResult().trait("t1")
            assertFalse("description" in t1, "absent, not null: $t1")
            assertFalse("appliesWhen" in t1, "absent, not null: $t1")
            assertEquals("t1", t1.str("name"))
            assertEquals(listOf("n1"), t1.list("notes").map { it.str("key") })
        }

    // ------------------------------------------------------------------ S3

    @Test
    fun `S3 description alone leaves appliesWhen absent`(): Unit =
        runBlocking {
            val t1 = seeded(traitYaml("    description: \"D only.\"\n")).call(effectiveOp()).okResult().trait("t1")
            assertEquals("D only.", t1.str("description"))
            assertFalse("appliesWhen" in t1, "$t1")
        }

    @Test
    fun `S3 applies_when alone leaves description absent`(): Unit =
        runBlocking {
            val t1 = seeded(traitYaml("    applies_when: \"W only.\"\n")).call(effectiveOp()).okResult().trait("t1")
            assertEquals("W only.", t1.str("appliesWhen"))
            assertFalse("description" in t1, "$t1")
        }

    // ------------------------------------------------------------------ S4

    @Test
    fun `S4 a per-root trait entry replaces the server descriptor wholesale`(): Unit =
        runBlocking {
            val k = seeded(traitYaml("    description: \"S\"\n"))
            k.call(pushOp(r1, traitYaml(""))).okResult()
            assertFalse("description" in k.call(effectiveOp(r1)).okResult().trait("t1"), "per-root entry declares none: it shadows")
            assertEquals(
                "S",
                k
                    .call(effectiveOp(r2))
                    .okResult()
                    .trait("t1")
                    .str("description"),
                "a root without config sees the server entry"
            )
            assertEquals(
                "S",
                k
                    .call(effectiveOp())
                    .okResult()
                    .trait("t1")
                    .str("description"),
                "the server view is unchanged"
            )

            k.call(pushOp(r1, traitYaml("    description: \"P\"\n"))).okResult()
            assertEquals(
                "P",
                k
                    .call(effectiveOp(r1))
                    .okResult()
                    .trait("t1")
                    .str("description")
            )
        }

    @Test
    fun `S4 probe - a description-only per-root trait without notes is still declared and shadows the server entry`(): Unit =
        runBlocking {
            val k = seeded(traitYaml("    description: \"S\"\n    applies_when: \"SW\"\n"))
            k.call(pushOp(r1, traitYaml("    description: \"P\"\n", notes = false))).okResult()
            val t1 = k.call(effectiveOp(r1)).okResult().trait("t1")
            assertEquals("P", t1.str("description"))
            assertFalse("appliesWhen" in t1, "the server applies_when is shadowed too: $t1")
            assertEquals(emptyList(), t1.list("notes"), "the server notes are shadowed too")
        }

    // ------------------------------------------------------------------ S5

    @Test
    fun `S5 validate and push accept string description and applies_when without errors or warnings`(): Unit =
        runBlocking {
            val k = seeded(base)
            val yaml = traitYaml("    description: \"One line.\"\n    applies_when: \"When X.\"\n")
            val checked = k.call(validateOp(r1, yaml)).okResult()
            assertTrue(checked.bool("valid"), "$checked")
            assertEquals(emptyList(), checked.list("errors"))
            assertFalse("warnings" in checked && checked.names("warnings").isNotEmpty(), "warnings: ${checked["warnings"]}")
            val pushed = k.call(pushOp(r1, yaml)).okResult()
            assertFalse("warnings" in pushed && pushed.names("warnings").isNotEmpty(), "warnings: ${pushed["warnings"]}")
            assertEquals(1, k.versions(r1))
        }

    // ------------------------------------------------------------------ S6

    @Test
    fun `S6 a non-string description or applies_when is rejected at its pointer and push leaves the head unchanged`(): Unit =
        runBlocking {
            val k = seeded(base)
            val cases =
                listOf(
                    "description: 5" to "/traits/t1/description",
                    "applies_when: [a]" to "/traits/t1/applies_when",
                    "description:" to "/traits/t1/description",
                    "description: true" to "/traits/t1/description",
                    "applies_when: { a: b }" to "/traits/t1/applies_when",
                    "applies_when:" to "/traits/t1/applies_when"
                )
            for ((field, pointer) in cases) {
                val yaml = traitYaml("    $field\n")
                val checked = k.call(validateOp(r1, yaml)).okResult()
                assertFalse(checked.bool("valid"), "$field: $checked")
                assertTrue(checked.list("errors").any { it.str("path") == pointer }, "$field: errors ${checked["errors"]}")
                val err = k.call(pushOp(r1, yaml)).errorAt()
                assertEquals("config_invalid", err.str("code"), field)
                assertTrue(err.violations().any { it.first == pointer }, "$field: ${err.violations()}")
                assertEquals(0, k.versions(r1), "$field: the head is unchanged")
            }
            assertEquals(1, k.call(pushOp(r1, traitYaml("    description: ok\n"))).okResult().int("version"), "control: a string pushes")
        }
}

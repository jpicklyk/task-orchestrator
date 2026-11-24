package io.github.jpicklyk.mcptask.current.config.internal.wire

import io.github.jpicklyk.mcptask.current.config.ConfigValidateResult
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item c5eec1ca / F2-A) for the wire half of the lint tier: scenarios S13 and S14 of the
 * frozen test-plan, through the real dispatcher over the real composition and SQLite.
 *
 * Oracles (never read from a run): task-scope Wire (the validate result is `{rootId, valid, errors, warnings, lint, diff?,
 * affectedInFlight?}`, `lint` a list of `{path, reason}`, always present, `[]` when nothing is found or the document has errors),
 * AC1 (lint is separate from errors and warnings), AC3 (no lint finding changes `valid`) and AC5 (a clean document, an invalid
 * document, a parse failure and a rootId mismatch each return `lint: []`). Parse failures and rootId mismatches are data on
 * this op, not errors (ConfigOpsTest S8, decision D7).
 *
 * Negative-assertion conditions: each `lint: []` result is paired with a control that returns a non-empty `lint` for the same
 * rule setup (the document with the invalidating prefix removed), so a server that never lints fails the control.
 *
 * Domain invariants satisfied by construction: roots are depth-0 `project` items seeded through the repository; every document is
 * STRICT-valid except the ones that are defective on purpose.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ConfigValidateLintTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-0000000000e1")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-0000000000e2")

    private suspend fun seeded(): CfgKit {
        val k = CfgKit(EventLogRig.build(db.db, dir, "work_item_schemas: {}\n"))
        k.root("R1", r1)
        k.root("R2", r2)
        return k
    }

    private suspend fun CfgKit.stashRule(
        root: UUID,
        key: String,
    ) {
        rig.raw.planDocumentRepository().stash(root, "rule/$key", "Rule body for $key\n")
    }

    /** A valid document whose only finding, with `alpha` served, is the seat rule `nope` (rules/1). */
    private val ruleDoc =
        "work_item_schemas:\n" +
            "  x:\n" +
            "    seats:\n" +
            "      - { name: implementer, phase: work, enters: true, rules: [alpha, nope] }\n" +
            "    notes:\n" +
            "      - key: impl\n" +
            "        role: work\n" +
            "        required: true\n" +
            "        seat: implementer\n"

    private val cleanDoc = ruleDoc.replace(", rules: [alpha, nope]", "")

    private fun JsonObject.lintPairs(): List<Pair<String, String>> = list("lint").map { it.str("path") to it.str("reason") }

    // ------------------------------------------------------------------ S14

    @Test
    fun `S14 a valid document with an unserved seat rule returns lint entries of path and reason only, and valid stays true`(): Unit =
        runBlocking {
            val k = seeded()
            k.stashRule(r1, "alpha")

            val result = k.call(validateOp(r1, ruleDoc)).okResult()

            assertTrue(result.bool("valid"), "$result")
            assertEquals(emptyList(), result.list("errors"), "$result")
            assertEquals(emptyList(), result.list("warnings"), "lint is not a warning: $result")
            val entries = result.list("lint")
            assertEquals(1, entries.size, "$result")
            assertEquals(setOf("path", "reason"), entries.single().keys, "a lint entry has exactly path and reason: $result")
            assertEquals("/work_item_schemas/x/seats/0/rules/1", entries.single().str("path"))
            assertTrue(entries.single().str("reason").isNotBlank())
        }

    @Test
    fun `S14 the wire lint equals the service Checked lint`(): Unit =
        runBlocking {
            val k = seeded()
            k.stashRule(r1, "alpha")

            val wire = k.call(validateOp(r1, ruleDoc)).okResult().lintPairs()
            val checked =
                assertIs<ConfigValidateResult.Checked>(
                    checkNotNull(k.rig.ctx.configService).validate(r1, ruleDoc),
                )

            assertEquals(1, wire.size, "precondition: one finding on the wire")
            assertEquals(checked.lint.map { it.path to it.reason }, wire)
        }

    @Test
    fun `S14 once the rule is stashed the same document has an empty lint`(): Unit =
        runBlocking {
            val k = seeded()
            k.stashRule(r1, "alpha")
            assertEquals(
                1,
                k
                    .call(validateOp(r1, ruleDoc))
                    .okResult()
                    .list("lint")
                    .size,
                "control: one finding before"
            )
            k.stashRule(r1, "nope")

            val result = k.call(validateOp(r1, ruleDoc)).okResult()

            assertTrue(result.bool("valid"))
            assertEquals(emptyList(), result.list("lint"), "$result")
        }

    // ------------------------------------------------------------------ S13

    @Test
    fun `S13 a clean document returns the lint member as an empty list`(): Unit =
        runBlocking {
            val k = seeded()

            val result = k.call(validateOp(r1, cleanDoc)).okResult()

            assertTrue(result.bool("valid"), "$result")
            assertTrue(result["lint"] is JsonArray, "lint is present and a list: $result")
            assertEquals(emptyList(), result.list("lint"))
        }

    @Test
    fun `S13 an invalid document returns lint as an empty list even though it also has lint patterns`(): Unit =
        runBlocking {
            val k = seeded()
            k.stashRule(r1, "alpha")
            assertEquals(
                1,
                k
                    .call(validateOp(r1, ruleDoc))
                    .okResult()
                    .list("lint")
                    .size,
                "control: the repaired document is linted"
            )

            val result = k.call(validateOp(r1, "orchestration:\n  mode: workflow\n" + ruleDoc)).okResult()

            assertEquals(false, result.bool("valid"), "$result")
            assertTrue(result.list("errors").any { it.str("path") == "/orchestration" }, "$result")
            assertTrue(result["lint"] is JsonArray, "lint is present: $result")
            assertEquals(emptyList(), result.list("lint"))
        }

    @Test
    fun `S13 a parse failure returns lint as an empty list`(): Unit =
        runBlocking {
            val k = seeded()

            val result = k.call(validateOp(r1, "work_item_schemas: [unclosed\n")).okResult()

            assertEquals(false, result.bool("valid"), "$result")
            assertTrue(result["lint"] is JsonArray, "lint is present: $result")
            assertEquals(emptyList(), result.list("lint"))
        }

    @Test
    fun `S13 a rootId mismatch returns lint as an empty list`(): Unit =
        runBlocking {
            val k = seeded()
            k.stashRule(r1, "alpha")

            val result = k.call(validateOp(r1, "project:\n  rootId: $r2\n" + ruleDoc)).okResult()

            assertEquals(false, result.bool("valid"), "$result")
            assertTrue(result.list("errors").any { it.str("path") == "/project/rootId" }, "$result")
            assertTrue(result["lint"] is JsonArray, "lint is present: $result")
            assertEquals(emptyList(), result.list("lint"))
        }
}

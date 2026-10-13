package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidationResult
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.application.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.config.LayeredConfig
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.config.RunsConfig
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.RunBudget
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8a71510c / R17) for the `runs:` config block: parsing, per-field layering,
 * and validation.
 *
 * Scenarios: S8 (parse and layered resolution) and S16 (validator errors at /runs/... paths) of the frozen test-plan.
 * Oracles: task-scope "Config `runs:`" and the planner additions (stall_after default 600; the four budget ceilings tokens,
 * cost_usd, attempts_per_seat, max_active_seats; budgets are opt-in so an absent ceiling is null and never invented;
 * `cost_usd` is converted to micro-USD; resolution is per FIELD: per-root value, else server value, else default;
 * `runs:` is allowed in both a server and a per-root document; the block is strict, so an unknown key is a finding),
 * the declared JSON schema (stall_after integer 1..2147483647; tokens integer >= 1; cost_usd number > 0; attempts_per_seat and
 * max_active_seats integer 1..2147483647; additionalProperties false) and RFC 6901 for the error paths (the leading-slash
 * pointer is the form every existing ConfigValidatorTest assertion uses, e.g. "/bogus_section").
 * Expected values are written out from those clauses, never read from the parser, resolver or validator.
 *
 * Ambiguity arbitration: whether an absent `budgets` resolves to a null [RunBudget] or to an all-null [RunBudget] is not
 * stated; the tests assert the four ceilings through [ceilings], which is the same list for both representations
 * (RunBudget documents all-null as unlimited).
 */
class RunsConfigParseTest {
    private val base = StrictConfigCases.VALID_ROOT_YAML

    private fun parsed(
        yaml: String,
        mode: ParseMode = ParseMode.MIGRATION
    ): ConfigDocument {
        val outcome = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = mode)
        return assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "expected Parsed: $outcome").document
    }

    private fun ceilings(budget: RunBudget?): List<Number?> =
        listOf(budget?.tokens, budget?.costMicroUsd, budget?.attemptsPerSeat, budget?.maxActiveSeats)

    private val noCeilings: List<Number?> = listOf(null, null, null, null)

    private fun validate(
        yaml: String,
        scope: ConfigScope = ConfigScope.ROOT
    ): ConfigValidationResult =
        ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { null }).validate(yaml, YamlConfigDocumentParser, scope)

    private fun ConfigValidationResult.hasErrorAt(
        pointer: String,
        within: Boolean
    ): Boolean = errors.any { it.path == pointer || (within && it.path.startsWith("$pointer/")) }

    // ---------------------------------------------------------------------------------------------
    // S8 - parsing
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 the runs-valid fixture parses to the stall_after and the four converted ceilings`() {
        val runs = assertNotNull(parsed(StrictConfigCases.resource("/config/runs-valid.yaml")).runs)

        assertEquals(300, runs.stallAfterSeconds)
        assertEquals(listOf<Number?>(2_000_000L, 12_500_000L, 3, 4), ceilings(runs.budgets))
    }

    @Test
    fun `S8 the task-scope example parses to RunsConfig 300 with budget 1 500000 2 3`() {
        val yaml =
            base +
                "runs:\n  stall_after: 300\n  budgets:\n    tokens: 1\n    cost_usd: 0.5\n    attempts_per_seat: 2\n    max_active_seats: 3\n"

        assertEquals(RunsConfig(300, RunBudget(1, 500_000, 2, 3)), parsed(yaml).runs)
    }

    @Test
    fun `S8 a document without a runs key has runs null, which is distinct from a present empty block`() {
        assertNull(parsed(base).runs, "absent key reads null (null means the key is absent)")

        val empty = assertNotNull(parsed(base + "runs: {}\n").runs, "a present empty block is not folded to absent")
        assertNull(empty.stallAfterSeconds)
        assertEquals(noCeilings, ceilings(empty.budgets))
    }

    @Test
    fun `S8 a block with only stall_after sets no ceiling and a block with only one ceiling leaves stall_after unset`() {
        val onlyStall = assertNotNull(parsed(base + "runs:\n  stall_after: 45\n").runs)
        assertEquals(45, onlyStall.stallAfterSeconds)
        assertEquals(noCeilings, ceilings(onlyStall.budgets), "no ceiling is invented")

        val onlyTokens = assertNotNull(parsed(base + "runs:\n  budgets:\n    tokens: 7\n").runs)
        assertNull(onlyTokens.stallAfterSeconds, "stall_after is not defaulted at parse time; resolution supplies the default")
        assertEquals(listOf<Number?>(7L, null, null, null), ceilings(onlyTokens.budgets))
    }

    @Test
    fun `S8 cost_usd converts to micro-USD exactly, including values whose binary product is just below the integer`() {
        fun micro(cost: String): Long? = parsed(base + "runs:\n  budgets:\n    cost_usd: $cost\n").runs?.budgets?.costMicroUsd

        assertEquals(500_000L, micro("0.5"))
        assertEquals(12_500_000L, micro("12.5"))
        // 8.2 * 1000000 evaluates to 8199999.999999999 in binary floating point; the decimal value is 8200000 micro-USD.
        assertEquals(8_200_000L, micro("8.2"), "a truncating conversion would give 8199999")
        assertEquals(3_000_000L, micro("3"), "an integer-valued YAML scalar is whole dollars")
        assertEquals(1L, micro("0.000001"), "the smallest representable amount is one micro-USD")
        assertEquals(19_990_000L, micro("19.99"))
    }

    @Test
    fun `S8 an unknown key under runs or runs budgets is ignored with a warning naming the dotted key`() {
        val topLevel = parsed(base + "runs:\n  stall_after: 60\n  foo: 1\n")
        assertTrue(topLevel.warnings.any { it.contains("runs.foo") }, "warnings: ${topLevel.warnings}")
        assertEquals(60, topLevel.runs?.stallAfterSeconds, "the known key is still read")

        val nested = parsed(base + "runs:\n  budgets:\n    tokens: 5\n    bar: 1\n")
        assertTrue(nested.warnings.any { it.contains("runs.budgets.bar") }, "warnings: ${nested.warnings}")
        assertEquals(5L, nested.runs?.budgets?.tokens)

        val clean = parsed(StrictConfigCases.resource("/config/runs-valid.yaml"))
        assertTrue(clean.warnings.none { it.contains("runs") }, "control: the valid fixture produces no runs warning: ${clean.warnings}")
    }

    // ---------------------------------------------------------------------------------------------
    // S8 - layered resolution, per field
    // ---------------------------------------------------------------------------------------------

    private fun layered(
        server: RunsConfig?,
        perRoot: RunsConfig?
    ): LayeredConfig {
        val serverDoc = ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), runs = server)
        val rootDoc = ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), runs = perRoot)
        return LayeredConfig(
            UUID.randomUUID(),
            ConfigLayer(rootDoc, "pr-fp", ConfigSource.PER_ROOT),
            LayerBackedGlobalLookup(ConfigLayer(serverDoc, "global-fp", ConfigSource.GLOBAL))
        )
    }

    @Test
    fun `S8 with no runs block anywhere stall_after is 600 and every ceiling is null`() {
        val resolved = layered(server = null, perRoot = null).runsConfig()

        assertEquals(600, resolved.stallAfterSeconds)
        assertEquals(noCeilings, ceilings(resolved.budgets), "the server never invents a cap")
    }

    @Test
    fun `S8 the server value applies when the per-root document has no runs block`() {
        val server = RunsConfig(300, RunBudget(1_000, 500_000, 2, 3))

        val resolved = layered(server = server, perRoot = null).runsConfig()

        assertEquals(300, resolved.stallAfterSeconds)
        assertEquals(listOf<Number?>(1_000L, 500_000L, 2, 3), ceilings(resolved.budgets))
    }

    @Test
    fun `S8 each field resolves independently - per-root value, else server value, else default`() {
        val server = RunsConfig(300, RunBudget(tokens = 1_000, attemptsPerSeat = 2))
        val perRoot = RunsConfig(null, RunBudget(tokens = 500, maxActiveSeats = 7))

        val resolved = layered(server = server, perRoot = perRoot).runsConfig()

        assertEquals(300, resolved.stallAfterSeconds, "per-root has no stall_after, so the server's applies")
        assertEquals(
            listOf<Number?>(500L, null, 2, 7),
            ceilings(resolved.budgets),
            "tokens from per-root, attempts_per_seat falls through to the server, max_active_seats from per-root, cost unset"
        )
    }

    @Test
    fun `S8 a per-root stall_after wins over the server stall_after and leaves the server ceilings in force`() {
        val server = RunsConfig(300, RunBudget(tokens = 1_000))
        val perRoot = RunsConfig(120, null)

        val resolved = layered(server = server, perRoot = perRoot).runsConfig()

        assertEquals(120, resolved.stallAfterSeconds)
        assertEquals(listOf<Number?>(1_000L, null, null, null), ceilings(resolved.budgets))
    }

    @Test
    fun `S8 the shared resolver returns the server runs block when the root has no per-root config`(): Unit =
        runBlocking {
            val server = RunsConfig(90, RunBudget(costMicroUsd = 250_000))
            val resolver = TestConfigResolver.resolver(ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), runs = server))

            for (rootId in listOf<UUID?>(null, UUID.randomUUID())) {
                val resolved = resolver.resolveRunsConfig(rootId)
                assertEquals(90, resolved.stallAfterSeconds, "root $rootId")
                assertEquals(listOf<Number?>(null, 250_000L, null, null), ceilings(resolved.budgets), "root $rootId")
            }

            val bare = TestConfigResolver.resolver(ConfigDocument.EMPTY).resolveRunsConfig(null)
            assertEquals(600, bare.stallAfterSeconds)
            assertEquals(noCeilings, ceilings(bare.budgets))
        }

    // ---------------------------------------------------------------------------------------------
    // S16 - validation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S16 the valid fixture is admitted at both ROOT and SERVER scope with no errors`() {
        val yaml = StrictConfigCases.resource("/config/runs-valid.yaml")

        for (scope in listOf(ConfigScope.ROOT, ConfigScope.SERVER)) {
            val result = validate(yaml, scope)
            assertEquals(emptyList(), result.errors, "runs: is allowed in a $scope document")
            assertTrue(result.isValid, "scope $scope")
        }
    }

    @Test
    fun `S16 the invalid fixture is rejected at the pointer of each defective key`() {
        val result = validate(StrictConfigCases.resource("/config/runs-invalid.yaml"))

        assertFalse(result.isValid)
        assertTrue(result.hasErrorAt("/runs/stall_after", within = false), "errors: ${result.errors}")
        assertTrue(result.hasErrorAt("/runs/budgets/tokens", within = false), "errors: ${result.errors}")
        assertTrue(result.hasErrorAt("/runs/budgets/cost_usd", within = false), "errors: ${result.errors}")
        assertTrue(result.hasErrorAt("/runs/budgets/attempts_per_seat", within = false), "errors: ${result.errors}")
        assertTrue(result.hasErrorAt("/runs/budgets/max_active_seats", within = false), "errors: ${result.errors}")
        assertTrue(result.hasErrorAt("/runs", within = true), "the unknown key foo is reported at or under /runs: ${result.errors}")
    }

    private data class Defect(
        val name: String,
        val runsBlock: String,
        val errorPath: String,
        val fixedRunsBlock: String,
        val within: Boolean = false
    )

    private val defects =
        listOf(
            Defect("stall_after 0", "runs:\n  stall_after: 0\n", "/runs/stall_after", "runs:\n  stall_after: 1\n"),
            Defect("stall_after negative", "runs:\n  stall_after: -1\n", "/runs/stall_after", "runs:\n  stall_after: 1\n"),
            Defect("stall_after as a string", "runs:\n  stall_after: \"600\"\n", "/runs/stall_after", "runs:\n  stall_after: 600\n"),
            Defect("stall_after fractional", "runs:\n  stall_after: 1.5\n", "/runs/stall_after", "runs:\n  stall_after: 2\n"),
            Defect("stall_after boolean", "runs:\n  stall_after: true\n", "/runs/stall_after", "runs:\n  stall_after: 1\n"),
            Defect(
                "stall_after one past the integer maximum",
                "runs:\n  stall_after: 2147483648\n",
                "/runs/stall_after",
                "runs:\n  stall_after: 2147483647\n"
            ),
            Defect("tokens 0", "runs:\n  budgets:\n    tokens: 0\n", "/runs/budgets/tokens", "runs:\n  budgets:\n    tokens: 1\n"),
            Defect("tokens negative", "runs:\n  budgets:\n    tokens: -5\n", "/runs/budgets/tokens", "runs:\n  budgets:\n    tokens: 1\n"),
            Defect(
                "tokens fractional",
                "runs:\n  budgets:\n    tokens: 2.5\n",
                "/runs/budgets/tokens",
                "runs:\n  budgets:\n    tokens: 3\n"
            ),
            Defect(
                "cost_usd 0",
                "runs:\n  budgets:\n    cost_usd: 0\n",
                "/runs/budgets/cost_usd",
                "runs:\n  budgets:\n    cost_usd: 0.000001\n"
            ),
            Defect(
                "cost_usd negative",
                "runs:\n  budgets:\n    cost_usd: -2.5\n",
                "/runs/budgets/cost_usd",
                "runs:\n  budgets:\n    cost_usd: 2.5\n"
            ),
            Defect(
                "cost_usd as a string",
                "runs:\n  budgets:\n    cost_usd: \"5\"\n",
                "/runs/budgets/cost_usd",
                "runs:\n  budgets:\n    cost_usd: 5\n"
            ),
            Defect(
                "attempts_per_seat 0",
                "runs:\n  budgets:\n    attempts_per_seat: 0\n",
                "/runs/budgets/attempts_per_seat",
                "runs:\n  budgets:\n    attempts_per_seat: 1\n"
            ),
            Defect(
                "attempts_per_seat one past the integer maximum",
                "runs:\n  budgets:\n    attempts_per_seat: 2147483648\n",
                "/runs/budgets/attempts_per_seat",
                "runs:\n  budgets:\n    attempts_per_seat: 2147483647\n"
            ),
            Defect(
                "max_active_seats 0",
                "runs:\n  budgets:\n    max_active_seats: 0\n",
                "/runs/budgets/max_active_seats",
                "runs:\n  budgets:\n    max_active_seats: 1\n"
            ),
            Defect(
                "max_active_seats fractional",
                "runs:\n  budgets:\n    max_active_seats: 1.5\n",
                "/runs/budgets/max_active_seats",
                "runs:\n  budgets:\n    max_active_seats: 2\n"
            ),
            Defect("budgets not a mapping", "runs:\n  budgets: 3\n", "/runs/budgets", "runs:\n  budgets:\n    tokens: 3\n"),
            Defect("runs not a mapping", "runs: 7\n", "/runs", "runs:\n  stall_after: 7\n"),
            Defect("unknown runs key", "runs:\n  foo: 1\n", "/runs", "runs:\n  stall_after: 1\n", within = true),
            Defect(
                "unknown budgets key",
                "runs:\n  budgets:\n    bar: 1\n",
                "/runs/budgets",
                "runs:\n  budgets:\n    tokens: 1\n",
                within = true
            ),
            Defect(
                "wrong-case key Stall_After",
                "runs:\n  Stall_After: 5\n",
                "/runs",
                "runs:\n  stall_after: 5\n",
                within = true
            )
        )

    @TestFactory
    fun `S16 each defect is rejected at its pointer and its repaired twin is admitted`(): List<DynamicTest> =
        defects.map { defect ->
            dynamicTest(defect.name) {
                val broken = validate(base + defect.runsBlock)
                assertFalse(broken.isValid, "the defect document must be rejected: ${defect.runsBlock}")
                assertTrue(
                    broken.hasErrorAt(defect.errorPath, defect.within),
                    "expected an error at ${defect.errorPath}${if (defect.within) " (or beneath it)" else ""}, got ${broken.errors}"
                )

                val fixed = validate(base + defect.fixedRunsBlock)
                assertEquals(emptyList(), fixed.errors, "the repaired twin must be admitted: ${defect.fixedRunsBlock}")
            }
        }

    @Test
    fun `S16 the declared boundaries are admitted - largest integers, a token count beyond 32 bits, one micro-dollar`() {
        val yaml =
            base + "runs:\n  stall_after: 2147483647\n  budgets:\n    tokens: 9000000000\n    cost_usd: 0.000001\n" +
                "    attempts_per_seat: 2147483647\n    max_active_seats: 2147483647\n"

        val result = validate(yaml)

        assertEquals(emptyList(), result.errors)
        assertEquals(9_000_000_000L, parsed(yaml).runs?.budgets?.tokens)
    }

    @Test
    fun `S16 a document with no runs key and a document with an empty runs block are both valid`() {
        assertEquals(emptyList(), validate(base).errors, "absent")
        assertEquals(emptyList(), validate(base + "runs: {}\n").errors, "present and empty")
        assertEquals(emptyList(), validate(base + "runs:\n  budgets: {}\n").errors, "empty budgets")
    }

    @Test
    fun `S16 two defects in one document are both reported and the list is stable across runs`() {
        val yaml = base + "runs:\n  stall_after: 0\n  budgets:\n    tokens: 0\n"

        val runs = (1..3).map { validate(yaml) }

        assertTrue(runs[0].hasErrorAt("/runs/stall_after", within = false), "errors: ${runs[0].errors}")
        assertTrue(runs[0].hasErrorAt("/runs/budgets/tokens", within = false), "errors: ${runs[0].errors}")
        assertEquals(runs[0].errors, runs[1].errors)
        assertEquals(runs[0].errors, runs[2].errors)
    }
}

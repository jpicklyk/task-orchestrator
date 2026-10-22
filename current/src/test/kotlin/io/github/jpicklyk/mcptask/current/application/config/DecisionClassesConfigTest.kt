package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.decision.DecisionClass
import io.github.jpicklyk.mcptask.current.application.decision.DecisionClasses
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestAnswerers
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
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
 * Independent tests (test-author seat, item a53e6875, R24) for the `decision_classes:` config block and the class table built
 * from it: parsing, per-key layering, the built-in classes with their per-FIELD override, and validation.
 *
 * Scenarios: S8 (a configured class is read, the built-ins resolve undeclared) and S16 (`run-budget` and `attempt-cap` cannot be
 * lowered to `any-actor`, plus the other defects of the block) of the frozen test-plan.
 *
 * Oracles (never read from a run): task-scope D5 (the entry shape `answerers` human-only | director-or-human | any-actor, `severity`
 * high | normal | low defaulting to normal, `expires_after` an integer number of seconds above zero, `on_expiry` whose only value is
 * `escalate`, `freeform` a boolean defaulting to false; a class name matches `^[a-z][a-z0-9_-]{0,63}$`; the four built-ins
 * `run-budget` and `attempt-cap` (director-or-human, high, server-raised), `deviation` (director-or-human, normal, 14,400 seconds,
 * escalate) and `question` (any-actor, low, freeform); a declared entry overrides the built-in per FIELD, between layers the per-root
 * entry replaces the server entry per KEY; the validator reports `config_invalid` at `/decision_classes/run-budget/answerers` and
 * `/decision_classes/attempt-cap/answerers` for `any-actor`, with the reason "cannot be lowered below director-or-human"; unknown
 * values, a non-positive `expires_after` and unknown entry keys are errors at the entry's own pointer), plan 3.3 and the RFC 6901
 * pointer form every existing ConfigValidatorTest assertion uses. Expected values are written out from those clauses.
 *
 * Negative-assertion conditions: every rejected document is paired with its repaired twin, which differs only in the defective
 * value and must be admitted, so a validator that rejects the whole block fails the twin.
 *
 * Domain invariants satisfied by construction: [DecisionClassConfig] has no invariant; the documents are parsed from YAML text that
 * extends the shared valid root document.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
class DecisionClassesConfigTest {
    private val base = StrictConfigCases.VALID_ROOT_YAML

    private fun parsed(yaml: String): ConfigDocument {
        val outcome = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
        return assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "expected Parsed: $outcome").document
    }

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
    fun `S8 a full entry parses to every field`() {
        val yaml =
            base +
                "decision_classes:\n  signoff:\n    answerers: human-only\n    severity: high\n    expires_after: 3600\n" +
                "    on_expiry: escalate\n    freeform: true\n"

        val classes = assertNotNull(parsed(yaml).decisionClasses)

        assertEquals(setOf("signoff"), classes.keys)
        assertEquals(
            DecisionClassConfig(
                answerers = DecisionRequestAnswerers.HUMAN_ONLY,
                severity = DecisionRequestSeverity.HIGH,
                expiresAfterSeconds = 3600L,
                onExpiry = "escalate",
                freeform = true
            ),
            classes.getValue("signoff")
        )
    }

    @Test
    fun `S8 a partial entry leaves every unstated field null, so the default is the resolver's and not the parser's`() {
        val yaml = base + "decision_classes:\n  deviation:\n    severity: high\n"

        val entry = assertNotNull(parsed(yaml).decisionClasses).getValue("deviation")

        assertEquals(DecisionClassConfig(severity = DecisionRequestSeverity.HIGH), entry)
        assertNull(entry.answerers)
        assertNull(entry.expiresAfterSeconds)
        assertNull(entry.onExpiry)
        assertNull(entry.freeform)
    }

    @Test
    fun `S8 a document without a decision_classes key reads null`() {
        assertNull(parsed(base).decisionClasses, "absent key reads null")
    }

    @Test
    fun `S8 every answerers and severity value parses to its enum constant`() {
        val answerers =
            mapOf(
                "human-only" to DecisionRequestAnswerers.HUMAN_ONLY,
                "director-or-human" to DecisionRequestAnswerers.DIRECTOR_OR_HUMAN,
                "any-actor" to DecisionRequestAnswerers.ANY_ACTOR
            )
        for ((wire, constant) in answerers) {
            val yaml = base + "decision_classes:\n  c:\n    answerers: $wire\n"
            assertEquals(constant, assertNotNull(parsed(yaml).decisionClasses).getValue("c").answerers, wire)
        }
        val severities =
            mapOf("high" to DecisionRequestSeverity.HIGH, "normal" to DecisionRequestSeverity.NORMAL, "low" to DecisionRequestSeverity.LOW)
        for ((wire, constant) in severities) {
            val yaml = base + "decision_classes:\n  c:\n    severity: $wire\n"
            assertEquals(constant, assertNotNull(parsed(yaml).decisionClasses).getValue("c").severity, wire)
        }
    }

    @Test
    fun `S8 several classes in one block parse side by side`() {
        val yaml =
            base +
                "decision_classes:\n  alpha:\n    answerers: any-actor\n  beta-two:\n    answerers: human-only\n  gamma_3:\n    severity: low\n"

        val classes = assertNotNull(parsed(yaml).decisionClasses)

        assertEquals(setOf("alpha", "beta-two", "gamma_3"), classes.keys)
        assertEquals(DecisionRequestAnswerers.ANY_ACTOR, classes.getValue("alpha").answerers)
        assertEquals(DecisionRequestAnswerers.HUMAN_ONLY, classes.getValue("beta-two").answerers)
        assertEquals(DecisionRequestSeverity.LOW, classes.getValue("gamma_3").severity)
    }

    // ---------------------------------------------------------------------------------------------
    // S8 - layered resolution: the per-root entry replaces the server entry per KEY
    // ---------------------------------------------------------------------------------------------

    private fun layered(
        server: Map<String, DecisionClassConfig>?,
        perRoot: Map<String, DecisionClassConfig>?
    ): LayeredConfig {
        val serverDoc = ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), decisionClasses = server)
        val rootDoc = ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), decisionClasses = perRoot)
        return LayeredConfig(
            UUID.randomUUID(),
            ConfigLayer(rootDoc, "pr-fp", ConfigSource.PER_ROOT),
            LayerBackedGlobalLookup(ConfigLayer(serverDoc, "global-fp", ConfigSource.GLOBAL))
        )
    }

    @Test
    fun `S8 the server entry applies when the per-root document has no decision_classes block`() {
        val server = mapOf("deviation" to DecisionClassConfig(severity = DecisionRequestSeverity.HIGH))

        val resolved = layered(server = server, perRoot = null).decisionClasses()

        assertEquals(DecisionRequestSeverity.HIGH, resolved.getValue("deviation").severity)
    }

    @Test
    fun `S8 a per-root entry replaces the server entry of the same key as a whole, fields are not merged`() {
        val server =
            mapOf(
                "deviation" to DecisionClassConfig(severity = DecisionRequestSeverity.HIGH, expiresAfterSeconds = 60L),
                "question" to DecisionClassConfig(severity = DecisionRequestSeverity.HIGH)
            )
        val perRoot = mapOf("deviation" to DecisionClassConfig(answerers = DecisionRequestAnswerers.HUMAN_ONLY))

        val resolved = layered(server = server, perRoot = perRoot).decisionClasses()

        val deviation = resolved.getValue("deviation")
        assertEquals(DecisionRequestAnswerers.HUMAN_ONLY, deviation.answerers, "the per-root value wins")
        assertNull(deviation.severity, "the server's severity is not carried into the per-root entry (per KEY, not per field)")
        assertNull(deviation.expiresAfterSeconds, "the server's expiry is not carried into the per-root entry")
        assertEquals(DecisionRequestSeverity.HIGH, resolved.getValue("question").severity, "a key only the server declares stays in force")
    }

    @Test
    fun `S8 the shared resolver returns the declared classes for a root with no per-root config`(): Unit =
        runBlocking {
            val server = mapOf("signoff" to DecisionClassConfig(answerers = DecisionRequestAnswerers.HUMAN_ONLY))
            val resolver =
                TestConfigResolver.resolver(
                    ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), decisionClasses = server)
                )

            for (rootId in listOf(UUID.randomUUID(), UUID.randomUUID())) {
                assertEquals(
                    DecisionRequestAnswerers.HUMAN_ONLY,
                    resolver.resolveDecisionClasses(rootId).getValue("signoff").answerers,
                    "root $rootId"
                )
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S8 - the class table: built-ins, per-field override, defaults
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 the four built-in classes carry the answerers, severity, expiry and flags of task-scope D5`() {
        val table = DecisionClasses.resolve(emptyMap())

        assertEquals(setOf("run-budget", "attempt-cap", "deviation", "question"), table.keys)
        for (name in listOf("run-budget", "attempt-cap")) {
            val c = table.getValue(name)
            assertEquals(DecisionRequestAnswerers.DIRECTOR_OR_HUMAN, c.answerers, name)
            assertEquals(DecisionRequestSeverity.HIGH, c.severity, name)
            assertTrue(c.serverRaised, "$name is raised by the server only")
            assertNull(c.expiresAfterSeconds, "$name never expires")
        }
        val deviation = table.getValue("deviation")
        assertEquals(DecisionRequestAnswerers.DIRECTOR_OR_HUMAN, deviation.answerers)
        assertEquals(DecisionRequestSeverity.NORMAL, deviation.severity)
        assertEquals(14_400L, deviation.expiresAfterSeconds)
        assertEquals(DecisionRequest.ON_EXPIRY_ESCALATE, deviation.onExpiry)
        assertFalse(deviation.serverRaised, "deviation is raised through the tool")
        assertFalse(deviation.freeform)
        val question = table.getValue("question")
        assertEquals(DecisionRequestAnswerers.ANY_ACTOR, question.answerers)
        assertEquals(DecisionRequestSeverity.LOW, question.severity)
        assertTrue(question.freeform, "a question may be answered in free text")
        assertFalse(question.serverRaised)
        assertNull(question.expiresAfterSeconds)
    }

    @Test
    fun `S8 the exposed built-in table is the table of an empty declaration`() {
        assertEquals(DecisionClasses.BUILT_IN, DecisionClasses.resolve(emptyMap()))
        assertEquals("run-budget", DecisionClasses.RUN_BUDGET)
        assertEquals("attempt-cap", DecisionClasses.ATTEMPT_CAP)
        assertEquals("deviation", DecisionClasses.DEVIATION)
        assertEquals("question", DecisionClasses.QUESTION)
        assertEquals(14_400L, DecisionClasses.DEVIATION_EXPIRES_AFTER_SECONDS)
        assertEquals(setOf("run-budget", "attempt-cap"), DecisionClasses.NEVER_ANY_ACTOR)
    }

    @Test
    fun `S8 a declared field overrides the built-in field and every other field of the built-in stays`() {
        val table = DecisionClasses.resolve(mapOf("deviation" to DecisionClassConfig(severity = DecisionRequestSeverity.HIGH)))

        val deviation = table.getValue("deviation")
        assertEquals(DecisionRequestSeverity.HIGH, deviation.severity, "the declared field wins")
        assertEquals(DecisionRequestAnswerers.DIRECTOR_OR_HUMAN, deviation.answerers, "an unstated field keeps the built-in value")
        assertEquals(14_400L, deviation.expiresAfterSeconds, "an unstated field keeps the built-in value")
        assertEquals(
            DecisionRequestSeverity.NORMAL,
            DecisionClasses.BUILT_IN.getValue("deviation").severity,
            "control: the built-in itself is unchanged"
        )
        assertEquals(DecisionRequestSeverity.LOW, table.getValue("question").severity, "a class not declared is untouched")
    }

    @Test
    fun `S8 a built-in can be raised, director-or-human to human-only, and a new class is added beside the built-ins`() {
        val table =
            DecisionClasses.resolve(
                mapOf(
                    "run-budget" to DecisionClassConfig(answerers = DecisionRequestAnswerers.HUMAN_ONLY),
                    "signoff" to DecisionClassConfig(answerers = DecisionRequestAnswerers.HUMAN_ONLY)
                )
            )

        assertEquals(DecisionRequestAnswerers.HUMAN_ONLY, table.getValue("run-budget").answerers, "raisable")
        assertTrue(table.getValue("run-budget").serverRaised, "the server-raised flag survives an override")
        assertEquals(setOf("run-budget", "attempt-cap", "deviation", "question", "signoff"), table.keys)
    }

    @Test
    fun `S8 a new class states only its answerers and gets normal severity, no expiry, escalate, not freeform, not server-raised`() {
        val table = DecisionClasses.resolve(mapOf("signoff" to DecisionClassConfig(answerers = DecisionRequestAnswerers.HUMAN_ONLY)))

        assertEquals(
            DecisionClass(
                name = "signoff",
                answerers = DecisionRequestAnswerers.HUMAN_ONLY,
                severity = DecisionRequestSeverity.NORMAL,
                expiresAfterSeconds = null,
                onExpiry = "escalate",
                freeform = false,
                serverRaised = false
            ),
            table.getValue("signoff")
        )
    }

    @Test
    fun `S8 a new class states every field and the table carries each of them`() {
        val table =
            DecisionClasses.resolve(
                mapOf(
                    "review" to
                        DecisionClassConfig(
                            answerers = DecisionRequestAnswerers.ANY_ACTOR,
                            severity = DecisionRequestSeverity.HIGH,
                            expiresAfterSeconds = 90L,
                            onExpiry = "escalate",
                            freeform = true
                        )
                )
            )

        val review = table.getValue("review")
        assertEquals(DecisionRequestAnswerers.ANY_ACTOR, review.answerers)
        assertEquals(DecisionRequestSeverity.HIGH, review.severity)
        assertEquals(90L, review.expiresAfterSeconds)
        assertTrue(review.freeform)
    }

    // ---------------------------------------------------------------------------------------------
    // S16 - validation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S16 a valid block is admitted at both ROOT and SERVER scope with no errors`() {
        val yaml =
            base +
                "decision_classes:\n  signoff:\n    answerers: human-only\n    severity: high\n    expires_after: 3600\n" +
                "    on_expiry: escalate\n    freeform: false\n  deviation:\n    severity: low\n"

        for (scope in listOf(ConfigScope.ROOT, ConfigScope.SERVER)) {
            val result = validate(yaml, scope)
            assertEquals(emptyList(), result.errors, "decision_classes: is allowed in a $scope document")
            assertTrue(result.isValid, "scope $scope")
        }
    }

    @Test
    fun `S16 a document with no decision_classes key and one with an empty block are both valid`() {
        assertEquals(emptyList(), validate(base).errors, "absent")
        assertEquals(emptyList(), validate(base + "decision_classes: {}\n").errors, "present and empty")
    }

    @Test
    fun `S16 run-budget and attempt-cap cannot be lowered to any-actor and the error names the rule`() {
        for (name in listOf("run-budget", "attempt-cap")) {
            val broken = validate(base + "decision_classes:\n  $name:\n    answerers: any-actor\n")

            assertFalse(broken.isValid, "$name any-actor must be rejected")
            assertTrue(broken.hasErrorAt("/decision_classes/$name/answerers", within = false), "errors: ${broken.errors}")
            assertTrue(
                broken.errors.toString().contains("cannot be lowered below director-or-human"),
                "the reason names the rule: ${broken.errors}"
            )

            // The twins differ only in the answerers value: director-or-human restates the built-in, human-only raises it.
            for (twin in listOf("director-or-human", "human-only")) {
                val fixed = validate(base + "decision_classes:\n  $name:\n    answerers: $twin\n")
                assertEquals(emptyList(), fixed.errors, "$name $twin must be admitted")
            }
        }
    }

    @Test
    fun `S16 the any-actor refusal is specific to the two never-lowered classes`() {
        for (name in listOf("deviation", "question", "signoff")) {
            val result = validate(base + "decision_classes:\n  $name:\n    answerers: any-actor\n")
            assertEquals(emptyList(), result.errors, "$name may be any-actor")
        }
    }

    private data class Defect(
        val name: String,
        val block: String,
        val errorPath: String,
        val fixedBlock: String,
        val within: Boolean = false
    )

    private val defects =
        listOf(
            Defect(
                "answerers human",
                "decision_classes:\n  x:\n    answerers: human\n",
                "/decision_classes/x/answerers",
                "decision_classes:\n  x:\n    answerers: human-only\n"
            ),
            Defect(
                "answerers wrong case",
                "decision_classes:\n  x:\n    answerers: Any-Actor\n",
                "/decision_classes/x/answerers",
                "decision_classes:\n  x:\n    answerers: any-actor\n"
            ),
            Defect(
                "answerers as a number",
                "decision_classes:\n  x:\n    answerers: 1\n",
                "/decision_classes/x/answerers",
                "decision_classes:\n  x:\n    answerers: any-actor\n"
            ),
            Defect(
                "severity urgent",
                "decision_classes:\n  x:\n    severity: urgent\n",
                "/decision_classes/x/severity",
                "decision_classes:\n  x:\n    severity: high\n"
            ),
            Defect(
                "severity wrong case",
                "decision_classes:\n  x:\n    severity: High\n",
                "/decision_classes/x/severity",
                "decision_classes:\n  x:\n    severity: high\n"
            ),
            Defect(
                "on_expiry default",
                "decision_classes:\n  x:\n    on_expiry: default\n",
                "/decision_classes/x/on_expiry",
                "decision_classes:\n  x:\n    on_expiry: escalate\n"
            ),
            Defect(
                "expires_after 0",
                "decision_classes:\n  x:\n    expires_after: 0\n",
                "/decision_classes/x/expires_after",
                "decision_classes:\n  x:\n    expires_after: 1\n"
            ),
            Defect(
                "expires_after negative",
                "decision_classes:\n  x:\n    expires_after: -60\n",
                "/decision_classes/x/expires_after",
                "decision_classes:\n  x:\n    expires_after: 60\n"
            ),
            Defect(
                "expires_after as a string",
                "decision_classes:\n  x:\n    expires_after: \"60\"\n",
                "/decision_classes/x/expires_after",
                "decision_classes:\n  x:\n    expires_after: 60\n"
            ),
            Defect(
                "expires_after fractional",
                "decision_classes:\n  x:\n    expires_after: 1.5\n",
                "/decision_classes/x/expires_after",
                "decision_classes:\n  x:\n    expires_after: 2\n"
            ),
            Defect(
                "freeform as a string",
                "decision_classes:\n  x:\n    freeform: \"yes\"\n",
                "/decision_classes/x/freeform",
                "decision_classes:\n  x:\n    freeform: true\n"
            ),
            Defect(
                "freeform as a number",
                "decision_classes:\n  x:\n    freeform: 1\n",
                "/decision_classes/x/freeform",
                "decision_classes:\n  x:\n    freeform: true\n"
            ),
            Defect(
                "unknown entry key",
                "decision_classes:\n  x:\n    answerers: any-actor\n    bogus: 1\n",
                "/decision_classes/x",
                "decision_classes:\n  x:\n    answerers: any-actor\n",
                within = true
            ),
            Defect(
                "wrong-case entry key Answerers",
                "decision_classes:\n  x:\n    Answerers: any-actor\n",
                "/decision_classes/x",
                "decision_classes:\n  x:\n    answerers: any-actor\n",
                within = true
            ),
            Defect(
                "entry not a mapping",
                "decision_classes:\n  x: 3\n",
                "/decision_classes/x",
                "decision_classes:\n  x:\n    answerers: any-actor\n"
            ),
            Defect(
                "block not a mapping",
                "decision_classes: 7\n",
                "/decision_classes",
                "decision_classes:\n  x:\n    answerers: any-actor\n"
            ),
            Defect(
                "class name with an upper-case letter",
                "decision_classes:\n  Bad:\n    answerers: any-actor\n",
                "/decision_classes",
                "decision_classes:\n  bad:\n    answerers: any-actor\n",
                within = true
            ),
            Defect(
                "class name starting with a digit",
                "decision_classes:\n  1st:\n    answerers: any-actor\n",
                "/decision_classes",
                "decision_classes:\n  first:\n    answerers: any-actor\n",
                within = true
            ),
            Defect(
                "class name with a space",
                "decision_classes:\n  \"a b\":\n    answerers: any-actor\n",
                "/decision_classes",
                "decision_classes:\n  a-b:\n    answerers: any-actor\n",
                within = true
            ),
            Defect(
                "class name of 65 characters",
                "decision_classes:\n  ${"a".repeat(65)}:\n    answerers: any-actor\n",
                "/decision_classes",
                "decision_classes:\n  ${"a".repeat(64)}:\n    answerers: any-actor\n",
                within = true
            )
        )

    @TestFactory
    fun `S16 each defect is rejected at its pointer and its repaired twin is admitted`(): List<DynamicTest> =
        defects.map { defect ->
            dynamicTest(defect.name) {
                val broken = validate(base + defect.block)
                assertFalse(broken.isValid, "the defect document must be rejected: ${defect.block}")
                assertTrue(
                    broken.hasErrorAt(defect.errorPath, defect.within),
                    "expected an error at ${defect.errorPath}${if (defect.within) " (or beneath it)" else ""}, got ${broken.errors}"
                )

                val fixed = validate(base + defect.fixedBlock)
                assertEquals(emptyList(), fixed.errors, "the repaired twin must be admitted: ${defect.fixedBlock}")
            }
        }

    @Test
    fun `S16 the boundaries are admitted - a 64-character class name, one second, every answerers and severity`() {
        val yaml =
            base +
                "decision_classes:\n  ${"a".repeat(64)}:\n    expires_after: 1\n  b:\n    answerers: human-only\n    severity: low\n" +
                "  c:\n    answerers: director-or-human\n    severity: normal\n"

        assertEquals(emptyList(), validate(yaml).errors)
        assertEquals(1L, assertNotNull(parsed(yaml).decisionClasses).getValue("a".repeat(64)).expiresAfterSeconds)
    }

    @Test
    fun `S16 two defects in one document are both reported and the list is stable across runs`() {
        val yaml = base + "decision_classes:\n  run-budget:\n    answerers: any-actor\n  x:\n    expires_after: 0\n"

        val runs = (1..3).map { validate(yaml) }

        assertTrue(runs[0].hasErrorAt("/decision_classes/run-budget/answerers", within = false), "errors: ${runs[0].errors}")
        assertTrue(runs[0].hasErrorAt("/decision_classes/x/expires_after", within = false), "errors: ${runs[0].errors}")
        assertEquals(runs[0].errors, runs[1].errors)
        assertEquals(runs[0].errors, runs[2].errors)
    }
}

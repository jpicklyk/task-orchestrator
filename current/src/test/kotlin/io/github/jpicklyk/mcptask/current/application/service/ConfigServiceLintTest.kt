package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.model.config.ConfigScope
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item c5eec1ca / F2-A) for the service half of the lint tier: scenarios S12 (push neither
 * computes nor stores lint), S14 (served rule keys reach the lint, `Checked.lint` carries `{path, reason}` only) and the service
 * side of S13 (an invalid document has `lint = []`), over a real SQLite database and the production validator wiring.
 *
 * Oracles (never read from a run): task-scope AC1 (`Checked.lint` is separate from errors and warnings), AC3 (no lint finding
 * changes `valid` or appears in errors or warnings), AC4 (the push result, its `schemaWarnings` and the stored version's warnings
 * are unchanged: for the fixture below the one existing advisory warning, the required note no seat owns, is the whole of them,
 * the same oracle as ConfigServiceSchemaWarningsTest S2), AC5 (an invalid document returns `lint: []`) and the served-keys rule
 * (a `rule/<key>` document is a served rule; a plan document of any other slug is not, per the PlanDocumentStore doc comment).
 *
 * Negative-assertion conditions: every "no lint" assertion has a control on the same fixture that does produce lint (the
 * document without the invalidating key, or with the rule left unstashed).
 *
 * Domain invariants satisfied by construction: roots are depth-0 `project` items; every document is STRICT-valid.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ConfigServiceLintTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val provider get() = sqlite.repositoryProvider()
    private val store: ConfigStore get() = provider.configStore()

    private val service: ConfigService by lazy {
        ConfigService(
            provider,
            YamlConfigDocumentParser,
            sqlite.unitOfWork(),
            ConfigValidator(NetworkntConfigStructuralValidator()),
            serverConfig = PerRootConfigService(store),
        )
    }

    private suspend fun newRoot(): UUID = provider.workItemRepository().create(WorkItem(title = "Root", type = "project", depth = 0)).id

    private suspend fun stashRule(
        root: UUID,
        key: String,
    ) {
        provider.planDocumentRepository().stash(root, "rule/$key", "Rule body for $key\n")
    }

    private fun yaml(vararg lines: String): String = lines.joinToString("\n") + "\n"

    /** One seat-aware schema; the seat rules are `alpha` (served in the tests), `nope` and `plan-doc`. */
    private val rulesDoc =
        yaml(
            "work_item_schemas:",
            "  x:",
            "    seats:",
            "      - { name: implementer, phase: work, enters: true, rules: [alpha, nope, plan-doc] }",
            "    notes:",
            "      - key: impl",
            "        role: work",
            "        required: true",
            "        seat: implementer",
        )

    private val invalidDoc = "orchestration:\n  mode: workflow\n" + rulesDoc

    /**
     * A document whose findings, with `alpha` served and no server layer, are exactly L1, L2, L3, L6, L7, L8 and L9, plus one
     * required note no seat owns (`unseated-req`), the only finding an ordinary push reports.
     */
    private val lintDoc =
        yaml(
            "work_item_schemas:",
            "  x:",
            "    default_traits: [t]",
            "    seats:",
            "      - { name: planner, phase: queue }",
            "      - { name: implementer, phase: work, enters: true, rules: [beta], skills: [alpha] }",
            "    notes:",
            "      - key: impl",
            "        role: work",
            "        required: true",
            "        seat: implementer",
            "        guidance: \"Dup guidance.\"",
            "        skill: alpha",
            "      - key: free",
            "        role: queue",
            "        required: false",
            "        guidance: \"Dup guidance.\"",
            "      - key: unseated-req",
            "        role: queue",
            "        required: true",
            "traits:",
            "  t:",
            "    notes:",
            "      - key: tn",
            "        role: work",
            "        required: false",
            "        guidance: \"Dup guidance.\"",
        )

    private val lintDocPaths =
        listOf(
            "/traits/t",
            "/work_item_schemas/x/notes/0/guidance",
            "/work_item_schemas/x/notes/0/skill",
            "/work_item_schemas/x/notes/1",
            "/work_item_schemas/x/seats/0",
            "/work_item_schemas/x/seats/1/rules/0",
            "/work_item_schemas/x/seats/1/skills/0",
        ).sorted()

    // ------------------------------------------------------------------ S14

    @Test
    fun `S14 an unserved seat rule is lint, a stashed rule is served, and a plan document is not a rule`(): Unit =
        runBlocking {
            val root = newRoot()
            stashRule(root, "alpha")
            provider.planDocumentRepository().stash(root, "plan-doc", "A plan, not a rule.\n")

            val checked = assertIs<ConfigValidateResult.Checked>(service.validate(root, rulesDoc))

            assertEquals(emptyList(), checked.errors)
            assertTrue(checked.isValid, "lint does not change validity")
            assertEquals(
                listOf("/work_item_schemas/x/seats/0/rules/1", "/work_item_schemas/x/seats/0/rules/2"),
                checked.lint.map { it.path },
                "alpha is served; nope is not stashed; plan-doc is a plan document, not a rule",
            )
            assertTrue(checked.lint.all { it.reason.isNotBlank() })
            assertEquals(emptyList(), checked.warnings, "lint is not carried in warnings")

            stashRule(root, "nope")
            stashRule(root, "plan-doc")
            val served = assertIs<ConfigValidateResult.Checked>(service.validate(root, rulesDoc))
            assertEquals(emptyList(), served.lint, "control: once the rules are stashed nothing is flagged")
            assertTrue(served.isValid)
        }

    @Test
    fun `S14 the served keys are read per root, so a rule stashed under another root does not serve this one`(): Unit =
        runBlocking {
            val root = newRoot()
            val other = newRoot()
            stashRule(other, "alpha")

            val checked = assertIs<ConfigValidateResult.Checked>(service.validate(root, rulesDoc))

            assertEquals(
                listOf(
                    "/work_item_schemas/x/seats/0/rules/0",
                    "/work_item_schemas/x/seats/0/rules/1",
                    "/work_item_schemas/x/seats/0/rules/2",
                ),
                checked.lint.map { it.path },
            )
        }

    // ------------------------------------------------------------------ S13 (service half)

    @Test
    fun `S13 an invalid document has no lint even when it also carries lint patterns, and its repaired twin does`(): Unit =
        runBlocking {
            val root = newRoot()

            val invalid = assertIs<ConfigValidateResult.Checked>(service.validate(root, invalidDoc))
            val repaired = assertIs<ConfigValidateResult.Checked>(service.validate(root, rulesDoc))

            assertTrue(invalid.errors.any { it.path == "/orchestration" }, "precondition: errors ${invalid.errors}")
            assertEquals(emptyList(), invalid.lint)
            assertEquals(emptyList(), repaired.errors)
            assertEquals(3, repaired.lint.size, "control: the same document without the bad key is linted: ${repaired.lint}")
        }

    @Test
    fun `S13 a clean document has an empty lint`(): Unit =
        runBlocking {
            val root = newRoot()
            val clean =
                yaml(
                    "work_item_schemas:",
                    "  x:",
                    "    seats:",
                    "      - { name: implementer, phase: work, enters: true }",
                    "    notes:",
                    "      - key: impl",
                    "        role: work",
                    "        required: true",
                    "        seat: implementer",
                )

            val checked = assertIs<ConfigValidateResult.Checked>(service.validate(root, clean))

            assertEquals(emptyList(), checked.errors)
            assertEquals(emptyList(), checked.lint)
        }

    // ------------------------------------------------------------------ S12

    @Test
    fun `S12 push of a document with lint findings succeeds and its warnings, stored and returned, are the existing ones only`(): Unit =
        runBlocking {
            val root = newRoot()
            stashRule(root, "alpha")
            val checked = assertIs<ConfigValidateResult.Checked>(service.validate(root, lintDoc))
            assertEquals(lintDocPaths, checked.lint.map { it.path }, "precondition: validate reports the lint findings")
            assertNull(store.head(ConfigScope.ROOT, root), "precondition: validate stored nothing")

            val pushed = assertIs<ConfigPushResult.Success>(service.push(root, lintDoc))

            val stored = assertNotNull(store.versionAt(ConfigScope.ROOT, root, 1))
            assertEquals(1, pushed.schemaWarnings.size, "the one advisory warning of the unowned required note: ${pushed.schemaWarnings}")
            assertTrue(pushed.schemaWarnings.single().contains("unseated-req"), pushed.schemaWarnings.single())
            assertEquals(pushed.schemaWarnings, stored.warnings, "the stored warnings are the push's warnings")
            assertEquals(checked.warnings.size, pushed.schemaWarnings.size, "push and validate agree on the warning count")
            for (finding in checked.lint) {
                assertFalse(
                    pushed.schemaWarnings.any { it.contains(finding.reason) },
                    "lint reason is not in the push warnings: ${finding.reason}",
                )
                assertFalse(
                    stored.warnings.any { it.contains(finding.reason) },
                    "lint reason is not in the stored warnings: ${finding.reason}",
                )
                assertFalse(checked.warnings.any { it.reason == finding.reason }, "lint reason is not a validate warning")
            }
            assertEquals(lintDoc, stored.yaml, "the document is stored verbatim")
        }

    @Test
    fun `S12 a document with no lint and the same document with lint push with identical warning lists`(): Unit =
        runBlocking {
            val withLint = newRoot()
            val withoutLint = newRoot()
            stashRule(withLint, "alpha")
            val advisoryOnly =
                yaml(
                    "work_item_schemas:",
                    "  x:",
                    "    seats:",
                    "      - { name: implementer, phase: work, enters: true }",
                    "    notes:",
                    "      - key: impl",
                    "        role: work",
                    "        required: true",
                    "        seat: implementer",
                    "      - key: unseated-req",
                    "        role: queue",
                    "        required: true",
                )

            val plain = assertIs<ConfigPushResult.Success>(service.push(withoutLint, advisoryOnly))
            val linted = assertIs<ConfigPushResult.Success>(service.push(withLint, lintDoc))

            assertEquals(1, plain.schemaWarnings.size, "control: the advisory-only document has the one existing warning")
            assertEquals(1, linted.schemaWarnings.size, "the lint-heavy document has the same single warning")
        }
}

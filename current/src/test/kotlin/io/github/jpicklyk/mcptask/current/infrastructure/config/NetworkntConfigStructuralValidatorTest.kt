package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.application.config.ConfigViolation
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for the structural pass: the
 * [ConfigStructuralValidator] port as implemented by [NetworkntConfigStructuralValidator] against the JSON Schema
 * (2020-12) model of the config document.
 *
 * Oracle P / R (task-scope build items 1 and 4): `additionalProperties: false` at every modeled level; violations are
 * reported at the RFC 6901 pointer of the offending key or value; `extensions` is the one open mapping.
 */
class NetworkntConfigStructuralValidatorTest {
    private val validator: ConfigStructuralValidator = NetworkntConfigStructuralValidator()

    @Suppress("UNCHECKED_CAST")
    private fun check(yaml: String): List<ConfigViolation> =
        validator.validate(
            Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any?>>(yaml) ?: emptyMap()
        )

    private fun List<ConfigViolation>.paths() = map { it.path }

    @Test
    fun `NONE accepts anything - the default port used when no structural pass is wired`() {
        val root = mapOf<String, Any?>("definitely" to "not a config")

        assertEquals(emptyList(), ConfigStructuralValidator.NONE.validate(root))
        assertSame(ConfigStructuralValidator.NONE, ConfigStructuralValidator.NONE)
    }

    @Test
    fun `a well-formed document has no violations`() {
        val yaml =
            "project:\n  name: Demo\n" +
                "work_item_schemas:\n  feature-task:\n    lifecycle: auto\n    default_traits: [t]\n    notes:\n" +
                "      - key: spec\n        role: queue\n        required: true\n" +
                "traits:\n  t:\n    notes:\n      - key: tn\n        role: work\n        required: false\n"

        assertEquals(emptyList(), check(yaml))
    }

    @Test
    fun `an empty mapping has no violations`() {
        assertEquals(emptyList(), check("{}\n"))
    }

    @Test
    fun `an unknown top-level key is a violation at that key`() {
        assertTrue("/bogus" in check("bogus: 1\n").paths(), "violations: ${check("bogus: 1\n")}")
    }

    @Test
    fun `an unknown key at the schema level is a violation at that key`() {
        val violations = check("work_item_schemas:\n  x:\n    bogus: 1\n    notes: []\n")

        assertTrue("/work_item_schemas/x/bogus" in violations.paths(), "violations: $violations")
    }

    @Test
    fun `an unknown key in a note entry is a violation at that key`() {
        val violations = check("work_item_schemas:\n  x:\n    notes:\n      - key: k\n        role: work\n        bogus: 1\n")

        assertTrue("/work_item_schemas/x/notes/0/bogus" in violations.paths(), "violations: $violations")
    }

    @Test
    fun `an unknown key at the trait level is a violation at that key`() {
        val violations = check("traits:\n  t:\n    bogus: 1\n    notes: []\n")

        assertTrue("/traits/t/bogus" in violations.paths(), "violations: $violations")
    }

    @Test
    fun `a wrong-case note role is a violation at the role value`() {
        val violations = check("work_item_schemas:\n  x:\n    notes:\n      - key: k\n        role: Work\n")

        assertTrue("/work_item_schemas/x/notes/0/role" in violations.paths(), "violations: $violations")
    }

    @Test
    fun `a wrong-typed section is a violation at the section`() {
        val violations = check("work_item_schemas:\n  - not\n  - a\n  - mapping\n")

        assertTrue(
            violations.any { it.path == "/work_item_schemas" || it.path.startsWith("/work_item_schemas/") },
            "violations: $violations"
        )
    }

    @Test
    fun `extensions contents are never inspected`() {
        val yaml = "extensions:\n  any-tool:\n    work_item_schemas: 5\n    bogus: [1, {a: b}]\n    traits: nonsense\n"

        assertEquals(emptyList(), check(yaml))
    }

    @Test
    fun `extensions must itself be a mapping`() {
        for (value in listOf("5", "\"text\"", "[a]", "true")) {
            assertTrue("/extensions" in check("extensions: $value\n").paths(), "extensions: $value")
        }
    }

    @Test
    fun `a key with slash and tilde reports the escaped pointer segment`() {
        val violations = check("work_item_schemas:\n  \"a/b~c\":\n    bogus: 1\n")

        assertTrue("/work_item_schemas/a~1b~0c/bogus" in violations.paths(), "violations: $violations")
    }

    @Test
    fun `the violation list is stable across runs`() {
        val yaml = "bogus-a: 1\nbogus-b: 2\nwork_item_schemas:\n  x:\n    bogus: 1\n"

        assertEquals(check(yaml), check(yaml))
        assertEquals(check(yaml), check(yaml))
    }
}

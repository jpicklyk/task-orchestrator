package io.github.jpicklyk.mcptask.current.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test-function return-type guard (item 51535295, adopted proposal 0334f2a3): JUnit 5 silently skips a
 * `@Test`/`@ParameterizedTest`/`@RepeatedTest` method whose JVM return type is not void, so such a function
 * must be a block body or declare `: Unit`. Oracle: the Kotlin spec (an untyped block body returns Unit, an
 * expression body infers its type) plus the JUnit Jupiter rule that test methods must return void.
 *
 * Rule, per function in the test source set (nested included, local excluded): an explicit return type other than
 * `Unit`/`kotlin.Unit` is a violation with any body; no explicit return type plus an expression body is a violation.
 * `@TestFactory`/`@TestTemplate` are out of scope. Limits: annotations are matched by simple name, so an import
 * alias (`import ...Test as X`) is not matched; there is no baseline, offenders are fixed in place.
 */
class TestFunctionReturnTypeTest {
    @TempDir
    lateinit var tempDir: File

    companion object {
        private val TEST_ANNOTATIONS = setOf("Test", "ParameterizedTest", "RepeatedTest")
        private const val MIN_ANNOTATED = 5000

        fun annotated(scope: KoScope): List<KoFunctionDeclaration> =
            scope
                .functions(includeNested = true, includeLocal = false)
                .filter { f -> f.annotations.any { it.name.substringAfterLast('.') in TEST_ANNOTATIONS } }

        fun isViolation(f: KoFunctionDeclaration): Boolean {
            val declared = f.returnType?.name // null when no explicit return type is written
            return if (declared != null) {
                declared.substringAfterLast('.') != "Unit"
            } else {
                f.hasExpressionBody
            }
        }

        fun violations(scope: KoScope): List<String> =
            annotated(scope)
                .filter(::isViolation)
                .map { "  ${GuardSupport.normalizedPath(it.location)} ${it.name}" }
                .sorted()
    }

    private fun scan(source: String): List<String> {
        File(tempDir, "Fixture.kt").writeText(source)
        return violations(Konsist.scopeFromExternalDirectory(tempDir.absolutePath))
    }

    @Test
    fun `no test function returns a non-Unit value`() {
        val scope = Konsist.scopeFromTest()
        val count = annotated(scope).size
        assertTrue(count >= MIN_ANNOTATED, "Only $count annotated test functions scanned (expected >= $MIN_ANNOTATED): scope misrooted?")
        val found = violations(scope)
        assertTrue(
            found.isEmpty(),
            "JUnit 5 silently skips test functions with a non-Unit return type. Declare `: Unit` or use a block body:\n" +
                found.joinToString("\n")
        )
    }

    @Test
    fun `block bodies and explicit Unit are clean`() {
        val src =
            """
            import org.junit.jupiter.api.Test
            class A {
                @Test fun s1() { println() }
                @Test fun s2(): Unit = run { }
                @Test fun s3(): Unit { }
                @Test fun s10(): kotlin.Unit = run { }
                @org.junit.jupiter.api.Test fun fq(): Unit = run { }
                @TestFactory fun f(): List<DynamicTest> = listOf()
                fun helper() = 1
            }
            """.trimIndent()
        assertEquals(emptyList(), scan(src))
    }

    @Test
    fun `untyped expression body is flagged with name`() {
        val found = scan("import org.junit.jupiter.api.Test\nclass A {\n    @Test fun x() = run { }\n}")
        assertEquals(1, found.size)
        assertTrue(found.single().contains("Fixture.kt") && found.single().endsWith(" x"), found.single())
    }

    @Test
    fun `explicit non-Unit type is flagged for any body`() {
        val src =
            """
            class A {
                @Test fun a(): Int { return 1 }
                @Test fun b(): String = "a"
            }
            """.trimIndent()
        assertEquals(2, scan(src).size)
    }

    @Test
    fun `parameterized, repeated and nested functions are flagged`() {
        val src =
            """
            class A {
                @ParameterizedTest fun p() = run { }
                @RepeatedTest(2) fun r() = run { }
                class Inner {
                    @Test fun n() = run { }
                }
            }
            """.trimIndent()
        assertEquals(3, scan(src).size)
    }
}

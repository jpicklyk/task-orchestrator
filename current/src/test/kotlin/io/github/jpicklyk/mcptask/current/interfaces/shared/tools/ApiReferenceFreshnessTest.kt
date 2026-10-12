package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.test.tools.ApiReferenceDocs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Guards the generated blocks of `docs/api-reference.md` (Q33 / 8c5cb2a3): every registry tool has exactly one block, no
 * block names an unknown tool, and each block, plus the error catalog, equals its rendering from the production registry.
 * The tables are never edited by hand; run [ApiReferenceDocs.REGENERATE_TASK] after a request class, `@Doc`, error set,
 * profile or capability change.
 */
class ApiReferenceFreshnessTest {
    private val registry = ToolRegistry(buildToolSpecs())
    private val text = ApiReferenceDocs.read()
    private val hint = "run ${ApiReferenceDocs.REGENERATE_TASK} and commit docs/api-reference.md"

    @Test
    fun `every registry tool and the error catalog has exactly one block and no block is unknown`() {
        val found = ApiReferenceDocs.foundNames(text)
        val expected = ApiReferenceDocs.names(registry)
        assertEquals(expected.sorted(), found.sorted(), "generated blocks in docs/api-reference.md vs registry; $hint")
    }

    @Test
    fun `each generated block equals its rendering`() {
        for (name in ApiReferenceDocs.names(registry)) {
            val actual = ApiReferenceDocs.extract(text, name)
            assertNotNull(actual, "block '$name' is missing or unterminated; $hint")
            assertEquals(ApiReferenceDocs.block(name, registry), actual, "block '$name' is stale; $hint")
        }
    }
}

package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Independent test authorship for item fc8f3748 (needs-test-author) --
 * `CreateWorkTreeTool.validateParams` priority-coercion parity fix.
 *
 * Oracles (frozen in test-plan note 826560f2 / diagnosis note 5237085a, before this file was
 * written):
 *  [PM] the manage_items message "invalid priority '<v>'. Valid: high, medium, low", applied here
 *       to both the root spec and every children[] spec.
 *  [DX] diagnosis F3: a non-blank string that fails case-insensitive Priority.fromString throws
 *       ToolValidationException naming the spec (root, or children[i]); blank or absent still
 *       means MEDIUM.
 *
 * Message-assertion style and the flat children + ref/title spec shape mirror
 * [CreateWorkTreeToolTest] (e.g. its "children[0]" citation at line 523) -- read from src/test,
 * not from src/main.
 */
class CreateWorkTreePriorityValidationTest {
    private val tool = CreateWorkTreeTool()

    private fun rootWithPriority(priority: String?) =
        buildJsonObject {
            put(
                "root",
                buildJsonObject {
                    put("title", JsonPrimitive("Root Task"))
                    if (priority != null) put("priority", JsonPrimitive(priority))
                }
            )
        }

    private fun childSpec(
        ref: String,
        title: String,
        priority: String? = null,
    ) = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("title", JsonPrimitive(title))
        if (priority != null) put("priority", JsonPrimitive(priority))
    }

    // -----------------------------------------------------------------------
    // S5 -- happy: an invalid root priority throws with the manage_items-parity message
    // -----------------------------------------------------------------------

    @Test
    fun `S5 root priority urgent throws ToolValidationException naming the invalid value`() {
        val ex =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(rootWithPriority("urgent"))
            }
        assertTrue(
            ex.message!!.contains("invalid priority 'urgent'. Valid: high, medium, low"),
            "actual message: ${ex.message}",
        )
    }

    // -----------------------------------------------------------------------
    // S10 -- failure: an invalid priority on a NON-root child is named by its index
    // -----------------------------------------------------------------------

    @Test
    fun `S10 children 1 priority critical throws naming children index 1 and the invalid value`() {
        val params =
            buildJsonObject {
                put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                put(
                    "children",
                    buildJsonArray {
                        add(childSpec("c0", "Child Zero"))
                        add(childSpec("c1", "Child One", priority = "critical"))
                    },
                )
            }

        val ex =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(params)
            }
        assertTrue(ex.message!!.contains("children[1]"), "actual message: ${ex.message}")
        assertTrue(
            ex.message!!.contains("invalid priority 'critical'"),
            "actual message: ${ex.message}",
        )
    }

    // -----------------------------------------------------------------------
    // S17 -- edge: validateParams accepts "HIGH", "High" and "" without throwing
    // -----------------------------------------------------------------------

    @Test
    fun `S17 root priority HIGH is accepted case-insensitively`() {
        tool.validateParams(rootWithPriority("HIGH"))
    }

    @Test
    fun `S17 root priority High mixed case is accepted`() {
        tool.validateParams(rootWithPriority("High"))
    }

    @Test
    fun `S17 root priority blank string is treated as absent and accepted`() {
        tool.validateParams(rootWithPriority(""))
    }

    // -----------------------------------------------------------------------
    // Probe -- absent priority (the field omitted entirely, distinct from blank) is also accepted
    // -----------------------------------------------------------------------

    @Test
    fun `probe absent root priority field is accepted`() {
        tool.validateParams(rootWithPriority(null))
    }

    // -----------------------------------------------------------------------
    // Probe -- an invalid priority on the FIRST child (index 0) is also rejected and named
    // -----------------------------------------------------------------------

    @Test
    fun `probe children 0 priority invalid is rejected and named by index`() {
        val params =
            buildJsonObject {
                put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                put(
                    "children",
                    buildJsonArray {
                        add(childSpec("c0", "Child Zero", priority = "urgent"))
                    },
                )
            }

        val ex =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(params)
            }
        assertTrue(ex.message!!.contains("children[0]"), "actual message: ${ex.message}")
    }
}

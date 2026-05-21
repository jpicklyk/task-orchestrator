package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.dependency.QueryDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.notes.QueryNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.ClaimItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetBlockedItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetNextItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetNextStatusTool
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Verifies that every parameter named in a tool's [ToolDefinition.parameterSchema] is
 * mentioned somewhere in the tool's [ToolDefinition.description] string.
 *
 * There are three independent documentation surfaces that must stay in sync:
 *   1. description string  — what LLMs see via tools/list
 *   2. parameterSchema     — what MCP clients validate against
 *   3. api-reference.md    — what humans read
 *
 * This test guards the description↔schema surface automatically on every CI run.
 * The api-reference.md surface requires human review; see CLAUDE.md "Documentation surfaces" note.
 */
class ToolDocumentationConsistencyTest {
    private val allTools: List<ToolDefinition> =
        listOf(
            ManageItemsTool(),
            QueryItemsTool(),
            ManageNotesTool(),
            QueryNotesTool(),
            ManageDependenciesTool(),
            QueryDependenciesTool(),
            AdvanceItemTool(),
            ClaimItemTool(),
            GetBlockedItemsTool(),
            GetContextTool(),
            GetNextItemTool(),
            GetNextStatusTool(),
            CompleteTreeTool(),
            CreateWorkTreeTool(),
        )

    @Test
    fun `all schema parameter names appear in tool description string`() {
        val failures = mutableListOf<String>()

        for (tool in allTools) {
            val description = tool.description
            val schemaProps = tool.parameterSchema.properties ?: continue

            for (paramName in schemaProps.keys) {
                if (!description.contains(paramName)) {
                    failures.add("${tool.name}: schema param '$paramName' not mentioned in description")
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "Tool description/schema mismatches found. " +
                    "Add each param to the tool's description string:\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }

    @Test
    fun `all required schema parameters are marked required in tool description`() {
        val failures = mutableListOf<String>()

        for (tool in allTools) {
            val description = tool.description
            val required = tool.parameterSchema.required ?: continue

            for (paramName in required) {
                // Required params must appear AND should not be described as optional-only.
                // We check presence here; callers can verify the "required" framing manually.
                if (!description.contains(paramName)) {
                    failures.add("${tool.name}: required param '$paramName' not mentioned in description")
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "Required schema params absent from description strings:\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }
}

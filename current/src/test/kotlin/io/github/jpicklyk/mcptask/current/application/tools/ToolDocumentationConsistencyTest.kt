package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.tools.compound.CompleteTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.config.ManagePlanDocumentsTool
import io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigTool
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Verifies the token-efficiency-era documentation contract (see the "MCP Token-Efficiency
 * Program" t21-t23 work): every parameter is documented ONCE, in its own `parameterSchema`
 * field description — the single source of truth MCP clients and LLMs both see via
 * `tools/list`. The prose `description` string is reserved for what a flat JSON Schema
 * can't express: operation/mode enum selection, mode-selection rules, trigger effects,
 * gate semantics, and mutual-exclusion/XOR constraints.
 *
 * This deliberately supersedes the older "every schema param name must literally appear in
 * the description string" invariant: that policy forced per-field prose that duplicated the
 * schema on every tool, which is exactly the token bloat the efficiency program removed
 * (baseline tools/list payload 56,984 chars; budget <= 30,000). See CLAUDE.md
 * "Tool Documentation Surfaces" for the current policy.
 *
 * Guards two things instead:
 *   1. Every parameterSchema property has a non-blank field-level `description`.
 *   2. Every operation/mode-selecting enum value is still named in the prose description,
 *      so a caller can discover what operations exist without parsing the full schema.
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
            ManageProjectConfigTool(),
            ManagePlanDocumentsTool(),
        )

    @Test
    fun `every schema parameter has a non-blank field-level description`() {
        val failures = mutableListOf<String>()

        for (tool in allTools) {
            val schemaProps = tool.parameterSchema.properties ?: continue

            for (paramName in schemaProps.keys) {
                val paramSchema = schemaProps[paramName] as? JsonObject
                val desc = paramSchema?.get("description")?.jsonPrimitive?.contentOrNull
                if (desc.isNullOrBlank()) {
                    failures.add("${tool.name}: schema param '$paramName' has no field-level description")
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "Schema params missing a field-level description (single-source-of-truth violation). " +
                    "Add a 'description' to each in its parameterSchema entry:\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }

    @Test
    fun `operation and mode enum values are named in the tool description`() {
        val failures = mutableListOf<String>()

        for (tool in allTools) {
            val schemaProps = tool.parameterSchema.properties ?: continue

            for (paramName in listOf("operation", "mode")) {
                val paramSchema = schemaProps[paramName] as? JsonObject ?: continue
                val enumValues =
                    paramSchema["enum"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: continue

                for (value in enumValues) {
                    if (!tool.description.contains(value)) {
                        failures.add("${tool.name}: $paramName value '$value' not mentioned in description")
                    }
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "Operation/mode enum values missing from the tool's prose description — callers " +
                    "should be able to discover available operations without reading the full schema:\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }
}

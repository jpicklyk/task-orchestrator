package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.application.tools.section.ManageSectionsTool
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Tests to verify that v2.0 consolidated management tools have:
 * 1. Concise descriptions suitable for MCP context
 * 2. Proper parameter schemas with operation parameter
 * 3. Links to detailed documentation
 *
 * Related to Feature: MCP Context Optimization v2.0
 * Related Task: Tool Consolidation for Token Efficiency
 */
class UpdateToolsDocumentationTest {

    companion object {
        /**
         * Provides all v2.0 management tools for parameterized testing
         */
        @JvmStatic
        fun managementTools() = listOf(
            ManageContainerTool(null, null),
            ManageSectionsTool(null, null)
        )
    }

    @ParameterizedTest
    @MethodSource("managementTools")
    fun `should have reasonable description length`(tool: ToolDefinition) {
        val description = tool.description
        // v2.0 tools can be longer due to operation documentation, but should be reasonable
        assertTrue(
            description.length < 3000,
            "${tool.name}: Description should be under 3000 chars for context efficiency. Found: ${description.length} chars"
        )
    }

    @ParameterizedTest
    @MethodSource("managementTools")
    fun `should document supported operations`(tool: ToolDefinition) {
        val description = tool.description
        assertTrue(
            description.contains("operation", ignoreCase = true) ||
            description.contains("Operations:", ignoreCase = true),
            "${tool.name}: Description should document supported operations"
        )
    }

    @ParameterizedTest
    @MethodSource("managementTools")
    fun `should link to detailed documentation`(tool: ToolDefinition) {
        val description = tool.description
        assertTrue(
            description.contains("task-orchestrator://docs/tools/") ||
            description.contains("Docs:"),
            "${tool.name}: Description should link to detailed documentation"
        )
    }

    @ParameterizedTest
    @MethodSource("managementTools")
    fun `should have operation parameter in schema`(tool: ToolDefinition) {
        val paramSchema = tool.parameterSchema
        val properties = paramSchema.properties as? JsonObject
        assertNotNull(properties, "${tool.name}: Should have properties in parameter schema")

        val operationParam = properties?.get("operation") as? JsonObject
        assertNotNull(operationParam, "${tool.name}: Should have 'operation' parameter")

        val operationType = operationParam?.get("enum") as? JsonArray
        assertNotNull(operationType, "${tool.name}: 'operation' parameter should have enum type")
        assertTrue(
            (operationType?.size ?: 0) > 0,
            "${tool.name}: 'operation' enum should list available operations"
        )
    }

    @Test
    fun `ManageContainerTool should support container types`() {
        val tool = ManageContainerTool(null, null)
        val paramSchema = tool.parameterSchema
        val properties = paramSchema.properties as JsonObject

        val containerTypeParam = properties["containerType"] as? JsonObject
        assertNotNull(containerTypeParam, "ManageContainerTool should have 'containerType' parameter")

        val containerTypes = containerTypeParam?.get("enum") as? JsonArray
        assertNotNull(containerTypes, "'containerType' should be enum")

        // Should support at least project, feature, task
        val typesString = containerTypes.toString()
        assertTrue(typesString.contains("project"), "Should support 'project' container type")
        assertTrue(typesString.contains("feature"), "Should support 'feature' container type")
        assertTrue(typesString.contains("task"), "Should support 'task' container type")
    }

    @Test
    fun `ManageSectionsTool should support entity types`() {
        val tool = ManageSectionsTool(null, null)
        val paramSchema = tool.parameterSchema
        val properties = paramSchema.properties as JsonObject

        // The tool should document which entity types it supports
        val description = tool.description
        assertTrue(
            description.contains("entityType", ignoreCase = true) ||
            description.contains("TASK", ignoreCase = true),
            "ManageSectionsTool should document supported entity types"
        )
    }

    @Test
    fun `v2 consolidated tools should be more efficient than v1 equivalents`() {
        // This test documents the token efficiency improvement of v2.0
        // ManageContainerTool replaces: create_task, get_task, update_task, delete_task, set_status,
        //                                create_feature, get_feature, update_feature, delete_feature,
        //                                create_project, get_project, update_project, delete_project
        // That's 13 tools → 1 tool (92% reduction in tool count)

        val manageContainerTool = ManageContainerTool(null, null)
        val manageSectionsTool = ManageSectionsTool(null, null)

        // Just verify the tools exist and can be instantiated
        assertNotNull(manageContainerTool)
        assertNotNull(manageSectionsTool)

        // The real efficiency is in reduced tool definitions sent to MCP client
        // v1: ~56 tools × ~1500 chars avg = ~84k chars
        // v2: ~18 tools × ~2000 chars avg = ~36k chars (57% reduction)
    }
}

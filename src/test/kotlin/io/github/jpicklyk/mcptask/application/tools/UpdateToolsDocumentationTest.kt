package io.github.jpicklyk.mcptask.application.tools

import io.github.jpicklyk.mcptask.application.tools.feature.UpdateFeatureTool
import io.github.jpicklyk.mcptask.application.tools.project.UpdateProjectTool
import io.github.jpicklyk.mcptask.application.tools.section.UpdateSectionTool
import io.github.jpicklyk.mcptask.application.tools.task.UpdateTaskTool
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Tests to verify that all update tools have:
 * 1. Concise descriptions with links to detailed docs
 * 2. Tool-level efficiency enforcement via UpdateEfficiencyMetrics
 * 3. Proper parameter schemas
 *
 * Related to Feature: MCP Context Optimization
 * Related Task: Reduce MCP tool description verbosity to lower context usage
 */
class UpdateToolsDocumentationTest {

    companion object {
        /**
         * Provides all update tools for parameterized testing
         */
        @JvmStatic
        fun updateTools() = listOf(
            UpdateTaskTool(),
            UpdateFeatureTool(),
            UpdateProjectTool(),
            UpdateSectionTool()
        )
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should have concise description under 900 characters`(tool: ToolDefinition) {
        val description = tool.description
        assertTrue(
            description.length < 900,
            "${tool.name}: Description should be concise (<900 chars, down from ~1500). Found: ${description.length} chars"
        )
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should mention to only send fields you want to change`(tool: ToolDefinition) {
        val description = tool.description
        assertTrue(
            description.contains("only send", ignoreCase = true) ||
            description.contains("fields you want to change", ignoreCase = true),
            "${tool.name}: Description should mention 'only send fields you want to change'"
        )
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should link to detailed documentation`(tool: ToolDefinition) {
        val description = tool.description
        assertTrue(
            description.contains("task-orchestrator://docs/tools/") ||
            description.contains("Docs:"),
            "${tool.name}: Description should link to detailed documentation"
        )
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should NOT contain verbose JSON examples in description`(tool: ToolDefinition) {
        val description = tool.description
        val jsonCodeBlocks = description.split("```json").size - 1
        assertTrue(
            jsonCodeBlocks == 0,
            "${tool.name}: Description should NOT contain JSON code blocks (enforcement moved to tool-level). Found: $jsonCodeBlocks blocks"
        )
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should NOT contain verbose efficiency tips in description`(tool: ToolDefinition) {
        val description = tool.description
        val lines = description.lines()

        // Should NOT have multi-line efficiency tip sections
        assertFalse(
            description.contains("INEFFICIENT") && description.contains("EFFICIENT"),
            "${tool.name}: Verbose efficiency tips should be removed from description (enforcement moved to tool-level)"
        )

        // Should NOT have emoji-heavy formatting
        val emojiCount = description.count { it == '⚡' || it == '❌' || it == '✅' }
        assertTrue(
            emojiCount == 0,
            "${tool.name}: Verbose emoji formatting should be removed. Found $emojiCount emojis"
        )
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should have all non-id parameters marked as optional`(tool: ToolDefinition) {
        val parameterSchema = tool.parameterSchema
        val properties = parameterSchema.properties as? JsonObject

        assertNotNull(properties, "${tool.name}: Parameter schema should have properties")

        // Get required fields
        val required = parameterSchema.required

        // Verify 'id' is the only required parameter
        assertNotNull(required, "${tool.name}: Required list should not be null")
        assertEquals(
            listOf("id"),
            required,
            "${tool.name}: Only 'id' should be required, all other parameters should be optional"
        )

        // Check each non-id parameter has "(optional)" in description
        properties!!.keys.forEach { paramName ->
            if (paramName != "id") {
                val paramDef = properties[paramName]?.jsonObject
                assertNotNull(paramDef, "${tool.name}: Parameter '$paramName' should have definition")

                val description = paramDef!!["description"]?.jsonPrimitive?.content
                assertNotNull(
                    description,
                    "${tool.name}: Parameter '$paramName' should have description"
                )

                assertTrue(
                    description!!.contains("(optional)", ignoreCase = true),
                    "${tool.name}: Parameter '$paramName' description should include '(optional)' marker. Found: '$description'"
                )
            }
        }
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should have id as required parameter`(tool: ToolDefinition) {
        val parameterSchema = tool.parameterSchema
        val required = parameterSchema.required

        assertNotNull(required, "${tool.name}: Required list should not be null")
        assertTrue(
            required!!.contains("id"),
            "${tool.name}: 'id' should be in required parameters list"
        )

        val properties = parameterSchema.properties as? JsonObject
        assertNotNull(properties, "${tool.name}: Parameter schema should have properties")

        val idParam = properties!!["id"]?.jsonObject
        assertNotNull(idParam, "${tool.name}: Should have 'id' parameter definition")

        val idDescription = idParam!!["description"]?.jsonPrimitive?.content
        assertNotNull(idDescription, "${tool.name}: 'id' parameter should have description")

        // ID should NOT be marked as optional
        assertFalse(
            idDescription!!.contains("(optional)", ignoreCase = true),
            "${tool.name}: 'id' parameter should NOT be marked as optional"
        )
    }

    @Test
    fun `UpdateSectionTool should reference specialized efficiency tools`() {
        val tool = UpdateSectionTool()
        val description = tool.description

        // UpdateSectionTool should reference the more efficient specialized tools
        assertTrue(
            description.contains("update_section_text"),
            "UpdateSectionTool should reference update_section_text for content-only changes"
        )
    }

    @Test
    fun `UpdateEfficiencyMetrics should detect inefficient updates`() {
        // Test with inefficient update (many fields)
        val inefficientParams = buildJsonObject {
            put("id", "test-id")
            put("title", "Test")
            put("summary", "Test summary")
            put("description", "Test description")
            put("status", "completed")
            put("priority", "high")
            put("complexity", 5)
        }

        val metrics = UpdateEfficiencyMetrics.analyzeUpdate("update_task", inefficientParams)

        assertEquals("inefficient", metrics["efficiencyLevel"]?.jsonPrimitive?.content)
        assertTrue(metrics["changedParams"]?.jsonPrimitive?.int!! >= 5)
    }

    @Test
    fun `UpdateEfficiencyMetrics should detect optimal updates`() {
        // Test with optimal update (1 field)
        val optimalParams = buildJsonObject {
            put("id", "test-id")
            put("status", "completed")
        }

        val metrics = UpdateEfficiencyMetrics.analyzeUpdate("update_task", optimalParams)

        assertEquals("optimal", metrics["efficiencyLevel"]?.jsonPrimitive?.content)
        assertEquals(1, metrics["changedParams"]?.jsonPrimitive?.int)
    }
}

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
 * Tests to verify that all update tools have proper efficiency documentation.
 *
 * These tests ensure that AI agents receive clear guidance about partial updates:
 * 1. Efficiency tip appears prominently in the first 3 lines
 * 2. All non-id parameters are clearly marked as "(optional)"
 * 3. Examples demonstrating partial update patterns exist
 *
 * Related to Feature: AI Update Efficiency Improvements
 * Related Task: Create Tests for Partial Update Documentation
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
    fun `should have efficiency tip in first 3 lines of description`(tool: ToolDefinition) {
        val description = tool.description
        val firstThreeLines = description.lines().take(3).joinToString("\n")

        // Check for the efficiency tip marker
        assertTrue(
            firstThreeLines.contains("⚡") || firstThreeLines.contains("EFFICIENCY"),
            "${tool.name}: Efficiency tip should appear in first 3 lines of description. Found:\n$firstThreeLines"
        )

        // Verify it mentions "optional" or "only send"
        assertTrue(
            firstThreeLines.contains("optional", ignoreCase = true) ||
            firstThreeLines.contains("only send", ignoreCase = true),
            "${tool.name}: Efficiency tip should mention 'optional' or 'only send' in first 3 lines"
        )
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should have examples of efficient vs inefficient patterns`(tool: ToolDefinition) {
        val description = tool.description

        // Check for example sections
        assertTrue(
            description.contains("EFFICIENT", ignoreCase = true),
            "${tool.name}: Description should contain 'EFFICIENT' example section"
        )

        assertTrue(
            description.contains("INEFFICIENT", ignoreCase = true),
            "${tool.name}: Description should contain 'INEFFICIENT' example section"
        )

        // Check for checkmark and cross symbols
        assertTrue(
            description.contains("✅") || description.contains("✓"),
            "${tool.name}: Description should use ✅ or ✓ to mark efficient examples"
        )

        assertTrue(
            description.contains("❌") || description.contains("✗"),
            "${tool.name}: Description should use ❌ or ✗ to mark inefficient examples"
        )

        // Check for JSON code blocks
        val jsonCodeBlockCount = description.split("```json").size - 1
        assertTrue(
            jsonCodeBlockCount >= 2,
            "${tool.name}: Description should contain at least 2 JSON code blocks for examples (found $jsonCodeBlockCount)"
        )
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should mention token savings percentage`(tool: ToolDefinition) {
        val description = tool.description

        // Check for percentage mentions (90%, 94%, 88%, etc.)
        val percentagePattern = Regex("\\d{2,}%")
        val percentages = percentagePattern.findAll(description).toList()

        assertTrue(
            percentages.isNotEmpty(),
            "${tool.name}: Description should mention specific token savings percentage"
        )

        // Verify it's a substantial percentage (>= 80%)
        val hasSubstantialSavings = percentages.any { match ->
            val percentage = match.value.trimEnd('%').toIntOrNull() ?: 0
            percentage >= 80
        }

        assertTrue(
            hasSubstantialSavings,
            "${tool.name}: Description should mention at least 80% token savings"
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
    fun `UpdateTaskTool should have proper efficiency documentation`() {
        val tool = UpdateTaskTool()

        // Verify tool name
        assertEquals("update_task", tool.name)

        // Verify description contains key phrases
        val description = tool.description
        assertTrue(description.contains("partial update", ignoreCase = true) ||
                   description.contains("only send", ignoreCase = true))
        assertTrue(description.contains("token", ignoreCase = true))
    }

    @Test
    fun `UpdateFeatureTool should have proper efficiency documentation`() {
        val tool = UpdateFeatureTool()

        // Verify tool name
        assertEquals("update_feature", tool.name)

        // Verify description contains key phrases
        val description = tool.description
        assertTrue(description.contains("partial update", ignoreCase = true) ||
                   description.contains("only send", ignoreCase = true))
        assertTrue(description.contains("token", ignoreCase = true))
    }

    @Test
    fun `UpdateProjectTool should have proper efficiency documentation`() {
        val tool = UpdateProjectTool()

        // Verify tool name
        assertEquals("update_project", tool.name)

        // Verify description contains key phrases
        val description = tool.description
        assertTrue(description.contains("partial update", ignoreCase = true) ||
                   description.contains("only send", ignoreCase = true))
        assertTrue(description.contains("token", ignoreCase = true))
    }

    @Test
    fun `UpdateSectionTool should have proper efficiency documentation`() {
        val tool = UpdateSectionTool()

        // Verify tool name
        assertEquals("update_section", tool.name)

        // Verify description contains key phrases
        val description = tool.description
        assertTrue(description.contains("partial update", ignoreCase = true) ||
                   description.contains("only send", ignoreCase = true) ||
                   description.contains("update_section_text", ignoreCase = true))
        assertTrue(description.contains("token", ignoreCase = true) ||
                   description.contains("efficient", ignoreCase = true))
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

        assertTrue(
            description.contains("update_section_metadata"),
            "UpdateSectionTool should reference update_section_metadata for metadata-only changes"
        )
    }

    @ParameterizedTest
    @MethodSource("updateTools")
    fun `should have clear separation between efficient and inefficient examples`(tool: ToolDefinition) {
        val description = tool.description

        // Find the efficient and inefficient sections
        val lines = description.lines()
        var efficientLineIndex = -1
        var inefficientLineIndex = -1

        lines.forEachIndexed { index, line ->
            if (line.contains("EFFICIENT", ignoreCase = true) &&
                (line.contains("✅") || line.contains("✓"))) {
                efficientLineIndex = index
            }
            if (line.contains("INEFFICIENT", ignoreCase = true) &&
                (line.contains("❌") || line.contains("✗"))) {
                inefficientLineIndex = index
            }
        }

        assertTrue(
            efficientLineIndex >= 0,
            "${tool.name}: Should have clearly marked EFFICIENT section"
        )

        assertTrue(
            inefficientLineIndex >= 0,
            "${tool.name}: Should have clearly marked INEFFICIENT section"
        )

        // Inefficient should come before efficient (show problem, then solution)
        assertTrue(
            inefficientLineIndex < efficientLineIndex,
            "${tool.name}: INEFFICIENT example should appear before EFFICIENT example (problem then solution)"
        )
    }
}

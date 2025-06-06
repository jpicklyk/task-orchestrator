package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.EntityType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BugInvestigationWorkflowTemplateCreatorTest {

    @Test
    fun `create should return a valid template and sections`() {
        // When
        val (template, sections) = BugInvestigationWorkflowTemplateCreator.create()

        // Then
        // Verify template properties
        assertEquals("Bug Investigation Workflow", template.name)
        assertEquals(EntityType.TASK, template.targetEntityType)
        assertTrue(template.isBuiltIn)
        assertTrue(template.isProtected)
        assertTrue(template.isEnabled)
        assertTrue(template.tags.contains("bug"))
        assertTrue(template.tags.contains("investigation"))
        assertTrue(template.tags.contains("workflow"))
        assertTrue(template.tags.contains("ai-optimized"))
        assertTrue(template.tags.contains("debugging"))
        assertTrue(template.tags.contains("mcp-tools"))
        assertTrue(template.tags.contains("task-type-bug"))

        // Verify sections
        assertEquals(3, sections.size, "Should create 3 sections")
        
        // Verify section titles
        val sectionTitles = sections.map { it.title }.toSet()
        assertTrue(sectionTitles.contains("Investigation Process"))
        assertTrue(sectionTitles.contains("Root Cause Analysis"))
        assertTrue(sectionTitles.contains("Fix Implementation & Verification"))
        
        // Verify section order
        val investigationSection = sections.first { it.title == "Investigation Process" }
        val rootCauseSection = sections.first { it.title == "Root Cause Analysis" }
        val fixImplementationSection = sections.first { it.title == "Fix Implementation & Verification" }

        assertEquals(0, investigationSection.ordinal, "Investigation Process should be first")
        assertEquals(1, rootCauseSection.ordinal, "Root Cause Analysis should be second")
        assertEquals(2, fixImplementationSection.ordinal, "Fix Implementation & Verification should be third")

        // Verify Investigation Process content includes MCP tool references
        val investigationContent = investigationSection.contentSample
        assertTrue(investigationContent.contains("get_task"))
        assertTrue(investigationContent.contains("search_tasks"))
        assertTrue(investigationContent.contains("MCP tool"))
        assertTrue(investigationContent.contains("Bug Context Retrieval"))
        assertTrue(investigationContent.contains("Related Issue Search"))

        // Verify Root Cause Analysis content
        val rootCauseContent = rootCauseSection.contentSample
        assertTrue(rootCauseContent.contains("Root Cause Hypothesis"))
        assertTrue(rootCauseContent.contains("Systematic Analysis Process"))
        assertTrue(rootCauseContent.contains("Hypothesis Testing"))

        // Verify Fix Implementation content includes MCP references
        val fixContent = fixImplementationSection.contentSample
        assertTrue(fixContent.contains("update_task"))
        assertTrue(fixContent.contains("Fix Strategy Development"))
        assertTrue(fixContent.contains("Fix Implementation Process"))
        assertTrue(fixContent.contains("Comprehensive Testing Strategy"))
        
        // Verify required sections
        assertTrue(investigationSection.isRequired, "Investigation Process should be required")
        assertTrue(rootCauseSection.isRequired, "Root Cause Analysis should be required")
        assertTrue(fixImplementationSection.isRequired, "Fix Implementation & Verification should be required")

        // Verify all sections have the correct template ID
        sections.forEach { section ->
            assertEquals(template.id, section.templateId, "All sections should reference the template ID")
        }
    }
}
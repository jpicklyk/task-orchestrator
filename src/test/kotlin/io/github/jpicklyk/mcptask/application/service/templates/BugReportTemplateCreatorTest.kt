package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.EntityType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BugReportTemplateCreatorTest {

    @Test
    fun `create should return a valid template and sections`() {
        // When
        val (template, sections) = BugReportTemplateCreator.create()

        // Then
        // Verify template properties
        assertEquals("Bug Report Template", template.name)
        assertEquals(EntityType.TASK, template.targetEntityType)
        assertTrue(template.isBuiltIn)
        assertTrue(template.isProtected)
        assertTrue(template.isEnabled)
        assertTrue(template.tags.contains("bug"))
        assertTrue(template.tags.contains("workflow"))
        assertTrue(template.tags.contains("ai-optimized"))

        // Verify sections
        assertEquals(6, sections.size, "Should create 6 sections")
        
        // Verify section titles
        val sectionTitles = sections.map { it.title }.toSet()
        assertTrue(sectionTitles.contains("Bug Description"))
        assertTrue(sectionTitles.contains("Technical Investigation"))
        assertTrue(sectionTitles.contains("Proposed Fix"))
        assertTrue(sectionTitles.contains("Verification Plan"))
        assertTrue(sectionTitles.contains("AI Workflow"))
        assertTrue(sectionTitles.contains("Follow-up Actions"))
        
        // Verify section order
        val bugDescriptionSection = sections.first { it.title == "Bug Description" }
        val aiWorkflowSection = sections.first { it.title == "AI Workflow" }
        assertEquals(0, bugDescriptionSection.ordinal, "Bug Description should be first")
        assertEquals(4, aiWorkflowSection.ordinal, "AI Workflow should be fifth")
        
        // Verify AI Workflow content
        val aiWorkflowContent = aiWorkflowSection.contentSample
        assertTrue(aiWorkflowContent.contains("Branch Creation"))
        assertTrue(aiWorkflowContent.contains("Branch Switch"))
        assertTrue(aiWorkflowContent.contains("Code Investigation"))
        assertTrue(aiWorkflowContent.contains("Fix Implementation"))
        assertTrue(aiWorkflowContent.contains("Build Verification"))
        assertTrue(aiWorkflowContent.contains("Test Creation"))
        assertTrue(aiWorkflowContent.contains("Test Verification"))
        assertTrue(aiWorkflowContent.contains("Full Test Suite"))
        assertTrue(aiWorkflowContent.contains("Commit Preparation"))
        
        // Verify required sections
        assertTrue(bugDescriptionSection.isRequired, "Bug Description should be required")
        assertTrue(aiWorkflowSection.isRequired, "AI Workflow should be required")
    }
}

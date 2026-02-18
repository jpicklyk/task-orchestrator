package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
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
        assertEquals("Bug Investigation", template.name)
        assertEquals(EntityType.TASK, template.targetEntityType)
        assertTrue(template.isBuiltIn)
        assertTrue(template.isProtected)
        assertTrue(template.isEnabled)
        assertTrue(template.tags.contains("bug"))
        assertTrue(template.tags.contains("investigation"))
        assertTrue(template.tags.contains("debugging"))
        assertTrue(template.tags.contains("task-type-bug"))

        // Verify sections
        assertEquals(4, sections.size, "Should create 4 sections")

        // Verify section titles
        val sectionTitles = sections.map { it.title }.toSet()
        assertTrue(sectionTitles.contains("Investigation Findings"))
        assertTrue(sectionTitles.contains("Root Cause"))
        assertTrue(sectionTitles.contains("Fix & Verification"))
        assertTrue(sectionTitles.contains("Verification"))

        // Verify section order
        val investigationSection = sections.first { it.title == "Investigation Findings" }
        val rootCauseSection = sections.first { it.title == "Root Cause" }
        val fixSection = sections.first { it.title == "Fix & Verification" }
        val verificationSection = sections.first { it.title == "Verification" }

        assertEquals(0, investigationSection.ordinal, "Investigation Findings should be first")
        assertEquals(1, rootCauseSection.ordinal, "Root Cause should be second")
        assertEquals(2, fixSection.ordinal, "Fix & Verification should be third")
        assertEquals(3, verificationSection.ordinal, "Verification should be fourth")

        // Verify sections are brief prompts (floor, not ceiling)
        sections.forEach { section ->
            assertTrue(
                section.contentSample.length < 500,
                "Section '${section.title}' content should be brief (was ${section.contentSample.length} chars)"
            )
        }

        // Verify required sections
        assertTrue(investigationSection.isRequired, "Investigation Findings should be required")
        assertTrue(rootCauseSection.isRequired, "Root Cause should be required")
        assertTrue(fixSection.isRequired, "Fix & Verification should be required")
        assertTrue(verificationSection.isRequired, "Verification should be required")

        // Verify Verification section properties
        assertEquals(ContentFormat.JSON, verificationSection.contentFormat, "Verification section should use JSON format")
        assertTrue(verificationSection.tags.contains("verification"), "Verification section should have 'verification' tag")
        assertTrue(verificationSection.tags.contains("acceptance-criteria"), "Verification section should have 'acceptance-criteria' tag")

        // Verify all sections have the correct template ID
        sections.forEach { section ->
            assertEquals(template.id, section.templateId, "All sections should reference the template ID")
        }
    }
}

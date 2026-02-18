package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanningTemplateCreatorsTest {

    @Nested
    inner class FeaturePlanTemplateCreatorTests {

        @Test
        fun `create should return a template targeting FEATURE`() {
            val (template, _) = FeaturePlanTemplateCreator.create()

            assertEquals("Feature Plan", template.name)
            assertEquals(EntityType.FEATURE, template.targetEntityType)
            assertTrue(template.isBuiltIn)
            assertTrue(template.isProtected)
            assertTrue(template.isEnabled)
            assertEquals("System", template.createdBy)
        }

        @Test
        fun `create should have correct tags`() {
            val (template, _) = FeaturePlanTemplateCreator.create()

            assertTrue(template.tags.contains("planning"))
            assertTrue(template.tags.contains("architecture"))
            assertTrue(template.tags.contains("ai-optimized"))
            assertTrue(template.tags.contains("engineering"))
        }

        @Test
        fun `create should return exactly 8 sections`() {
            val (_, sections) = FeaturePlanTemplateCreator.create()

            assertEquals(8, sections.size, "Should create 8 sections")
        }

        @Test
        fun `sections should have ordinals 0 through 7`() {
            val (_, sections) = FeaturePlanTemplateCreator.create()

            val ordinals = sections.map { it.ordinal }.sorted()
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), ordinals)
        }

        @Test
        fun `required sections should be at correct ordinals`() {
            val (_, sections) = FeaturePlanTemplateCreator.create()

            val problemStatement = sections.first { it.title == "Problem Statement" }
            val architectureOverview = sections.first { it.title == "Architecture Overview" }
            val implementationPhases = sections.first { it.title == "Implementation Phases" }
            val fileChangeManifest = sections.first { it.title == "File Change Manifest" }
            val verification = sections.first { it.title == "Verification" }

            assertEquals(0, problemStatement.ordinal)
            assertEquals(1, architectureOverview.ordinal)
            assertEquals(2, implementationPhases.ordinal)
            assertEquals(3, fileChangeManifest.ordinal)
            assertEquals(7, verification.ordinal)

            assertTrue(problemStatement.isRequired, "Problem Statement should be required")
            assertTrue(architectureOverview.isRequired, "Architecture Overview should be required")
            assertTrue(implementationPhases.isRequired, "Implementation Phases should be required")
            assertTrue(fileChangeManifest.isRequired, "File Change Manifest should be required")
            assertTrue(verification.isRequired, "Verification should be required")
        }

        @Test
        fun `optional sections should be at correct ordinals`() {
            val (_, sections) = FeaturePlanTemplateCreator.create()

            val designDecisions = sections.first { it.title == "Design Decisions" }
            val executionNotes = sections.first { it.title == "Execution Notes" }
            val risksAndMitigations = sections.first { it.title == "Risks & Mitigations" }

            assertEquals(4, designDecisions.ordinal)
            assertEquals(5, executionNotes.ordinal)
            assertEquals(6, risksAndMitigations.ordinal)

            assertFalse(designDecisions.isRequired, "Design Decisions should be optional")
            assertFalse(executionNotes.isRequired, "Execution Notes should be optional")
            assertFalse(risksAndMitigations.isRequired, "Risks & Mitigations should be optional")
        }

        @Test
        fun `verification section should use JSON content format`() {
            val (_, sections) = FeaturePlanTemplateCreator.create()

            val verification = sections.first { it.title == "Verification" }
            assertEquals(ContentFormat.JSON, verification.contentFormat, "Verification section should use JSON format")
            assertTrue(verification.tags.contains("verification"))
            assertTrue(verification.tags.contains("acceptance-criteria"))
        }

        @Test
        fun `all sections should reference the template ID`() {
            val (template, sections) = FeaturePlanTemplateCreator.create()

            sections.forEach { section ->
                assertEquals(template.id, section.templateId, "Section '${section.title}' should reference the template ID")
            }
        }

        @Test
        fun `section content samples should be brief`() {
            val (_, sections) = FeaturePlanTemplateCreator.create()

            sections.forEach { section ->
                assertTrue(
                    section.contentSample.length < 500,
                    "Section '${section.title}' content should be brief (was ${section.contentSample.length} chars)"
                )
            }
        }
    }

    @Nested
    inner class CodebaseExplorationTemplateCreatorTests {

        @Test
        fun `create should return a template targeting TASK`() {
            val (template, _) = CodebaseExplorationTemplateCreator.create()

            assertEquals("Codebase Exploration", template.name)
            assertEquals(EntityType.TASK, template.targetEntityType)
            assertTrue(template.isBuiltIn)
            assertTrue(template.isProtected)
            assertTrue(template.isEnabled)
        }

        @Test
        fun `create should have correct tags`() {
            val (template, _) = CodebaseExplorationTemplateCreator.create()

            assertTrue(template.tags.contains("planning"))
            assertTrue(template.tags.contains("exploration"))
            assertTrue(template.tags.contains("research"))
            assertTrue(template.tags.contains("ai-optimized"))
        }

        @Test
        fun `create should return exactly 3 sections all required`() {
            val (_, sections) = CodebaseExplorationTemplateCreator.create()

            assertEquals(3, sections.size, "Should create 3 sections")
            sections.forEach { section ->
                assertTrue(section.isRequired, "Section '${section.title}' should be required")
            }
        }

        @Test
        fun `sections should have correct titles and ordinals`() {
            val (_, sections) = CodebaseExplorationTemplateCreator.create()

            val sectionByOrdinal = sections.associateBy { it.ordinal }
            assertEquals("Exploration Scope", sectionByOrdinal[0]?.title)
            assertEquals("Key Questions", sectionByOrdinal[1]?.title)
            assertEquals("Findings", sectionByOrdinal[2]?.title)
        }

        @Test
        fun `all sections should reference the template ID`() {
            val (template, sections) = CodebaseExplorationTemplateCreator.create()

            sections.forEach { section ->
                assertEquals(template.id, section.templateId, "Section '${section.title}' should reference the template ID")
            }
        }
    }

    @Nested
    inner class DesignDecisionTemplateCreatorTests {

        @Test
        fun `create should return a template targeting TASK`() {
            val (template, _) = DesignDecisionTemplateCreator.create()

            assertEquals("Design Decision", template.name)
            assertEquals(EntityType.TASK, template.targetEntityType)
            assertTrue(template.isBuiltIn)
            assertTrue(template.isProtected)
            assertTrue(template.isEnabled)
        }

        @Test
        fun `create should have correct tags`() {
            val (template, _) = DesignDecisionTemplateCreator.create()

            assertTrue(template.tags.contains("planning"))
            assertTrue(template.tags.contains("architecture"))
            assertTrue(template.tags.contains("design-decision"))
            assertTrue(template.tags.contains("ai-optimized"))
        }

        @Test
        fun `create should return exactly 3 sections all required`() {
            val (_, sections) = DesignDecisionTemplateCreator.create()

            assertEquals(3, sections.size, "Should create 3 sections")
            sections.forEach { section ->
                assertTrue(section.isRequired, "Section '${section.title}' should be required")
            }
        }

        @Test
        fun `sections should have correct titles and ordinals`() {
            val (_, sections) = DesignDecisionTemplateCreator.create()

            val sectionByOrdinal = sections.associateBy { it.ordinal }
            assertEquals("Decision Context", sectionByOrdinal[0]?.title)
            assertEquals("Options Analysis", sectionByOrdinal[1]?.title)
            assertEquals("Recommendation", sectionByOrdinal[2]?.title)
        }

        @Test
        fun `all sections should reference the template ID`() {
            val (template, sections) = DesignDecisionTemplateCreator.create()

            sections.forEach { section ->
                assertEquals(template.id, section.templateId, "Section '${section.title}' should reference the template ID")
            }
        }
    }

    @Nested
    inner class ImplementationSpecificationTemplateCreatorTests {

        @Test
        fun `create should return a template targeting TASK`() {
            val (template, _) = ImplementationSpecificationTemplateCreator.create()

            assertEquals("Implementation Specification", template.name)
            assertEquals(EntityType.TASK, template.targetEntityType)
            assertTrue(template.isBuiltIn)
            assertTrue(template.isProtected)
            assertTrue(template.isEnabled)
        }

        @Test
        fun `create should have correct tags`() {
            val (template, _) = ImplementationSpecificationTemplateCreator.create()

            assertTrue(template.tags.contains("planning"))
            assertTrue(template.tags.contains("implementation"))
            assertTrue(template.tags.contains("specification"))
            assertTrue(template.tags.contains("ai-optimized"))
        }

        @Test
        fun `create should return exactly 5 sections all required`() {
            val (_, sections) = ImplementationSpecificationTemplateCreator.create()

            assertEquals(5, sections.size, "Should create 5 sections")
            sections.forEach { section ->
                assertTrue(section.isRequired, "Section '${section.title}' should be required")
            }
        }

        @Test
        fun `sections should have correct titles and ordinals`() {
            val (_, sections) = ImplementationSpecificationTemplateCreator.create()

            val sectionByOrdinal = sections.associateBy { it.ordinal }
            assertEquals("Scope & Boundaries", sectionByOrdinal[0]?.title)
            assertEquals("Code Change Points", sectionByOrdinal[1]?.title)
            assertEquals("Technical Specification", sectionByOrdinal[2]?.title)
            assertEquals("Test Plan", sectionByOrdinal[3]?.title)
            assertEquals("Verification", sectionByOrdinal[4]?.title)
        }

        @Test
        fun `verification section should use JSON content format`() {
            val (_, sections) = ImplementationSpecificationTemplateCreator.create()

            val verification = sections.first { it.title == "Verification" }
            assertEquals(ContentFormat.JSON, verification.contentFormat, "Verification section should use JSON format")
            assertTrue(verification.tags.contains("verification"))
            assertTrue(verification.tags.contains("acceptance-criteria"))
        }

        @Test
        fun `all sections should reference the template ID`() {
            val (template, sections) = ImplementationSpecificationTemplateCreator.create()

            sections.forEach { section ->
                assertEquals(template.id, section.templateId, "Section '${section.title}' should reference the template ID")
            }
        }
    }

    @Nested
    inner class DisabledTemplatesTests {

        @Test
        fun `LocalGitBranchingWorkflowTemplateCreator should create a disabled template`() {
            val (template, _) = LocalGitBranchingWorkflowTemplateCreator.create()

            assertFalse(template.isEnabled, "Local Git Branching Workflow template should be disabled")
            assertEquals("Local Git Branching Workflow", template.name)
            assertTrue(template.isBuiltIn)
            assertTrue(template.isProtected)
        }

        @Test
        fun `GitHubPRWorkflowTemplateCreator should create a disabled template`() {
            val (template, _) = GitHubPRWorkflowTemplateCreator.create()

            assertFalse(template.isEnabled, "GitHub PR Workflow template should be disabled")
            assertEquals("GitHub PR Workflow", template.name)
            assertTrue(template.isBuiltIn)
            assertTrue(template.isProtected)
        }
    }
}

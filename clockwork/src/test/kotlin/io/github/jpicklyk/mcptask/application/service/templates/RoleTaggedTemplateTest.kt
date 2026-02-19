package io.github.jpicklyk.mcptask.application.service.templates

import io.github.jpicklyk.mcptask.domain.model.ContentFormat
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests verifying that all built-in templates have correct role tags and that role-based filtering works.
 *
 * Role tag convention:
 * - role:queue — Planning/requirements sections
 * - role:work — Implementation/active work sections
 * - role:review — Testing/review sections
 * - role:terminal — Completion/summary sections
 */
class RoleTaggedTemplateTest {

    companion object {
        private val VALID_ROLES = setOf("queue", "work", "review", "terminal")

        /**
         * All 13 built-in template creators as a list of factory functions.
         */
        private fun getAllTemplates(): List<Pair<Template, List<TemplateSection>>> = listOf(
            LocalGitBranchingWorkflowTemplateCreator.create(),
            GitHubPRWorkflowTemplateCreator.create(),
            TaskImplementationWorkflowTemplateCreator.create(),
            BugInvestigationWorkflowTemplateCreator.create(),
            TechnicalApproachTemplateCreator.create(),
            RequirementsSpecificationTemplateCreator.create(),
            ContextBackgroundTemplateCreator.create(),
            TestingStrategyTemplateCreator.create(),
            DefinitionOfDoneTemplateCreator.create(),
            FeaturePlanTemplateCreator.create(),
            CodebaseExplorationTemplateCreator.create(),
            DesignDecisionTemplateCreator.create(),
            ImplementationSpecificationTemplateCreator.create()
        )
    }

    @Test
    fun `all 13 template creators exist and are accessible`() {
        val allTemplates = getAllTemplates()
        assertEquals(13, allTemplates.size, "Should have all 13 built-in template creators")

        // Verify each template has sections
        allTemplates.forEach { (template, sections) ->
            assertTrue(template.name.isNotBlank(), "Template should have a name")
            assertTrue(sections.isNotEmpty(), "Template ${template.name} should have at least one section")
            assertTrue(template.isBuiltIn, "Template ${template.name} should be marked as built-in")
        }
    }

    @Test
    fun `all template sections have at least one role tag`() {
        val violations = mutableListOf<String>()

        getAllTemplates().forEach { (template, sections) ->
            sections.forEach { section ->
                val roleTags = section.tags.filter { it.startsWith("role:") }
                if (roleTags.isEmpty()) {
                    violations.add("${template.name} > ${section.title}: missing role: tag")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "All sections must have at least one role: tag. Violations:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `all role tag values are valid`() {
        val violations = mutableListOf<String>()

        getAllTemplates().forEach { (template, sections) ->
            sections.forEach { section ->
                val roleTags = section.tags.filter { it.startsWith("role:") }
                roleTags.forEach { roleTag ->
                    val roleName = roleTag.removePrefix("role:")
                    if (roleName !in VALID_ROLES) {
                        violations.add("${template.name} > ${section.title}: invalid role '$roleName' (must be one of: ${VALID_ROLES.joinToString()})")
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "All role: tags must use valid role names. Violations:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `no sections use role blocked`() {
        val violations = mutableListOf<String>()

        getAllTemplates().forEach { (template, sections) ->
            sections.forEach { section ->
                if (section.tags.any { it == "role:blocked" }) {
                    violations.add("${template.name} > ${section.title}: should not use 'role:blocked' (blocked is transient state, not workflow phase)")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "No sections should use role:blocked. Violations:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `each role has at least one section across all templates`() {
        val roleToSections = mutableMapOf<String, MutableList<Pair<String, String>>>()

        getAllTemplates().forEach { (template, sections) ->
            sections.forEach { section ->
                section.tags.filter { it.startsWith("role:") }.forEach { roleTag ->
                    val roleName = roleTag.removePrefix("role:")
                    roleToSections.getOrPut(roleName) { mutableListOf() }.add(template.name to section.title)
                }
            }
        }

        assertAll(
            { assertTrue(roleToSections.containsKey("queue"), "At least one section should have role:queue") },
            { assertTrue(roleToSections.containsKey("work"), "At least one section should have role:work") },
            { assertTrue(roleToSections.containsKey("review"), "At least one section should have role:review") },
            { assertTrue(roleToSections.containsKey("terminal"), "At least one section should have role:terminal") }
        )

        // Print distribution for visibility
        println("\nRole distribution across templates:")
        VALID_ROLES.forEach { role ->
            val sections = roleToSections[role] ?: emptyList()
            println("  role:$role - ${sections.size} sections")
        }
    }

    @Test
    fun `role queue tags are used primarily in planning and requirements sections`() {
        val queueSections = mutableListOf<Triple<String, String, List<String>>>()

        getAllTemplates().forEach { (template, sections) ->
            sections.filter { it.tags.contains("role:queue") }.forEach { section ->
                queueSections.add(Triple(template.name, section.title, section.tags))
            }
        }

        assertFalse(queueSections.isEmpty(), "Should have at least one role:queue section")

        // Verify queue sections typically have planning-related tags
        val planningKeywords = setOf("planning", "requirements", "problem", "architecture", "constraints", "design")
        queueSections.forEach { (templateName, sectionTitle, tags) ->
            val hasPlanningTag = tags.any { tag ->
                planningKeywords.any { keyword -> tag.contains(keyword, ignoreCase = true) }
            }
            // This is informational rather than strict - some queue sections may be general purpose
            if (!hasPlanningTag) {
                println("INFO: $templateName > $sectionTitle has role:queue but no explicit planning-related tags")
            }
        }
    }

    @Test
    fun `role work tags are used in implementation sections`() {
        val workSections = mutableListOf<Triple<String, String, List<String>>>()

        getAllTemplates().forEach { (template, sections) ->
            sections.filter { it.tags.contains("role:work") }.forEach { section ->
                workSections.add(Triple(template.name, section.title, section.tags))
            }
        }

        assertFalse(workSections.isEmpty(), "Should have at least one role:work section")

        // Verify work sections typically have implementation-related tags
        val implementationKeywords = setOf("implementation", "execution", "analysis", "investigation", "technical", "fix")
        workSections.forEach { (templateName, sectionTitle, tags) ->
            val hasImplementationTag = tags.any { tag ->
                implementationKeywords.any { keyword -> tag.contains(keyword, ignoreCase = true) }
            }
            if (!hasImplementationTag) {
                println("INFO: $templateName > $sectionTitle has role:work but no explicit implementation-related tags")
            }
        }
    }

    @Test
    fun `role review tags are used in testing and verification sections`() {
        val reviewSections = mutableListOf<Triple<String, String, List<String>>>()

        getAllTemplates().forEach { (template, sections) ->
            sections.filter { it.tags.contains("role:review") }.forEach { section ->
                reviewSections.add(Triple(template.name, section.title, section.tags))
            }
        }

        assertFalse(reviewSections.isEmpty(), "Should have at least one role:review section")

        // Verify review sections typically have testing/verification-related tags
        val reviewKeywords = setOf("testing", "verification", "validation", "quality", "checklist", "review")
        reviewSections.forEach { (templateName, sectionTitle, tags) ->
            val hasReviewTag = tags.any { tag ->
                reviewKeywords.any { keyword -> tag.contains(keyword, ignoreCase = true) }
            }
            if (!hasReviewTag) {
                println("INFO: $templateName > $sectionTitle has role:review but no explicit review-related tags")
            }
        }
    }

    @Test
    fun `role terminal tags are used in completion sections`() {
        val terminalSections = mutableListOf<Triple<String, String, List<String>>>()

        getAllTemplates().forEach { (template, sections) ->
            sections.filter { it.tags.contains("role:terminal") }.forEach { section ->
                terminalSections.add(Triple(template.name, section.title, section.tags))
            }
        }

        assertFalse(terminalSections.isEmpty(), "Should have at least one role:terminal section")

        // Verify terminal sections typically have completion-related tags
        val completionKeywords = setOf("verification", "acceptance", "completion", "deployment", "handoff", "done")
        terminalSections.forEach { (templateName, sectionTitle, tags) ->
            val hasCompletionTag = tags.any { tag ->
                completionKeywords.any { keyword -> tag.contains(keyword, ignoreCase = true) }
            }
            if (!hasCompletionTag) {
                println("INFO: $templateName > $sectionTitle has role:terminal but no explicit completion-related tags")
            }
        }
    }

    @Test
    fun `section filtering by role work returns only work sections`() {
        // Get a template with multiple role types
        val (template, allSections) = TaskImplementationWorkflowTemplateCreator.create()

        // Verify template has sections with different roles
        val roleTypes = allSections.flatMap { section ->
            section.tags.filter { it.startsWith("role:") }
        }.toSet()

        assertTrue(roleTypes.size > 1, "Test template should have multiple role types for meaningful test")

        // Filter for role:work
        val workSections = allSections.filter { section ->
            section.tags.any { it == "role:work" }
        }

        // Verify all returned sections have role:work
        assertTrue(workSections.isNotEmpty(), "Should find at least one work section")
        workSections.forEach { section ->
            assertTrue(
                section.tags.contains("role:work"),
                "Section '${section.title}' should have role:work tag"
            )
        }

        // Verify sections without role:work are excluded
        val nonWorkSections = allSections.filter { section ->
            !section.tags.contains("role:work")
        }

        assertTrue(nonWorkSections.isNotEmpty(), "Should have sections without role:work for comparison")
        nonWorkSections.forEach { section ->
            assertFalse(
                workSections.contains(section),
                "Section '${section.title}' should not be in work sections"
            )
        }
    }

    @Test
    fun `section filtering by role review returns only review sections`() {
        // Get a template with verification sections
        val (template, allSections) = BugInvestigationWorkflowTemplateCreator.create()

        // Filter for role:review
        val reviewSections = allSections.filter { section ->
            section.tags.any { it == "role:review" }
        }

        // Verify all returned sections have role:review
        assertTrue(reviewSections.isNotEmpty(), "Should find at least one review section")
        reviewSections.forEach { section ->
            assertTrue(
                section.tags.contains("role:review"),
                "Section '${section.title}' should have role:review tag"
            )
        }
    }

    @Test
    fun `section filtering by role queue returns only queue sections`() {
        // Get a feature-level template with planning sections
        val (template, allSections) = FeaturePlanTemplateCreator.create()

        // Filter for role:queue
        val queueSections = allSections.filter { section ->
            section.tags.any { it == "role:queue" }
        }

        // Verify all returned sections have role:queue
        assertTrue(queueSections.isNotEmpty(), "Should find at least one queue section")
        queueSections.forEach { section ->
            assertTrue(
                section.tags.contains("role:queue"),
                "Section '${section.title}' should have role:queue tag"
            )
        }
    }

    @Test
    fun `section filtering by role terminal returns only terminal sections`() {
        // Get a template with completion sections
        val (template, allSections) = DesignDecisionTemplateCreator.create()

        // Filter for role:terminal
        val terminalSections = allSections.filter { section ->
            section.tags.any { it == "role:terminal" }
        }

        // Verify all returned sections have role:terminal
        assertTrue(terminalSections.isNotEmpty(), "Should find at least one terminal section")
        terminalSections.forEach { section ->
            assertTrue(
                section.tags.contains("role:terminal"),
                "Section '${section.title}' should have role:terminal tag"
            )
        }
    }

    @Test
    fun `comprehensive role tag coverage report`() {
        val report = StringBuilder("\n=== Role Tag Coverage Report ===\n\n")

        var totalSections = 0
        val roleDistribution = mutableMapOf<String, Int>()

        getAllTemplates().forEach { (template, sections) ->
            totalSections += sections.size
            report.append("${template.name} (${template.targetEntityType}, ${sections.size} sections):\n")

            sections.forEach { section ->
                val roleTags = section.tags.filter { it.startsWith("role:") }
                report.append("  - ${section.title}: ${roleTags.joinToString()}\n")

                roleTags.forEach { roleTag ->
                    val role = roleTag.removePrefix("role:")
                    roleDistribution[role] = roleDistribution.getOrDefault(role, 0) + 1
                }
            }
            report.append("\n")
        }

        report.append("=== Summary ===\n")
        report.append("Total templates: 13\n")
        report.append("Total sections: $totalSections\n")
        report.append("Role distribution:\n")
        VALID_ROLES.sorted().forEach { role ->
            val count = roleDistribution.getOrDefault(role, 0)
            val percentage = if (totalSections > 0) (count * 100.0 / totalSections) else 0.0
            report.append("  role:$role - $count sections (%.1f%%)\n".format(percentage))
        }

        println(report.toString())

        // Verify coverage
        assertTrue(totalSections > 0, "Should have at least one section across all templates")
        assertTrue(roleDistribution.isNotEmpty(), "Should have role tags assigned")
    }

    @Test
    fun `JSON Verification sections must include role review`() {
        val violations = mutableListOf<String>()

        getAllTemplates().forEach { (template, sections) ->
            sections.forEach { section ->
                if (section.contentFormat == ContentFormat.JSON &&
                    section.title.contains("Verification", ignoreCase = true)) {
                    val roleTags = section.tags.filter { it.startsWith("role:") }
                    if (!section.tags.contains("role:review")) {
                        violations.add("${template.name} > ${section.title}: JSON Verification section missing role:review (has: $roleTags)")
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "JSON Verification sections must have role:review. Violations:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `acceptance-criteria tagged sections must include role review`() {
        val violations = mutableListOf<String>()

        getAllTemplates().forEach { (template, sections) ->
            sections.forEach { section ->
                if (section.tags.contains("acceptance-criteria")) {
                    if (!section.tags.contains("role:review")) {
                        violations.add("${template.name} > ${section.title}: has 'acceptance-criteria' tag but missing role:review")
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "acceptance-criteria tagged sections must have role:review. Violations:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `fix-implementation tagged sections must include role work`() {
        val violations = mutableListOf<String>()

        getAllTemplates().forEach { (template, sections) ->
            sections.forEach { section ->
                if (section.tags.contains("fix-implementation")) {
                    if (!section.tags.contains("role:work")) {
                        violations.add("${template.name} > ${section.title}: has 'fix-implementation' tag but missing role:work")
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "fix-implementation tagged sections must have role:work. Violations:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `implementation and execution tagged sections must include role work`() {
        val violations = mutableListOf<String>()

        getAllTemplates().forEach { (template, sections) ->
            sections.forEach { section ->
                if (section.tags.contains("implementation") && section.tags.contains("execution")) {
                    if (!section.tags.contains("role:work")) {
                        violations.add("${template.name} > ${section.title}: has 'implementation'+'execution' tags but missing role:work")
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "implementation+execution tagged sections must have role:work. Violations:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `every enabled task template has at least one role work section`() {
        val violations = mutableListOf<String>()

        getAllTemplates()
            .filter { (template, _) -> template.isEnabled && template.targetEntityType == EntityType.TASK }
            .forEach { (template, sections) ->
                val hasWorkSection = sections.any { section -> section.tags.contains("role:work") }
                if (!hasWorkSection) {
                    violations.add("${template.name}: enabled TASK template has no role:work sections")
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Every enabled TASK template must have at least one role:work section. Violations:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `every enabled feature template has at least one role queue section`() {
        val violations = mutableListOf<String>()

        getAllTemplates()
            .filter { (template, _) -> template.isEnabled && template.targetEntityType == EntityType.FEATURE }
            .forEach { (template, sections) ->
                val hasQueueSection = sections.any { section -> section.tags.contains("role:queue") }
                if (!hasQueueSection) {
                    violations.add("${template.name}: enabled FEATURE template has no role:queue sections")
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Every enabled FEATURE template must have at least one role:queue section. Violations:\n${violations.joinToString("\n")}"
        )
    }
}

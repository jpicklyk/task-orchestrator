package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.Template
import io.github.jpicklyk.mcptask.domain.model.TemplateSection
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.TemplateRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Service to initialize the system with a set of predefined templates.
 * These templates provide useful starting points for tasks and features.
 */
class TemplateInitializerImpl(
    private val templateRepository: TemplateRepository
) : TemplateInitializer {
    private val logger = LoggerFactory.getLogger(TemplateInitializerImpl::class.java)

    /**
     * Initialize the templates, creating any that don't already exist.
     * This is incremental: existing templates are skipped, new templates are created.
     * This ensures that when new templates are added to the code, existing databases
     * pick them up on the next startup.
     */
    override fun initializeTemplates() {
        logger.info("Initializing predefined templates")

        // Get existing built-in templates
        val existingTemplates = runBlocking {
            val result = templateRepository.getAllTemplates(isBuiltIn = true)
            if (result is Result.Success) {
                result.data
            } else {
                emptyList()
            }
        }

        // Build a set of existing template names for fast lookup
        val existingNames = existingTemplates.map { it.name }.toSet()

        // Initialize each template individually - only templates that have actual creators
        val templateNames = listOf(
            // Workflow templates
            "Local Git Branching Workflow",
            "GitHub PR Workflow",
            "Task Implementation",
            "Bug Investigation",
            // Documentation and quality templates
            "Technical Approach",
            "Requirements Specification",
            "Context & Background",
            "Test Plan",
            "Definition of Done",
            // Planning templates
            "Feature Plan",
            "Codebase Exploration",
            "Design Decision",
            "Implementation Specification"
        )

        var createdCount = 0
        var skippedCount = 0
        templateNames.forEach { templateName ->
            if (templateName in existingNames) {
                skippedCount++
            } else if (initializeTemplate(templateName)) {
                createdCount++
            }
        }

        logger.info("Template initialization complete: $createdCount created, $skippedCount already existed, ${templateNames.size} total")
    }

    /**
     * Initializes a specific template by name.
     */
    override fun initializeTemplate(templateName: String): Boolean {
        return when (templateName) {
            // Workflow templates
            "Local Git Branching Workflow" -> createLocalGitBranchingWorkflowTemplate()
            "GitHub PR Workflow" -> createGitHubPRWorkflowTemplate()
            "Task Implementation" -> createTaskImplementationTemplate()
            "Bug Investigation" -> createBugInvestigationTemplate()
            // Documentation and quality templates
            "Technical Approach" -> createTechnicalApproachTemplate()
            "Requirements Specification" -> createRequirementsSpecificationTemplate()
            "Context & Background" -> createContextBackgroundTemplate()
            "Test Plan" -> createTestPlanTemplate()
            "Definition of Done" -> createDefinitionOfDoneTemplate()
            // Planning templates
            "Feature Plan" -> createFeaturePlanTemplate()
            "Codebase Exploration" -> createCodebaseExplorationTemplate()
            "Design Decision" -> createDesignDecisionTemplate()
            "Implementation Specification" -> createImplementationSpecificationTemplate()
            else -> {
                logger.warn("Unknown template name: $templateName")
                false
            }
        }
    }

    /**
     * Helper method to create a template and its sections, with proper error handling.
     */
    private fun createTemplateWithSections(
        template: Template,
        sections: List<TemplateSection>,
        templateName: String
    ): Boolean {
        return runBlocking {
            val result = templateRepository.createTemplate(template)
            if (result is Result.Success) {
                var sectionsCreated = 0
                sections.forEach { section ->
                    val sectionResult = templateRepository.addTemplateSection(template.id, section)
                    if (sectionResult is Result.Success) {
                        sectionsCreated++
                    } else {
                        logger.error("Failed to create section '${section.title}' for $templateName: $sectionResult")
                    }
                }

                if (sectionsCreated == sections.size) {
                    logger.info("Created $templateName with all $sectionsCreated sections")
                } else {
                    logger.warn("Created $templateName with $sectionsCreated/${sections.size} sections")
                }

                true
            } else {
                logger.error("Failed to create $templateName: $result")
                false
            }
        }
    }

    /**
     * Creates the Local Git Branching Workflow template.
     */
    private fun createLocalGitBranchingWorkflowTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.LocalGitBranchingWorkflowTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Local Git Branching Workflow")
        } catch (e: Exception) {
            logger.error("Failed to create Local Git Branching Workflow template", e)
            return false
        }
    }

    /**
     * Creates the GitHub PR Workflow template.
     */
    private fun createGitHubPRWorkflowTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.GitHubPRWorkflowTemplateCreator.create()
            return createTemplateWithSections(template, sections, "GitHub PR Workflow")
        } catch (e: Exception) {
            logger.error("Failed to create GitHub PR Workflow template", e)
            return false
        }
    }

    /**
     * Creates the Task Implementation template.
     */
    private fun createTaskImplementationTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.TaskImplementationWorkflowTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Task Implementation")
        } catch (e: Exception) {
            logger.error("Failed to create Task Implementation template", e)
            return false
        }
    }

    /**
     * Creates the Bug Investigation template.
     */
    private fun createBugInvestigationTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.BugInvestigationWorkflowTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Bug Investigation")
        } catch (e: Exception) {
            logger.error("Failed to create Bug Investigation template", e)
            return false
        }
    }

    /**
     * Creates the Technical Approach template.
     */
    private fun createTechnicalApproachTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.TechnicalApproachTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Technical Approach")
        } catch (e: Exception) {
            logger.error("Failed to create Technical Approach template", e)
            return false
        }
    }

    /**
     * Creates the Requirements Specification template.
     */
    private fun createRequirementsSpecificationTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.RequirementsSpecificationTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Requirements Specification")
        } catch (e: Exception) {
            logger.error("Failed to create Requirements Specification template", e)
            return false
        }
    }

    /**
     * Creates the Context & Background template.
     */
    private fun createContextBackgroundTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.ContextBackgroundTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Context & Background")
        } catch (e: Exception) {
            logger.error("Failed to create Context & Background template", e)
            return false
        }
    }

    /**
     * Creates the Test Plan template.
     */
    private fun createTestPlanTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.TestingStrategyTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Test Plan")
        } catch (e: Exception) {
            logger.error("Failed to create Test Plan template", e)
            return false
        }
    }

    /**
     * Creates the Definition of Done template.
     */
    private fun createDefinitionOfDoneTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.DefinitionOfDoneTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Definition of Done")
        } catch (e: Exception) {
            logger.error("Failed to create Definition of Done template", e)
            return false
        }
    }

    /**
     * Creates the Feature Plan template.
     */
    private fun createFeaturePlanTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.FeaturePlanTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Feature Plan")
        } catch (e: Exception) {
            logger.error("Failed to create Feature Plan template", e)
            return false
        }
    }

    /**
     * Creates the Codebase Exploration template.
     */
    private fun createCodebaseExplorationTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.CodebaseExplorationTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Codebase Exploration")
        } catch (e: Exception) {
            logger.error("Failed to create Codebase Exploration template", e)
            return false
        }
    }

    /**
     * Creates the Design Decision template.
     */
    private fun createDesignDecisionTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.DesignDecisionTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Design Decision")
        } catch (e: Exception) {
            logger.error("Failed to create Design Decision template", e)
            return false
        }
    }

    /**
     * Creates the Implementation Specification template.
     */
    private fun createImplementationSpecificationTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.ImplementationSpecificationTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Implementation Specification")
        } catch (e: Exception) {
            logger.error("Failed to create Implementation Specification template", e)
            return false
        }
    }
}
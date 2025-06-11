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
     * Initialize the templates if they don't already exist.
     * This ensures that the system always has a set of useful templates.
     */
    override fun initializeTemplates() {
        logger.info("Initializing predefined templates")

        // Get existing templates
        val existingTemplates = runBlocking {
            val result = templateRepository.getAllTemplates(isBuiltIn = true)
            if (result is Result.Success) {
                result.data
            } else {
                emptyList()
            }
        }

        // If we already have built-in templates, skip initialization
        if (existingTemplates.isNotEmpty()) {
            logger.info("Found ${existingTemplates.size} existing built-in templates, skipping initialization")
            return
        }

        logger.info("Creating predefined templates")

        // Initialize each template individually - only templates that have actual creators
        val templateNames = listOf(
            // Workflow templates
            "Local Git Branching Workflow",
            "GitHub PR Workflow", 
            "Task Implementation Workflow",
            "Bug Investigation Workflow",
            // Documentation and quality templates
            "Technical Approach",
            "Requirements Specification",
            "Context & Background",
            "Testing Strategy",
            "Definition of Done"
        )

        var initializedCount = 0
        templateNames.forEach { templateName ->
            if (initializeTemplate(templateName)) {
                initializedCount++
            }
        }

        logger.info("$initializedCount/${templateNames.size} predefined templates initialized")
    }

    /**
     * Initializes a specific template by name.
     */
    override fun initializeTemplate(templateName: String): Boolean {
        return when (templateName) {
            // Workflow templates
            "Local Git Branching Workflow" -> createLocalGitBranchingWorkflowTemplate()
            "GitHub PR Workflow" -> createGitHubPRWorkflowTemplate()
            "Task Implementation Workflow" -> createTaskImplementationWorkflowTemplate()
            "Bug Investigation Workflow" -> createBugInvestigationWorkflowTemplate()
            // New documentation and quality templates
            "Technical Approach" -> createTechnicalApproachTemplate()
            "Requirements Specification" -> createRequirementsSpecificationTemplate()
            "Context & Background" -> createContextBackgroundTemplate()
            "Testing Strategy" -> createTestingStrategyTemplate()
            "Definition of Done" -> createDefinitionOfDoneTemplate()
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
     * Creates the Task Implementation Workflow template.
     */
    private fun createTaskImplementationWorkflowTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.TaskImplementationWorkflowTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Task Implementation Workflow")
        } catch (e: Exception) {
            logger.error("Failed to create Task Implementation Workflow template", e)
            return false
        }
    }

    /**
     * Creates the Bug Investigation Workflow template.
     */
    private fun createBugInvestigationWorkflowTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.BugInvestigationWorkflowTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Bug Investigation Workflow")
        } catch (e: Exception) {
            logger.error("Failed to create Bug Investigation Workflow template", e)
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
     * Creates the Testing Strategy template.
     */
    private fun createTestingStrategyTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.TestingStrategyTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Testing Strategy")
        } catch (e: Exception) {
            logger.error("Failed to create Testing Strategy template", e)
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
}
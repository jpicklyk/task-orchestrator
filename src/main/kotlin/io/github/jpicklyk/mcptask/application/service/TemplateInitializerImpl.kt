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

        // Initialize each template individually
        val templateNames = listOf(
            "Code Review and Analysis",
            "Feature Context",
            "Implementation Progress",
            "Implementation and Usage Strategy",
            "Project Context",
            "Related Classes and Components",
            "Task Implementation",
            "Bug Report Template"
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
            "Code Review and Analysis" -> createCodeReviewTemplate()
            "Feature Context" -> createFeatureContextTemplate()
            "Implementation Progress" -> createImplementationProgressTemplate()
            "Implementation and Usage Strategy" -> createImplementationStrategyTemplate()
            "Project Context" -> createProjectContextTemplate()
            "Related Classes and Components" -> createRelatedClassesTemplate()
            "Task Implementation" -> createTaskImplementationTemplate()
            "Bug Report Template" -> createBugReportTemplate()
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
     * Creates the Code Review and Analysis template.
     */
    private fun createCodeReviewTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.CodeReviewTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Code Review and Analysis")
        } catch (e: Exception) {
            logger.error("Failed to create Code Review and Analysis template", e)
            return false
        }
    }

    /**
     * Creates the Feature Context template.
     */
    private fun createFeatureContextTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.FeatureContextTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Feature Context")
        } catch (e: Exception) {
            logger.error("Failed to create Feature Context template", e)
            return false
        }
    }

    /**
     * Creates the Implementation Progress template.
     */
    private fun createImplementationProgressTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.ImplementationProgressTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Implementation Progress")
        } catch (e: Exception) {
            logger.error("Failed to create Implementation Progress template", e)
            return false
        }
    }

    /**
     * Creates the Implementation and Usage Strategy template.
     */
    private fun createImplementationStrategyTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.ImplementationStrategyTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Implementation and Usage Strategy")
        } catch (e: Exception) {
            logger.error("Failed to create Implementation and Usage Strategy template", e)
            return false
        }
    }

    /**
     * Creates the Project Context template.
     */
    private fun createProjectContextTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.ProjectContextTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Project Context")
        } catch (e: Exception) {
            logger.error("Failed to create Project Context template", e)
            return false
        }
    }

    /**
     * Creates the Related Classes and Components template.
     */
    private fun createRelatedClassesTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.RelatedClassesTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Related Classes and Components")
        } catch (e: Exception) {
            logger.error("Failed to create Related Classes and Components template", e)
            return false
        }
    }

    /**
     * Creates the Task Implementation template.
     */
    private fun createTaskImplementationTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.TaskImplementationTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Task Implementation")
        } catch (e: Exception) {
            logger.error("Failed to create Task Implementation template", e)
            return false
        }
    }

    /**
     * Creates the Bug Report template.
     */
    private fun createBugReportTemplate(): Boolean {
        try {
            val (template, sections) = io.github.jpicklyk.mcptask.application.service.templates.BugReportTemplateCreator.create()
            return createTemplateWithSections(template, sections, "Bug Report Template")
        } catch (e: Exception) {
            logger.error("Failed to create Bug Report Template", e)
            return false
        }
    }
}

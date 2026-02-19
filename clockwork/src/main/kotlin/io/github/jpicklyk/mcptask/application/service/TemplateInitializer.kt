package io.github.jpicklyk.mcptask.application.service

/**
 * Interface defining template initialization functionality.
 * This ensures we can provide different implementation strategies
 * while maintaining a consistent contract.
 */
interface TemplateInitializer {
    /**
     * Initializes the system with a set of predefined templates.
     * This should be called during application startup to ensure
     * template availability.
     */
    fun initializeTemplates()

    /**
     * Initializes a specific template by name.
     * This allows for selective initialization of templates.
     *
     * @param templateName The name of the template to initialize
     * @return True if the template was successfully initialized, false otherwise
     */
    fun initializeTemplate(templateName: String): Boolean
}

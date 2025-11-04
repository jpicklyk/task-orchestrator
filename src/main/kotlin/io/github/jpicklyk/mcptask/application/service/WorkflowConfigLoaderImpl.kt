package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.workflow.EventHandler
import io.github.jpicklyk.mcptask.domain.model.workflow.EventOverride
import io.github.jpicklyk.mcptask.domain.model.workflow.FlowConfig
import io.github.jpicklyk.mcptask.domain.model.workflow.WorkflowConfig
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Implementation of WorkflowConfigLoader that reads from status_workflow_config.yaml.
 *
 * Configuration loading strategy:
 * 1. Check .taskorchestrator/status_workflow_config.yaml (uses AGENT_CONFIG_DIR)
 * 2. If not found, fall back to hardcoded defaults
 * 3. Cache configuration with file modification time checking
 *
 * Thread-safe with volatile caching.
 */
class WorkflowConfigLoaderImpl : WorkflowConfigLoader {
    private val logger = LoggerFactory.getLogger(WorkflowConfigLoaderImpl::class.java)

    // Cached config to avoid repeated file reads
    @Volatile
    private var cachedConfig: WorkflowConfig? = null

    @Volatile
    private var lastModified: Long = 0L

    private val configCacheTimeout = 60_000L // 60 seconds

    override fun getFlowMappings(): Map<String, FlowConfig> {
        val config = loadConfig()
        return config.flowMappings
    }

    override fun getFlowConfig(flowName: String): FlowConfig {
        val mappings = getFlowMappings()
        return mappings[flowName] ?: mappings["default_flow"]
            ?: error("default_flow not found in configuration")
    }

    override fun getEventHandlers(flowName: String): Map<String, EventHandler> {
        val flowConfig = getFlowConfig(flowName)
        return flowConfig.eventHandlers
    }

    override fun getStatusProgressions(): Map<String, List<String>> {
        val config = loadConfig()
        return config.statusProgressions
    }

    override fun reloadConfig() {
        cachedConfig = null
        lastModified = 0L
    }

    /**
     * Loads workflow configuration from file or returns cached version.
     * Thread-safe with double-checked locking.
     */
    private fun loadConfig(): WorkflowConfig {
        val configPath = getConfigPath()
        val file = configPath.toFile()

        // If file doesn't exist, return default config
        if (!file.exists()) {
            logger.info("Workflow config not found at $configPath, using default configuration")
            return getDefaultConfig()
        }

        val currentModified = file.lastModified()
        val cached = cachedConfig

        // Return cached if still valid
        if (cached != null && currentModified == lastModified) {
            return cached
        }

        // Load and parse config
        return synchronized(this) {
            // Double-check after acquiring lock
            if (cachedConfig != null && currentModified == lastModified) {
                return cachedConfig!!
            }

            try {
                logger.info("Loading workflow configuration from $configPath")
                val config = parseWorkflowConfig(file.readText())
                cachedConfig = config
                lastModified = currentModified
                logger.info("Workflow configuration loaded successfully")
                config
            } catch (e: Exception) {
                logger.error("Failed to load workflow configuration: ${e.message}", e)
                logger.info("Falling back to default configuration")
                getDefaultConfig()
            }
        }
    }

    /**
     * Gets the configuration file path.
     * Uses AGENT_CONFIG_DIR environment variable if set, otherwise uses user.dir.
     */
    private fun getConfigPath(): Path {
        val projectRoot = Paths.get(
            System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
        )
        return projectRoot.resolve(".taskorchestrator/status_workflow_config.yaml")
    }

    /**
     * Parses workflow configuration from YAML string.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseWorkflowConfig(yamlContent: String): WorkflowConfig {
        val yaml = Yaml()
        val data = yaml.load<Map<String, Any>>(yamlContent)

        // Parse status progressions
        val statusProgressions = parseStatusProgressions(
            data["status_progressions"] as? Map<String, Any> ?: emptyMap()
        )

        // Parse flow mappings
        val flowMappings = parseFlowMappings(
            data["flow_mappings"] as? Map<String, Any> ?: emptyMap(),
            data["event_handlers"] as? Map<String, Any> ?: emptyMap()
        )

        // Parse validation config
        val validationConfig = data["status_validation"] as? Map<String, Any>

        return WorkflowConfig(
            statusProgressions = statusProgressions,
            flowMappings = flowMappings,
            validationConfig = validationConfig
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseStatusProgressions(data: Map<String, Any>): Map<String, List<String>> {
        return data.mapValues { (_, value) ->
            (value as? List<String>) ?: emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFlowMappings(
        flowMappingsData: Map<String, Any>,
        globalEventHandlers: Map<String, Any>
    ): Map<String, FlowConfig> {
        return flowMappingsData.mapValues { (flowName, value) ->
            val flowData = value as? Map<String, Any> ?: emptyMap()

            val statuses = (flowData["statuses"] as? List<String>) ?: emptyList()
            val tags = (flowData["tags"] as? List<String>)

            // Parse flow-specific event handlers or use global ones
            val eventHandlersData = (flowData["event_handlers"] as? Map<String, Any>)
                ?: globalEventHandlers
            val eventHandlers = parseEventHandlers(eventHandlersData)

            // Parse event overrides
            val eventOverrides = (flowData["event_overrides"] as? Map<String, Any>)?.let {
                parseEventOverrides(it)
            }

            FlowConfig(
                name = flowName,
                statuses = statuses,
                tags = tags,
                eventHandlers = eventHandlers,
                eventOverrides = eventOverrides
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEventHandlers(data: Map<String, Any>): Map<String, EventHandler> {
        return data.mapValues { (_, value) ->
            val handlerData = value as? Map<String, Any> ?: emptyMap()
            EventHandler(
                from = handlerData["from"] as? String ?: "",
                to = handlerData["to"] as? String ?: "",
                auto = handlerData["auto"] as? Boolean ?: true,
                validation = handlerData["validation"] as? String?
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEventOverrides(data: Map<String, Any>): Map<String, EventOverride> {
        return data.mapValues { (_, value) ->
            val overrideData = value as? Map<String, Any> ?: emptyMap()
            EventOverride(
                from = overrideData["from"] as? String ?: "",
                to = overrideData["to"] as? String ?: ""
            )
        }
    }

    /**
     * Returns hardcoded default configuration as fallback.
     */
    private fun getDefaultConfig(): WorkflowConfig {
        return WorkflowConfig(
            statusProgressions = mapOf(
                "feature" to listOf("planning", "in-development", "testing", "validating", "completed"),
                "task" to listOf("backlog", "pending", "in-progress", "testing", "completed"),
                "project" to listOf("concept", "planning", "active", "completed")
            ),
            flowMappings = mapOf(
                "default_flow" to FlowConfig(
                    name = "default_flow",
                    statuses = listOf("planning", "in-development", "testing", "validating", "completed"),
                    eventHandlers = mapOf(
                        "first_task_started" to EventHandler("planning", "in-development", true),
                        "all_tasks_complete" to EventHandler("in-development", "testing", true),
                        "tests_passed" to EventHandler("testing", "validating", true),
                        "completion_requested" to EventHandler("validating", "completed", false)
                    )
                ),
                "rapid_prototype_flow" to FlowConfig(
                    name = "rapid_prototype_flow",
                    statuses = listOf("planning", "in-development", "completed"),
                    tags = listOf("prototype", "spike", "experiment"),
                    eventHandlers = mapOf(
                        "first_task_started" to EventHandler("planning", "in-development", true),
                        "all_tasks_complete" to EventHandler("in-development", "completed", true)
                    )
                ),
                "with_review_flow" to FlowConfig(
                    name = "with_review_flow",
                    statuses = listOf("planning", "in-development", "testing", "validating", "pending-review", "completed"),
                    tags = listOf("security", "compliance", "audit"),
                    eventHandlers = mapOf(
                        "first_task_started" to EventHandler("planning", "in-development", true),
                        "all_tasks_complete" to EventHandler("in-development", "testing", true),
                        "tests_passed" to EventHandler("testing", "validating", true),
                        "completion_requested" to EventHandler("validating", "pending-review", true),
                        "review_approved" to EventHandler("pending-review", "completed", false)
                    )
                )
            ),
            validationConfig = mapOf(
                "validate_prerequisites" to true
            )
        )
    }
}

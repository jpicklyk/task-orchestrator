package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileReader
import java.nio.file.Paths

/**
 * YAML-backed implementation of [NoteSchemaService].
 *
 * Reads note schemas from `.taskorchestrator/config.yaml` in the project root.
 * The project root is resolved from the `AGENT_CONFIG_DIR` environment variable,
 * falling back to `user.dir` if not set.
 *
 * Expected YAML structure:
 * ```yaml
 * note_schemas:
 *   schema-tag-name:
 *     - key: note-key
 *       role: queue        # or work, review
 *       required: true
 *       description: "..."
 *       guidance: "..."    # optional
 * ```
 *
 * Schema matching: The first tag in the provided list that matches a schema key wins.
 * If no config file is present, or no tags match, returns null (schema-free mode).
 */
class YamlNoteSchemaService(
    private val configPath: java.nio.file.Path = resolveDefaultConfigPath()
) : NoteSchemaService {

    private val logger = LoggerFactory.getLogger(YamlNoteSchemaService::class.java)

    /** Lazily loaded schema cache. Null until first access. Empty map if file missing. */
    private val schemas: Map<String, List<NoteSchemaEntry>> by lazy { loadSchemas() }

    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? {
        for (tag in tags) {
            val schema = schemas[tag]
            if (schema != null) return schema
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadSchemas(): Map<String, List<NoteSchemaEntry>> {
        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; running in schema-free mode", configPath)
            return emptyMap()
        }

        return try {
            val yaml = Yaml()
            FileReader(configPath.toFile()).use { reader ->
                val root = yaml.load<Map<String, Any>>(reader) ?: return emptyMap()
                val noteSchemas = root["note_schemas"] as? Map<String, Any> ?: return emptyMap()

                noteSchemas.entries.associate { (schemaName, rawEntries) ->
                    val entries = (rawEntries as? List<Map<String, Any>>)
                        ?.mapNotNull { parseEntry(it) }
                        ?: emptyList()
                    schemaName to entries
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to load note schemas from config: {}", e.message)
            emptyMap()
        }
    }

    private fun parseEntry(raw: Map<String, Any>): NoteSchemaEntry? {
        val key = raw["key"] as? String ?: return null
        val role = raw["role"] as? String ?: return null
        val required = raw["required"] as? Boolean ?: false
        val description = raw["description"] as? String ?: ""
        val guidance = raw["guidance"] as? String
        return NoteSchemaEntry(key = key, role = role, required = required, description = description, guidance = guidance)
    }

    companion object {
        fun resolveDefaultConfigPath(): java.nio.file.Path {
            val projectRoot = Paths.get(
                System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
            )
            return projectRoot.resolve(".taskorchestrator/config.yaml")
        }
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
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

    /**
     * Holds the result of a schema load: the parsed schemas and any warnings collected.
     */
    private data class SchemaLoadResult(
        val schemas: Map<String, List<NoteSchemaEntry>>,
        val warnings: MutableList<String>
    )

    /** Lazily loaded schema cache and warnings. Initialized once on first access. */
    private val loadResult: SchemaLoadResult by lazy { loadSchemas() }

    /** Lazily loaded schema cache. Null until first access. Empty map if file missing. */
    private val schemas: Map<String, List<NoteSchemaEntry>> get() = loadResult.schemas

    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? {
        for (tag in tags) {
            val schema = schemas[tag]
            if (schema != null) return schema
        }
        return schemas["default"]
    }

    override fun getLoadWarnings(): List<String> = loadResult.warnings

    @Suppress("UNCHECKED_CAST")
    private fun loadSchemas(): SchemaLoadResult {
        val warnings = mutableListOf<String>()

        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; running in schema-free mode", configPath)
            return SchemaLoadResult(emptyMap(), warnings)
        }

        val schemas = try {
            val yaml = Yaml()
            FileReader(configPath.toFile()).use { reader ->
                val root = yaml.load<Map<String, Any>>(reader)
                    ?: return@use emptyMap<String, List<NoteSchemaEntry>>()

                if (!root.containsKey("note_schemas")) {
                    warnings.add("Config file is missing 'note_schemas' key; no schemas loaded")
                    return@use emptyMap()
                }

                val noteSchemas = root["note_schemas"] as? Map<String, Any>
                    ?: return@use emptyMap<String, List<NoteSchemaEntry>>()

                noteSchemas.entries.associate { (schemaName, rawEntries) ->
                    val entryList = rawEntries as? List<Map<String, Any>> ?: emptyList()
                    val entries = entryList.mapIndexedNotNull { index, raw ->
                        parseEntry(raw, schemaName, index, warnings)
                    }
                    schemaName to entries
                }
            }
        } catch (e: Exception) {
            val msg = "Failed to load note schemas from '${configPath}': ${e.message}"
            warnings.add(msg)
            logger.warn(msg)
            emptyMap()
        }

        val totalEntries = schemas.values.sumOf { it.size }
        warnings.forEach { w -> logger.warn(w) }
        logger.info(
            "Loaded {} schemas ({} entries, {} warnings)",
            schemas.size,
            totalEntries,
            warnings.size
        )

        return SchemaLoadResult(schemas, warnings)
    }

    private fun parseEntry(
        raw: Map<String, Any>,
        schemaName: String,
        index: Int,
        warnings: MutableList<String>
    ): NoteSchemaEntry? {
        val key = raw["key"] as? String
        if (key == null) {
            warnings.add("Schema '$schemaName' entry[$index] is missing required field 'key'; skipping")
            return null
        }

        val roleRaw = raw["role"] as? String
        if (roleRaw == null) {
            warnings.add("Schema '$schemaName' entry[$index] (key='$key') is missing required field 'role'; skipping")
            return null
        }

        val parsedRole = VALID_SCHEMA_ROLES[roleRaw]
        if (parsedRole == null) {
            logger.warn(
                "Skipping schema entry '{}': invalid role '{}' (valid: {})",
                key,
                roleRaw,
                VALID_SCHEMA_ROLES.keys
            )
            return null
        }

        val requiredRaw = raw["required"]
        val required = if (requiredRaw != null && requiredRaw !is Boolean) {
            warnings.add(
                "Schema '$schemaName' entry (key='$key') has non-boolean 'required' value '$requiredRaw'; defaulting to false"
            )
            false
        } else {
            requiredRaw as? Boolean ?: false
        }

        val description = raw["description"] as? String ?: ""
        val guidance = raw["guidance"] as? String
        return NoteSchemaEntry(
            key = key,
            role = parsedRole,
            required = required,
            description = description,
            guidance = guidance,
        )
    }

    companion object {
        private val VALID_SCHEMA_ROLES =
            mapOf(
                "queue" to Role.QUEUE,
                "work" to Role.WORK,
                "review" to Role.REVIEW,
            )

        fun resolveDefaultConfigPath(): java.nio.file.Path {
            val projectRoot =
                Paths.get(
                    System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
                )
            return projectRoot.resolve(".taskorchestrator/config.yaml")
        }
    }
}

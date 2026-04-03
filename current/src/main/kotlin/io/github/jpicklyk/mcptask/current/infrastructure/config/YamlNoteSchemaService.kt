package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
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
 *
 * traits:
 *   trait-name:
 *     notes:
 *       - key: note-key
 *         role: review
 *         required: true
 *         description: "..."
 *         guidance: "..."  # optional
 * ```
 *
 * Schemas may also declare `default_traits` to associate trait names with items of that type:
 * ```yaml
 * note_schemas:
 *   feature-implementation:
 *     default_traits: [needs-security-review]
 *     notes:
 *       - key: specification
 *         ...
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
     * Holds the result of a schema load: the parsed schemas, traits, and any warnings collected.
     */
    private data class SchemaLoadResult(
        val schemas: Map<String, List<NoteSchemaEntry>>,
        val workItemSchemas: Map<String, WorkItemSchema>,
        val traits: Map<String, List<NoteSchemaEntry>>,
        val warnings: MutableList<String>
    )

    /** Lazily loaded schema cache and warnings. Initialized once on first access. */
    private val loadResult: SchemaLoadResult by lazy { loadSchemas() }

    /** Lazily loaded schema cache. Null until first access. Empty map if file missing. */
    private val schemas: Map<String, List<NoteSchemaEntry>> get() = loadResult.schemas

    /** Lazily loaded WorkItemSchema objects (with defaultTraits). */
    private val workItemSchemas: Map<String, WorkItemSchema> get() = loadResult.workItemSchemas

    /** Lazily loaded trait definitions. */
    private val traits: Map<String, List<NoteSchemaEntry>> get() = loadResult.traits

    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? {
        for (tag in tags) {
            val schema = schemas[tag]
            if (schema != null) return schema
        }
        return schemas["default"]
    }

    override fun getLoadWarnings(): List<String> = loadResult.warnings

    override fun getTraitNotes(traitName: String): List<NoteSchemaEntry>? = traits[traitName]

    override fun getDefaultTraits(type: String?): List<String> =
        if (type != null) workItemSchemas[type]?.defaultTraits ?: emptyList() else emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun loadSchemas(): SchemaLoadResult {
        val warnings = mutableListOf<String>()

        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; running in schema-free mode", configPath)
            return SchemaLoadResult(emptyMap(), emptyMap(), emptyMap(), warnings)
        }

        var schemas: Map<String, List<NoteSchemaEntry>> = emptyMap()
        var workItemSchemas: Map<String, WorkItemSchema> = emptyMap()
        var traits: Map<String, List<NoteSchemaEntry>> = emptyMap()

        try {
            val yaml = Yaml()
            FileReader(configPath.toFile()).use { reader ->
                val root =
                    yaml.load<Map<String, Any>>(reader)
                        ?: return SchemaLoadResult(emptyMap(), emptyMap(), emptyMap(), warnings)

                if (!root.containsKey("note_schemas")) {
                    warnings.add("Config file is missing 'note_schemas' key; no schemas loaded")
                } else {
                    val noteSchemas =
                        root["note_schemas"] as? Map<String, Any>
                            ?: emptyMap<String, Any>()

                    val parsedSchemas = parseWorkItemSchemas(noteSchemas, warnings)
                    workItemSchemas = parsedSchemas
                    schemas = parsedSchemas.mapValues { (_, wis) -> wis.notes }
                }

                traits = parseTraits(root, warnings)
            }
        } catch (e: Exception) {
            val msg = "Failed to load note schemas from '$configPath': ${e.message}"
            warnings.add(msg)
            logger.warn(msg)
        }

        val totalEntries = schemas.values.sumOf { it.size }
        warnings.forEach { w -> logger.warn(w) }
        logger.info(
            "Loaded {} schemas ({} entries, {} traits, {} warnings)",
            schemas.size,
            totalEntries,
            traits.size,
            warnings.size
        )

        return SchemaLoadResult(schemas, workItemSchemas, traits, warnings)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseWorkItemSchemas(
        noteSchemas: Map<String, Any>,
        warnings: MutableList<String>
    ): Map<String, WorkItemSchema> =
        noteSchemas.entries.associate { (schemaName, rawValue) ->
            val defaultTraits: List<String>
            val entryList: List<Map<String, Any>>

            when (rawValue) {
                is Map<*, *> -> {
                    // New format: { default_traits: [...], notes: [...] }
                    // or just a map with note fields (legacy flat list treated as entries)
                    @Suppress("UNCHECKED_CAST")
                    val rawMap = rawValue as Map<String, Any>
                    @Suppress("UNCHECKED_CAST")
                    defaultTraits = (rawMap["default_traits"] as? List<String>) ?: emptyList()
                    @Suppress("UNCHECKED_CAST")
                    entryList = (rawMap["notes"] as? List<Map<String, Any>>) ?: emptyList()
                }
                is List<*> -> {
                    // Legacy flat-list format: schema-name: [ {key:..., role:...}, ... ]
                    defaultTraits = emptyList()
                    @Suppress("UNCHECKED_CAST")
                    entryList = (rawValue as? List<Map<String, Any>>) ?: emptyList()
                }
                else -> {
                    defaultTraits = emptyList()
                    entryList = emptyList()
                }
            }

            val entries = entryList.mapIndexedNotNull { index, raw ->
                parseEntry(raw, schemaName, index, warnings)
            }

            schemaName to WorkItemSchema(
                name = schemaName,
                notes = entries,
                defaultTraits = defaultTraits
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseTraits(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): Map<String, List<NoteSchemaEntry>> {
        val traitsRaw = root["traits"] as? Map<String, Any> ?: return emptyMap()

        return traitsRaw.entries.associate { (traitName, rawValue) ->
            val rawMap = rawValue as? Map<String, Any> ?: emptyMap()
            val notesList = rawMap["notes"] as? List<Map<String, Any>> ?: emptyList()
            val entries = notesList.mapIndexedNotNull { index, raw ->
                parseEntry(raw, "trait:$traitName", index, warnings)
            }
            traitName to entries
        }
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
        val required =
            if (requiredRaw != null && requiredRaw !is Boolean) {
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

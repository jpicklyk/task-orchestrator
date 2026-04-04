package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileReader
import java.nio.file.Paths

/**
 * YAML-backed implementation of [WorkItemSchemaService].
 *
 * Reads note schemas from `.taskorchestrator/config.yaml` in the project root.
 * The project root is resolved from the `AGENT_CONFIG_DIR` environment variable,
 * falling back to `user.dir` if not set.
 *
 * Supports two YAML formats:
 *
 * **New format** (`work_item_schemas:`) — supports lifecycle mode per schema:
 * ```yaml
 * work_item_schemas:
 *   feature-implementation:
 *     lifecycle: auto          # parsed via LifecycleMode.fromString()
 *     notes:
 *       - key: specification
 *         role: queue
 *         required: true
 *         description: "..."
 *         guidance: "..."
 * ```
 *
 * **Legacy format** (`note_schemas:`) — backward compatible, lifecycle defaults to AUTO:
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
 * **Precedence**: if both `work_item_schemas:` and `note_schemas:` keys are present,
 * `work_item_schemas:` wins entirely (legacy key is ignored).
 *
 * Schema matching: The first tag in the provided list that matches a schema key wins.
 * If no config file is present, or no tags match, returns null (schema-free mode).
 */
class YamlWorkItemSchemaService(
    private val configPath: java.nio.file.Path = resolveDefaultConfigPath()
) : WorkItemSchemaService {
    private val logger = LoggerFactory.getLogger(YamlWorkItemSchemaService::class.java)

    /**
     * Holds the result of a schema load: the parsed schemas and any warnings collected.
     */
    private data class SchemaLoadResult(
        val schemas: Map<String, List<NoteSchemaEntry>>,
        val workItemSchemas: Map<String, WorkItemSchema>,
        val traits: Map<String, List<NoteSchemaEntry>>,
        val warnings: MutableList<String>
    )

    /** Lazily loaded schema cache and warnings. Initialized once on first access. */
    private val loadResult: SchemaLoadResult by lazy { loadSchemas() }

    /** Lazily loaded tag→entries schema cache. */
    private val schemas: Map<String, List<NoteSchemaEntry>> get() = loadResult.schemas

    /** Lazily loaded type→WorkItemSchema cache. */
    private val workItemSchemas: Map<String, WorkItemSchema> get() = loadResult.workItemSchemas

    /** Lazily loaded trait definitions. */
    private val traitDefs: Map<String, List<NoteSchemaEntry>> get() = loadResult.traits

    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? {
        for (tag in tags) {
            val schema = schemas[tag]
            if (schema != null) return schema
        }
        return schemas["default"]
    }

    override fun getSchemaForType(type: String?): WorkItemSchema? {
        if (type == null) return null
        return workItemSchemas[type] ?: workItemSchemas["default"]
    }

    override fun getLoadWarnings(): List<String> = loadResult.warnings

    override fun getTraitNotes(traitName: String): List<NoteSchemaEntry>? = traitDefs[traitName]

    override fun getAvailableTraits(): List<String> = traitDefs.keys.toList()

    override fun getDefaultTraits(type: String?): List<String> =
        if (type != null) workItemSchemas[type]?.defaultTraits ?: emptyList() else emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun loadSchemas(): SchemaLoadResult {
        val warnings = mutableListOf<String>()

        if (!configPath.toFile().exists()) {
            logger.debug("No config file found at {}; running in schema-free mode", configPath)
            return SchemaLoadResult(emptyMap(), emptyMap(), emptyMap(), warnings)
        }

        return try {
            val yaml = Yaml()
            FileReader(configPath.toFile()).use { reader ->
                val root =
                    yaml.load<Map<String, Any>>(reader)
                        ?: return@use SchemaLoadResult(emptyMap(), emptyMap(), emptyMap(), warnings)

                val parsedTraits = parseTraits(root, warnings)

                when {
                    root.containsKey("work_item_schemas") -> {
                        parseWorkItemSchemas(root, warnings).copy(traits = parsedTraits)
                    }
                    root.containsKey("note_schemas") -> {
                        parseLegacyNoteSchemas(root, warnings).copy(traits = parsedTraits)
                    }
                    else -> {
                        warnings.add("Config file is missing 'note_schemas' key; no schemas loaded")
                        SchemaLoadResult(emptyMap(), emptyMap(), parsedTraits, warnings)
                    }
                }
            }
        } catch (e: Exception) {
            val msg = "Failed to load note schemas from '$configPath': ${e.message}"
            warnings.add(msg)
            logger.warn(msg)
            SchemaLoadResult(emptyMap(), emptyMap(), emptyMap(), warnings)
        }.also { result ->
            result.warnings.forEach { w -> logger.warn(w) }
            val totalEntries = result.schemas.values.sumOf { it.size }
            logger.info(
                "Loaded {} schemas ({} entries, {} warnings)",
                result.schemas.size,
                totalEntries,
                result.warnings.size
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseWorkItemSchemas(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): SchemaLoadResult {
        val rawSchemas =
            root["work_item_schemas"] as? Map<String, Any>
                ?: return SchemaLoadResult(emptyMap(), emptyMap(), emptyMap(), warnings)

        val schemasMap = mutableMapOf<String, List<NoteSchemaEntry>>()
        val workItemSchemasMap = mutableMapOf<String, WorkItemSchema>()

        for ((schemaName, rawValue) in rawSchemas) {
            val schemaMap = rawValue as? Map<String, Any> ?: continue

            val lifecycleRaw = schemaMap["lifecycle"] as? String
            val lifecycleMode =
                if (lifecycleRaw != null) {
                    val parsed = LifecycleMode.fromString(lifecycleRaw)
                    if (parsed == null) {
                        warnings.add(
                            "Schema '$schemaName' has invalid lifecycle value '$lifecycleRaw'; defaulting to AUTO"
                        )
                        LifecycleMode.AUTO
                    } else {
                        parsed
                    }
                } else {
                    LifecycleMode.AUTO
                }

            @Suppress("UNCHECKED_CAST")
            val defaultTraits = (schemaMap["default_traits"] as? List<String>) ?: emptyList()

            val rawNotes = schemaMap["notes"] as? List<Map<String, Any>> ?: emptyList()
            val entries =
                rawNotes.mapIndexedNotNull { index, raw ->
                    parseEntry(raw, schemaName, index, warnings)
                }

            schemasMap[schemaName] = entries
            workItemSchemasMap[schemaName] =
                WorkItemSchema(
                    type = schemaName,
                    lifecycleMode = lifecycleMode,
                    notes = entries,
                    defaultTraits = defaultTraits
                )
        }

        return SchemaLoadResult(schemasMap, workItemSchemasMap, emptyMap(), warnings)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLegacyNoteSchemas(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): SchemaLoadResult {
        val noteSchemas =
            root["note_schemas"] as? Map<String, Any>
                ?: return SchemaLoadResult(emptyMap(), emptyMap(), emptyMap(), warnings)

        val schemasMap = mutableMapOf<String, List<NoteSchemaEntry>>()
        val workItemSchemasMap = mutableMapOf<String, WorkItemSchema>()

        for ((schemaName, rawEntries) in noteSchemas) {
            val entryList = rawEntries as? List<Map<String, Any>> ?: emptyList()
            val entries =
                entryList.mapIndexedNotNull { index, raw ->
                    parseEntry(raw, schemaName, index, warnings)
                }
            schemasMap[schemaName] = entries
            // Wrap into WorkItemSchema with AUTO lifecycle for backward compat
            workItemSchemasMap[schemaName] =
                WorkItemSchema(
                    type = schemaName,
                    lifecycleMode = LifecycleMode.AUTO,
                    notes = entries
                )
        }

        return SchemaLoadResult(schemasMap, workItemSchemasMap, emptyMap(), warnings)
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
            val entries =
                notesList.mapIndexedNotNull { index, raw ->
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

/**
 * Backward-compatibility alias. All existing code importing [YamlNoteSchemaService] continues
 * to compile without modification.
 */
typealias YamlNoteSchemaService = YamlWorkItemSchemaService

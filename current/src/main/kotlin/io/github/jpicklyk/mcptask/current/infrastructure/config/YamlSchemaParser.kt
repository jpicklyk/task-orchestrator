package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.slf4j.LoggerFactory

/**
 * Parses a already-YAML-deserialized config root map (`work_item_schemas:` / `note_schemas:` /
 * `traits:` / `note_limits:`) into the schema/trait structures the rest of the application
 * consumes.
 *
 * Extracted from [YamlWorkItemSchemaService] so both the global config loader (file-backed,
 * `.taskorchestrator/config.yaml`) and [PerRootConfigService] (DB-backed, one YAML document per
 * project root) share exactly one parsing implementation — there is no behavioral difference
 * between "global schema YAML" and "per-root schema YAML" beyond *where the bytes come from* and
 * whether a missing schemas section is worth warning about (see [warnOnMissingSchemas]).
 *
 * [YamlWorkItemSchemaService.loadSchemas] retains its own responsibilities that this object does
 * NOT take on: resolving the config file path, checking file existence, reading bytes, and
 * catching/logging file-level exceptions (YAML syntax errors, IO errors). Only the "given a
 * parsed `Map<String, Any>` root, produce schemas/traits/warnings" step lives here.
 */
internal object YamlSchemaParser {
    private val logger = LoggerFactory.getLogger(YamlSchemaParser::class.java)

    private val VALID_SCHEMA_ROLES =
        mapOf(
            "queue" to Role.QUEUE,
            "work" to Role.WORK,
            "review" to Role.REVIEW,
        )

    /** Default `note_limits.mode` when unconfigured: accept notes over maxLength, just warn. */
    const val DEFAULT_NOTE_LIMITS_MODE = "warn"

    /** Recognized `note_limits.mode` values. */
    private val VALID_NOTE_LIMITS_MODES = setOf("warn", "reject")

    /**
     * Result of parsing a config root map: schemas (keyed by type/tag), traits, warnings, and the
     * note-limits mode. Per-tag note lists are read via `workItemSchemas[tag]?.notes` — there is no
     * separate tag→entries map, since it would be a redundant view of the same `NoteSchemaEntry`
     * lists already held inside each [WorkItemSchema].
     */
    data class ParsedConfig(
        val workItemSchemas: Map<String, WorkItemSchema>,
        val traits: Map<String, List<NoteSchemaEntry>>,
        val warnings: List<String>,
        val noteLimitsMode: String = DEFAULT_NOTE_LIMITS_MODE
    )

    /**
     * Parses [root] into a [ParsedConfig].
     *
     * Precedence: `work_item_schemas:` wins entirely over `note_schemas:` when both are present;
     * `note_schemas:` is the legacy format (wrapped into [WorkItemSchema] with AUTO lifecycle for
     * backward compatibility). When neither key is present, [warnOnMissingSchemas] controls
     * whether a "no schemas loaded" warning is recorded — the global file-backed loader wants
     * this warning (a `.taskorchestrator/config.yaml` with no schema section is almost always a
     * mistake), but a per-root config document legitimately may carry only other settings with no
     * schema section at all, so callers with looser expectations pass `false`.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseRoot(
        root: Map<String, Any>,
        warnOnMissingSchemas: Boolean = true
    ): ParsedConfig {
        val warnings = mutableListOf<String>()
        val parsedTraits = parseTraits(root, warnings)
        val parsedNoteLimitsMode = parseNoteLimitsMode(root, warnings)

        val base =
            when {
                root.containsKey("work_item_schemas") -> parseWorkItemSchemas(root, warnings)
                root.containsKey("note_schemas") -> parseLegacyNoteSchemas(root, warnings)
                else -> {
                    if (warnOnMissingSchemas) {
                        warnings.add("Config file is missing 'note_schemas' key; no schemas loaded")
                    }
                    ParsedConfig(emptyMap(), emptyMap(), warnings)
                }
            }

        return base.copy(traits = parsedTraits, warnings = warnings, noteLimitsMode = parsedNoteLimitsMode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseWorkItemSchemas(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): ParsedConfig {
        val rawSchemas =
            root["work_item_schemas"] as? Map<String, Any>
                ?: return ParsedConfig(emptyMap(), emptyMap(), warnings)

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

            workItemSchemasMap[schemaName] =
                WorkItemSchema(
                    type = schemaName,
                    lifecycleMode = lifecycleMode,
                    notes = entries,
                    defaultTraits = defaultTraits
                )
        }

        return ParsedConfig(workItemSchemasMap, emptyMap(), warnings)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLegacyNoteSchemas(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): ParsedConfig {
        val noteSchemas =
            root["note_schemas"] as? Map<String, Any>
                ?: return ParsedConfig(emptyMap(), emptyMap(), warnings)

        val workItemSchemasMap = mutableMapOf<String, WorkItemSchema>()

        for ((schemaName, rawEntries) in noteSchemas) {
            val entryList = rawEntries as? List<Map<String, Any>> ?: emptyList()
            val entries =
                entryList.mapIndexedNotNull { index, raw ->
                    parseEntry(raw, schemaName, index, warnings)
                }
            // Wrap into WorkItemSchema with AUTO lifecycle for backward compat
            workItemSchemasMap[schemaName] =
                WorkItemSchema(
                    type = schemaName,
                    lifecycleMode = LifecycleMode.AUTO,
                    notes = entries
                )
        }

        return ParsedConfig(workItemSchemasMap, emptyMap(), warnings)
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
        val skill = raw["skill"] as? String

        val maxLengthRaw = raw["maxLength"]
        val maxLength =
            if (maxLengthRaw != null && maxLengthRaw !is Number) {
                warnings.add(
                    "Schema '$schemaName' entry (key='$key') has non-numeric 'maxLength' value '$maxLengthRaw'; ignoring"
                )
                null
            } else {
                (maxLengthRaw as? Number)?.toInt()
            }

        return NoteSchemaEntry(
            key = key,
            role = parsedRole,
            required = required,
            description = description,
            guidance = guidance,
            skill = skill,
            maxLength = maxLength,
        )
    }

    /**
     * Parses the top-level `note_limits.mode` key, defaulting to [DEFAULT_NOTE_LIMITS_MODE]
     * ("warn") when the block is absent or the value is not one of [VALID_NOTE_LIMITS_MODES].
     * An invalid (non-empty, unrecognized) value is recorded as a load warning.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseNoteLimitsMode(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): String {
        val noteLimits = root["note_limits"] as? Map<String, Any> ?: return DEFAULT_NOTE_LIMITS_MODE
        val modeRaw = noteLimits["mode"] as? String ?: return DEFAULT_NOTE_LIMITS_MODE
        if (modeRaw !in VALID_NOTE_LIMITS_MODES) {
            warnings.add(
                "Invalid note_limits.mode value '$modeRaw'; defaulting to '$DEFAULT_NOTE_LIMITS_MODE' " +
                    "(valid: $VALID_NOTE_LIMITS_MODES)"
            )
            return DEFAULT_NOTE_LIMITS_MODE
        }
        return modeRaw
    }
}

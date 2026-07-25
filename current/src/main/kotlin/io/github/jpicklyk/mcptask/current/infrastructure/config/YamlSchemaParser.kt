package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
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

    /** Resource keys (`traits.<name>.resources[].key` / top-level `resources:` keys) must match this shape. */
    private val RESOURCE_KEY_REGEX = Regex("^[a-z0-9][a-z0-9\\-_./]*$")

    /** Max length for a resource key — see [RESOURCE_KEY_REGEX]. */
    private const val RESOURCE_KEY_MAX_LENGTH = 128

    /** Fallback TTL (seconds) for a resource with no registry entry and no per-requirement override. */
    const val DEFAULT_RESOURCE_TTL_SECONDS = 3600

    /** Valid inclusive bounds for [ResourceDefinition.defaultTtlSeconds]. */
    private const val MIN_RESOURCE_TTL_SECONDS = 1
    private const val MAX_RESOURCE_TTL_SECONDS = 86400

    /** A resource key declared by at least this many traits triggers a fan-out warning. */
    private const val RESOURCE_FANOUT_WARNING_THRESHOLD = 3

    /** Recognized `traits.<name>.resources[].mode` values, matched case-insensitively. */
    private val VALID_RESOURCE_MODES =
        mapOf(
            "exclusive" to ResourceMode.EXCLUSIVE,
            "advisory" to ResourceMode.ADVISORY,
        )

    /**
     * Budget-related keys reserved for future use. If present on a `resources:` entry (registry or
     * per-trait), they are parsed-and-warned but never stored — see [warnReservedBudgetKeys].
     */
    private val RESERVED_BUDGET_KEYS = setOf("budgetLimit", "budgetWindowSeconds")

    /**
     * Result of parsing a config root map: schemas (keyed by type/tag), traits, warnings, and the
     * note-limits mode. Per-tag note lists are read via `workItemSchemas[tag]?.notes` — there is no
     * separate tag→entries map, since it would be a redundant view of the same `NoteSchemaEntry`
     * lists already held inside each [WorkItemSchema].
     *
     * @property noteLimitsMode always resolves to a mode ("warn" default) — unchanged behavior for
     *   the global file-backed loader, which has no fallback layer beneath it.
     * @property noteLimitsModeExplicit null when the document has no top-level `note_limits` key at
     *   all; otherwise the resolved mode (same value as [noteLimitsMode]). Callers that layer this
     *   config over another (e.g. [PerRootConfigService]) use this field to distinguish "this
     *   document doesn't opine on note limits, fall through" from "this document explicitly
     *   configures note limits" — a plain [noteLimitsMode] read can't make that distinction because
     *   it always defaults to "warn" when the key is absent.
     * @property statusLabels null when the document has no top-level `status_labels` key at all;
     *   otherwise the parsed trigger→label map (values may themselves be null, mirroring
     *   [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService]'s
     *   "explicit null clears the label" semantics — only an ABSENT key in this map falls through to
     *   another layer).
     * @property traitResources per-trait resource requirements, parsed from `traits.<name>.resources:`
     *   (short form: a list of bare key strings; long form: a list of maps with `key`, optional
     *   `mode`, optional `ttlSeconds`). A trait with no `resources:` key is absent from this map
     *   entirely (not mapped to an empty list).
     * @property resourceRegistry the top-level `resources:` registry, keyed by resource key. Empty
     *   when the document has no top-level `resources:` section.
     */
    data class ParsedConfig(
        val workItemSchemas: Map<String, WorkItemSchema>,
        val traits: Map<String, List<NoteSchemaEntry>>,
        val warnings: List<String>,
        val noteLimitsMode: String = DEFAULT_NOTE_LIMITS_MODE,
        val noteLimitsModeExplicit: String? = null,
        val statusLabels: Map<String, String?>? = null,
        val traitResources: Map<String, List<ResourceRequirement>> = emptyMap(),
        val resourceRegistry: Map<String, ResourceDefinition> = emptyMap()
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
        val noteLimitsModeExplicit = if (root.containsKey("note_limits")) parsedNoteLimitsMode else null
        val parsedStatusLabels = parseStatusLabels(root, warnings)
        val resourceRegistry = parseResourceRegistry(root, warnings)
        val traitResources = parseTraitResources(root, resourceRegistry, warnings)

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

        return base.copy(
            traits = parsedTraits,
            warnings = warnings,
            noteLimitsMode = parsedNoteLimitsMode,
            noteLimitsModeExplicit = noteLimitsModeExplicit,
            statusLabels = parsedStatusLabels,
            traitResources = traitResources,
            resourceRegistry = resourceRegistry
        )
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

    /**
     * Parses the top-level `resources:` registry into a key→[ResourceDefinition] map. A malformed
     * section (present but not a map) is warned-and-ignored, matching every other top-level-section
     * parse in this object — never fails the whole config load. Individual malformed/invalid
     * entries are warned-and-skipped the same way: the rest of the registry still loads.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseResourceRegistry(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): Map<String, ResourceDefinition> {
        val raw = root["resources"] ?: return emptyMap()
        val rawMap =
            raw as? Map<String, Any> ?: run {
                warnings.add("Top-level 'resources' section is not a map; ignoring")
                return emptyMap()
            }

        val registry = mutableMapOf<String, ResourceDefinition>()
        for ((key, rawValue) in rawMap) {
            if (!isValidResourceKey(key)) {
                warnings.add(
                    "Resource registry key '$key' is invalid (must match '${RESOURCE_KEY_REGEX.pattern}', " +
                        "max $RESOURCE_KEY_MAX_LENGTH chars); skipping"
                )
                continue
            }

            val entryMap = rawValue as? Map<String, Any>
            if (entryMap == null) {
                warnings.add("Resource registry entry '$key' is not a map; skipping")
                continue
            }

            warnReservedBudgetKeys(entryMap, "Resource registry entry '$key'", warnings)

            val description = entryMap["description"] as? String ?: ""

            val maxHoldersRaw = entryMap["maxHolders"]
            val maxHolders =
                when (maxHoldersRaw) {
                    null -> 1
                    is Number -> maxHoldersRaw.toInt()
                    else -> {
                        warnings.add(
                            "Resource registry entry '$key' has non-numeric 'maxHolders' value '$maxHoldersRaw'; defaulting to 1"
                        )
                        1
                    }
                }
            if (maxHolders > 1) {
                warnings.add("Resource registry entry '$key': maxHolders > 1 not yet supported; skipping entry")
                continue
            }
            if (maxHolders < 1) {
                warnings.add("Resource registry entry '$key' has maxHolders '$maxHolders' below 1; defaulting to 1")
            }

            val ttlRaw = entryMap["defaultTtlSeconds"]
            val defaultTtlSeconds =
                when (ttlRaw) {
                    null -> DEFAULT_RESOURCE_TTL_SECONDS
                    is Number -> {
                        val ttl = ttlRaw.toInt()
                        if (ttl in MIN_RESOURCE_TTL_SECONDS..MAX_RESOURCE_TTL_SECONDS) {
                            ttl
                        } else {
                            warnings.add(
                                "Resource registry entry '$key' has defaultTtlSeconds '$ttl' out of bounds " +
                                    "($MIN_RESOURCE_TTL_SECONDS..$MAX_RESOURCE_TTL_SECONDS); defaulting to ${DEFAULT_RESOURCE_TTL_SECONDS}s"
                            )
                            DEFAULT_RESOURCE_TTL_SECONDS
                        }
                    }
                    else -> {
                        warnings.add(
                            "Resource registry entry '$key' has non-numeric 'defaultTtlSeconds' value '$ttlRaw'; " +
                                "defaulting to ${DEFAULT_RESOURCE_TTL_SECONDS}s"
                        )
                        DEFAULT_RESOURCE_TTL_SECONDS
                    }
                }

            registry[key] =
                ResourceDefinition(
                    key = key,
                    description = description,
                    defaultTtlSeconds = defaultTtlSeconds,
                    maxHolders = maxHolders.coerceAtLeast(1)
                )
        }
        return registry
    }

    /**
     * Parses per-trait `resources:` lists into a trait-name→[ResourceRequirement] map (traits with
     * no `resources:` key are absent from the result, not mapped to an empty list), then emits two
     * cross-trait warnings against the already-parsed [registry]:
     *  - a requirement referencing a key absent from [registry] ("undeclared resource") — warned but
     *    still honored with built-in defaults, never dropped;
     *  - a resource key declared by [RESOURCE_FANOUT_WARNING_THRESHOLD] or more traits ("fan-out").
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseTraitResources(
        root: Map<String, Any>,
        registry: Map<String, ResourceDefinition>,
        warnings: MutableList<String>
    ): Map<String, List<ResourceRequirement>> {
        val traitsRaw = root["traits"] as? Map<String, Any> ?: return emptyMap()

        val result = mutableMapOf<String, List<ResourceRequirement>>()
        val keyCounts = mutableMapOf<String, Int>()

        for ((traitName, rawValue) in traitsRaw) {
            val rawMap = rawValue as? Map<String, Any> ?: continue
            val resourcesRaw = rawMap["resources"] ?: continue

            val requirements = parseResourceRequirementList(resourcesRaw, traitName, warnings)
            if (requirements.isEmpty()) continue

            result[traitName] = requirements
            for (req in requirements) {
                keyCounts[req.key] = (keyCounts[req.key] ?: 0) + 1
                if (req.key !in registry) {
                    warnings.add(
                        "Trait '$traitName' references undeclared resource '${req.key}'; enforcing with defaults " +
                            "(ttl ${DEFAULT_RESOURCE_TTL_SECONDS}s)"
                    )
                }
            }
        }

        keyCounts
            .filterValues { it >= RESOURCE_FANOUT_WARNING_THRESHOLD }
            .forEach { (key, count) ->
                warnings.add("Resource '$key' is declared by $count traits (fan-out); contention is likely")
            }

        return result
    }

    /**
     * Parses a single trait's `resources:` value, which must be a list. Supports both short form
     * (bare key strings, coerced to [ResourceMode.EXCLUSIVE] with no ttl override) and long form
     * (maps with `key`, optional `mode` — case-insensitive, unknown values warn and fall back to
     * EXCLUSIVE — and optional `ttlSeconds`). A non-list value or an unrecognized list-element shape
     * is warned-and-skipped at that granularity (element or whole section), never fatal.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseResourceRequirementList(
        resourcesRaw: Any,
        traitName: String,
        warnings: MutableList<String>
    ): List<ResourceRequirement> {
        val list =
            resourcesRaw as? List<Any?> ?: run {
                warnings.add("Trait '$traitName' has a malformed 'resources' section (expected a list); skipping")
                return emptyList()
            }

        val result = mutableListOf<ResourceRequirement>()
        for ((index, rawEntry) in list.withIndex()) {
            when (rawEntry) {
                is String -> {
                    if (!isValidResourceKey(rawEntry)) {
                        warnings.add("Trait '$traitName' resources[$index] has invalid key '$rawEntry'; skipping")
                        continue
                    }
                    result.add(ResourceRequirement(key = rawEntry))
                }

                is Map<*, *> -> {
                    val entryMap = rawEntry as Map<String, Any>
                    val key = entryMap["key"] as? String
                    if (key == null) {
                        warnings.add("Trait '$traitName' resources[$index] is missing required field 'key'; skipping")
                        continue
                    }
                    if (!isValidResourceKey(key)) {
                        warnings.add("Trait '$traitName' resources[$index] has invalid key '$key'; skipping")
                        continue
                    }

                    warnReservedBudgetKeys(entryMap, "Trait '$traitName' resources[$index] (key='$key')", warnings)

                    val modeRaw = entryMap["mode"] as? String
                    val mode =
                        if (modeRaw == null) {
                            ResourceMode.EXCLUSIVE
                        } else {
                            VALID_RESOURCE_MODES[modeRaw.lowercase()] ?: run {
                                warnings.add(
                                    "Trait '$traitName' resources[$index] (key='$key') has unknown mode '$modeRaw'; " +
                                        "defaulting to exclusive"
                                )
                                ResourceMode.EXCLUSIVE
                            }
                        }

                    val ttlRaw = entryMap["ttlSeconds"]
                    val ttlSeconds =
                        when (ttlRaw) {
                            null -> null
                            is Number -> ttlRaw.toInt()
                            else -> {
                                warnings.add(
                                    "Trait '$traitName' resources[$index] (key='$key') has non-numeric 'ttlSeconds' " +
                                        "value '$ttlRaw'; ignoring"
                                )
                                null
                            }
                        }

                    result.add(ResourceRequirement(key = key, mode = mode, ttlSeconds = ttlSeconds))
                }

                else -> {
                    warnings.add("Trait '$traitName' resources[$index] is neither a string nor a map; skipping")
                }
            }
        }
        return result
    }

    private fun isValidResourceKey(key: String): Boolean = key.length in 1..RESOURCE_KEY_MAX_LENGTH && RESOURCE_KEY_REGEX.matches(key)

    /**
     * Warns (without storing) when [entryMap] contains any of [RESERVED_BUDGET_KEYS] — these fields
     * are reserved for a future budget-enforcement feature and are intentionally not modeled by
     * [ResourceDefinition]/[ResourceRequirement] yet.
     */
    private fun warnReservedBudgetKeys(
        entryMap: Map<String, Any>,
        context: String,
        warnings: MutableList<String>
    ) {
        for (budgetKey in RESERVED_BUDGET_KEYS) {
            if (entryMap.containsKey(budgetKey)) {
                warnings.add("$context uses '$budgetKey', which is reserved for future use; ignoring")
            }
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

    /**
     * Parses the top-level `status_labels` key into a trigger→label map, mirroring
     * [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService]'s reading of
     * the same key from the global file: each value is coerced to a string via `toString()` (YAML
     * `null` survives as a Kotlin `null`, meaning "explicitly no label for this trigger").
     *
     * Returns null when the document has no `status_labels` key at all (see [ParsedConfig.statusLabels]
     * for what callers do with that distinction), or when the key is present but not a map (a
     * malformed section is treated the same as "absent", with a warning).
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseStatusLabels(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): Map<String, String?>? {
        if (!root.containsKey("status_labels")) return null
        val raw = root["status_labels"] as? Map<String, Any?>
        if (raw == null) {
            warnings.add("status_labels section is not a map; ignoring")
            return null
        }
        return raw.entries.associate { (trigger, label) -> trigger to label?.toString() }
    }
}

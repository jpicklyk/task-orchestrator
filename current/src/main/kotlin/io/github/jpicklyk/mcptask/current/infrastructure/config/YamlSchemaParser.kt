package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.SchemaResolutionMode
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * Thrown for a STRUCTURAL config error that must fail the whole config load/push rather than being
 * recorded as a load warning (A1a): currently only the seat-declaration checks F1-F4 in
 * [YamlSchemaParser.parseRoot] (two `enters: true` seats in one phase, a duplicate seat name, a
 * seat reserved-named `unowned`, or an `after` cycle — all within one seats list, or within a
 * schema's own seats plus the seats of its SAME-DOCUMENT default traits).
 *
 * Deliberately NOT an [IllegalArgumentException]: [io.github.jpicklyk.mcptask.current.infrastructure.config.GlobalConfigFile]
 * re-throws an [IllegalArgumentException] from [YamlSchemaParser.parseRoot] unchanged (so its
 * message would lose the "Failed to parse note schemas in '<path>'" wrapper this type needs), but
 * catches every other [Exception] — including this one — and wraps it, naming the config path, so
 * a structural seat error fails global startup the same way any other malformed config file does.
 * On the per-root push path, [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser]
 * catches every [Exception] generically and turns it into a parse-failure outcome (nothing stored),
 * so this type needs no special handling there either.
 */
class ConfigStructureException(
    message: String
) : RuntimeException(message)

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
     * Recognized `traits.<name>.dispatch.<phase>.effort` values, matched case-SENSITIVELY —
     * unlike [VALID_RESOURCE_MODES], `"HIGH"` is invalid (dropped with a warning), only exact
     * lowercase matches.
     */
    private val VALID_DISPATCH_EFFORTS = setOf("low", "medium", "high", "xhigh", "max")

    /** Recognized fields on a `traits.<name>.dispatch.<phase>:` profile map. */
    private val VALID_DISPATCH_FIELDS = setOf("agent", "model", "effort")

    /**
     * Recognized keys on a `dispatch.<phase>:` map, INCLUDING `seats` (A1a) — used only for the
     * unknown-field warning; [VALID_DISPATCH_FIELDS] (which does not include `seats`) still governs
     * what [parseDispatchProfile] reads into a phase-level [DispatchProfile].
     */
    private val VALID_DISPATCH_PHASE_KEYS = VALID_DISPATCH_FIELDS + "seats"

    /** Known top-level `config.yaml` sections (A1a W2): anything else warns, never fails. */
    private val KNOWN_TOP_LEVEL_SECTIONS =
        setOf(
            "work_item_schemas",
            "note_schemas",
            "traits",
            "resources",
            "note_limits",
            "status_labels",
            "schema_resolution",
            "actor_authentication",
            "project",
            "retrospective",
            "actor_attribution",
        )

    /** Known keys on a `work_item_schemas.<name>:` map (A1a W3). */
    private val KNOWN_SCHEMA_LEVEL_KEYS = setOf("lifecycle", "default_traits", "notes", "seats")

    /** Known keys on a `traits.<name>:` map (A1a W3). */
    private val KNOWN_TRAIT_LEVEL_KEYS = setOf("notes", "resources", "dispatch", "seats")

    /** Known keys on a note-schema entry map (A1a W1; `seat`/`independent_of` are new). */
    private val KNOWN_NOTE_ENTRY_KEYS =
        setOf("key", "role", "required", "description", "guidance", "skill", "maxLength", "seat", "independent_of")

    /** Known keys on a `seats[]` entry map (A1a W4). */
    private val VALID_SEAT_ENTRY_KEYS = setOf("name", "phase", "enters", "after", "reads_exclude")

    /** Reserved seat name: the `missingBySeat`/served-schema "unowned" bucket (DEC-11, A1a F3). Exact match only. */
    private const val UNOWNED_RESERVED_SEAT_NAME = "unowned"

    /**
     * Budget-related keys reserved for future use. If present on a `resources:` entry (registry or
     * per-trait), they are parsed-and-warned but never stored — see [warnReservedBudgetKeys].
     */
    private val RESERVED_BUDGET_KEYS = setOf("budgetLimit", "budgetWindowSeconds")

    /**
     * Backward-compatibility alias: everything that used to name `YamlSchemaParser.ParsedConfig`
     * (the parse result of a config root map: schemas keyed by type/tag, traits, warnings, and
     * every other document-level facet) now names [ConfigDocument] — the same shape, moved to
     * `application.config` so both the global file loader and the per-root loader share one
     * layer-agnostic type. Per-tag note lists are read via `workItemSchemas[tag]?.notes` — there
     * is no separate tag→entries map, since it would be a redundant view of the same
     * [NoteSchemaEntry] lists already held inside each [WorkItemSchema].
     *
     * Kept for this PR only (see the C1 task-scope note's "Blast radius" for the conditional
     * fallback if the compiler rejects a nested typealias); a later item may delete it once every
     * caller has migrated to naming [ConfigDocument] directly.
     */
    typealias ParsedConfig = ConfigDocument

    /**
     * Parses [root] into a [ConfigDocument].
     *
     * Precedence: `work_item_schemas:` wins entirely over `note_schemas:` when both are present;
     * `note_schemas:` is the legacy format (wrapped into [WorkItemSchema] with AUTO lifecycle for
     * backward compatibility). When neither key is present, [warnOnMissingSchemas] controls
     * whether a "no schemas loaded" warning is recorded — the global file-backed loader wants
     * this warning (a `.taskorchestrator/config.yaml` with no schema section is almost always a
     * mistake), but a per-root config document legitimately may carry only other settings with no
     * schema section at all, so callers with looser expectations pass `false`.
     *
     * [ConfigDocument.noteLimitsMode] is `null` only when the document has no top-level
     * `note_limits` key at all; otherwise it holds the resolved mode (defaulting to `"warn"` even
     * when the key's own `mode` sub-key was absent, empty, or invalid). Callers layering this
     * document over another use that `null` to distinguish "this document doesn't opine, fall
     * through" from "this document explicitly configures note limits".
     *
     * [ConfigDocument.schemaResolution] is parsed from the top-level `schema_resolution` key via
     * [SchemaResolutionMode.fromConfigString]; a non-string or unrecognized value becomes `null`
     * and, when the key is present at all, adds exactly one warning naming the raw value (AR-39,
     * C4). [ConfigDocument.actorAuthenticationSection] is the raw `actor_authentication` value
     * (map, scalar, or explicit YAML `null`), or Kotlin `null` when the key is absent entirely.
     * [ConfigDocument.presentSections] is `root.keys` in document order (a `LinkedHashMap`'s
     * iteration order, since SnakeYAML deserializes mappings into `LinkedHashMap`).
     */
    @Suppress("UNCHECKED_CAST")
    fun parseRoot(
        root: Map<String, Any>,
        warnOnMissingSchemas: Boolean = true
    ): ConfigDocument {
        val warnings = mutableListOf<String>()

        for (key in root.keys) {
            if (key !in KNOWN_TOP_LEVEL_SECTIONS) {
                warnings.add("Unknown top-level section '$key'; ignoring")
            }
        }

        val parsedTraits = parseTraits(root, warnings)
        val traitSeatsMap = parseTraitSeatsMap(root, warnings)
        val parsedNoteLimitsMode = parseNoteLimitsMode(root, warnings)
        val noteLimitsMode = if (root.containsKey("note_limits")) parsedNoteLimitsMode else null
        val parsedStatusLabels = parseStatusLabels(root, warnings)
        val resourceRegistry = parseResourceRegistry(root, warnings)
        val traitResources = parseTraitResources(root, resourceRegistry, warnings)
        val (traitDispatch, traitDispatchBySeat) = parseTraitDispatchAndBySeat(root, warnings)
        val schemaResolution = (root["schema_resolution"] as? String)?.let { SchemaResolutionMode.fromConfigString(it) }
        if (root.containsKey("schema_resolution") && schemaResolution == null) {
            warnings.add(
                "Unrecognized schema_resolution value '${root["schema_resolution"]}' " +
                    "(expected legacy, layered or isolated); treating as absent"
            )
        }
        val actorAuthenticationSection = root["actor_authentication"]
        val presentSections: Set<String> = LinkedHashSet(root.keys)

        val base =
            when {
                root.containsKey("work_item_schemas") -> parseWorkItemSchemas(root, traitSeatsMap, parsedTraits, warnings)
                root.containsKey("note_schemas") -> parseLegacyNoteSchemas(root, warnings)
                else -> {
                    if (warnOnMissingSchemas) {
                        warnings.add("Config file is missing 'note_schemas' key; no schemas loaded")
                    }
                    ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap())
                }
            }

        return base.copy(
            traits = parsedTraits,
            warnings = warnings,
            noteLimitsMode = noteLimitsMode,
            statusLabels = parsedStatusLabels,
            traitResources = traitResources,
            resourceRegistry = resourceRegistry,
            traitDispatch = traitDispatch,
            traitDispatchBySeat = traitDispatchBySeat,
            traitSeats = traitSeatsMap,
            schemaResolution = schemaResolution,
            actorAuthenticationSection = actorAuthenticationSection,
            presentSections = presentSections,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseWorkItemSchemas(
        root: Map<String, Any>,
        traitSeatsMap: Map<String, List<SeatDefinition>>,
        traitNotesMap: Map<String, List<NoteSchemaEntry>>,
        warnings: MutableList<String>
    ): ConfigDocument {
        val rawSchemas =
            root["work_item_schemas"] as? Map<String, Any>
                ?: return ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap())

        val workItemSchemasMap = mutableMapOf<String, WorkItemSchema>()

        for ((schemaName, rawValue) in rawSchemas) {
            val schemaMap = rawValue as? Map<String, Any> ?: continue

            for (key in schemaMap.keys) {
                if (key !in KNOWN_SCHEMA_LEVEL_KEYS) {
                    warnings.add("Schema '$schemaName' has unknown key '$key'; ignoring")
                }
            }

            val lifecycleRaw = schemaMap["lifecycle"] as? String
            val lifecycleMode =
                if (lifecycleRaw != null) {
                    val normalized = lifecycleRaw.trim().uppercase().replace('-', '_')
                    if (normalized == "AUTO_REOPEN") {
                        warnings.add(
                            "Schema '$schemaName': lifecycle 'auto-reopen' was removed (it behaved exactly like " +
                                "'auto'); treating as 'auto'"
                        )
                        LifecycleMode.AUTO
                    } else {
                        val parsed = LifecycleMode.fromString(lifecycleRaw)
                        if (parsed == null) {
                            warnings.add(
                                "Schema '$schemaName' has invalid lifecycle value '$lifecycleRaw'; defaulting to AUTO"
                            )
                            LifecycleMode.AUTO
                        } else {
                            parsed
                        }
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

            val seats = parseSeatEntries(schemaMap["seats"], "Schema '$schemaName'", warnings)
            validateSeatScope("Schema '$schemaName'", seats)

            val effectiveSeats = mutableListOf<SeatDefinition>()
            effectiveSeats.addAll(seats)
            // distinct(): a trait listed twice in default_traits contributes its seats once — resolve-time
            // mergeTraits de-dups the same way, so a repeat must not trip a false F2 duplicate-name fatal.
            for (trait in defaultTraits.distinct()) {
                traitSeatsMap[trait]?.let { effectiveSeats.addAll(it) }
            }
            if (effectiveSeats.size != seats.size) {
                validateSeatScope("Schema '$schemaName'", effectiveSeats)
            }

            warnUnownedRequiredNotes(schemaName, entries, defaultTraits, traitNotesMap, effectiveSeats, warnings)

            workItemSchemasMap[schemaName] =
                WorkItemSchema(
                    type = schemaName,
                    lifecycleMode = lifecycleMode,
                    notes = entries,
                    defaultTraits = defaultTraits,
                    seats = seats
                )
        }

        return ConfigDocument(workItemSchemas = workItemSchemasMap, traits = emptyMap(), warnings = warnings)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLegacyNoteSchemas(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): ConfigDocument {
        val noteSchemas =
            root["note_schemas"] as? Map<String, Any>
                ?: return ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap(), warnings = warnings)

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

        return ConfigDocument(workItemSchemas = workItemSchemasMap, traits = emptyMap(), warnings = warnings)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTraits(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): Map<String, List<NoteSchemaEntry>> {
        val traitsRaw = root["traits"] as? Map<String, Any> ?: return emptyMap()
        return traitsRaw.entries.associate { (traitName, rawValue) ->
            val rawMap = rawValue as? Map<String, Any> ?: emptyMap()
            for (key in rawMap.keys) {
                if (key !in KNOWN_TRAIT_LEVEL_KEYS) {
                    warnings.add("Trait '$traitName' has unknown key '$key'; ignoring")
                }
            }
            val notesList = rawMap["notes"] as? List<Map<String, Any>> ?: emptyList()
            val entries =
                notesList.mapIndexedNotNull { index, raw ->
                    parseEntry(raw, "trait:$traitName", index, warnings)
                }
            traitName to entries
        }
    }

    /**
     * Parses per-trait `seats:` lists into a trait-name→[SeatDefinition] map. A trait with no
     * `seats:` key at all is absent from the result entirely (not mapped to an empty list),
     * mirroring [parseTraitResources]/[parseTraitDispatchAndBySeat] — see [ConfigDocument.traitSeats].
     * Each trait's own seats list is validated standalone (F1-F4); the cross-check against a
     * schema's own seats (for schemas naming this trait in `default_traits`, in the SAME document)
     * happens separately in [parseWorkItemSchemas].
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseTraitSeatsMap(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): Map<String, List<SeatDefinition>> {
        val traitsRaw = root["traits"] as? Map<String, Any> ?: return emptyMap()
        val result = mutableMapOf<String, List<SeatDefinition>>()
        for ((traitName, rawValue) in traitsRaw) {
            val rawMap = rawValue as? Map<String, Any> ?: continue
            if (!rawMap.containsKey("seats")) continue
            val seats = parseSeatEntries(rawMap["seats"], "Trait '$traitName'", warnings)
            validateSeatScope("Trait '$traitName'", seats)
            result[traitName] = seats
        }
        return result
    }

    /**
     * Parses a `seats:` list (schema-level or trait-level) into [SeatDefinition]s. Malformed
     * entries warn-and-skip at their own granularity (A1a W4), never failing the whole section:
     * missing/blank `name`, invalid/missing `phase`, non-boolean `enters` (defaults to false),
     * non-list `after`/`reads_exclude` (default to empty), and an unknown entry key.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseSeatEntries(
        rawSeats: Any?,
        context: String,
        warnings: MutableList<String>
    ): List<SeatDefinition> {
        if (rawSeats == null) return emptyList()
        val list =
            rawSeats as? List<Any?> ?: run {
                warnings.add("$context has a malformed 'seats' section (expected a list); ignoring")
                return emptyList()
            }

        val result = mutableListOf<SeatDefinition>()
        for ((index, rawEntry) in list.withIndex()) {
            val entryMap =
                rawEntry as? Map<String, Any> ?: run {
                    warnings.add("$context seats[$index] is not a map; skipping")
                    continue
                }

            val nameRaw = entryMap["name"] as? String
            val name = nameRaw?.trim()
            if (name.isNullOrBlank()) {
                warnings.add("$context seats[$index] is missing required field 'name'; skipping")
                continue
            }

            val phaseRaw = entryMap["phase"] as? String
            val phase = phaseRaw?.let { VALID_SCHEMA_ROLES[it] }
            if (phase == null) {
                warnings.add(
                    "$context seats[$index] (name='$name') has invalid or missing 'phase' value '$phaseRaw' " +
                        "(valid: ${VALID_SCHEMA_ROLES.keys}); skipping"
                )
                continue
            }

            val entersRaw = entryMap["enters"]
            val enters =
                when (entersRaw) {
                    null -> false
                    is Boolean -> entersRaw
                    else -> {
                        warnings.add(
                            "$context seats[$index] (name='$name') has non-boolean 'enters' value '$entersRaw'; " +
                                "defaulting to false"
                        )
                        false
                    }
                }

            val after = parseSeatStringListField(entryMap, "after", context, name, warnings)
            val readsExclude = parseSeatStringListField(entryMap, "reads_exclude", context, name, warnings)

            for (key in entryMap.keys) {
                if (key !in VALID_SEAT_ENTRY_KEYS) {
                    warnings.add("$context seats[$index] (name='$name') has unknown key '$key'; ignoring")
                }
            }

            result.add(SeatDefinition(name = name, phase = phase, enters = enters, after = after, readsExclude = readsExclude))
        }
        return result
    }

    /**
     * DEC-11 (A1a W5): in a schema whose effective seats (its own + its SAME-DOCUMENT default
     * traits' seats) are non-empty, warns for every REQUIRED note (the schema's own notes + its
     * default traits' notes, base-key-wins — the same-document counterpart of resolve-time trait
     * merging) whose `seat` is null, or does not name a seat of the SAME phase as the note's `role`
     * — such a note is served under the `unowned` bucket in `missingBySeat` (see
     * `io.github.jpicklyk.mcptask.current.application.service.SeatOwnership`). A no-op when
     * [effectiveSeats] is empty (the schema isn't seat-aware).
     */
    private fun warnUnownedRequiredNotes(
        schemaName: String,
        baseNotes: List<NoteSchemaEntry>,
        defaultTraits: List<String>,
        traitNotesMap: Map<String, List<NoteSchemaEntry>>,
        effectiveSeats: List<SeatDefinition>,
        warnings: MutableList<String>
    ) {
        if (effectiveSeats.isEmpty()) return

        val combinedNotes = mutableListOf<NoteSchemaEntry>()
        val seenKeys = mutableSetOf<String>()
        for (note in baseNotes) {
            if (seenKeys.add(note.key)) combinedNotes.add(note)
        }
        for (trait in defaultTraits) {
            val traitNotes = traitNotesMap[trait] ?: continue
            for (note in traitNotes) {
                if (seenKeys.add(note.key)) combinedNotes.add(note)
            }
        }

        for (note in combinedNotes) {
            if (!note.required) continue
            val owned = effectiveSeats.any { it.name == note.seat && it.phase == note.role }
            if (!owned) {
                warnings.add(
                    "Schema '$schemaName': required note '${note.key}' (role '${note.role.name.lowercase()}') is " +
                        "not owned by any declared seat; listed under 'unowned' in missingBySeat"
                )
            }
        }
    }

    private fun parseSeatStringListField(
        entryMap: Map<String, Any>,
        field: String,
        context: String,
        seatName: String,
        warnings: MutableList<String>
    ): List<String> {
        if (!entryMap.containsKey(field)) return emptyList()
        val raw = entryMap[field]
        val list =
            raw as? List<*> ?: run {
                warnings.add("$context seats (name='$seatName') has non-list '$field' value '$raw'; ignoring")
                return emptyList()
            }
        return list.filterIsInstance<String>()
    }

    /**
     * Structural validation (F1-F4, ConfigStructureException — fails the whole load/push, see
     * [ConfigStructureException]) over one seats scope: a single list (a schema's own, or a
     * trait's own), or the union of a schema's own seats with its same-document default traits'
     * seats (see [parseWorkItemSchemas]).
     *
     * F3 (reserved name) and F2 (duplicate name) are checked before F1 (duplicate `enters` per
     * phase) and F4 (an `after` cycle), so a config violating more than one rule fails with the
     * FIRST rule number's message, deterministically.
     */
    private fun validateSeatScope(
        context: String,
        seats: List<SeatDefinition>
    ) {
        if (seats.isEmpty()) return

        seats.firstOrNull { it.name == UNOWNED_RESERVED_SEAT_NAME }?.let {
            throw ConfigStructureException(
                "$context declares a seat named '$UNOWNED_RESERVED_SEAT_NAME', which is reserved for the " +
                    "unowned-notes bucket"
            )
        }

        val seenNames = mutableSetOf<String>()
        for (seat in seats) {
            if (!seenNames.add(seat.name)) {
                throw ConfigStructureException("$context declares duplicate seat name '${seat.name}'")
            }
        }

        val entersByPhase = mutableMapOf<Role, String>()
        for (seat in seats) {
            if (!seat.enters) continue
            val existing = entersByPhase[seat.phase]
            if (existing != null) {
                throw ConfigStructureException(
                    "$context has two seats entering phase '${seat.phase.name.lowercase()}' " +
                        "('$existing' and '${seat.name}')"
                )
            }
            entersByPhase[seat.phase] = seat.name
        }

        val byName = seats.associateBy { it.name }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(name: String) {
            if (name in visited) return
            if (!visiting.add(name)) {
                throw ConfigStructureException("$context has a seat 'after' cycle involving '$name'")
            }
            byName[name]?.after?.forEach { dep -> if (dep in byName) visit(dep) }
            visiting.remove(name)
            visited.add(name)
        }
        for (seat in seats) visit(seat.name)
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
     * Parses per-trait `dispatch:` maps into BOTH the by-role profile map (unchanged behavior) and
     * the new by-seat override map (A1a, `dispatch.<phase>.seats:`), sharing one pass over the
     * phase entries so both shapes see identical phase/role validation. A trait with no `dispatch:`
     * key is absent from BOTH result maps entirely, mirroring [parseTraitResources]. See
     * [ConfigDocument.traitDispatch] / [ConfigDocument.traitDispatchBySeat] for the "absent, not
     * empty" convention this preserves.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseTraitDispatchAndBySeat(
        root: Map<String, Any>,
        warnings: MutableList<String>
    ): Pair<Map<String, Map<Role, DispatchProfile>>, Map<String, Map<Role, Map<String, SeatDispatchOverride>>>> {
        val traitsRaw =
            root["traits"] as? Map<String, Any>
                ?: return emptyMap<String, Map<Role, DispatchProfile>>() to emptyMap()

        val byRoleResult = mutableMapOf<String, Map<Role, DispatchProfile>>()
        val bySeatResult = mutableMapOf<String, Map<Role, Map<String, SeatDispatchOverride>>>()

        for ((traitName, rawValue) in traitsRaw) {
            val rawMap = rawValue as? Map<String, Any> ?: continue
            val dispatchRaw = rawMap["dispatch"] ?: continue

            val dispatchMap =
                dispatchRaw as? Map<String, Any> ?: run {
                    warnings.add("Trait '$traitName' has a malformed 'dispatch' section (expected a map); skipping")
                    continue
                }

            val phases = mutableMapOf<Role, DispatchProfile>()
            val phasesBySeat = mutableMapOf<Role, Map<String, SeatDispatchOverride>>()

            for ((phaseKey, phaseRaw) in dispatchMap) {
                val role = VALID_SCHEMA_ROLES[phaseKey]
                if (role == null) {
                    warnings.add(
                        "Trait '$traitName' dispatch has invalid phase '$phaseKey' " +
                            "(valid: ${VALID_SCHEMA_ROLES.keys}); skipping"
                    )
                    continue
                }

                val phaseMap =
                    phaseRaw as? Map<String, Any> ?: run {
                        warnings.add("Trait '$traitName' dispatch.$phaseKey is not a map; skipping")
                        continue
                    }

                val profile = parseDispatchProfile(phaseMap, traitName, phaseKey, warnings)
                if (profile != null) {
                    phases[role] = profile
                }

                if (phaseMap.containsKey("seats")) {
                    val seatOverrides = parseSeatDispatchOverrides(phaseMap["seats"], traitName, phaseKey, warnings)
                    if (seatOverrides.isNotEmpty()) {
                        phasesBySeat[role] = seatOverrides
                    }
                }
            }

            if (phases.isNotEmpty()) {
                byRoleResult[traitName] = phases
            }
            if (phasesBySeat.isNotEmpty()) {
                bySeatResult[traitName] = phasesBySeat
            }
        }
        return byRoleResult to bySeatResult
    }

    /**
     * Parses a `dispatch.<phase>.seats:` map into a seat-name→[SeatDispatchOverride] map (A1a).
     * Malformed shapes warn-and-skip at their own granularity: `seats:` not a map -> the whole
     * sub-section is ignored; an individual seat's override not a map -> that seat is skipped; an
     * override with no valid field -> that seat is skipped (see [parseSeatDispatchOverride]).
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseSeatDispatchOverrides(
        rawSeats: Any?,
        traitName: String,
        phaseKey: String,
        warnings: MutableList<String>
    ): Map<String, SeatDispatchOverride> {
        val seatsMap =
            rawSeats as? Map<String, Any?> ?: run {
                warnings.add("Trait '$traitName' dispatch.$phaseKey.seats is not a map; ignoring")
                return emptyMap()
            }
        val result = mutableMapOf<String, SeatDispatchOverride>()
        for ((seatName, overrideRaw) in seatsMap) {
            val overrideMap =
                overrideRaw as? Map<String, Any?> ?: run {
                    warnings.add("Trait '$traitName' dispatch.$phaseKey.seats.$seatName is not a map; skipping")
                    continue
                }
            parseSeatDispatchOverride(overrideMap, traitName, phaseKey, seatName, warnings)?.let {
                result[seatName] = it
            }
        }
        return result
    }

    /**
     * Parses one `dispatch.<phase>.seats.<seat>:` override map. A field present with an explicit
     * YAML `null` value is recorded in [SeatDispatchOverride.cleared] (agent:null CLEARS the phase
     * default for that seat, per DEC — task-scope §2); a field present with a non-string or blank
     * value drops with a warning (same rule as [parseDispatchField]); `effort` is additionally
     * validated against [VALID_DISPATCH_EFFORTS]. An override with no field set at all (nothing
     * overridden, nothing cleared) is dropped with a warning, matching an empty phase-level profile.
     */
    private fun parseSeatDispatchOverride(
        overrideMap: Map<String, Any?>,
        traitName: String,
        phaseKey: String,
        seatName: String,
        warnings: MutableList<String>
    ): SeatDispatchOverride? {
        var agent: String? = null
        var model: String? = null
        var effort: String? = null
        val cleared = mutableSetOf<String>()

        for (field in VALID_DISPATCH_FIELDS) {
            if (!overrideMap.containsKey(field)) continue
            val raw = overrideMap[field]
            if (raw == null) {
                cleared.add(field)
                continue
            }
            val value = raw as? String
            if (value == null) {
                warnings.add(
                    "Trait '$traitName' dispatch.$phaseKey.seats.$seatName.$field has non-string value '$raw'; dropping"
                )
                continue
            }
            if (value.isBlank()) {
                warnings.add("Trait '$traitName' dispatch.$phaseKey.seats.$seatName.$field is blank; dropping")
                continue
            }
            if (field == "effort" && value !in VALID_DISPATCH_EFFORTS) {
                warnings.add(
                    "Trait '$traitName' dispatch.$phaseKey.seats.$seatName.effort has invalid value '$value' " +
                        "(valid: $VALID_DISPATCH_EFFORTS); dropping"
                )
                continue
            }
            when (field) {
                "agent" -> agent = value
                "model" -> model = value
                "effort" -> effort = value
            }
        }

        for (key in overrideMap.keys) {
            if (key !in VALID_DISPATCH_FIELDS) {
                warnings.add("Trait '$traitName' dispatch.$phaseKey.seats.$seatName has unknown field '$key'; ignoring")
            }
        }

        if (agent == null && model == null && effort == null && cleared.isEmpty()) {
            warnings.add("Trait '$traitName' dispatch.$phaseKey.seats.$seatName has no valid fields; skipping")
            return null
        }
        return SeatDispatchOverride(agent = agent, model = model, effort = effort, cleared = cleared)
    }

    /**
     * Parses a single `traits.<name>.dispatch.<phase>:` map into a [DispatchProfile]. Returns null
     * (caller skips the phase, with a warning already recorded) when no field survives parsing —
     * an empty profile is never stored, matching a `resources:` entry with no valid fields.
     */
    private fun parseDispatchProfile(
        phaseMap: Map<String, Any>,
        traitName: String,
        phaseKey: String,
        warnings: MutableList<String>
    ): DispatchProfile? {
        val agent = parseDispatchField(phaseMap, "agent", traitName, phaseKey, warnings)
        val model = parseDispatchField(phaseMap, "model", traitName, phaseKey, warnings)
        var effort = parseDispatchField(phaseMap, "effort", traitName, phaseKey, warnings)
        if (effort != null && effort !in VALID_DISPATCH_EFFORTS) {
            warnings.add(
                "Trait '$traitName' dispatch.$phaseKey.effort has invalid value '$effort' " +
                    "(valid: $VALID_DISPATCH_EFFORTS); dropping"
            )
            effort = null
        }

        for (key in phaseMap.keys) {
            if (key !in VALID_DISPATCH_PHASE_KEYS) {
                warnings.add("Trait '$traitName' dispatch.$phaseKey has unknown field '$key'; ignoring")
            }
        }

        if (agent == null && model == null && effort == null) {
            // A phase map carrying ONLY `seats:` (A1a) is valid — no phase-level profile, but not
            // a "no valid fields" warning either; parseSeatDispatchOverrides handles its content.
            if (!phaseMap.containsKey("seats")) {
                warnings.add("Trait '$traitName' dispatch.$phaseKey has no valid fields; skipping")
            }
            return null
        }
        return DispatchProfile(agent = agent, model = model, effort = effort)
    }

    /**
     * Reads a single string field off a `dispatch.<phase>:` profile map, warning-and-dropping
     * (returning null) when the field is present but non-string or blank. An absent field is
     * silently null (not a warning) — the field is simply unset for this profile.
     */
    private fun parseDispatchField(
        phaseMap: Map<String, Any>,
        field: String,
        traitName: String,
        phaseKey: String,
        warnings: MutableList<String>
    ): String? {
        if (!phaseMap.containsKey(field)) return null
        val raw = phaseMap[field]
        val value = raw as? String
        if (value == null) {
            warnings.add("Trait '$traitName' dispatch.$phaseKey.$field has non-string value '$raw'; dropping")
            return null
        }
        if (value.isBlank()) {
            warnings.add("Trait '$traitName' dispatch.$phaseKey.$field is blank; dropping")
            return null
        }
        return value
    }

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
            warnings.add(
                "Schema '$schemaName' entry[$index] (key='$key') has invalid role '$roleRaw' " +
                    "(valid: ${VALID_SCHEMA_ROLES.keys}); skipping"
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

        val seat = raw["seat"] as? String

        val independentOfRaw = raw["independent_of"]
        val independentOf =
            when (independentOfRaw) {
                null -> emptyList()
                is List<*> -> independentOfRaw.filterIsInstance<String>()
                else -> {
                    warnings.add(
                        "Schema '$schemaName' entry (key='$key') has non-list 'independent_of' value " +
                            "'$independentOfRaw'; ignoring"
                    )
                    emptyList()
                }
            }

        for (rawKey in raw.keys) {
            if (rawKey !in KNOWN_NOTE_ENTRY_KEYS) {
                warnings.add("Schema '$schemaName' entry (key='$key') has unknown key '$rawKey'; ignoring")
            }
        }

        return NoteSchemaEntry(
            key = key,
            role = parsedRole,
            required = required,
            description = description,
            guidance = guidance,
            skill = skill,
            maxLength = maxLength,
            seat = seat,
            independentOf = independentOf,
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

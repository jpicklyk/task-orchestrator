package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.OwningSeat
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * One validation finding: [path] is an RFC 6901 JSON Pointer into the config document (the root is the empty
 * string; an object key containing `~` or `/` is escaped as `~0` / `~1`) and [reason] says what is wrong there.
 */
data class ConfigViolation(
    val path: String,
    val reason: String,
)

/** What [ConfigValidator] found: [errors] reject the document, [warnings] never do. Both are in a stable order. */
data class ConfigValidationResult(
    val errors: List<ConfigViolation>,
    val warnings: List<ConfigViolation>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Structural (JSON Schema) check of a parsed config root, implemented in infrastructure so the application layer
 * stays library-free. Returns one [ConfigViolation] per structural error, with JSON Pointer paths (an unknown key is
 * reported at the unknown key's own path).
 */
fun interface ConfigStructuralValidator {
    fun validate(root: Map<String, Any?>): List<ConfigViolation>

    companion object {
        /** Reports nothing; used where no structural validator is wired (the parser's own findings still apply). */
        val NONE: ConfigStructuralValidator = ConfigStructuralValidator { emptyList() }
    }
}

/**
 * The admission check for config: the structural pass ([structural]), the parser's findings (a document parsed in
 * [ParseMode.STRICT]) and a semantic pass, merged into one result.
 *
 * Semantic rules: a per-root document must not carry the server-only key `resources`;
 * `work_item_schemas` and the legacy `note_schemas` cannot be combined; every `default_traits` entry must name a
 * trait defined in the document or in the server layer; every trait `resources[].key` must be declared in the server
 * resource registry; a `lifecycle: none` schema declares no `seats` of its own and no `required: true` note (a
 * lifecycle-free item has no phase, so no gate could ever enforce either); the `runs:` block (allowed in either scope)
 * follows [runsValueViolations] and the `decision_classes:` block (either scope) [decisionClassesValueViolations]. For
 * a [ConfigScope.SERVER] document the
 * document is the server layer, so it is checked against
 * itself. [serverLayer] is the server-wide config (today the global file); with none, a per-root document sees no
 * server traits and an empty registry.
 *
 * Lint is separate: [lint] returns warning-only quality findings for a valid per-root document, never part of a
 * [ConfigValidationResult], so `push` and the import neither compute nor store them.
 */
class ConfigValidator(
    private val structural: ConfigStructuralValidator = ConfigStructuralValidator.NONE,
    private val serverLayer: GlobalConfigSource = GlobalConfigSource { null },
) {
    /**
     * Validates an already-parsed [parsed] outcome. The document must have been parsed in [ParseMode.STRICT]; its
     * `errors` are the parser's former soft skips.
     */
    fun validate(
        parsed: ConfigDocumentParser.Outcome.Parsed,
        scope: ConfigScope = ConfigScope.ROOT,
    ): ConfigValidationResult = validate(parsed, scope, serverLayer.layer())

    /**
     * Same as the 2-arg overload, but checks a per-root document against the explicitly supplied [serverLayer] (the
     * DB head, read by the caller) instead of the constructor's. Ignored for [ConfigScope.SERVER]: that document is
     * its own server layer.
     */
    fun validate(
        parsed: ConfigDocumentParser.Outcome.Parsed,
        scope: ConfigScope,
        serverLayer: ConfigLayer?,
    ): ConfigValidationResult {
        val document = parsed.document
        val root = parsed.rawRoot
        val errors = LinkedHashMap<Pair<String, String>, ConfigViolation>()
        var semanticWarnings: List<ConfigViolation> = emptyList()

        fun add(violation: ConfigViolation) {
            errors.putIfAbsent(violation.path to violation.reason, violation)
        }

        document.errors.forEach(::add)
        val parserPaths = document.errors.mapTo(HashSet()) { it.path }
        if (root != null) {
            structural
                .validate(root)
                .filter { it.path !in parserPaths }
                .forEach(::add)
            semantic(document, root, scope, serverLayer).forEach(::add)
            semanticWarnings = traitOrderWarnings(document, root, scope, serverLayer)
        }

        val order = compareBy<ConfigViolation>({ it.path }, { it.reason })
        return ConfigValidationResult(
            errors = errors.values.sortedWith(order),
            warnings = (document.warningViolations + semanticWarnings).distinct().sortedWith(order),
        )
    }

    /**
     * Parses [yaml] with [parser] in [ParseMode.STRICT] and validates it. A document the parser cannot read
     * (a YAML syntax error, a non-mapping root, a structural seat error) comes back as errors of its own.
     */
    fun validate(
        yaml: String,
        parser: ConfigDocumentParser,
        scope: ConfigScope = ConfigScope.ROOT,
    ): ConfigValidationResult =
        when (val outcome = parser.parse(yaml, warnOnMissingSchemas = false, mode = ParseMode.STRICT)) {
            is ConfigDocumentParser.Outcome.Parsed -> validate(outcome, scope)
            is ConfigDocumentParser.Outcome.Failed ->
                ConfigValidationResult(
                    errors = outcome.violations.ifEmpty { listOf(ConfigViolation("", outcome.detail)) },
                    warnings = emptyList(),
                )
        }

    /**
     * Lint findings for a per-root [parsed] document that has already passed [validate] with no errors, checked against
     * the constructor's server layer. See the 3-arg overload.
     */
    fun lint(
        parsed: ConfigDocumentParser.Outcome.Parsed,
        servedRuleKeys: Set<String>,
    ): List<ConfigViolation> = lint(parsed, serverLayer.layer(), servedRuleKeys)

    /**
     * Lint: warning-only structural findings about the quality of a valid per-root document, kept apart from
     * [ConfigValidationResult] so `push` and the import can never compute or store them. Only content declared in
     * [parsed] is reported; [serverLayer] is read for resolution and comparison only. A trait named in
     * `default_traits` resolves from this document first, else [serverLayer] (a per-root trait replaces every
     * dimension of the server's trait of the same name). [servedRuleKeys] are the rule keys the root serves.
     *
     * Checks: a seat that owns no note of its phase; an unseated optional note on a seat-aware schema or trait; the
     * same guidance on several notes; a per-root schema or trait that shadows a server one; a trait with no
     * `applies_when`; a seat `rules` entry that is not served; a seat or note skill that names a served rule. The
     * required-unseated-note case is reported by the parser's own advisory and is not repeated here. The result is
     * distinct and sorted by path, then reason. The caller passes a valid document (the paths use the positions of a
     * strict parse; the parser collapses a repeated seat rule or skill, so those two use raw positions).
     */
    fun lint(
        parsed: ConfigDocumentParser.Outcome.Parsed,
        serverLayer: ConfigLayer?,
        servedRuleKeys: Set<String>,
    ): List<ConfigViolation> {
        val document = parsed.document
        val root = parsed.rawRoot ?: return emptyList()
        val serverDocument = serverLayer?.document
        val out = mutableListOf<ConfigViolation>()

        val rawSchemas = root["work_item_schemas"] as? Map<*, *>
        val rawTraits = root["traits"] as? Map<*, *>
        val schemas = document.workItemSchemas.filterKeys { rawSchemas?.containsKey(it) == true }
        val traits = document.traits.filterKeys { rawTraits?.containsKey(it) == true }

        fun traitNotes(trait: String): List<NoteSchemaEntry> =
            if (trait in document.traits) document.traits[trait].orEmpty() else serverDocument?.traits?.get(trait).orEmpty()

        fun traitSeats(trait: String): List<SeatDefinition> =
            if (trait in document.traits) document.traitSeats[trait].orEmpty() else serverDocument?.traitSeats?.get(trait).orEmpty()

        fun mergedNotes(schema: WorkItemSchema): List<NoteSchemaEntry> {
            val seen = HashSet<String>()
            return (schema.notes + schema.defaultTraits.flatMap(::traitNotes)).filter { seen.add(it.key) }
        }

        fun effectiveSeats(schema: WorkItemSchema): List<SeatDefinition> = schema.seats + schema.defaultTraits.flatMap(::traitSeats)

        fun owns(
            seat: SeatDefinition,
            notes: List<NoteSchemaEntry>,
        ): Boolean = OwningSeat.keysOf(seat.name, notes, listOf(seat)).isNotEmpty()

        fun seatChecks(
            seats: List<SeatDefinition>,
            rawSeats: List<*>?,
            basePath: Array<String>,
        ) {
            seats.forEachIndexed { i, seat ->
                val seatPath = arrayOf(*basePath, "seats", i.toString())
                val rawSeat = rawSeats?.getOrNull(i) as? Map<*, *>
                // The parser collapses a repeated rule or skill, so a kept entry's index in the parsed list is not its
                // position in the document: point at its first occurrence in the raw list.
                val rawRules = rawSeat?.get("rules") as? List<*>
                val rawSkills = rawSeat?.get("skills") as? List<*>
                seat.rules.orEmpty().forEachIndexed { parsedIndex, rule ->
                    val j = rawRules?.indexOf(rule)?.takeIf { it >= 0 } ?: parsedIndex
                    if (rule !in servedRuleKeys) {
                        out.add(
                            ConfigViolation(
                                pointer(*seatPath, "rules", j.toString()),
                                "seat '${seat.name}' names rule '$rule', which this root does not serve"
                            )
                        )
                    }
                }
                seat.skills.orEmpty().forEachIndexed { parsedIndex, skill ->
                    val j = rawSkills?.indexOf(skill)?.takeIf { it >= 0 } ?: parsedIndex
                    if (skill in servedRuleKeys) {
                        out.add(
                            ConfigViolation(
                                pointer(*seatPath, "skills", j.toString()),
                                "seat '${seat.name}' lists '$skill' under skills, but it is a served rule; consider listing it under rules"
                            )
                        )
                    }
                }
            }
        }

        fun noteSkillChecks(
            notes: List<NoteSchemaEntry>,
            rawNotes: List<*>?,
            basePath: Array<String>,
        ) {
            notes.forEachIndexed { i, note ->
                val listForm = (rawNotes?.getOrNull(i) as? Map<*, *>)?.get("skill") is List<*>
                note.skills.forEachIndexed { j, skill ->
                    if (skill in servedRuleKeys) {
                        val path =
                            if (listForm) {
                                arrayOf(
                                    *basePath,
                                    "notes",
                                    i.toString(),
                                    "skill",
                                    j.toString()
                                )
                            } else {
                                arrayOf(*basePath, "notes", i.toString(), "skill")
                            }
                        out.add(
                            ConfigViolation(
                                pointer(*path),
                                "note '${note.key}' names skill '$skill', which is a served rule; consider a rule pointer instead"
                            )
                        )
                    }
                }
            }
        }

        // L1, L2, L7, L8, L9 over schemas.
        schemas.forEach { (name, schema) ->
            val base = arrayOf("work_item_schemas", name)
            val merged = mergedNotes(schema)
            schema.seats.forEachIndexed { i, seat ->
                if (!owns(seat, merged)) {
                    out.add(
                        ConfigViolation(
                            pointer(*base, "seats", i.toString()),
                            "seat '${seat.name}' (${seat.phase.name.lowercase()}) owns no ${seat.phase.name.lowercase()}-phase note in schema '$name'"
                        )
                    )
                }
            }
            if (effectiveSeats(schema).isNotEmpty()) {
                schema.notes.forEachIndexed { i, note ->
                    if (note.seat == null && !note.required) {
                        out.add(
                            ConfigViolation(
                                pointer(*base, "notes", i.toString()),
                                "optional note '${note.key}' has no seat although schema '$name' declares seats; no seat owns it"
                            )
                        )
                    }
                }
            }
            seatChecks(schema.seats, (rawSchemas?.get(name) as? Map<*, *>)?.get("seats") as? List<*>, base)
            noteSkillChecks(schema.notes, (rawSchemas?.get(name) as? Map<*, *>)?.get("notes") as? List<*>, base)
        }

        // L1, L2, L7, L8, L9 over traits.
        traits.forEach { (name, notes) ->
            val base = arrayOf("traits", name)
            val seats = document.traitSeats[name].orEmpty()
            val listing = schemas.values.filter { name in it.defaultTraits }
            seats.forEachIndexed { i, seat ->
                if (!owns(seat, notes) && listing.none { owns(seat, mergedNotes(it)) }) {
                    out.add(
                        ConfigViolation(
                            pointer(*base, "seats", i.toString()),
                            "seat '${seat.name}' (${seat.phase.name.lowercase()}) owns no ${seat.phase.name.lowercase()}-phase note in trait '$name' or in a schema here that lists it"
                        )
                    )
                }
            }
            if (seats.isNotEmpty()) {
                notes.forEachIndexed { i, note ->
                    if (note.seat == null && !note.required) {
                        out.add(
                            ConfigViolation(
                                pointer(*base, "notes", i.toString()),
                                "optional note '${note.key}' has no seat although trait '$name' declares seats; no seat owns it"
                            )
                        )
                    }
                }
            }
            seatChecks(seats, (rawTraits?.get(name) as? Map<*, *>)?.get("seats") as? List<*>, base)
            noteSkillChecks(notes, (rawTraits?.get(name) as? Map<*, *>)?.get("notes") as? List<*>, base)
            // L6
            if (document.traitDescriptors[name]?.appliesWhen.isNullOrBlank()) {
                out.add(ConfigViolation(pointer(*base), "trait '$name' has no applies_when; say when the trait should be applied"))
            }
        }

        // L3 duplicate guidance, in document order: every schema's notes, then every trait's notes.
        class Occurrence(
            val owner: String,
            val key: String,
            val path: String,
            val guidance: String,
        )
        val occurrences = mutableListOf<Occurrence>()
        schemas.forEach { (name, schema) ->
            schema.notes.forEachIndexed { i, note ->
                val text = note.guidance?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    occurrences.add(Occurrence(name, note.key, pointer("work_item_schemas", name, "notes", i.toString(), "guidance"), text))
                }
            }
        }
        traits.forEach { (name, notes) ->
            notes.forEachIndexed { i, note ->
                val text = note.guidance?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    occurrences.add(Occurrence(name, note.key, pointer("traits", name, "notes", i.toString(), "guidance"), text))
                }
            }
        }
        occurrences.groupBy { it.guidance }.values.filter { it.size > 1 }.forEach { group ->
            val first = group.first()
            val others = group.drop(1).joinToString(", ") { "${it.owner}/${it.key}" }
            out.add(ConfigViolation(first.path, "guidance of '${first.owner}/${first.key}' is identical to that of: $others"))
        }

        // L4 shadowed type.
        if (serverDocument != null) {
            schemas.forEach { (name, schema) ->
                val server = serverDocument.workItemSchemas[name] ?: return@forEach
                val differs = mutableListOf<String>()
                if (schema.lifecycleMode != server.lifecycleMode) differs.add("lifecycle")
                if (schema.defaultTraits != server.defaultTraits) differs.add("default_traits")
                if (schema.seats != server.seats) differs.add("seats")
                noteDiff(schema.notes, server.notes)?.let { differs.add(it) }
                out.add(
                    ConfigViolation(
                        pointer("work_item_schemas", name),
                        if (differs.isEmpty()) {
                            "per-root type '$name' shadows an identical server type"
                        } else {
                            "per-root type '$name' shadows the server type; differs in: ${differs.joinToString(", ")}"
                        }
                    )
                )
            }

            // L5 shadowed trait.
            traits.forEach { (name, notes) ->
                if (name !in serverDocument.traits) return@forEach
                val differs = mutableListOf<String>()
                noteDiff(notes, serverDocument.traits[name].orEmpty())?.let { differs.add(it) }
                if (document.traitSeats[name].orEmpty() != serverDocument.traitSeats[name].orEmpty()) differs.add("seats")
                if (document.traitDispatch[name].orEmpty() != serverDocument.traitDispatch[name].orEmpty() ||
                    document.traitDispatchBySeat[name].orEmpty() != serverDocument.traitDispatchBySeat[name].orEmpty()
                ) {
                    differs.add("dispatch")
                }
                if (document.traitResources[name].orEmpty() != serverDocument.traitResources[name].orEmpty()) differs.add("resources")
                val mine = document.traitDescriptors[name]
                val theirs = serverDocument.traitDescriptors[name]
                if (mine?.description != theirs?.description) differs.add("description")
                if (mine?.appliesWhen != theirs?.appliesWhen) differs.add("applies_when")
                out.add(
                    ConfigViolation(
                        pointer("traits", name),
                        if (differs.isEmpty()) {
                            "per-root trait '$name' shadows an identical server trait"
                        } else {
                            "per-root trait '$name' shadows the server trait; differs in: ${differs.joinToString(", ")}"
                        }
                    )
                )
            }
        }

        return out.distinct().sortedWith(compareBy({ it.path }, { it.reason }))
    }

    /** `notes (added: ..; removed: ..; changed: ..)` between a per-root and a server note list, or null when equal. */
    private fun noteDiff(
        mine: List<NoteSchemaEntry>,
        server: List<NoteSchemaEntry>,
    ): String? {
        val mineByKey = mine.associateBy { it.key }
        val serverByKey = server.associateBy { it.key }
        val added = mine.map { it.key }.filter { it !in serverByKey }
        val removed = server.map { it.key }.filter { it !in mineByKey }
        val changed = mine.map { it.key }.filter { it in serverByKey && mineByKey[it] != serverByKey[it] }
        val parts =
            listOf("added" to added, "removed" to removed, "changed" to changed)
                .filter { it.second.isNotEmpty() }
                .map { "${it.first}: ${it.second.joinToString(",")}" }
        return if (parts.isEmpty()) null else "notes (${parts.joinToString("; ")})"
    }

    /**
     * Advisory warnings for `default_traits` lists where a trait that does not select a model for a
     * seat precedes a seat-only trait that does. Dispatch resolution lets the model-selecting trait
     * win regardless of order, so the old order is only reported as a migration aid. One warning per
     * (phase, seat), at the later (model-selecting) trait's list index. Trait definitions come
     * per-root first, then the server layer; a duplicate list entry counts at its first position.
     */
    private fun traitOrderWarnings(
        document: ConfigDocument,
        root: Map<String, Any?>,
        scope: ConfigScope,
        serverLayer: ConfigLayer?,
    ): List<ConfigViolation> {
        val serverDocument = if (scope == ConfigScope.SERVER) document else serverLayer?.document

        fun sourceFor(trait: String): ConfigDocument? =
            if (trait in document.traits) document else serverDocument?.takeIf { trait in it.traits }

        val out = mutableListOf<ConfigViolation>()
        val schemas = root["work_item_schemas"] as? Map<*, *> ?: return out
        schemas.forEach { (schemaName, schemaValue) ->
            val defaults = (schemaValue as? Map<*, *>)?.get("default_traits") as? List<*> ?: return@forEach
            val entries = mutableListOf<Pair<Int, String>>()
            defaults.forEachIndexed { index, trait ->
                if (trait is String && entries.none { it.second == trait }) entries.add(index to trait)
            }
            // (phase, seat) -> first trait declaring an override there, and whether that one selects a model
            val first = LinkedHashMap<Pair<Role, String>, Pair<String, Boolean>>()
            val warned = HashSet<Pair<Role, String>>()
            for ((index, trait) in entries) {
                val source = sourceFor(trait) ?: continue
                val bySeat = source.traitDispatchBySeat[trait].orEmpty()
                if (bySeat.isEmpty()) continue
                val seatOnly = source.traitDispatch[trait].isNullOrEmpty()
                for ((role, seatMap) in bySeat) {
                    for ((seat, override) in seatMap) {
                        val key = role to seat
                        val selecting = seatOnly && TraitResolver.isModelSelecting(override)
                        val earlier = first[key]
                        if (earlier == null) {
                            first[key] = trait to selecting
                        } else if (selecting && !earlier.second && warned.add(key)) {
                            out.add(
                                ConfigViolation(
                                    pointer("work_item_schemas", schemaName.toString(), "default_traits", index.toString()),
                                    "trait '$trait' selects a model for ${role.name.lowercase()} seat '$seat' but follows " +
                                        "'${earlier.first}' in default_traits; the model-selecting trait now wins " +
                                        "regardless of order, so this order is deprecated (migration aid)",
                                )
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    private fun semantic(
        document: ConfigDocument,
        root: Map<String, Any?>,
        scope: ConfigScope,
        serverLayer: ConfigLayer?,
    ): List<ConfigViolation> {
        val out = mutableListOf<ConfigViolation>()

        if (scope == ConfigScope.ROOT) {
            for (key in SERVER_ONLY_KEYS) {
                if (root.containsKey(key)) {
                    out.add(ConfigViolation(pointer(key), "'$key' is server-only and cannot be set in a per-root config"))
                }
            }
        }

        if (root.containsKey("work_item_schemas") && root.containsKey("note_schemas")) {
            out.add(
                ConfigViolation(
                    pointer("note_schemas"),
                    "'note_schemas' cannot be combined with 'work_item_schemas'; use 'work_item_schemas' only",
                )
            )
        }

        val serverDocument = if (scope == ConfigScope.SERVER) document else serverLayer?.document
        val knownTraits = document.traits.keys + (serverDocument?.traits?.keys ?: emptySet())
        val knownResources = serverDocument?.resourceRegistry?.keys ?: emptySet()

        val schemas = root["work_item_schemas"] as? Map<*, *>
        schemas?.forEach { (schemaName, schemaValue) -> out.addAll(lifecycleFreeViolations(schemaName.toString(), schemaValue)) }
        schemas?.forEach { (schemaName, schemaValue) ->
            val defaults = (schemaValue as? Map<*, *>)?.get("default_traits") as? List<*> ?: return@forEach
            defaults.forEachIndexed { index, trait ->
                if (trait is String && trait !in knownTraits) {
                    out.add(
                        ConfigViolation(
                            pointer("work_item_schemas", schemaName.toString(), "default_traits", index.toString()),
                            "default trait '$trait' is not defined in this document or in the server config",
                        )
                    )
                }
            }
        }

        if (root.containsKey("runs")) out.addAll(runsValueViolations(root["runs"]))
        if (root.containsKey("decision_classes")) out.addAll(decisionClassesValueViolations(root["decision_classes"]))

        val traits = root["traits"] as? Map<*, *>
        traits?.forEach { (traitName, traitValue) ->
            val resources = (traitValue as? Map<*, *>)?.get("resources") as? List<*> ?: return@forEach
            resources.forEachIndexed { index, entry ->
                val base = arrayOf("traits", traitName.toString(), "resources", index.toString())
                val (key, keyPath) =
                    when (entry) {
                        is String -> entry to base
                        is Map<*, *> -> (entry["key"] as? String) to base + "key"
                        else -> null to base
                    }
                if (key != null && key !in knownResources) {
                    out.add(
                        ConfigViolation(
                            pointer(*keyPath),
                            "resource '$key' is not declared in the server resource registry",
                        )
                    )
                }
            }
        }
        return out
    }

    /**
     * The lifecycle-free rule for the raw schema [schemaValue] named [schemaName]: when its `lifecycle` reads as
     * [LifecycleMode.NONE], a non-empty `seats` list is an error at `.../seats` and every note with `required: true` is
     * an error at `.../notes/<i>/required`. Any other lifecycle yields nothing.
     */
    private fun lifecycleFreeViolations(
        schemaName: String,
        schemaValue: Any?,
    ): List<ConfigViolation> {
        val schema = schemaValue as? Map<*, *> ?: return emptyList()
        val lifecycle = (schema["lifecycle"] as? String)?.let { LifecycleMode.fromString(it) }
        if (lifecycle != LifecycleMode.NONE) return emptyList()
        val out = mutableListOf<ConfigViolation>()
        val seats = schema["seats"]
        if (seats != null && !(seats is List<*> && seats.isEmpty())) {
            out.add(
                ConfigViolation(
                    pointer("work_item_schemas", schemaName, "seats"),
                    "a 'lifecycle: none' schema cannot declare seats (a lifecycle-free item has no phases)",
                )
            )
        }
        (schema["notes"] as? List<*>)?.forEachIndexed { index, note ->
            if ((note as? Map<*, *>)?.get("required") == true) {
                out.add(
                    ConfigViolation(
                        pointer("work_item_schemas", schemaName, "notes", index.toString(), "required"),
                        "a 'lifecycle: none' schema cannot declare a required note (no gate runs on a lifecycle-free item)",
                    )
                )
            }
        }
        return out
    }

    companion object {
        private val SERVER_ONLY_KEYS = listOf("resources")

        /** The `runs.budgets` ceilings that are checked: both are integers. `tokens` and `cost_usd` were removed in 4.0.1. */
        private val RUN_INTEGER_CEILINGS = listOf("attempts_per_seat", "max_active_seats")

        /**
         * The value rules of the top-level `runs:` block [raw]: it is a mapping; `stall_after` is an integer greater
         * than 0; `budgets` is a mapping whose ceilings are each greater than 0 (`attempts_per_seat` and
         * `max_active_seats`, integers; the removed `tokens` and `cost_usd` keys are not checked). Unknown keys are not reported here (the
         * parser and the structural schema report them). The parser reports the same findings with the same paths and
         * reasons, so a strict validation lists each once.
         */
        fun runsValueViolations(raw: Any?): List<ConfigViolation> {
            val runs = raw as? Map<*, *> ?: return listOf(ConfigViolation(pointer("runs"), "runs must be a mapping"))
            val out = mutableListOf<ConfigViolation>()
            if (runs.containsKey("stall_after") && !isPositiveInteger(runs["stall_after"], Int.MAX_VALUE.toLong())) {
                out.add(ConfigViolation(pointer("runs", "stall_after"), "stall_after must be an integer greater than 0 (seconds)"))
            }
            if (runs.containsKey("budgets")) {
                val budgets = runs["budgets"] as? Map<*, *>
                if (budgets == null) {
                    out.add(ConfigViolation(pointer("runs", "budgets"), "budgets must be a mapping"))
                } else {
                    for (key in RUN_INTEGER_CEILINGS) {
                        if (!budgets.containsKey(key)) continue
                        if (!isPositiveInteger(budgets[key], Int.MAX_VALUE.toLong())) {
                            out.add(ConfigViolation(pointer("runs", "budgets", key), "$key must be an integer greater than 0"))
                        }
                    }
                }
            }
            return out
        }

        /** The `answerers` values of a decision class. */
        private val DECISION_ANSWERERS = listOf("human-only", "director-or-human", "any-actor")

        /** The `severity` values of a decision class. */
        private val DECISION_SEVERITIES = listOf("high", "normal", "low")

        /** The `on_expiry` values of a decision class. */
        private val DECISION_ON_EXPIRY = listOf("escalate")

        /** The classes whose answerers can be raised but never lowered to `any-actor`. */
        private val DECISION_NEVER_ANY_ACTOR = setOf("run-budget", "attempt-cap")

        /** A decision class name. */
        private val DECISION_CLASS_NAME = Regex("^[a-z][a-z0-9_-]{0,63}$")

        /**
         * The value rules of the top-level `decision_classes:` block [raw] (allowed in either scope): it is a mapping of
         * class names (`^[a-z][a-z0-9_-]{0,63}$`) to mappings; `answerers` is human-only, director-or-human or any-actor,
         * and never any-actor for `run-budget` or `attempt-cap`; `severity` is high, normal or low; `expires_after` is an
         * integer greater than 0 (seconds); `on_expiry` is escalate; `freeform` is a boolean. Unknown keys are not
         * reported here (the parser and the structural schema report them). The parser reports the same findings with
         * the same paths and reasons, so a strict validation lists each once.
         */
        fun decisionClassesValueViolations(raw: Any?): List<ConfigViolation> {
            val classes =
                raw as? Map<*, *>
                    ?: return listOf(ConfigViolation(pointer("decision_classes"), "decision_classes must be a mapping"))
            val out = mutableListOf<ConfigViolation>()
            for ((rawName, rawEntry) in classes) {
                val name = rawName.toString()
                if (!DECISION_CLASS_NAME.matches(name)) {
                    out.add(
                        ConfigViolation(
                            pointer("decision_classes", name),
                            "a decision class name must match ${DECISION_CLASS_NAME.pattern}"
                        )
                    )
                    continue
                }
                val entry = rawEntry as? Map<*, *>
                if (entry == null) {
                    out.add(ConfigViolation(pointer("decision_classes", name), "a decision class must be a mapping"))
                    continue
                }
                if (entry.containsKey("answerers")) {
                    val answerers = entry["answerers"]
                    if (answerers !is String || answerers !in DECISION_ANSWERERS) {
                        out.add(
                            ConfigViolation(
                                pointer("decision_classes", name, "answerers"),
                                "answerers must be one of: ${DECISION_ANSWERERS.joinToString(", ")}"
                            )
                        )
                    } else if (answerers == "any-actor" && name in DECISION_NEVER_ANY_ACTOR) {
                        out.add(
                            ConfigViolation(
                                pointer("decision_classes", name, "answerers"),
                                "cannot be lowered below director-or-human"
                            )
                        )
                    }
                }
                if (entry.containsKey("severity") && entry["severity"] !in DECISION_SEVERITIES) {
                    out.add(
                        ConfigViolation(
                            pointer("decision_classes", name, "severity"),
                            "severity must be one of: ${DECISION_SEVERITIES.joinToString(", ")}"
                        )
                    )
                }
                if (entry.containsKey("expires_after") && !isPositiveInteger(entry["expires_after"], Int.MAX_VALUE.toLong())) {
                    out.add(
                        ConfigViolation(
                            pointer("decision_classes", name, "expires_after"),
                            "expires_after must be an integer greater than 0 (seconds)"
                        )
                    )
                }
                if (entry.containsKey("on_expiry") && entry["on_expiry"] !in DECISION_ON_EXPIRY) {
                    out.add(ConfigViolation(pointer("decision_classes", name, "on_expiry"), "on_expiry must be escalate"))
                }
                if (entry.containsKey("freeform") && entry["freeform"] !is Boolean) {
                    out.add(ConfigViolation(pointer("decision_classes", name, "freeform"), "freeform must be true or false"))
                }
            }
            return out
        }

        /** True when [value] is a whole number in `1..max` (a YAML integer: Int, Long or BigInteger). */
        fun isPositiveInteger(
            value: Any?,
            max: Long
        ): Boolean =
            when (value) {
                is Int, is Long, is Short, is Byte -> (value as Number).toLong() in 1..max
                is java.math.BigInteger -> value.signum() > 0 && value.bitLength() < 64 && value.toLong() <= max
                else -> false
            }

        /** Builds an RFC 6901 JSON Pointer from unescaped path [segments]. */
        fun pointer(vararg segments: String): String = segments.joinToString("") { "/" + escape(it) }

        /** Escapes one JSON Pointer reference token: `~` becomes `~0` and `/` becomes `~1`, in that order. */
        fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")
    }
}

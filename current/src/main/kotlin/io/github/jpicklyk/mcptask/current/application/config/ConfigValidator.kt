package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode

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
 * Semantic rules: a per-root document must not carry the server-only keys `resources` or `actor_authentication`;
 * `work_item_schemas` and the legacy `note_schemas` cannot be combined; every `default_traits` entry must name a
 * trait defined in the document or in the server layer; every trait `resources[].key` must be declared in the server
 * resource registry; a `lifecycle: none` schema declares no `seats` of its own and no `required: true` note (a
 * lifecycle-free item has no phase, so no gate could ever enforce either); the `runs:` block (allowed in either scope)
 * follows [runsValueViolations] and the `decision_classes:` block (either scope) [decisionClassesValueViolations]. For
 * a [ConfigScope.SERVER] document the
 * document is the server layer, so it is checked against
 * itself. [serverLayer] is the server-wide config (today the global file); with none, a per-root document sees no
 * server traits and an empty registry.
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
        }

        val order = compareBy<ConfigViolation>({ it.path }, { it.reason })
        return ConfigValidationResult(
            errors = errors.values.sortedWith(order),
            warnings = document.warningViolations.distinct().sortedWith(order),
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
        private val SERVER_ONLY_KEYS = listOf("resources", "actor_authentication")

        /** The integer-valued `runs.budgets` ceilings; `cost_usd` is the one decimal ceiling. */
        private val RUN_INTEGER_CEILINGS = listOf("tokens", "attempts_per_seat", "max_active_seats")
        private const val RUN_COST_CEILING = "cost_usd"

        /**
         * The value rules of the top-level `runs:` block [raw]: it is a mapping; `stall_after` is an integer greater
         * than 0; `budgets` is a mapping whose ceilings are each greater than 0 (`tokens`, `attempts_per_seat` and
         * `max_active_seats` integers, `cost_usd` a number in US dollars). Unknown keys are not reported here (the
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
                        val max = if (key == "tokens") Long.MAX_VALUE else Int.MAX_VALUE.toLong()
                        if (!isPositiveInteger(budgets[key], max)) {
                            out.add(ConfigViolation(pointer("runs", "budgets", key), "$key must be an integer greater than 0"))
                        }
                    }
                    if (budgets.containsKey(RUN_COST_CEILING) && !isPositiveCost(budgets[RUN_COST_CEILING])) {
                        out.add(
                            ConfigViolation(
                                pointer("runs", "budgets", RUN_COST_CEILING),
                                "$RUN_COST_CEILING must be a number greater than 0 (US dollars)"
                            )
                        )
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

        /** True when [value] is a finite number of US dollars greater than 0 that converts to at least one micro-USD. */
        fun isPositiveCost(value: Any?): Boolean = costMicroUsd(value)?.let { it > 0 } == true

        /** [value] (US dollars) in micro-USD, rounded half-up, or null when it is not a finite number in the Long range. */
        fun costMicroUsd(value: Any?): Long? {
            if (value !is Number || value is Byte) return null
            if (value is Double && !value.isFinite()) return null
            if (value is Float && !value.isFinite()) return null
            return try {
                java.math
                    .BigDecimal(value.toString())
                    .movePointRight(6)
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact()
            } catch (e: ArithmeticException) {
                null
            } catch (e: NumberFormatException) {
                null
            }
        }

        /** Builds an RFC 6901 JSON Pointer from unescaped path [segments]. */
        fun pointer(vararg segments: String): String = segments.joinToString("") { "/" + escape(it) }

        /** Escapes one JSON Pointer reference token: `~` becomes `~0` and `/` becomes `~1`, in that order. */
        fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")
    }
}

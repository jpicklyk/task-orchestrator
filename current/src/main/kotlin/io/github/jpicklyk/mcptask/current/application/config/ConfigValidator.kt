package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope

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
 * resource registry. For a [ConfigScope.SERVER] document the document is the server layer, so it is checked against
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

    companion object {
        private val SERVER_ONLY_KEYS = listOf("resources", "actor_authentication")

        /** Builds an RFC 6901 JSON Pointer from unescaped path [segments]. */
        fun pointer(vararg segments: String): String = segments.joinToString("") { "/" + escape(it) }

        /** Escapes one JSON Pointer reference token: `~` becomes `~0` and `/` becomes `~1`, in that order. */
        fun escape(segment: String): String = segment.replace("~", "~0").replace("/", "~1")
    }
}

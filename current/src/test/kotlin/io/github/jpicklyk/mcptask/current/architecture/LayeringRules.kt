package io.github.jpicklyk.mcptask.current.architecture

/**
 * The layering rule engine behind [LayeringTest], kept pure (a file's path, its import names and its text in; violations
 * out) so [LayeringRulesTest] can prove each rule on synthetic input without a Konsist scan.
 *
 * Layer model (CONTRIBUTING.md, CLAUDE.md): `domain -> application -> infrastructure -> interfaces`; an outer layer may
 * depend on an inner one, the reverse is forbidden. The shared `model` tree is innermost, beside `domain`.
 *
 * - R1: `domain` and `model` (the shared model) must not import from `application`, `infrastructure` or `interfaces`.
 * - R2: `application` must not import from `infrastructure` or `interfaces`.
 * - R3: `infrastructure` must not import from `interfaces`.
 * - R4: `domain` and `application` must not import the transport frameworks, `io.modelcontextprotocol` or `io.ktor`
 *   (infrastructure keeps its Ktor HTTP client; interfaces is the transport layer).
 * - R5: no layer (`domain`, `application`, `infrastructure`, `interfaces`) may import the composition root package
 *   `bootstrap`; only the root package (`CurrentMain.kt`) and `bootstrap` itself may.
 *
 * `infrastructure -> application` IS allowed (adapters implement application ports) and `interfaces -> anything` is
 * unrestricted apart from R5. Paths outside the four layers (the root package, `bootstrap/`) are not checked.
 *
 * Interim, until the package reorganisation retires it: a folder under `platform/` counts as the legacy layer named in
 * [PLATFORM_FOLDER_LAYER], and a file under `platform/` whose folder has no row there is itself a violation, so no
 * `platform/` folder escapes R1-R5. `domain` and `model` forbid all of `platform`; `application` forbids every
 * `platform` folder that counts as `infrastructure` or `interfaces`; `infrastructure` forbids those that count as
 * `interfaces`.
 *
 * A rule is broken by an import or by a fully-qualified reference on a CODE line (an FQN needs no import). Comment lines
 * (trimmed lines starting with a line-comment marker, a block-comment opener or an asterisk, which covers KDoc bodies) are not code and are skipped.
 */
object LayeringRules {
    const val CURRENT_ROOT = "io.github.jpicklyk.mcptask.current"
    private const val DOMAIN = "$CURRENT_ROOT.domain"
    private const val APPLICATION = "$CURRENT_ROOT.application"
    private const val INFRASTRUCTURE = "$CURRENT_ROOT.infrastructure"
    private const val INTERFACES = "$CURRENT_ROOT.interfaces"
    private const val BOOTSTRAP = "$CURRENT_ROOT.bootstrap"
    private const val PLATFORM = "$CURRENT_ROOT.platform"

    /** Interim table: each `platform/` folder and the legacy layer it still counts as; a step that adds a folder adds its row. */
    private val PLATFORM_FOLDER_LAYER = mapOf("sqlite" to "infrastructure")
    private val TRANSPORTS = listOf("io.modelcontextprotocol", "io.ktor")

    /** One forbidden dependency: the file [path] (relative to the `current/` source root) reaches [importedFqName]. */
    data class Violation(
        val path: String,
        val importedFqName: String
    ) {
        /** Baseline key: forward-slash-normalized path + " -> " + imported FQN. */
        fun key(): String = "${path.replace('\\', '/')} -> $importedFqName"
    }

    /** The result of comparing the found violations with the baseline: both sets empty means the ratchet holds. */
    data class BaselineDiff(
        /** Violations the baseline does not list: new, forbidden. */
        val unbaselined: Set<String>,
        /** Baseline lines no real violation matches any more: fixed, delete the line. */
        val stale: Set<String>
    ) {
        val clean: Boolean get() = unbaselined.isEmpty() && stale.isEmpty()
    }

    private fun platformFoldersCountingAs(vararg layers: String): List<String> =
        PLATFORM_FOLDER_LAYER.filterValues { it in layers }.keys.map { "$PLATFORM.$it" }

    /** The `platform/` folder of [path] (the segment after `platform/`), or `""` for a file directly under it; null outside `platform/`. */
    private fun platformFolderOf(path: String): String? {
        val parts = path.replace('\\', '/').split('/')
        if (parts.first() != "platform") return null
        return if (parts.size > 2) parts[1] else ""
    }

    /** The layer a file at [path] belongs to: its first folder, or for `platform/<folder>/` the layer its folder row names. */
    private fun layerOf(path: String): String? {
        val p = path.replace('\\', '/')
        val platformFolder = platformFolderOf(p)
        return if (platformFolder != null) PLATFORM_FOLDER_LAYER[platformFolder] else p.substringBefore('/', "")
    }

    /** The package prefixes a file at [path] (relative to the `current/` source root) must not reach. */
    fun forbiddenPrefixesFor(path: String): List<String> =
        when (layerOf(path)) {
            "domain", "model" -> listOf(APPLICATION, INFRASTRUCTURE, INTERFACES, BOOTSTRAP, PLATFORM) + TRANSPORTS
            "application" ->
                listOf(INFRASTRUCTURE, INTERFACES, BOOTSTRAP) + platformFoldersCountingAs("infrastructure", "interfaces") + TRANSPORTS
            "infrastructure" -> listOf(INTERFACES, BOOTSTRAP) + platformFoldersCountingAs("interfaces")
            "interfaces" -> listOf(BOOTSTRAP)
            else -> emptyList()
        }

    /** True for a line that is executable code rather than a comment or KDoc line. */
    fun isCodeLine(rawLine: String): Boolean {
        val trimmed = rawLine.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.startsWith("//") &&
            !trimmed.startsWith("/*") &&
            !trimmed.startsWith("*")
    }

    /** Every violation of R1-R5 in one file: its [imports] (FQ names) and the FQN references on the code lines of [text]. */
    fun violations(
        path: String,
        imports: List<String>,
        text: String
    ): List<Violation> {
        val forbidden = forbiddenPrefixesFor(path)
        val found = mutableListOf<Violation>()
        platformFolderOf(path)?.let { folder ->
            if (folder !in PLATFORM_FOLDER_LAYER) found += Violation(path, "$PLATFORM.${folder.ifEmpty { "<root>" }} has no layer row")
        }
        if (forbidden.isEmpty()) return found
        imports.forEach { name ->
            if (forbidden.any { name == it || name.startsWith("$it.") }) found += Violation(path, name)
        }
        text.lineSequence().filter { isCodeLine(it) && !it.trim().startsWith("import ") }.forEach { line ->
            forbidden.forEach { prefix ->
                if (line.contains(prefix)) {
                    Regex("(?<![A-Za-z0-9_.])" + Regex.escape(prefix) + "(\\.[A-Za-z0-9_]+)+").findAll(line).forEach { match ->
                        found += Violation(path, match.value)
                    }
                }
            }
        }
        return found
    }

    /** Baseline lines from the raw file text: trimmed, blank lines and `#` comments dropped. */
    fun parseBaseline(lines: List<String>): Set<String> =
        lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    /** The two-way ratchet: what is found but not listed, and what is listed but no longer found. */
    fun diff(
        violationKeys: Set<String>,
        baseline: Set<String>
    ): BaselineDiff = BaselineDiff(unbaselined = violationKeys - baseline, stale = baseline - violationKeys)
}

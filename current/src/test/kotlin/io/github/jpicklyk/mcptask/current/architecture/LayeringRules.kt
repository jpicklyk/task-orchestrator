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
 * Slices (the slice-first layout of plans/application-package-reorg.md): a path whose first folder is not a legacy layer or
 * a shared area ([GuardSupport.RESERVED_FIRST_SEGMENTS]) is a slice `<s>/`. `<s>/F.kt` is [SLICE_TOP], what another slice or
 * the platform names; `<s>/internal/{domain,app,sqlite,wire}/..` counts as `domain`, `application`, `infrastructure` and
 * `interfaces` respectively; any other path under `<s>/` is itself a violation (slice shape), like a `platform/` folder with no
 * row. A reference to `<root>.<s>.internal.<l>..` takes the layer of `<l>` and `<root>.<s>.X` is slice-top (only for a slice
 * `<s>` in the `slices` set the caller passes, the first folders of the production files, so a look-alike package such as
 * `applicationx` is no slice). The layer rules above then also hold for slice references, whichever slice they name,
 * the file's own included: a file counting as `model` or `domain` (a slice's `internal/domain/` too) forbids slice-top and the
 * slice layers `application`, `infrastructure` and `interfaces`, but not slice `domain`; `application` forbids slice
 * `infrastructure` and `interfaces`; `infrastructure` forbids slice `interfaces`; and a slice-top file forbids only
 * `bootstrap` and the transports.
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
    private val PLATFORM_FOLDER_LAYER =
        mapOf(
            "sqlite" to "infrastructure",
            "port" to "application",
            "call" to "application",
            "calltelemetry" to "application",
            "eventrecording" to "application",
            "idempotency" to "application",
            "support" to "application",
            "settings" to "infrastructure",
            "yaml" to "infrastructure",
            "retention" to "infrastructure",
            "health" to "infrastructure",
            "logging" to "infrastructure",
            "security" to "infrastructure",
            "shutdown" to "infrastructure",
            "time" to "infrastructure",
            "request" to "application",
            "tool" to "interfaces",
            "envelope" to "interfaces",
            "rest" to "interfaces"
        )
    private val TRANSPORTS = listOf("io.modelcontextprotocol", "io.ktor")

    /** The layer of a slice's top package: what another slice or the platform names. */
    const val SLICE_TOP = "slice-top"

    /** The layer folders allowed under `<s>/internal/` and the layer each counts as. */
    private val SLICE_INTERNAL_LAYER =
        mapOf("domain" to "domain", "app" to "application", "sqlite" to "infrastructure", "wire" to "interfaces")

    /** The slice layers a file of each layer must not reach, in any slice including its own (see the class KDoc). */
    private val FORBIDDEN_SLICE_LAYERS =
        mapOf(
            "domain" to setOf(SLICE_TOP, "application", "infrastructure", "interfaces"),
            "model" to setOf(SLICE_TOP, "application", "infrastructure", "interfaces"),
            "application" to setOf("infrastructure", "interfaces"),
            "infrastructure" to setOf("interfaces")
        )
    private val SLICE_FQN = Regex("(?<![A-Za-z0-9_.])" + Regex.escape("$CURRENT_ROOT.") + "([a-z][A-Za-z0-9_]*)((?:\\.[A-Za-z0-9_]+)+)")

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

    private fun platformFoldersCountingAs(
        folderLayer: Map<String, String>,
        vararg layers: String
    ): List<String> = folderLayer.filterValues { it in layers }.keys.map { "$PLATFORM.$it" }

    /** The `platform/` folder of [path] (the segment after `platform/`), or `""` for a file directly under it; null outside `platform/`. */
    private fun platformFolderOf(path: String): String? {
        val parts = path.replace('\\', '/').split('/')
        if (parts.first() != "platform") return null
        return if (parts.size > 2) parts[1] else ""
    }

    /** The layer of a slice file at [path]: [SLICE_TOP], the layer of its `internal/<l>/` folder, or null for a path of no valid slice shape. */
    private fun sliceLayerOf(path: String): String? {
        val parts = path.split('/')
        return when {
            parts.size == 2 -> SLICE_TOP
            parts.size >= 4 && parts[1] == "internal" -> SLICE_INTERNAL_LAYER[parts[2]]
            else -> null
        }
    }

    /**
     * The layer of the slice class [fqn] names, or null when it names no slice. `<root>.<s>.internal.<l>.X` takes the layer
     * of `<l>`; any other `<root>.<s>.X` is [SLICE_TOP]. `<s>` must be in [slices] and be followed by a segment.
     */
    private fun sliceLayerOfFqn(
        fqn: String,
        slices: Set<String>
    ): String? {
        if (!fqn.startsWith("$CURRENT_ROOT.")) return null
        val parts = fqn.removePrefix("$CURRENT_ROOT.").split('.')
        if (parts.size < 2 || parts[0] !in slices) return null
        return if (parts[1] == "internal" && parts.size >= 4) SLICE_INTERNAL_LAYER[parts[2]] ?: SLICE_TOP else SLICE_TOP
    }

    /** The layer a file at [path] belongs to: a slice's top or internal layer, its first folder, or for `platform/<folder>/` the layer its folder row names. */
    fun layerOf(
        path: String,
        folderLayer: Map<String, String> = PLATFORM_FOLDER_LAYER
    ): String? {
        val p = path.replace('\\', '/')
        if (GuardSupport.sliceOf(p) != null) return sliceLayerOf(p)
        val platformFolder = platformFolderOf(p)
        return if (platformFolder != null) folderLayer[platformFolder] else p.substringBefore('/', "")
    }

    /**
     * The package prefixes a file at [path] (relative to the `current/` source root) must not reach. [folderLayer] is the
     * `platform/` folder table, injectable so a test can prove a row the shipped table does not hold yet.
     */
    fun forbiddenPrefixesFor(
        path: String,
        folderLayer: Map<String, String> = PLATFORM_FOLDER_LAYER
    ): List<String> =
        when (layerOf(path, folderLayer)) {
            "domain", "model" -> listOf(APPLICATION, INFRASTRUCTURE, INTERFACES, BOOTSTRAP, PLATFORM) + TRANSPORTS
            "application" ->
                listOf(INFRASTRUCTURE, INTERFACES, BOOTSTRAP) +
                    platformFoldersCountingAs(folderLayer, "infrastructure", "interfaces") + TRANSPORTS
            "infrastructure" -> listOf(INTERFACES, BOOTSTRAP) + platformFoldersCountingAs(folderLayer, "interfaces")
            "interfaces" -> listOf(BOOTSTRAP)
            SLICE_TOP -> listOf(BOOTSTRAP) + TRANSPORTS
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

    /**
     * Every violation of R1-R5 in one file: its [imports] (FQ names) and the FQN references on the code lines of [text].
     * [slices] are the slices of the tree (the first folders of its production files); a reference names a slice only if its
     * first segment is in it.
     */
    fun violations(
        path: String,
        imports: List<String>,
        text: String,
        slices: Set<String>
    ): List<Violation> {
        val forbidden = forbiddenPrefixesFor(path)
        val forbiddenSlice = FORBIDDEN_SLICE_LAYERS[layerOf(path)].orEmpty()
        val found = mutableListOf<Violation>()
        platformFolderOf(path)?.let { folder ->
            if (folder !in PLATFORM_FOLDER_LAYER) found += Violation(path, "$PLATFORM.${folder.ifEmpty { "<root>" }} has no layer row")
        }
        GuardSupport.sliceOf(path)?.let { slice ->
            if (layerOf(path) == null) {
                found +=
                    Violation(path, "$CURRENT_ROOT.$slice has no valid slice shape (<s>/F.kt or <s>/internal/{domain,app,sqlite,wire}/..)")
            }
        }
        if (forbidden.isEmpty() && forbiddenSlice.isEmpty()) return found
        imports.forEach { name ->
            if (forbidden.any { name == it || name.startsWith("$it.") } || sliceLayerOfFqn(name, slices) in forbiddenSlice) {
                found += Violation(path, name)
            }
        }
        // A file's own `package` line names its own package, never a dependency, so it is skipped like an import line.
        val referenceLines =
            text.lineSequence().filter {
                isCodeLine(it) &&
                    !it.trim().startsWith("import ") &&
                    !it.trim().startsWith("package ")
            }
        referenceLines.forEach { line ->
            forbidden.forEach { prefix ->
                if (line.contains(prefix)) {
                    Regex("(?<![A-Za-z0-9_.])" + Regex.escape(prefix) + "(\\.[A-Za-z0-9_]+)+").findAll(line).forEach { match ->
                        found += Violation(path, match.value)
                    }
                }
            }
            if (forbiddenSlice.isNotEmpty() && line.contains(CURRENT_ROOT)) {
                SLICE_FQN.findAll(line).forEach { match ->
                    if (sliceLayerOfFqn(match.value, slices) in forbiddenSlice) found += Violation(path, match.value)
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

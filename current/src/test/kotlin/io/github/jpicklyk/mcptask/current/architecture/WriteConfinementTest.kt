package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Write-confinement guard (item 0f3eec2d, task-scope Build 1-6 and AC1-AC4, test-plan S1-S11). It replaces the receiver-name
 * regex guards ClaimWriteConfinementTest and StoreWriteConfinementTest, which a scope function, an extension receiver, a
 * store-returning call, a delegation or a callable reference could bypass.
 *
 * The mechanism is the compiler's own type resolution: the confined port methods carry a `@RequiresOptIn(level = ERROR)`
 * marker (`StoreWrite` for the item, note, dependency, config, plan-document, document and link stores; `ClaimWrite` for the
 * claim and lease stores), so a call, override or callable reference through ANY receiver form fails to compile unless the
 * caller opts in. This guard confines where the marker may be named, opted into or suppressed, so the compiler guarantee
 * cannot be quietly widened:
 *  - the marker names (USE as an annotation) appear only in their allow-lists (service and knowledge dirs, the port dir, the
 *    SQLite impl dirs for StoreWrite; ClaimService.kt, the port dir and three SQLite impl files for ClaimWrite);
 *  - an OPT-IN of a marker (`@OptIn`, `@file:OptIn`, `@kotlin.OptIn`, any argument layout) is narrower: StoreWrite only in the
 *    service and knowledge dirs, ClaimWrite only in ClaimService.kt, so a port or SQLite store file can never opt in to its
 *    own marker and unlock an unmarked default or helper method;
 *  - exactly the listed port methods carry their marker (every overload), and no other port method does;
 *  - every production override of a marked method carries the marker itself, never an `@OptIn`;
 *  - no production source suppresses the opt-in diagnostics;
 *  - no production class or object implements a confined port outside the SQLite impl dirs;
 *  - build.gradle.kts opts in to the markers for test compilation only.
 *
 * Zero tolerance, no baseline (AC4): a violation is a finding to route through the owning service, never an allow-list change.
 *
 * Oracle: task-scope Build 1-6 and AC1-AC4 (the Kotlin opt-in rules). The matchers are proven on synthetic sources, each
 * with a positive and a negative control, and the scans on the real tree are proven non-vacuous.
 */
internal class WriteConfinementTest {
    internal class Confined(
        val port: String,
        val marker: String,
        val methods: Set<String>
    )

    internal class Decl(
        val kind: String,
        val name: String,
        val supertypes: List<String>,
        val bodyStart: Int,
        val bodyEnd: Int
    )

    internal class Model(
        val path: String,
        val code: String,
        val decls: List<Decl>
    )

    internal class OverrideReport(
        val problems: List<String>,
        val examined: Int
    )

    internal class Member(
        val name: String,
        val isOverride: Boolean,
        val pre: String
    )

    companion object {
        const val STORE_WRITE = "StoreWrite"
        const val CLAIM_WRITE = "ClaimWrite"
        const val PORT_DIR = "application/port/"
        const val CLAIM_SERVICE = "application/service/ClaimService.kt"

        private val STORE_OWNER_DIRS = listOf("application/service/", "application/knowledge/")
        private val IMPL_DIRS = listOf("infrastructure/sqlite/repository/", "infrastructure/sqlite/knowledge/")
        private val CLAIM_IMPL_FILES =
            setOf(
                "infrastructure/sqlite/repository/SQLiteWorkItemRepository.kt",
                "infrastructure/sqlite/repository/SQLiteResourceLeaseRepository.kt",
                "infrastructure/sqlite/repository/SqliteClaimStore.kt"
            )

        val CONFINED =
            listOf(
                Confined("ItemStore", STORE_WRITE, setOf("create", "update", "delete", "deleteAll")),
                Confined("NoteStore", STORE_WRITE, setOf("upsert", "delete", "deleteByItemId")),
                Confined("DependencyStore", STORE_WRITE, setOf("create", "createBatch", "delete", "deleteByItemId")),
                Confined("ConfigStore", STORE_WRITE, setOf("append", "fillCanonical")),
                Confined("PlanDocumentStore", STORE_WRITE, setOf("stash", "markAdopted")),
                Confined("DocumentStore", STORE_WRITE, setOf("insert", "updateCurrent", "appendVersion", "setArchived", "delete")),
                Confined("LinkStore", STORE_WRITE, setOf("insert", "deleteFrom", "deleteFromAny", "deleteToAny", "deleteByIds", "resolve", "unresolveTo")),
                Confined("ClaimStore", CLAIM_WRITE, setOf("claim", "release", "clear")),
                Confined("LeaseStore", CLAIM_WRITE, setOf("acquireAll", "releaseAllForItem", "releaseAllForItems", "forceReleaseByKey"))
            )

        private val CONFINED_PORTS: Set<String> = CONFINED.map { it.port }.toSet()

        // ---------------------------------------------------------------------------------------------------------
        // Path predicates (S11)
        // ---------------------------------------------------------------------------------------------------------

        private fun norm(path: String): String = GuardSupport.normalizedPath(path)

        fun isImplDir(path: String): Boolean = norm(path).let { p -> IMPL_DIRS.any { p.startsWith(it) } }

        /** Marker USE (a bare annotation on a declaration) and mentions: ports, impl dirs and owner dirs. Opt-ins are narrower. */
        fun storeWriteAllowed(path: String): Boolean {
            val p = norm(path)
            return STORE_OWNER_DIRS.any { p.startsWith(it) } || p.startsWith(PORT_DIR) || IMPL_DIRS.any { p.startsWith(it) }
        }

        fun claimWriteAllowed(path: String): Boolean {
            val p = norm(path)
            return p == CLAIM_SERVICE || p.startsWith(PORT_DIR) || p in CLAIM_IMPL_FILES
        }

        // ---------------------------------------------------------------------------------------------------------
        // Lexer: comments removed, string contents blanked (or kept), char literals neutralised
        // ---------------------------------------------------------------------------------------------------------

        private fun skipString(
            text: String,
            start: Int
        ): Int {
            val n = text.length
            if (text.startsWith("\"\"\"", start)) {
                val end = text.indexOf("\"\"\"", start + 3)
                if (end < 0) return n
                var e = end + 3
                while (e < n && text[e] == '"') e++
                return e
            }
            var j = start + 1
            while (j < n) {
                val c = text[j]
                if (c == '\\') {
                    j += 2
                } else if (c == '"') {
                    return j + 1
                } else if (c == '\n') {
                    return j
                } else if (c == '$' && j + 1 < n && text[j + 1] == '{') {
                    j = skipTemplate(text, j + 1)
                } else {
                    j++
                }
            }
            return n
        }

        private fun skipTemplate(
            text: String,
            start: Int
        ): Int {
            var depth = 0
            var j = start
            while (j < text.length) {
                val c = text[j]
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) return j + 1
                } else if (c == '"') {
                    j = skipString(text, j)
                    continue
                }
                j++
            }
            return text.length
        }

        private fun skipChar(
            text: String,
            i: Int,
            out: StringBuilder
        ): Int {
            if (i + 1 < text.length && text[i + 1] == '\\') {
                val j = text.indexOf('\'', i + 3)
                if (j > 0) {
                    out.append("''")
                    return j + 1
                }
            } else if (i + 2 < text.length && text[i + 2] == '\'') {
                out.append("''")
                return i + 3
            }
            out.append('\'')
            return i + 1
        }

        /** [text] without comments; string contents blanked to `""` unless [keepStrings]; char literals blanked. */
        fun clean(
            text: String,
            keepStrings: Boolean
        ): String {
            val out = StringBuilder(text.length)
            val n = text.length
            var i = 0
            while (i < n) {
                val c = text[i]
                if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                    while (i < n && text[i] != '\n') i++
                } else if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (text.startsWith("/*", i)) {
                            depth++
                            i += 2
                        } else if (text.startsWith("*/", i)) {
                            depth--
                            i += 2
                        } else {
                            if (text[i] == '\n') out.append('\n')
                            i++
                        }
                    }
                } else if (c == '"') {
                    val e = skipString(text, i)
                    if (keepStrings) out.append(text, i, e) else out.append("\"\"")
                    i = e
                } else if (c == '\'') {
                    i = skipChar(text, i, out)
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        // ---------------------------------------------------------------------------------------------------------
        // Declaration model (class / object / interface headers and bodies)
        // ---------------------------------------------------------------------------------------------------------

        private val DECL_KEYWORD = Regex("""(?<![\w.:])(class|object|interface)\b""")
        private val DECL_NAME = Regex("""^\s*(\w+)""")
        private val SUPERTYPE_NAME = Regex("""^\s*([\w.]+)""")
        private val FUN = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(""")
        private val OVERRIDE = Regex("""\boverride\b""")
        private val ANNOTATION_ONLY = Regex("""^(?:@(?:file:)?[\w.]+(?:\([^)]*\))?\s*)+$""")
        private val OPT_IN_NAMING_MARKER = Regex("""@(?:[\w.]*\.)?OptIn\s*\([^)]*\b(?:StoreWrite|ClaimWrite)\b""")

        private fun markerAnnotation(marker: String): Regex = Regex("""@(?:[\w.]*\.)?$marker\b""")

        private fun matchingBrace(
            code: String,
            open: Int
        ): Int {
            var depth = 0
            var i = open
            while (i < code.length) {
                if (code[i] == '{') {
                    depth++
                } else if (code[i] == '}') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return code.length
        }

        private fun continuesHeader(
            code: String,
            from: Int,
            newline: Int
        ): Boolean {
            val last = code.substring(from, newline).trimEnd().lastOrNull()
            var k = newline + 1
            while (k < code.length && code[k].isWhitespace()) k++
            val next = code.getOrNull(k)
            return last == ':' || last == ',' || next == ':' || next == ',' || code.startsWith("where ", k)
        }

        private fun supertypesOf(header: String): List<String> {
            var parens = 0
            var angles = 0
            var colon = -1
            for ((idx, c) in header.withIndex()) {
                if (c == '(') {
                    parens++
                } else if (c == ')') {
                    if (parens > 0) parens--
                } else if (parens == 0 && c == '<') {
                    angles++
                } else if (parens == 0 && c == '>') {
                    if (angles > 0) angles--
                } else if (parens == 0 && angles == 0 && c == ':') {
                    colon = idx
                    break
                }
            }
            if (colon < 0) return emptyList()
            val rest = header.substring(colon + 1).substringBefore(" where ")
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var p = 0
            var a = 0
            for (c in rest) {
                if (c == '(') p++
                if (c == ')' && p > 0) p--
                if (c == '<') a++
                if (c == '>' && a > 0) a--
                if (c == ',' && p == 0 && a == 0) {
                    parts += current.toString()
                    current.clear()
                } else {
                    current.append(c)
                }
            }
            parts += current.toString()
            return parts.mapNotNull {
                SUPERTYPE_NAME
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.substringAfterLast('.')
            }
        }

        fun declarations(code: String): List<Decl> {
            val result = mutableListOf<Decl>()
            for (m in DECL_KEYWORD.findAll(code)) {
                val from = m.range.last + 1
                var i = from
                var parens = 0
                var bodyStart = -1
                while (i < code.length) {
                    val c = code[i]
                    if (c == '(') {
                        parens++
                    } else if (c == ')') {
                        if (parens > 0) parens--
                    } else if (c == '{' && parens == 0) {
                        bodyStart = i
                        break
                    } else if (c == ';' && parens == 0) {
                        break
                    } else if (c == '\n' && parens == 0 && !continuesHeader(code, from, i)) {
                        break
                    }
                    i++
                }
                val header = code.substring(from, i)
                val name =
                    DECL_NAME
                        .find(header)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                val bodyEnd = if (bodyStart >= 0) matchingBrace(code, bodyStart) else -1
                result += Decl(m.groupValues[1], name, supertypesOf(header), bodyStart, bodyEnd)
            }
            return result
        }

        fun model(source: GuardSupport.Source): Model {
            val code = clean(source.text, keepStrings = false)
            return Model(norm(source.path), code, declarations(code))
        }

        /** For a type name, the confined ports among itself and all its transitive supertypes (simple-name resolution). */
        private fun ancestorsResolver(models: List<Model>): (String) -> Set<String> {
            val supers = HashMap<String, MutableSet<String>>()
            models.forEach { m ->
                m.decls.forEach { d -> if (d.name.isNotEmpty()) supers.getOrPut(d.name) { mutableSetOf() }.addAll(d.supertypes) }
            }
            val cache = HashMap<String, Set<String>>()

            fun visit(
                name: String,
                seen: MutableSet<String>,
                acc: MutableSet<String>
            ) {
                if (!seen.add(name)) return
                if (name in CONFINED_PORTS) acc += name
                supers[name]?.forEach { visit(it, seen, acc) }
            }
            return { name -> cache.getOrPut(name) { mutableSetOf<String>().also { visit(name, mutableSetOf(), it) } } }
        }

        private fun innermost(
            decls: List<Decl>,
            pos: Int
        ): Decl? =
            decls
                .filter { it.bodyStart in 0 until pos && pos < it.bodyEnd }
                .minByOrNull { it.bodyEnd - it.bodyStart }

        /** The declaration head (modifiers before `fun` on its line) plus the annotation-only lines directly above it. */
        private fun preamble(
            code: String,
            funStart: Int
        ): String {
            val lineStart = code.lastIndexOf('\n', funStart) + 1
            val sb = StringBuilder(code.substring(lineStart, funStart))
            var end = lineStart
            while (end > 0) {
                val newline = end - 1
                val prevStart = code.lastIndexOf('\n', newline - 1) + 1
                val line = code.substring(prevStart, newline).trim()
                if (line.isEmpty()) {
                    end = prevStart
                } else if (ANNOTATION_ONLY.matches(line)) {
                    sb.append(' ').append(line)
                    end = prevStart
                } else {
                    break
                }
            }
            return sb.toString()
        }

        // ---------------------------------------------------------------------------------------------------------
        // Marker mentions (S1, S6, S10, S11)
        // ---------------------------------------------------------------------------------------------------------

        fun mentions(
            code: String,
            marker: String
        ): Int = Regex("""\b$marker\b""").findAll(code).count()

        /** Marker-name mentions in [text] (any spelling) that sit outside the marker's allow-list for [path]. */
        fun markerViolations(
            path: String,
            text: String
        ): Int {
            val code = clean(text, keepStrings = false)
            var n = 0
            if (!storeWriteAllowed(path)) n += mentions(code, STORE_WRITE)
            if (!claimWriteAllowed(path)) n += mentions(code, CLAIM_WRITE)
            return n
        }

        // ---------------------------------------------------------------------------------------------------------
        // Opt-ins of a marker (S12)
        // ---------------------------------------------------------------------------------------------------------

        private val OPT_IN = Regex("""@(?:file:)?(?:[\w.]*\.)?OptIn\s*\(([^)]*)\)""")

        /** An OPT-IN of StoreWrite is allowed only in the service and knowledge dirs (marker USE: see [storeWriteAllowed]). */
        fun storeWriteOptInAllowed(path: String): Boolean = norm(path).let { p -> STORE_OWNER_DIRS.any { p.startsWith(it) } }

        /** An OPT-IN of ClaimWrite is allowed only in ClaimService.kt (marker USE: see [claimWriteAllowed]). */
        fun claimWriteOptInAllowed(path: String): Boolean = norm(path) == CLAIM_SERVICE

        /**
         * Marker names inside an `@OptIn(...)`, `@file:OptIn(...)` or `@kotlin.OptIn(...)` argument list (any spelling, any line
         * layout) in [text] whose file is not a sanctioned owner of that marker. A bare marker annotation on a declaration is
         * marker USE, judged by [markerViolations] against the wider allow-list, and is not counted here.
         */
        fun optInViolations(
            path: String,
            text: String
        ): Int {
            val code = clean(text, keepStrings = false)
            var n = 0
            for (m in OPT_IN.findAll(code)) {
                val args = m.groupValues[1]
                if (!storeWriteOptInAllowed(path)) n += mentions(args, STORE_WRITE)
                if (!claimWriteOptInAllowed(path)) n += mentions(args, CLAIM_WRITE)
            }
            return n
        }

        // ---------------------------------------------------------------------------------------------------------
        // Opt-in diagnostic suppression (S7)
        // ---------------------------------------------------------------------------------------------------------

        private val SUPPRESS = Regex("""@(?:file:)?(?:[\w.]*\.)?Suppress\s*\(([^)]*)\)""")
        private val OPT_IN_DIAGNOSTICS =
            Regex("""\b(?:OPT_IN_(?:USAGE|OVERRIDE)\w*|EXPERIMENTAL_(?:API_USAGE|OVERRIDE)\w*)\b""")

        /** Opt-in diagnostic ids named inside a `@Suppress(...)` or `@file:Suppress(...)` argument list in [text]. */
        fun suppressionViolations(text: String): Int {
            val code = clean(text, keepStrings = true)
            return SUPPRESS.findAll(code).sumOf { OPT_IN_DIAGNOSTICS.findAll(it.groupValues[1]).count() }
        }

        // ---------------------------------------------------------------------------------------------------------
        // Confined port implementors (S8)
        // ---------------------------------------------------------------------------------------------------------

        /** Every class or object (named or anonymous) whose supertypes reach a confined port, as (path, description). */
        fun confinedImplementors(sources: List<GuardSupport.Source>): List<Pair<String, String>> {
            val models = sources.map { model(it) }
            val ancestors = ancestorsResolver(models)
            return models.flatMap { m ->
                m.decls
                    .filter { it.kind != "interface" && it.supertypes.flatMap(ancestors).isNotEmpty() }
                    .map { m.path to "${it.kind} '${it.name}' implements ${it.supertypes.flatMap(ancestors).toSortedSet()}" }
            }
        }

        fun implementorProblems(sources: List<GuardSupport.Source>): List<String> =
            confinedImplementors(sources).filter { !isImplDir(it.first) }.map { "${it.first}: ${it.second}" }

        // ---------------------------------------------------------------------------------------------------------
        // Port declarations (S2)
        // ---------------------------------------------------------------------------------------------------------

        private fun membersOf(
            m: Model,
            d: Decl
        ): List<Member> =
            FUN
                .findAll(m.code)
                .filter { it.range.first > d.bodyStart && it.range.first < d.bodyEnd }
                .filter { f ->
                    val between = m.code.substring(d.bodyStart + 1, f.range.first)
                    between.count { it == '{' } == between.count { it == '}' }
                }.map { f ->
                    val pre = preamble(m.code, f.range.first)
                    Member(f.groupValues[1], OVERRIDE.containsMatchIn(pre), pre)
                }.toList()

        private fun carries(
            pre: String,
            marker: String
        ): Boolean = markerAnnotation(marker).containsMatchIn(pre)

        private fun carriesAny(pre: String): Boolean = carries(pre, STORE_WRITE) || carries(pre, CLAIM_WRITE)

        fun portProblems(sources: List<GuardSupport.Source>): List<String> {
            val portModels = sources.map { model(it) }.filter { it.path.startsWith(PORT_DIR) }
            val problems = mutableListOf<String>()
            for (c in CONFINED) {
                val other = if (c.marker == STORE_WRITE) CLAIM_WRITE else STORE_WRITE
                val found =
                    portModels.flatMap { m ->
                        m.decls.filter { it.kind == "interface" && it.name == c.port && it.bodyStart >= 0 }.map { m to it }
                    }
                if (found.size != 1) {
                    problems += "${c.port}: expected exactly one declaration under $PORT_DIR, found ${found.size}"
                    continue
                }
                val (m, d) = found.single()
                val byName = membersOf(m, d).filter { !it.isOverride }.groupBy { it.name }
                for (name in c.methods) {
                    val overloads = byName[name]
                    if (overloads == null) {
                        problems += "${c.port}.$name: not declared"
                        continue
                    }
                    overloads.forEach {
                        if (!carries(it.pre, c.marker) || carries(it.pre, other)) {
                            problems += "${c.port}.$name: an overload does not carry exactly @${c.marker}"
                        }
                    }
                }
                byName.filterKeys { it !in c.methods }.forEach { (name, overloads) ->
                    overloads.filter { carriesAny(it.pre) }.forEach { _ ->
                        problems += "${c.port}.$name: unlisted method carries a write marker"
                    }
                }
            }
            for (m in portModels) {
                for (d in m.decls.filter { it.kind == "interface" && it.name !in CONFINED_PORTS && it.bodyStart >= 0 }) {
                    membersOf(m, d).filter { !it.isOverride && carriesAny(it.pre) }.forEach {
                        problems += "${d.name}.${it.name}: method of an unlisted port carries a write marker"
                    }
                }
            }
            return problems
        }

        // ---------------------------------------------------------------------------------------------------------
        // Overrides of confined methods (S3)
        // ---------------------------------------------------------------------------------------------------------

        fun overrideReport(sources: List<GuardSupport.Source>): OverrideReport {
            val models = sources.map { model(it) }
            val ancestors = ancestorsResolver(models)
            val problems = mutableListOf<String>()
            var examined = 0
            for (m in models) {
                for (f in FUN.findAll(m.code)) {
                    val pos = f.range.first
                    val lineStart = m.code.lastIndexOf('\n', pos) + 1
                    if (!OVERRIDE.containsMatchIn(m.code.substring(lineStart, pos))) continue
                    val owner = innermost(m.decls, pos) ?: continue
                    val reached = owner.supertypes.flatMap(ancestors).toSet()
                    if (reached.isEmpty()) continue
                    val name = f.groupValues[1]
                    val entry = CONFINED.firstOrNull { it.port in reached && name in it.methods } ?: continue
                    examined++
                    val pre = preamble(m.code, pos)
                    val site = "${m.path}: ${owner.name.ifEmpty { "<anonymous>" }}.$name"
                    if (OPT_IN_NAMING_MARKER.containsMatchIn(pre)) {
                        problems += "$site: override uses @OptIn instead of carrying @${entry.marker}"
                    } else if (!carries(pre, entry.marker)) {
                        problems += "$site: override is missing @${entry.marker}"
                    }
                }
            }
            return OverrideReport(problems, examined)
        }

        // ---------------------------------------------------------------------------------------------------------
        // Build script (S9)
        // ---------------------------------------------------------------------------------------------------------

        private val TEST_COMPILE_BLOCK = Regex("""\bcompileTestKotlin\b"?\s*\)?\s*\{""")
        private val MARKER_NAME = Regex("""\b(StoreWrite|ClaimWrite)\b""")

        private fun testCompileRanges(code: String): List<IntRange> =
            TEST_COMPILE_BLOCK
                .findAll(code)
                .map { m ->
                    val open = m.range.last
                    open..matchingBrace(code, open)
                }.toList()

        /** Marker names in [script] outside any `compileTestKotlin` configuration block: main compilation must never opt in. */
        fun buildScriptViolations(script: String): Int {
            val code = clean(script, keepStrings = true)
            val ranges = testCompileRanges(code)
            return MARKER_NAME.findAll(code).count { m -> ranges.none { m.range.first in it } }
        }

        /** The marker names the script opts in to inside its `compileTestKotlin` configuration block. */
        fun buildScriptTestOptIns(script: String): Set<String> {
            val code = clean(script, keepStrings = true)
            val ranges = testCompileRanges(code)
            return MARKER_NAME
                .findAll(code)
                .filter { m -> ranges.any { m.range.first in it } }
                .map { it.value }
                .toSet()
        }
    }

    // =========================================================================================================
    // Synthetic-source helpers
    // =========================================================================================================

    private fun src(
        path: String,
        text: String
    ) = GuardSupport.Source(path, text)

    private fun marked(
        c: Confined,
        name: String
    ) = "@${c.marker} suspend fun $name(x: Int): Int"

    private fun unmarked(name: String) = "suspend fun $name(x: Int): Int"

    /** A correct synthetic port tree: one interface per confined port; [member] renders each method (null omits it). */
    private fun portFixture(member: (Confined, String) -> String? = { c, n -> marked(c, n) }): List<GuardSupport.Source> =
        CONFINED.map { c ->
            val body =
                c.methods
                    .sorted()
                    .mapNotNull { member(c, it) }
                    .joinToString("\n") { "    $it" }
            src("application/port/${c.port}.kt", "package x\n\ninterface ${c.port} {\n$body\n}\n")
        }

    private val compositePort =
        src("application/port/WorkItemRepository.kt", "package x\n\ninterface WorkItemRepository : ItemStore, HierarchyStore, ClaimStore\n")

    private val noteRepositoryPort = src("application/port/NoteRepository.kt", "package x\n\ninterface NoteRepository : NoteStore\n")

    private val createOverride = "override suspend fun create(x: Int): Int = x"

    private fun implOf(vararg members: String) = "class Impl : ItemStore {\n" + members.joinToString("\n") { "    $it" } + "\n}"

    private val implPath = "infrastructure/sqlite/repository/SqliteX.kt"

    private val storePortPath = "application/port/ItemStore.kt"
    private val storeImplPath = "infrastructure/sqlite/repository/SqliteItemStore.kt"
    private val claimImplPath = "infrastructure/sqlite/repository/SqliteClaimStore.kt"
    private val portPackage = "io.github.jpicklyk.mcptask.current.application.port"

    // =========================================================================================================
    // Real-tree scans
    // =========================================================================================================

    @Test
    fun `S1 no production file names a write marker outside the allow-lists`() {
        val actual = GuardSupport.countByFile(GuardSupport.productionSources()) { markerViolations(it.path, it.text) }
        assertEquals(emptyMap(), actual, "write marker named outside its allow-list (file to mentions): $actual")
    }

    @Test
    fun `S12 no production file opts in to a write marker outside its owner files`() {
        val actual = GuardSupport.countByFile(GuardSupport.productionSources()) { optInViolations(it.path, it.text) }
        assertEquals(emptyMap(), actual, "write marker opted into outside its owner files (file to opt-ins): $actual")
    }

    @Test
    fun `S1 no production file suppresses the opt-in diagnostics`() {
        val actual = GuardSupport.countByFile(GuardSupport.productionSources()) { suppressionViolations(it.text) }
        assertEquals(emptyMap(), actual, "opt-in diagnostic suppressed in production (file to count): $actual")
    }

    @Test
    fun `S8 no production class or object implements a confined port outside the SQLite implementation directories`() {
        val problems = implementorProblems(GuardSupport.productionSources())
        assertEquals(emptyList(), problems, "confined port implemented outside the impl dirs")
    }

    @Test
    fun `S2 every confined port method carries its marker and no other port method does`() {
        val problems = portProblems(GuardSupport.productionSources())
        assertEquals(emptyList(), problems, "port marker placement")
    }

    @Test
    fun `S3 every production override of a confined method carries the marker and never an OptIn`() {
        val report = overrideReport(GuardSupport.productionSources())
        assertEquals(emptyList(), report.problems, "override marker propagation")
    }

    @Test
    fun `S4 the real-tree scans are not vacuous`() {
        val sources = GuardSupport.productionSources()
        val cleaned = sources.associate { GuardSupport.normalizedPath(it.path) to clean(it.text, keepStrings = false) }

        fun declaresAnnotation(marker: String) =
            cleaned.any { (p, c) -> p.startsWith(PORT_DIR) && Regex("""annotation\s+class\s+$marker\b""").containsMatchIn(c) }
        assertTrue(declaresAnnotation(STORE_WRITE), "control: the StoreWrite annotation class is declared under $PORT_DIR")
        assertTrue(declaresAnnotation(CLAIM_WRITE), "control: the ClaimWrite annotation class is declared under $PORT_DIR")

        val storeUsers = cleaned.filter { (p, c) -> p.startsWith("application/service/") && mentions(c, STORE_WRITE) > 0 }
        assertTrue(storeUsers.size >= 2, "control: service files opt in to StoreWrite; saw ${storeUsers.keys}")
        val claimService = cleaned[CLAIM_SERVICE] ?: error("$CLAIM_SERVICE must exist")
        assertTrue(mentions(claimService, CLAIM_WRITE) > 0, "control: ClaimService.kt names ClaimWrite")

        val report = overrideReport(sources)
        assertTrue(report.examined >= 20, "control: the override scan examined real overrides, saw ${report.examined}")
        val implementors = confinedImplementors(sources).filter { isImplDir(it.first) }
        assertTrue(implementors.size >= 5, "control: the implementor scan sees the SQLite impls, saw ${implementors.map { it.first }}")
    }

    @Test
    fun `S9 the real build script opts in to the markers for test compilation only`() {
        val file = File("build.gradle.kts")
        assertTrue(file.isFile, "fixture: build.gradle.kts is read relative to the Gradle working directory (current/)")
        val script = file.readText()
        assertEquals(0, buildScriptViolations(script), "a marker is named outside the compileTestKotlin configuration block")
        assertEquals(
            setOf(STORE_WRITE, CLAIM_WRITE),
            buildScriptTestOptIns(script),
            "control: both markers are opted in inside compileTestKotlin"
        )
    }

    // =========================================================================================================
    // Matcher self-tests
    // =========================================================================================================

    @Test
    fun `S6 the marker matcher flags every spelling of a marker mention outside the allow-list`() {
        val denied = "application/tools/X.kt"
        val port = "io.github.jpicklyk.mcptask.current.application.port"
        listOf(
            "@OptIn(StoreWrite::class)\nclass A",
            "@file:OptIn(ClaimWrite::class)\npackage x",
            "@StoreWrite fun f() {}",
            "@StoreWrite\nfun f() {}",
            "import $port.StoreWrite",
            "import $port.ClaimWrite as W",
            "typealias W = ClaimWrite",
            "@$port.StoreWrite fun f() {}",
            "@OptIn($port.ClaimWrite::class)\nclass A",
            "@OptIn(\n    StoreWrite::class\n)\nclass A",
            "@OptIn(\n    ClaimWrite::class,\n)\nclass A"
        ).forEach { assertEquals(1, markerViolations(denied, it), "not flagged: $it") }
        val twice = "@OptIn(StoreWrite::class)\n@OptIn(StoreWrite::class)\nclass A"
        assertEquals(2, markerViolations(denied, twice), "duplicate mentions count each")
        assertEquals(2, markerViolations(denied, "@OptIn(StoreWrite::class, ClaimWrite::class)\nclass A"), "both markers count each")
    }

    @Test
    fun `S10 the marker matcher ignores look-alikes comments and strings`() {
        val denied = "application/tools/X.kt"
        listOf(
            "val a = StoreWriter()",
            "val b = ClaimWriteResult.Ok",
            "val storewrite = 1",
            "val c = MyStoreWrite",
            "// @OptIn(StoreWrite::class)",
            "val x = 1 // @OptIn(ClaimWrite::class)",
            "/**\n * [StoreWrite] is mentioned in docs only\n */\nclass A",
            "/* ClaimWrite */ class A",
            "val msg = \"route through StoreWrite owners\"",
            "@OptIn(ExperimentalSerializationApi::class)\nclass A",
            "@Suppress(\"UNCHECKED_CAST\")\nfun f() {}"
        ).forEach { assertEquals(0, markerViolations(denied, it), "wrongly flagged: $it") }
    }

    @Test
    fun `S11 the allow-list predicates match directory and file boundaries`() {
        listOf(
            "application/service/X.kt",
            "application/service/nested/X.kt",
            "application/knowledge/document/DocumentService.kt",
            "application/port/ItemStore.kt",
            "infrastructure/sqlite/repository/SqliteItemStore.kt",
            "infrastructure/sqlite/repository/nested/Deep.kt",
            "infrastructure/sqlite/knowledge/SqliteDocumentStore.kt",
            "infrastructure/sqlite/knowledge/nested/Deep.kt",
            "application\\service\\X.kt"
        ).forEach { assertTrue(storeWriteAllowed(it), "StoreWrite must be allowed in: $it") }
        listOf(
            "application/tools/items/CreateItemHandler.kt",
            "interfaces/api/v1/routes/NoteWriteRoutes.kt",
            "infrastructure/config/PerRootConfigService.kt",
            "infrastructure/sqlite/Other.kt",
            "application/servicex/A.kt",
            "application/portal/A.kt",
            "application/knowledgebase/A.kt",
            "infrastructure/sqlite/repositoryx/A.kt",
            "CurrentMain.kt",
            "application\\tools\\X.kt"
        ).forEach { assertFalse(storeWriteAllowed(it), "StoreWrite must be denied in: $it") }

        listOf(
            CLAIM_SERVICE,
            "application/port/ClaimStore.kt",
            "infrastructure/sqlite/repository/SQLiteWorkItemRepository.kt",
            "infrastructure/sqlite/repository/SQLiteResourceLeaseRepository.kt",
            "infrastructure/sqlite/repository/SqliteClaimStore.kt",
            "application\\service\\ClaimService.kt"
        ).forEach { assertTrue(claimWriteAllowed(it), "ClaimWrite must be allowed in: $it") }
        listOf(
            "application/service/AdvanceService.kt",
            "application/service/ClaimServiceHelper.kt",
            "application/service/nested/ClaimService.kt",
            "application/knowledge/document/DocumentService.kt",
            "infrastructure/sqlite/repository/SqliteItemStore.kt",
            "infrastructure/sqlite/repository/nested/SqliteClaimStore.kt",
            "infrastructure/sqlite/knowledge/SqliteDocumentStore.kt",
            "interfaces/api/v1/routes/ResourceLeaseRoutes.kt",
            "CurrentMain.kt"
        ).forEach { assertFalse(claimWriteAllowed(it), "ClaimWrite must be denied in: $it") }

        val claimOptIn = "@OptIn(ClaimWrite::class)\nclass A"
        assertEquals(1, markerViolations("application/service/AdvanceService.kt", claimOptIn), "ClaimWrite outside ClaimService")
        assertEquals(0, markerViolations(CLAIM_SERVICE, "@OptIn(ClaimWrite::class, StoreWrite::class)\nclass A"), "ClaimService owns both")
        assertEquals(0, markerViolations("application/service/X.kt", "@OptIn(StoreWrite::class)\nclass A"), "services own StoreWrite")
    }

    @Test
    fun `S12a a file opt-in of StoreWrite in a port is a violation`() {
        assertEquals(1, optInViolations(storePortPath, "@file:OptIn(StoreWrite::class)\npackage x"), "(a) file opt-in in a port")
    }

    @Test
    fun `S12b a file opt-in of StoreWrite in a SQLite store implementation is a violation`() {
        assertEquals(1, optInViolations(storeImplPath, "@file:OptIn(StoreWrite::class)\npackage x"), "(b) file opt-in in a store impl")
    }

    @Test
    fun `S12c an opt-in of ClaimWrite in the SQLite claim store is a violation`() {
        assertEquals(1, optInViolations(claimImplPath, "@OptIn(ClaimWrite::class)\nclass A"), "(c) opt-in in the claim store impl")
    }

    @Test
    fun `S12d a multi-line opt-in of StoreWrite in a port is a violation`() {
        assertEquals(1, optInViolations(storePortPath, "@OptIn(\n  StoreWrite::class\n)\ninterface A"), "(d) multi-line opt-in in a port")
    }

    @Test
    fun `S12e an owner file opt-in and a bare marker use in ports and impls stay allowed`() {
        val storePort = storePortPath
        val storeImpl = storeImplPath
        val fileOptIn = "@file:OptIn(StoreWrite::class)\npackage x"
        val portUse = "interface ItemStore {\n    @StoreWrite suspend fun update(x: Int): Int\n}"
        assertEquals(0, optInViolations("application/service/ItemCommandService.kt", fileOptIn), "(e)")
        assertEquals(0, markerViolations(storePort, portUse), "(f) marker use in a port")
        assertEquals(0, optInViolations(storePort, portUse), "(f) marker use is not an opt-in")
        val implUse = "class A {\n    @StoreWrite override suspend fun update(x: Int): Int = x\n}"
        assertEquals(0, optInViolations(storeImpl, implUse), "use in impl")
    }

    @Test
    fun `S12 every spelling of an opt-in is flagged outside the owner files and allowed inside them`() {
        val storePort = storePortPath

        // every spelling is flagged outside the owner files
        listOf(
            "@OptIn(StoreWrite::class)\nclass A",
            "@file:OptIn(StoreWrite::class)\npackage x",
            "@kotlin.OptIn(StoreWrite::class)\nclass A",
            "@OptIn($portPackage.StoreWrite::class)\nclass A",
            "@OptIn(\n    StoreWrite::class,\n)\nclass A",
            "@OptIn(ExperimentalStdlibApi::class, StoreWrite::class)\nclass A",
            "@OptIn(markerClass = [StoreWrite::class])\nclass A"
        ).forEach {
            assertEquals(1, optInViolations("application/tools/X.kt", it), "StoreWrite opt-in not flagged: $it")
            assertEquals(1, optInViolations(storePort, it), "StoreWrite opt-in not flagged in a port: $it")
        }
        val claimOptIn = "@OptIn(ClaimWrite::class)\nclass A"
        val bothOptIn = "@OptIn(ClaimWrite::class, StoreWrite::class)\nclass A"
        assertEquals(1, optInViolations("application/service/AdvanceService.kt", claimOptIn), "ClaimWrite in a service")
        assertEquals(1, optInViolations("application/service/X.kt", bothOptIn), "only ClaimWrite is denied in a service")
        assertEquals(2, optInViolations("application/tools/X.kt", bothOptIn), "both counted")

        // owner files stay green
        listOf(
            "application/service/AdvanceService.kt",
            "application/service/nested/X.kt",
            "application/knowledge/document/DocumentService.kt",
            "application/knowledge/link/LinkService.kt"
        ).forEach { assertEquals(0, optInViolations(it, "@file:OptIn(StoreWrite::class)\npackage x"), "StoreWrite owner: $it") }
        assertEquals(0, optInViolations(CLAIM_SERVICE, bothOptIn), "ClaimService owns both")
        assertEquals(1, optInViolations("application/knowledge/link/LinkService.kt", claimOptIn), "knowledge: no claim")

        // lexer conventions: comments and strings are not opt-ins; unrelated opt-ins are fine
        listOf(
            "// @OptIn(StoreWrite::class)",
            "/**\n * @file:OptIn(StoreWrite::class)\n */\nclass A",
            "val msg = \"@OptIn(StoreWrite::class)\"",
            "@OptIn(ExperimentalSerializationApi::class)\nclass A",
            "@StoreWrite fun f() {}"
        ).forEach { assertEquals(0, optInViolations(storePort, it), "wrongly flagged: $it") }
    }

    @Test
    fun `S7 the suppression matcher flags every spelling of an opt-in diagnostic suppression`() {
        listOf(
            "@Suppress(\"OPT_IN_USAGE_ERROR\")\nclass A",
            "@file:Suppress(\"OPT_IN_USAGE\")\npackage x",
            "@Suppress(\"X\", \"OPT_IN_OVERRIDE_ERROR\")\nfun f() {}",
            "@Suppress(\"OPT_IN_OVERRIDE\")\nfun f() {}",
            "@Suppress(\n    \"UNCHECKED_CAST\",\n    \"OPT_IN_USAGE_ERROR\"\n)\nfun f() {}",
            "@kotlin.Suppress(\"OPT_IN_USAGE_ERROR\")\nfun f() {}",
            "@Suppress(names = [\"OPT_IN_USAGE_ERROR\"])\nfun f() {}",
            "@Suppress(\"EXPERIMENTAL_API_USAGE_ERROR\")\nfun f() {}",
            "@Suppress(\"EXPERIMENTAL_OVERRIDE\")\nfun f() {}"
        ).forEach { assertEquals(1, suppressionViolations(it), "not flagged: $it") }
        assertEquals(2, suppressionViolations("@Suppress(\"OPT_IN_USAGE\", \"OPT_IN_USAGE_ERROR\")\nfun f() {}"), "each id counts")
        listOf(
            "@Suppress(\"UNCHECKED_CAST\")\nfun f() {}",
            "@Suppress(\"UNUSED_PARAMETER\", \"DEPRECATION\")\nfun f() {}",
            "// @Suppress(\"OPT_IN_USAGE_ERROR\")",
            "/**\n * @Suppress(\"OPT_IN_USAGE\")\n */\nclass A",
            "val msg = \"OPT_IN_USAGE_ERROR\"",
            "@OptIn(ExperimentalSerializationApi::class)\nclass A"
        ).forEach { assertEquals(0, suppressionViolations(it), "wrongly flagged: $it") }
    }

    @Test
    fun `S8 the implementor matcher flags every way to implement a confined port outside the impl dirs`() {
        val outside = "application/tools/X.kt"
        listOf(
            "class X(i: NoteStore) : NoteStore by i",
            "class X(i: NoteStore) : NoteStore by i {\n}",
            "fun f() = object : LinkStore {\n}",
            "class Y : WorkItemRepository {\n}",
            "class Z(\n    private val a: Int,\n    private val b: String = \"x\"\n) : Closeable,\n    NoteStore by delegate {\n}",
            "class W<T : Any>(val x: T) : LinkStore",
            "class V :\n    ClaimStore {\n}",
            "class U(val r: NoteRepository) : NoteRepository by r"
        ).forEach {
            val problems = implementorProblems(listOf(compositePort, noteRepositoryPort, src(outside, it)))
            assertEquals(1, problems.size, "not flagged: $it")
        }
        listOf(
            "class Plain(private val store: NoteStore) : Closeable {\n}",
            "class Plain2(private val store: NoteStore)",
            "fun f() = object : Runnable {\n}",
            "class Other : RoleTransitionRepository {\n}",
            "interface Sub : NoteStore",
            "val k = NoteStore::class",
            "val s = \"class X : NoteStore\""
        ).forEach {
            assertEquals(emptyList(), implementorProblems(listOf(compositePort, src(outside, it))), "wrongly flagged: $it")
        }
        listOf("infrastructure/sqlite/repository/SqliteX.kt", "infrastructure/sqlite/knowledge/nested/SqliteY.kt").forEach {
            val text = "class Impl : NoteStore {\n}\nclass R : WorkItemRepository {\n}"
            val problems = implementorProblems(listOf(compositePort, src(it, text)))
            assertEquals(emptyList(), problems, "impl dirs may implement confined ports: $it")
        }
    }

    @Test
    fun `S2 the port matcher requires every overload of every listed method and no marker elsewhere`() {
        assertEquals(emptyList(), portProblems(portFixture()), "control: a correct tree has no problems")
        assertEquals(
            emptyList(),
            portProblems(portFixture { c, n -> "@${c.marker}\n    suspend fun $n(x: Int): Int" }),
            "an annotation on its own line counts"
        )
        assertEquals(
            emptyList(),
            portProblems(portFixture { c, n -> if (n == "releaseAllForItems") "${marked(c, n)} { return x }" else marked(c, n) }),
            "a default body does not disturb the next member"
        )

        val missing = portProblems(portFixture { c, n -> if (c.port == "NoteStore" && n == "upsert") unmarked(n) else marked(c, n) })
        assertEquals(1, missing.size, "missing marker: $missing")
        assertTrue(missing.single().contains("NoteStore.upsert"), missing.single())

        val overload =
            portProblems(
                portFixture { c, n ->
                    if (c.port == "NoteStore" && n == "delete") "${marked(c, n)}\n    suspend fun $n(id: String): Boolean" else marked(c, n)
                }
            )
        assertEquals(1, overload.size, "an unmarked overload next to a marked one: $overload")

        val wrongKind =
            portProblems(portFixture { c, n -> if (n == "claim") "@$STORE_WRITE suspend fun $n(x: Int): Int" else marked(c, n) })
        assertEquals(1, wrongKind.size, "StoreWrite on a claim method: $wrongKind")

        val extra =
            portProblems(
                portFixture { c, n ->
                    if (c.port == "LinkStore" && n == "resolve") "${marked(c, n)}\n    ${marked(c, "findFrom")}" else marked(c, n)
                }
            )
        assertEquals(1, extra.size, "marker on an unlisted method of a listed port: $extra")

        val absent = portProblems(portFixture { c, n -> if (c.port == "LinkStore" && n == "resolve") null else marked(c, n) })
        assertEquals(1, absent.size, "a listed method that is not declared: $absent")

        val otherPort = src("application/port/Other.kt", "interface Other {\n    @StoreWrite suspend fun get(id: Int): Int\n}\n")
        assertEquals(1, portProblems(portFixture() + otherPort).size, "marker on a method of an unlisted port")

        val noInterface = portProblems(portFixture().filterNot { it.path.endsWith("/DocumentStore.kt") })
        assertEquals(1, noInterface.size, "a listed port that is not declared: $noInterface")

        val plainPort = src("application/port/Other.kt", "interface Other {\n    suspend fun get(id: Int): Int\n}\n")
        assertEquals(emptyList(), portProblems(portFixture() + plainPort), "an unmarked method of an unlisted port is fine")
    }

    @Test
    fun `S3 the override matcher requires the marker itself on every override and rejects an OptIn`() {
        val ports = portFixture() + compositePort + noteRepositoryPort

        fun report(text: String) = overrideReport(ports + src(implPath, text))

        val ok = report(implOf("@StoreWrite", createOverride))
        assertEquals(emptyList(), ok.problems, "control: a marked override is accepted")
        assertEquals(1, ok.examined, "control: the override was examined")

        assertEquals(1, report(implOf(createOverride)).problems.size, "unmarked override")
        assertEquals(1, report(implOf("@OptIn(StoreWrite::class)", createOverride)).problems.size, "OptIn instead of the marker")
        val both = "@StoreWrite @OptIn(StoreWrite::class) $createOverride"
        assertEquals(1, report(implOf(both)).problems.size, "OptIn next to the marker is still an OptIn")
        assertEquals(1, report(implOf("@ClaimWrite $createOverride")).problems.size, "the wrong marker kind")
        assertEquals(0, report(implOf("@StoreWrite internal $createOverride")).problems.size, "visibility modifier between marker and fun")
        val reordered = "@StoreWrite suspend override fun create(x: Int): Int = x"
        assertEquals(0, report(implOf(reordered)).problems.size, "modifier order")
        val unrelatedOptIn = report(implOf("@OptIn(ExperimentalStdlibApi::class)", "@StoreWrite", createOverride))
        assertEquals(0, unrelatedOptIn.problems.size, "an unrelated OptIn next to the marker")

        val composite =
            report(
                "class R : WorkItemRepository {\n" +
                    "    @ClaimWrite override suspend fun release(x: Int): Int = x\n" +
                    "    @StoreWrite override suspend fun update(x: Int): Int = x\n" +
                    "    override suspend fun clear(x: Int): Int = x\n}"
            )
        assertEquals(1, composite.problems.size, "composite port: only the unmarked clear: ${composite.problems}")
        assertEquals(3, composite.examined, "composite port: all three examined")

        val unrelated = report("class U : Runnable, RoleTransitionRepository {\n    $createOverride\n}")
        assertEquals(emptyList(), unrelated.problems, "an override in a class that implements no confined port")
        assertEquals(0, unrelated.examined, "unrelated class is not examined")

        assertEquals(0, report(implOf("suspend fun create(x: Int): Int = x")).problems.size, "a non-override with the same name")
        val toString = "override fun toString(): String = \"x\""
        val mixed = report(implOf("@StoreWrite $createOverride", toString))
        assertEquals(0, mixed.problems.size, "a non-confined override in a confined class")

        val nested =
            report(
                "class Outer : ItemStore {\n    @StoreWrite $createOverride\n" +
                    "    class Inner : Runnable {\n        override fun create(x: Int): Int = x\n    }\n}"
            )
        assertEquals(emptyList(), nested.problems, "an unrelated nested class is attributed to itself")

        val anonymousText =
            "class Host {\n    fun f() = object : LinkStore {\n        override suspend fun insert(x: Int): Int = x\n    }\n}"
        val anonymous = overrideReport(ports + src("application/tools/Y.kt", anonymousText))
        assertEquals(1, anonymous.problems.size, "an anonymous object implementing a confined port: ${anonymous.problems}")

        val chainedText = "class N : NoteRepository {\n    override suspend fun upsert(x: Int): Int = x\n}"
        assertEquals(1, report(chainedText).problems.size, "an override reached through an intermediate interface")
    }

    @Test
    fun `S9 the build script matcher flags a marker outside the test compile block only`() {
        val testBlock =
            "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileTestKotlin\") {\n" +
                "    compilerOptions {\n        optIn.addAll(\n            \"io.x.port.StoreWrite\",\n" +
                "            \"io.x.port.ClaimWrite\",\n        )\n    }\n}\n"
        val mainStore = "kotlin {\n    compilerOptions { optIn.add(\"io.x.port.StoreWrite\") }\n}\n"
        val mainClaim = "kotlin {\n    compilerOptions { optIn.add(\"io.x.port.ClaimWrite\") }\n}\n"
        assertEquals(0, buildScriptViolations(testBlock), "control: the test compile block alone is fine")
        assertEquals(setOf(STORE_WRITE, CLAIM_WRITE), buildScriptTestOptIns(testBlock), "control: both markers are read inside the block")

        assertEquals(1, buildScriptViolations(mainStore), "main-level optIn")
        assertEquals(1, buildScriptViolations(testBlock + mainStore), "main-level optIn after the test block")
        assertEquals(1, buildScriptViolations(mainClaim + testBlock), "main-level optIn before the test block")
        val mainTask =
            "tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(\"compileKotlin\") {\n" +
                "    compilerOptions { optIn.add(\"io.x.port.StoreWrite\") }\n}\n"
        assertEquals(1, buildScriptViolations(mainTask), "the main compile task is not the test compile task")
        val allTasks =
            "tasks.withType<KotlinCompile>().configureEach {\n" +
                "    compilerOptions { freeCompilerArgs.add(\"-opt-in=io.x.port.StoreWrite\") }\n}\n"
        assertEquals(1, buildScriptViolations(allTasks), "a compiler argument form for all compile tasks")
        val dependsOn = "tasks.named(\"test\") {\n    dependsOn(\"compileTestKotlin\")\n}\n"
        assertEquals(1, buildScriptViolations(dependsOn + mainClaim), "a dependsOn mention does not open an allowed block")
        val accessor = "tasks.compileTestKotlin {\n    compilerOptions { optIn.add(\"io.x.port.StoreWrite\") }\n}\n"
        assertEquals(0, buildScriptViolations(accessor), "the accessor form")
        val commented = "// optIn.add(\"io.x.port.StoreWrite\") is for tests only\nplugins { }\n"
        assertEquals(0, buildScriptViolations(commented), "a comment mention")
        assertEquals(0, buildScriptViolations("tasks.test { jvmArgs(\"-Dx=StoreWriter\") }\n"), "a look-alike identifier")
        assertEquals(emptySet(), buildScriptTestOptIns(mainStore), "no test block, no test opt-ins")
    }

    @Test
    fun `the lexer keeps declaration spans correct around braces in comments strings and literals`() {
        val quotes = "\"\"\""
        val text =
            "class A : NoteStore {\n" +
                "    val s = \"}\"\n" +
                "    val c = '}'\n" +
                "    val e = '\\''\n" +
                "    // }\n" +
                "    /* } /* } */ } */\n" +
                "    val r = $quotes\n        }\n    $quotes\n" +
                "    override suspend fun upsert(x: Int): Int = x\n" +
                "}\n" +
                "class B : Runnable {\n    override fun upsert(x: Int): Int = x\n}\n"
        val report = overrideReport(portFixture() + src(implPath, text))
        assertEquals(1, report.problems.size, "A's unmarked override is found, B's is not: ${report.problems}")
        assertTrue(report.problems.single().contains("A.upsert"), report.problems.single())
        assertEquals(1, report.examined)
        assertEquals("val s = \"\"", clean("val s = \"abc{\"", keepStrings = false).trim())
        assertEquals("val s = \"abc\"", clean("val s = \"abc\" // tail", keepStrings = true).trim())
    }
}

package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File

/**
 * Migration scaffolding for the slice-first layout (plans/application-package-reorg.md, step 2): maps today's
 * production files to their target slices and writes the slice-to-slice dependency graph, its cycles and the area-rule
 * breaches to `build/reports/slice-graph/report.md`. Report-only: it never fails. Runs only with `SLICE_REPORT=1`.
 * Delete this file, with its mapping, when the legacy packages are gone (plan step 9).
 *
 * An edge is a reference that Kotlin would resolve to a top-level declaration of another file: an explicit import, a
 * name from the file's own package, or a name from a wildcard-imported package. Comments and string literals are
 * stripped first, so KDoc mentions are not edges.
 */
class SliceGraphReportTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "SLICE_REPORT", matches = "1")
    fun `write the slice dependency report`() {
        val sources = GuardSupport.productionSources()
        val parsed = sources.map { parse(it) }
        val index = HashMap<String, MutableSet<String>>()
        parsed.forEach { p -> p.declared.forEach { name -> index.getOrPut("${p.pkg}.$name") { mutableSetOf() } += p.path } }

        val fileEdges = mutableSetOf<Triple<String, String, String>>()
        parsed.forEach { p ->
            resolve(p, index).forEach { (target, name) ->
                if (target !=
                    p.path
                ) {
                    fileEdges += Triple(p.path, target, name)
                }
            }
        }

        val sliceOf = sources.associate { it.path to sliceFor(it.path) }
        val report = render(sliceOf, fileEdges)
        val out = File("build/reports/slice-graph/report.md")
        out.parentFile.mkdirs()
        out.writeText(report)
        println(report)
    }

    private data class Parsed(
        val path: String,
        val pkg: String,
        val declared: Set<String>,
        val explicitImports: Map<String, String>,
        val wildcardImports: List<String>,
        val tokens: Set<String>
    )

    private fun parse(src: GuardSupport.Source): Parsed {
        val pkg = PACKAGE.find(src.text)?.groupValues?.get(1) ?: ""
        val explicit = HashMap<String, String>()
        val wildcards = mutableListOf<String>()
        IMPORT.findAll(src.text).forEach { m ->
            val fqn = m.groupValues[1]
            val alias = m.groupValues[2]
            if (fqn.endsWith(".*")) wildcards += fqn.removeSuffix(".*") else explicit[alias.ifEmpty { fqn.substringAfterLast('.') }] = fqn
        }
        val code = stripCommentsAndStrings(src.text)
        val declared =
            code.lines().mapNotNull { line -> topLevelName(line) }.toSet()
        val body = code.lines().filterNot { it.startsWith("import ") || it.startsWith("package ") }.joinToString("\n")
        val tokens = IDENT.findAll(body).map { it.value }.toSet()
        return Parsed(src.path, pkg, declared, explicit, wildcards, tokens)
    }

    /** Name of a non-private top-level declaration starting at column 0, or null. */
    private fun topLevelName(line: String): String? {
        if (line.isEmpty() || line[0].isWhitespace()) return null
        val words = line.split(Regex("\\s+"))
        var i = 0
        while (i < words.size && (words[i] in MODIFIERS || words[i].startsWith("@"))) {
            if (words[i] == "private") return null
            i++
        }
        if (i >= words.size) return null
        var keyword = words[i]
        if (keyword == "fun" && words.getOrNull(i + 1) == "interface") keyword = "interface".also { i++ }
        if (keyword !in KEYWORDS) return null
        val rest = line.substringAfter(" $keyword ", line.substringAfter("$keyword ")).trimStart()
        val withoutTypeParams = if (rest.startsWith("<")) rest.substringAfter("> ").trimStart() else rest
        val head =
            if (keyword ==
                "fun"
            ) {
                withoutTypeParams.substringBefore('(')
            } else {
                withoutTypeParams.takeWhile {
                    it.isLetterOrDigit() ||
                        it == '_' ||
                        it == '.' ||
                        it == '<' ||
                        it == '>' ||
                        it == '?' ||
                        it == ',' ||
                        it == ' '
                }
            }
        val name =
            head.substringBefore('<').let {
                if (keyword == "fun" ||
                    keyword == "val" ||
                    keyword == "var"
                ) {
                    head.substringAfterLast('.')
                } else {
                    it
                }
            }
        return IDENT.find(name.trim())?.value
    }

    private fun resolve(
        p: Parsed,
        index: Map<String, Set<String>>
    ): List<Pair<String, String>> {
        val hits = mutableListOf<Pair<String, String>>()
        p.explicitImports.values.forEach { fqn ->
            lookup(fqn, index)?.let { files ->
                files.forEach {
                    hits +=
                        it to fqn.substringAfterLast('.')
                }
            }
        }
        p.tokens.forEach { t ->
            if (t in p.explicitImports) return@forEach
            val same = index["${p.pkg}.$t"]
            if (same != null) {
                same.forEach { hits += it to t }
                return@forEach
            }
            p.wildcardImports.forEach { w -> index["$w.$t"]?.forEach { hits += it to t } }
        }
        return hits
    }

    /** Resolves an import FQN, trimming trailing segments so `Outer.Nested` and `Object.member` find their file. */
    private fun lookup(
        fqn: String,
        index: Map<String, Set<String>>
    ): Set<String>? {
        var candidate = fqn
        while (candidate.contains('.')) {
            index[candidate]?.let { return it }
            candidate = candidate.substringBeforeLast('.')
        }
        return null
    }

    private fun render(
        sliceOf: Map<String, String>,
        fileEdges: Set<Triple<String, String, String>>
    ): String {
        val sb = StringBuilder("# Slice dependency report\n\n")
        val unmapped = sliceOf.filterValues { it == UNMAPPED }.keys.sorted()
        sb.append("## Unmapped files (${unmapped.size})\n\n")
        unmapped.forEach { sb.append("- $it\n") }

        val bySlicePair =
            fileEdges.groupBy { (from, to, _) -> sliceOf.getValue(from) to sliceOf.getValue(to) }.filterKeys { (a, b) ->
                a !=
                    b
            }
        sb.append("\n## Slice edges (from -> to: file-level edges, examples)\n\n")
        bySlicePair.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).forEach { (pair, edges) ->
            val examples =
                edges
                    .take(
                        4
                    ).joinToString("; ") { (f, t, n) -> "${f.substringAfterLast('/')} -> ${t.substringAfterLast('/')} ($n)" }
            sb.append("- ${pair.first} -> ${pair.second}: ${edges.size} | $examples\n")
        }

        val business = sliceOf.values.toSet() - setOf(MODEL, PLATFORM, SERVER, UNMAPPED)
        val graph = bySlicePair.keys.filter { (a, b) -> a in business && b in business }.groupBy({ it.first }, { it.second })
        val sccs = tarjan(business, graph).filter { it.size > 1 }
        sb.append("\n## Slice matrix (rows depend on columns; file-level edge counts)\n\n")
        val cols = business.sorted()
        sb.append("| from \\ to | ${cols.joinToString(" | ")} |\n|---|${cols.joinToString("") { "---|" }}\n")
        cols.forEach { a -> sb.append("| $a | ${cols.joinToString(" | ") { b -> bySlicePair[a to b]?.size?.toString() ?: "" }} |\n") }
        sb.append("\n## Cycles among slices, server excluded (${sccs.size} strongly connected group(s))\n\n")
        sccs.forEach { group ->
            sb.append("### ${group.sorted().joinToString(", ")}\n\n")
            bySlicePair.filterKeys { (a, b) -> a in group && b in group }.entries.sortedBy { it.value.size }.forEach { (pair, edges) ->
                val all = edges.joinToString("; ") { (f, t, n) -> "${f.substringAfterLast('/')} -> ${t.substringAfterLast('/')} ($n)" }
                sb.append("- ${pair.first} -> ${pair.second} (${edges.size}): $all\n")
            }
            sb.append("\n")
        }

        sb.append("## Edges against the intended order (low to high: ${ORDER.joinToString(" < ")})\n\n")
        bySlicePair
            .filterKeys { (a, b) -> a in ORDER && b in ORDER && ORDER.indexOf(a) < ORDER.indexOf(b) }
            .entries
            .sortedBy { it.value.size }
            .forEach { (pair, edges) ->
                val all = edges.joinToString("; ") { (f, t, n) -> "${f.substringAfterLast('/')} -> ${t.substringAfterLast('/')} ($n)" }
                sb.append("- ${pair.first} -> ${pair.second} (${edges.size}): $all\n")
            }
        sb.append("\n")

        sb.append("## Area breaches (model must be a leaf; platform may use only model; only server may use server)\n\n")
        bySlicePair
            .filterKeys { (a, b) ->
                (a == MODEL && b != MODEL) ||
                    (a == PLATFORM && b !in setOf(MODEL, PLATFORM)) ||
                    (b == SERVER && a != SERVER)
            }.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .forEach { (pair, edges) ->
                val all = edges.joinToString("; ") { (f, t, n) -> "${f.substringAfterLast('/')} -> ${t.substringAfterLast('/')} ($n)" }
                sb.append("- ${pair.first} -> ${pair.second} (${edges.size}): $all\n")
            }
        return sb.toString()
    }

    private fun tarjan(
        nodes: Set<String>,
        graph: Map<String, List<String>>
    ): List<Set<String>> {
        var counter = 0
        val indexOf = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = HashSet<String>()
        val result = mutableListOf<Set<String>>()

        fun visit(v: String) {
            indexOf[v] = counter
            low[v] = counter
            counter++
            stack.addLast(v)
            onStack += v
            graph[v].orEmpty().forEach { w ->
                if (w !in indexOf) {
                    visit(w)
                    low[v] = minOf(low.getValue(v), low.getValue(w))
                } else if (w in onStack) {
                    low[v] = minOf(low.getValue(v), indexOf.getValue(w))
                }
            }
            if (low[v] == indexOf[v]) {
                val group = mutableSetOf<String>()
                do {
                    val w = stack.removeLast()
                    onStack -= w
                    group += w
                } while (w != v)
                result += group
            }
        }
        nodes.sorted().forEach { if (it !in indexOf) visit(it) }
        return result
    }

    private fun stripCommentsAndStrings(text: String): String {
        val noRaw = RAW_STRING.replace(text, "\"\"")
        val noBlock = BLOCK_COMMENT.replace(noRaw) { m -> "\n".repeat(m.value.count { it == '\n' }) }
        return noBlock.lines().joinToString("\n") { line -> GuardSupport.stripStrings(line.substringBefore("//")) }
    }

    private fun sliceFor(path: String): String =
        MAPPING.filter { (prefix, _) -> path.startsWith(prefix) }.maxByOrNull { it.first.length }?.second
            ?: GuardSupport.sliceOf(path)?.takeIf { it in ORDER }
            ?: UNMAPPED

    private companion object {
        const val MODEL = "model"
        const val PLATFORM = "platform"
        const val SERVER = "server"

        /** Intended dependency order, lowest first: a slice may depend only on slices earlier in this list. */
        val ORDER =
            listOf(
                "identity",
                "telemetry",
                "claim",
                "item",
                "dependency",
                "knowledge",
                "config",
                "note",
                "lifecycle",
                "workflow",
                "event",
                "run",
                "report",
                "upgrade"
            )
        const val UNMAPPED = "UNMAPPED"

        val PACKAGE = Regex("(?m)^package\\s+([\\w.]+)")
        val IMPORT = Regex("(?m)^import\\s+([\\w.*]+)(?:\\s+as\\s+(\\w+))?")
        val IDENT = Regex("\\b[A-Za-z_]\\w*\\b")
        val RAW_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
        val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        val MODIFIERS =
            setOf(
                "public",
                "internal",
                "private",
                "data",
                "sealed",
                "enum",
                "abstract",
                "open",
                "value",
                "inline",
                "annotation",
                "suspend",
                "const",
                "operator",
                "infix",
                "tailrec",
                "expect",
                "actual",
                "override"
            )
        val KEYWORDS = setOf("class", "interface", "object", "typealias", "fun", "val", "var")

        /** Target slice per path prefix (relative to the `current/` package root); the longest matching prefix wins. */
        val MAPPING: List<Pair<String, String>> =
            listOf(
                "CurrentMain.kt" to "server",
                "bootstrap/" to "server",
                "application/BuildInfo.kt" to "server",
                // model
                "model/" to MODEL,
                "domain/lifecycle/" to "lifecycle",
                "domain/lifecycle/Trigger.kt" to MODEL,
                "domain/lifecycle/SeatFacts.kt" to MODEL,
                "domain/graph/" to "dependency",
                // application/api
                "application/api/BatchRunner.kt" to "server",
                "application/api/ToolExecutionContext.kt" to "server",
                "application/api/AnswerProvenance.kt" to "run",
                "application/api/RunRefs.kt" to "run",
                "application/api/advance/" to "lifecycle",
                "application/api/docs/" to "knowledge",
                "application/api/notes/" to "note",
                "application/api/runs/" to "run",
                // other application packages
                "application/config/" to "config",
                "application/decision/" to "run",
                "application/knowledge/" to "knowledge",
                "application/read/RunFrontierQueries.kt" to "run",
                "application/read/" to "lifecycle",
                "application/report/" to "report",
                "application/run/" to "run",
                "application/upgrade/" to "upgrade",
                // application/port
                "application/port/RepositoryProvider.kt" to "server",
                "application/port/Config" to "config",
                "application/port/DataStepStore.kt" to "upgrade",
                "application/port/Decision" to "run",
                "application/port/ServerDecisionRaiser.kt" to "run",
                "application/port/KnowledgeStoreProvider.kt" to "knowledge",
                "application/port/LifecycleStoreProvider.kt" to "lifecycle",
                "application/port/WorkflowStoreProvider.kt" to "workflow",
                "application/port/DocumentStore.kt" to "knowledge",
                "application/port/LinkStore.kt" to "knowledge",
                "application/port/PlanDocumentStore.kt" to "knowledge",
                "application/port/Search" to "knowledge",
                "application/port/HierarchyStore.kt" to "item",
                "application/port/ItemLookup.kt" to "item",
                "application/port/ItemQuery.kt" to "item",
                "application/port/ItemStore.kt" to "item",
                "application/port/ItemStoreProvider.kt" to "item",
                "application/port/WorkItemRepository.kt" to "item",
                "application/port/NoteStore.kt" to "note",
                "application/port/NoteStoreProvider.kt" to "note",
                "application/port/ReportQueries.kt" to "report",
                "application/port/ReportStoreProvider.kt" to "report",
                "application/port/Run" to "run",
                // application/service
                "application/service/Advance" to "lifecycle",
                "application/service/GatePredicate.kt" to "lifecycle",
                "application/service/PhaseNoteContext.kt" to "lifecycle",
                "application/service/SeatOwnership.kt" to "lifecycle",
                "application/service/Transition" to "lifecycle",
                "application/service/TreeCompletionService.kt" to "lifecycle",
                "application/service/NextItemRecommender.kt" to "lifecycle",
                "application/service/CredentialRefValidation.kt" to "lifecycle",
                "application/service/Config" to "config",
                "application/service/Fingerprint.kt" to "config",
                "application/service/SchemaPinService.kt" to "config",
                "application/service/RuleService.kt" to "config",
                "application/service/ItemSchemaView.kt" to "lifecycle",
                "application/service/SchemaEntryJsonBuilder.kt" to "lifecycle",
                "application/service/ItemCommandService.kt" to "workflow",
                "application/service/WorkTreeService.kt" to "workflow",
                "application/service/MarkdownSectionSplitter.kt" to "knowledge",
                "application/service/PlanDocumentService.kt" to "knowledge",
                "application/service/Note" to "note",
                // infrastructure
                "platform/" to PLATFORM,
                "infrastructure/config/" to "config",
                "infrastructure/sqlite/knowledge/" to "knowledge",
                "infrastructure/sqlite/report/" to "report",
                "infrastructure/sqlite/upgrade/" to "upgrade",
                "infrastructure/sqlite/repository/DocumentBackedPlanDocumentStore.kt" to "knowledge",
                "infrastructure/sqlite/repository/SQLiteNoteRepository.kt" to "note",
                "infrastructure/sqlite/repository/SQLiteWorkItemRepository.kt" to "item",
                "infrastructure/sqlite/repository/SqliteConfigStore.kt" to "config",
                "infrastructure/sqlite/repository/SqliteDataStepStore.kt" to "upgrade",
                "infrastructure/sqlite/repository/SqliteDecisionRequestStore.kt" to "run",
                "infrastructure/sqlite/repository/DefaultRepositoryProvider.kt" to "server",
                "infrastructure/sqlite/repository/SqliteHierarchyStore.kt" to "item",
                "infrastructure/sqlite/repository/SqliteItemStore.kt" to "item",
                "infrastructure/sqlite/repository/SqliteRun" to "run",
                // interfaces
                "interfaces/" to "server",
                "interfaces/api/v1/mapping/" to "lifecycle",
                "interfaces/api/v1/routes/ConfigRoutes.kt" to "config",
                "interfaces/api/v1/routes/ItemRoutes.kt" to "item",
                "interfaces/api/v1/routes/NoteResourceRoutes.kt" to "note",
                "interfaces/mcp/tools/advance/" to "lifecycle",
                "interfaces/mcp/tools/advance/ClaimOp.kt" to "lifecycle",
                "interfaces/mcp/tools/advance/HeartbeatOp.kt" to "lifecycle",
                "interfaces/mcp/tools/advance/ReleaseOp.kt" to "lifecycle",
                "interfaces/mcp/tools/notes/" to "note",
                "interfaces/shared/tools/NoteSchemaJson.kt" to "lifecycle",
                "interfaces/shared/tools/config/" to "config",
                "interfaces/shared/tools/docs/" to "knowledge",
                "interfaces/shared/tools/items/" to "item",
                "interfaces/shared/tools/items/CompleteTreeOp.kt" to "workflow",
                "interfaces/shared/tools/items/CreateItemOp.kt" to "workflow",
                "interfaces/shared/tools/items/CreateTreeOp.kt" to "workflow",
                "interfaces/shared/tools/items/DeleteItemOp.kt" to "workflow",
                "interfaces/shared/tools/items/UpdateItemOp.kt" to "workflow",
                "interfaces/shared/tools/items/RepinItemOp.kt" to "workflow",
                "interfaces/shared/tools/items/TreeWire.kt" to "workflow",
                "interfaces/shared/tools/items/WorkflowToolServices.kt" to "workflow",
                "interfaces/shared/tools/items/ItemsReadBlocked.kt" to "lifecycle",
                "interfaces/shared/tools/items/ItemsReadContext.kt" to "lifecycle",
                "interfaces/shared/tools/items/ItemsReadNext.kt" to "lifecycle",
                "interfaces/shared/tools/items/ItemsReadRelated.kt" to "knowledge",
                "interfaces/shared/tools/items/ItemsReadSearch.kt" to "knowledge",
                "interfaces/shared/tools/items/ItemsReadSchema.kt" to "lifecycle",
                "interfaces/shared/tools/items/ItemsReadTool.kt" to "server",
                "interfaces/shared/tools/items/ItemsWriteTool.kt" to "server",
                "interfaces/shared/tools/report/" to "report",
                "interfaces/shared/tools/runs/" to "run",
                "interfaces/shared/tools/runs/Decision" to "run",
                "interfaces/shared/tools/runs/RunsAckOp.kt" to "run",
                "interfaces/shared/tools/runs/RunsAnswerOp.kt" to "run",
                "interfaces/shared/tools/runs/RunsRaiseOp.kt" to "run",
                "interfaces/shared/tools/runs/RunsWithdrawOp.kt" to "run",
                "interfaces/shared/tools/runs/RunsDecision" to "run"
            )
    }
}

package io.github.jpicklyk.mcptask.current.architecture

/**
 * The package-cycle rule engine behind [PackageCycleTest], kept pure (a file's path, its import names and its text in;
 * edges and a baseline diff out) so [PackageCycleRulesTest] can prove it on synthetic input without a Konsist scan.
 *
 * Model: every production file under `application/<p>/` belongs to sub-package `p` (the first segment under
 * `application/`; nested packages fold into it). A file reaching `<root>.application.<q>.<rest>` with `q != p`, by an
 * import or by a fully-qualified reference on a code line, is an [Edge] from `p` to `q`. Comment/KDoc lines are skipped
 * and string literals are stripped before scanning. Files outside `application/` and application-root files are out of
 * scope, and `q` must be a lowercase-initial segment followed by a further segment.
 *
 * Ratchet (feedback-arc-set model): a non-baselined edge `u -> v` is unbaselined iff `v` reaches `u` over non-baselined
 * edges only, so every package cycle must contain at least one baselined edge. A baseline key is stale when it matches
 * no current edge, or when its `u -> v` no longer lies on any cycle of the full graph. Accepted limitation: a new edge
 * whose every cycle passes through a baselined edge passes.
 */
object PackageCycleRules {
    private const val APPLICATION = "${LayeringRules.CURRENT_ROOT}.application"
    private val FQN = Regex("(?<![A-Za-z0-9_.])" + Regex.escape("$APPLICATION.") + "[A-Za-z0-9_*]+(\\.[A-Za-z0-9_*]+)*")

    /** One file-level reference: [path] (relative to the `current/` source root) reaches [importedFqName]. */
    data class Edge(
        val path: String,
        val importedFqName: String,
        val from: String,
        val to: String
    ) {
        /** Baseline key: forward-slash-normalized path + " -> " + imported FQN. */
        fun key(): String = "${path.replace('\\', '/')} -> $importedFqName"
    }

    private fun sourcePackage(path: String): String? {
        val parts = path.replace('\\', '/').split('/')
        return if (parts.size >= 3 && parts[0] == "application") parts[1] else null
    }

    private fun targetPackage(fqn: String): String? {
        if (!fqn.startsWith("$APPLICATION.")) return null
        val rest = fqn.removePrefix("$APPLICATION.").split('.')
        if (rest.size < 2) return null
        return rest[0].takeIf { it.isNotEmpty() && it[0].isLowerCase() }
    }

    /** Every cross-sub-package edge in one file, distinct by FQN. */
    fun edges(
        path: String,
        imports: List<String>,
        text: String
    ): List<Edge> {
        val from = sourcePackage(path) ?: return emptyList()
        val names = LinkedHashSet<String>()
        names += imports
        text
            .lineSequence()
            .filter { LayeringRules.isCodeLine(it) && !it.trim().startsWith("import ") }
            .forEach { line -> FQN.findAll(GuardSupport.stripStrings(line)).forEach { names += it.value } }
        return names.mapNotNull { name ->
            val to = targetPackage(name)
            if (to == null || to == from) null else Edge(path, name, from, to)
        }
    }

    private fun reaches(
        graph: Map<String, Set<String>>,
        start: String,
        goal: String
    ): Boolean {
        val seen = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node == goal) return true
            graph[node].orEmpty().forEach { if (seen.add(it)) queue += it }
        }
        return false
    }

    private fun graphOf(edges: Collection<Edge>): Map<String, Set<String>> =
        edges
            .groupBy({
                it.from
            }, { it.to })
            .mapValues { it.value.toSet() }

    /** Unbaselined = cycle-closing edges not in [baseline]; stale = baseline keys matching no edge or no cycle. */
    fun diff(
        edges: Collection<Edge>,
        baseline: Set<String>
    ): LayeringRules.BaselineDiff {
        val all = edges.distinctBy { it.key() }
        val free = all.filter { it.key() !in baseline }
        val freeGraph = graphOf(free)
        val fullGraph = graphOf(all)
        val unbaselined = free.filter { reaches(freeGraph, it.to, it.from) }.map { it.key() }.toSet()
        val byKey = all.associateBy { it.key() }
        val stale =
            baseline
                .filter { key -> byKey[key]?.let { !reaches(fullGraph, it.to, it.from) } ?: true }
                .toSet()
        return LayeringRules.BaselineDiff(unbaselined = unbaselined, stale = stale)
    }
}

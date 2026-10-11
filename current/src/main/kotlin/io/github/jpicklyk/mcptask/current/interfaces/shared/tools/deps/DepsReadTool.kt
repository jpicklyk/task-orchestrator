package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec

const val DEPS_READ = "deps_read"

/** The deps_read tool: dependency edges and the blocking graph. Backlinks are list with direction incoming. */
fun depsReadTool(): SelectToolSpec =
    SelectToolSpec(
        name = DEPS_READ,
        description =
            "Read dependency edges between items. select: list (edges of one item, with direction and type filters) or " +
                "graph (the blocking graph around an item). Edge: {id, from, to, type, unblockAt?}; from blocks to.",
        selects = listOf(depsListSelect, depsGraphSelect)
    )

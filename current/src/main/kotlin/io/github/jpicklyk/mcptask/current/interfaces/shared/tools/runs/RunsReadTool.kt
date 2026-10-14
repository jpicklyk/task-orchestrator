package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec

const val RUNS_READ = "runs_read"

/** The runs_read tool: one run, a filtered page of runs, or a run's seats. Orchestration and director profiles (seats: orchestration). */
fun runsReadTool(): SelectToolSpec =
    SelectToolSpec(
        name = RUNS_READ,
        description =
            "Read runs, the bounded budgeted passes over a set of items. select: get (one run) or list (runs, newest " +
                "first, with status, endedSince and unmarked filters) or seats (a run's seat assignments). A run is " +
                "outside the call scope when its root is.",
        selects = listOf(runsGetSelect, runsListSelect, runsSeatsSelect)
    )

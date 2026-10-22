package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec

const val RUNS_READ = "runs_read"

/**
 * The runs_read tool: one run, a filtered page of runs, a run's seats, a run's frontier, a page of decisions or one
 * decision. Orchestration and director profiles (seats: orchestration; decisions and decision: director).
 */
fun runsReadTool(): SelectToolSpec =
    SelectToolSpec(
        name = RUNS_READ,
        description =
            "Read runs, the bounded budgeted passes over a set of items. select: get (one run), list (runs, newest " +
                "first), seats (a run's seat assignments), frontier (ready, in-flight and waiting members), decisions " +
                "or decision. A run or decision is outside the call scope when its root is.",
        selects =
            listOf(
                runsGetSelect,
                runsListSelect,
                runsSeatsSelect,
                runsFrontierSelect,
                runsDecisionsSelect,
                runsDecisionSelect
            )
    )

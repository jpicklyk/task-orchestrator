package io.github.jpicklyk.mcptask.current.report

import io.github.jpicklyk.mcptask.current.platform.tool.SelectToolSpec
import io.github.jpicklyk.mcptask.current.report.internal.wire.reportContentionSelect
import io.github.jpicklyk.mcptask.current.report.internal.wire.reportGatesSelect
import io.github.jpicklyk.mcptask.current.report.internal.wire.reportNotesSelect
import io.github.jpicklyk.mcptask.current.report.internal.wire.reportRollupSelect
import io.github.jpicklyk.mcptask.current.report.internal.wire.reportUsageSelect
import io.github.jpicklyk.mcptask.current.report.internal.wire.reportWasteSelect

/**
 * The `report` tool (plan R9): read-only aggregate reports, one file per select. Director-only on MCP (profiles
 * {DIRECTOR}); REST serves it at `POST /api/v1/ops/report`. R9 ships `usage`, R11 `gates` and `contention`, R12 `waste`,
 * R16 the anchor `rollup`; R28 adds the run rollup. The report slice's tool door: callers outside the slice name this
 * object, not the selects.
 */
object ReportTools {
    const val REPORT = "report"

    /** The `report` tool spec. */
    fun tool(): SelectToolSpec =
        SelectToolSpec(
            name = REPORT,
            description =
                "Aggregate metrics to see how work is going and where it stalls. select: usage (call metrics), gates " +
                    "(rejections), notes (writes), contention (claims, leases), waste, rollup (subtree or run).",
            selects =
                listOf(
                    reportUsageSelect,
                    reportGatesSelect,
                    reportNotesSelect,
                    reportContentionSelect,
                    reportWasteSelect,
                    reportRollupSelect
                )
        )
}

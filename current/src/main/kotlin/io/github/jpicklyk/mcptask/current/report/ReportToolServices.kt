package io.github.jpicklyk.mcptask.current.report

import io.github.jpicklyk.mcptask.current.report.ReportStoreProvider
import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.github.jpicklyk.mcptask.current.platform.port.UnitOfWork

/** The services the `report` selects take as `env`: each runs one read unit and windows its query on [clock]. */
class ReportToolServices(
    val unitOfWork: UnitOfWork<ReportStoreProvider>,
    val clock: Clock
)

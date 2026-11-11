package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.run.RunLimits
import java.util.Collections
import java.util.WeakHashMap

/** One [RunLimits] per [ToolExecutionContext] (identity keyed, released with the context). */
private val runLimitsByContext: MutableMap<ToolExecutionContext, RunLimits> =
    Collections.synchronizedMap(WeakHashMap())

/**
 * The run limits (R27) of this context: the assign with its attempt and seat caps, raising its server decisions through [decisionService]. Built here in the tool layer, like
 * [decisionService], so the context itself never references the decision package.
 */
val ToolExecutionContext.runLimits: RunLimits
    get() =
        synchronized(runLimitsByContext) {
            runLimitsByContext.getOrPut(this) {
                RunLimits(repositoryProvider, unitOfWork, runService, seatService, decisionService)
            }
        }

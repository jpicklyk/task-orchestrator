package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.decision.DecisionService
import java.util.Collections
import java.util.WeakHashMap

/** One [DecisionService] per [ToolExecutionContext] (identity keyed, released with the context). */
private val decisionServices: MutableMap<ToolExecutionContext, DecisionService> =
    Collections.synchronizedMap(WeakHashMap())

/**
 * The single owner of decision-request writes and decision events (R24) for this context, built here in the tool
 * layer (so the context itself never references the decision package) on first use and then reused. It runs over the
 * context's repository provider and unit of work, resolves classes through its config resolver, and calls its
 * [ToolExecutionContext.decisionEffects] hook inside each answer's unit. Its store is reached only when a decision is
 * touched (a provider that serves no decisions throws then).
 */
val ToolExecutionContext.decisionService: DecisionService
    get() =
        synchronized(decisionServices) {
            decisionServices.getOrPut(this) {
                val ctx = this
                DecisionService(
                    ctx.repositoryProvider,
                    ctx.unitOfWork,
                    decisionClasses = { rootId -> ctx.configResolver.resolveDecisionClasses(rootId) },
                    effects = ctx.decisionEffects
                )
            }
        }

package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.run.RunLimits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/** One [RunLimits] per [ToolExecutionContext] (identity keyed, released with the context). */
private val runLimitsByContext: MutableMap<ToolExecutionContext, RunLimits> =
    Collections.synchronizedMap(WeakHashMap())

/**
 * The run limits (R27) of this context: the budget check every token-carrying write runs and the assign with its
 * attempt and seat caps, raising its server decisions through [decisionService]. Built here in the tool layer, like
 * [decisionService], so the context itself never references the decision package.
 */
val ToolExecutionContext.runLimits: RunLimits
    get() =
        synchronized(runLimitsByContext) {
            runLimitsByContext.getOrPut(this) {
                RunLimits(repositoryProvider, unitOfWork, runService, seatService, decisionService)
            }
        }

/** The member a write's result carries when its run is paused for its budget: `paused: {decisionId}`. */
const val PAUSED_MEMBER = "paused"

/** [this] with `paused: {decisionId}` added when [decisionId] is not null, else unchanged. */
fun JsonObject.withPaused(decisionId: UUID?): JsonObject =
    if (decisionId == null) {
        this
    } else {
        JsonObject(this + (PAUSED_MEMBER to JsonObject(mapOf("decisionId" to JsonPrimitive(decisionId.toString())))))
    }

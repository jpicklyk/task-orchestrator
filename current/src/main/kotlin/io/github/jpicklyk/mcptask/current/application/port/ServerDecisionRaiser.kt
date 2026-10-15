package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequest
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestOption
import java.util.UUID

/**
 * A decision the server raises itself (a server-raised class such as `run-budget` or `attempt-cap`, R27): [id] is the id a
 * new request takes, so a caller can name it before the request is stored; [context] is the numbers behind it.
 */
data class ServerDecision(
    val id: UUID,
    val rootId: UUID,
    val runId: UUID,
    val itemId: UUID?,
    val decisionClass: String,
    val question: String,
    val options: List<DecisionRequestOption>,
    val recommended: String?,
    val context: String?,
    val dedupeKey: String
)

/**
 * Raises a [ServerDecision] as the server, joining the caller's unit. Returns the stored request: the new one, or the
 * not-withdrawn one its dedupe key already names (whose id then differs from [ServerDecision.id]).
 */
fun interface ServerDecisionRaiser {
    suspend fun raiseServer(decision: ServerDecision): Outcome<DecisionRequest>
}

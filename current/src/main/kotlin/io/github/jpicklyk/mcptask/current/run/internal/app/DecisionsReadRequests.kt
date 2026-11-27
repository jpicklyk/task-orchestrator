package io.github.jpicklyk.mcptask.current.run.internal.app

import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.model.run.DecisionWaitingOn
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire form of a decision status filter: lowercase. */
@Serializable
enum class DecisionStatusFilter(
    val domain: DecisionRequestStatus
) {
    @SerialName("open")
    OPEN(DecisionRequestStatus.OPEN),

    @SerialName("acknowledged")
    ACKNOWLEDGED(DecisionRequestStatus.ACKNOWLEDGED),

    @SerialName("answered")
    ANSWERED(DecisionRequestStatus.ANSWERED),

    @SerialName("withdrawn")
    WITHDRAWN(DecisionRequestStatus.WITHDRAWN)
}

/** Wire form of a routing filter: who a pending decision waits on. */
@Serializable
enum class DecisionWaitingOnFilter(
    val domain: DecisionWaitingOn
) {
    @SerialName("director")
    DIRECTOR(DecisionWaitingOn.DIRECTOR),

    @SerialName("user")
    USER(DecisionWaitingOn.USER)
}

/** `decisions_read list`: decisions, newest first, with the classes in force on their roots. */
@Serializable
data class DecisionsListRequest(
    @Doc("Only decisions about this item. Item id: UUID or 8-32 hex.") val itemId: IdRef? = null,
    @Doc("Only decisions about this item or one below it, and the item-less decisions of it as a root. Item id: UUID or 8-32 hex.")
    val ancestorId: IdRef? = null,
    @Doc("Only decisions of this run. Run id: UUID or 8-32 hex.") val runId: IdRef? = null,
    @Doc("Only decisions on this root. Item id: UUID or 8-32 hex.") val rootId: IdRef? = null,
    @Doc("Only these decisions: full UUIDs, at most 50; an unknown id is left out and [] matches nothing.")
    val decisionIds: List<String>? = null,
    @Doc("Only decisions in one of these statuses; absent means any.") val status: List<DecisionStatusFilter>? = null,
    @Doc("Only pending decisions routed to this answerer.") val waitingOn: DecisionWaitingOnFilter? = null,
    @Doc("Only decisions of this class.") val `class`: String? = null,
    @Doc("Only decisions of this effective severity.") val severity: DecisionSeverityRequest? = null,
    @Doc("true: only pending decisions past their expiry; false or absent: no filter.") val overdue: Boolean? = null,
    @Doc("true: include each decision's context; default false.") val includeContext: Boolean? = null,
    @Doc("Page size, 1 to 200 (default 50).") val limit: Int? = null,
    @Doc("The page.next value of the previous page.") val cursor: String? = null
)

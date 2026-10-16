package io.github.jpicklyk.mcptask.current.application.api.runs

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IsoInstantSerializer
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Wire form of a run status filter: lowercase. */
@Serializable
enum class RunStatusFilter(
    val domain: RunStatus
) {
    @SerialName("open")
    OPEN(RunStatus.OPEN),

    @SerialName("paused")
    PAUSED(RunStatus.PAUSED),

    @SerialName("closed")
    CLOSED(RunStatus.CLOSED)
}

internal const val RUN_ID_DOC = "Run id: a UUID or a hex prefix of 8 to 32 characters."
internal const val DECISION_ID_DOC = "Decision id: a UUID or a hex prefix of 8 to 32 characters."
private const val ROOT_FILTER_DOC = "Only those on this root. Item id: a UUID or a hex prefix of 8 to 32 characters."
private const val ITEM_FILTER_DOC = "Only those on this item (a UUID or a hex prefix)."
private const val LIMIT_DOC = "Page size, 1 to 200 (default 50)."
private const val CURSOR_DOC = "The page.next value of the previous page."

/** `runs_read get`: one run. */
@Serializable
data class RunsGetRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef
)

/** `runs_read frontier`: the ready, in-flight and waiting members of one run. */
@Serializable
data class RunsFrontierRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef
)

/** `runs_read seats`: the seat assignments of one run. */
@Serializable
data class RunsSeatsRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef,
    @Doc(ITEM_FILTER_DOC) val itemId: IdRef? = null,
    @Doc("true: include ended seats; default only active ones.") val ended: Boolean? = null
)

/** `runs_read list`: runs, newest first. */
@Serializable
data class RunsListRequest(
    @Doc(ROOT_FILTER_DOC) val rootId: IdRef? = null,
    @Doc("Only runs in this status: open, paused or closed.") val status: RunStatusFilter? = null,
    @Doc("Only runs closed at or after this instant (ISO 8601 UTC, for example 2026-10-13T00:00:00Z).")
    @Serializable(with = IsoInstantSerializer::class)
    val endedSince: Instant? = null,
    @Doc(
        "Only closed runs that do not carry this mark key (lowercase letters, digits, - and _, starting with a letter). Implies closed runs."
    )
    val unmarked: String? = null,
    @Doc(LIMIT_DOC) val limit: Int? = null,
    @Doc(CURSOR_DOC) val cursor: String? = null
)

/** `runs_read decisions`: decision requests, newest first. */
@Serializable
data class RunsDecisionsRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef? = null,
    @Doc(ITEM_FILTER_DOC) val itemId: IdRef? = null,
    @Doc(ROOT_FILTER_DOC) val rootId: IdRef? = null,
    @Doc("true: only decisions past their expiry.") val overdue: Boolean? = null,
    @Doc(LIMIT_DOC) val limit: Int? = null,
    @Doc(CURSOR_DOC) val cursor: String? = null
)

/** `runs_read decision`: one decision request. */
@Serializable
data class RunsDecisionRequest(
    @Doc(DECISION_ID_DOC) val decisionId: IdRef
)

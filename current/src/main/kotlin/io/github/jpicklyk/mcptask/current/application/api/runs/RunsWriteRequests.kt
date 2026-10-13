package io.github.jpicklyk.mcptask.current.application.api.runs

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why a caller closes a run. `budget_exhausted` is server-only and is not accepted. */
@Serializable
enum class RunCloseReason(
    val domain: RunTerminalReason
) {
    @SerialName("completed")
    COMPLETED(RunTerminalReason.COMPLETED),

    @SerialName("cancelled")
    CANCELLED(RunTerminalReason.CANCELLED),

    @SerialName("failed")
    FAILED(RunTerminalReason.FAILED),

    @SerialName("abandoned")
    ABANDONED(RunTerminalReason.ABANDONED)
}

/** A run budget as the caller writes it. Every field is optional; an unset field is unlimited (or, on update, unchanged). */
@Serializable
@Doc("A run budget. Every field is optional; an unset field is unlimited. On update a field can only be lowered or newly set.")
data class RunBudgetRequest(
    @Doc("Token limit.") val tokens: Long? = null,
    @Doc("Cost limit in US dollars, a decimal such as 12.5.") val costUsd: Double? = null,
    @Doc("Most attempts one seat may take.") val attemptsPerSeat: Int? = null,
    @Doc("Most seats active at once.") val maxActiveSeats: Int? = null
)

private const val BUDGET_DOC =
    "Run budget: tokens, costUsd, attemptsPerSeat, maxActiveSeats; absent means no cap. On open it may not exceed a " +
        "configured ceiling; on update a field can only be lowered or newly set."
private const val HINTS_DOC = "Short structured coordinator hints, string to string. On update they replace the stored hints."
private const val ITEM_DOC = "Item id: a UUID or a hex prefix of 8 to 32 characters."

/** `runs_write open`: starts a run over a set of items of one root. */
@Serializable
data class RunsOpenRequest(
    @Doc("The root item the run belongs to. Item id: a UUID or a hex prefix of 8 to 32 characters.") val rootId: IdRef,
    @Doc("Member items, at most 500. Each is an item of the root that has a lifecycle and is in no other open run.")
    val items: List<IdRef> = emptyList(),
    @Doc("An item the run is scoped to. $ITEM_DOC") val scopeId: IdRef? = null,
    @Doc("A short label, at most 64 characters.") val label: String? = null,
    @Doc("A title, at most 200 characters.") val title: String? = null,
    @Doc(BUDGET_DOC) val budget: RunBudgetRequest? = null,
    @Doc("Seconds without a coordinator sign of life before the run reads as stalled. Default from config.")
    val stallAfterSeconds: Int? = null,
    @Doc(HINTS_DOC) val hints: Map<String, String>? = null
)

/** `runs_write update`: changes an open run. */
@Serializable
data class RunsUpdateRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef,
    @Doc("Items to add to the run. $ITEM_DOC") val addItems: List<IdRef> = emptyList(),
    @Doc("Items to remove from the run. $ITEM_DOC") val removeItems: List<IdRef> = emptyList(),
    @Doc(BUDGET_DOC) val budget: RunBudgetRequest? = null,
    @Doc(HINTS_DOC) val hints: Map<String, String>? = null,
    @Doc("Fails with version_conflict unless the run is at this version.") val expectedVersion: Long? = null
)

/** `runs_write close`: ends a run. */
@Serializable
data class RunsCloseRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef,
    @Doc("Why the run ends: completed, cancelled, failed or abandoned. completed is refused while a seat is active.")
    val reason: RunCloseReason
)

/** `runs_write pause`: pauses an open run. */
@Serializable
data class RunsPauseRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef
)

/** `runs_write resume`: resumes a manually paused run. */
@Serializable
data class RunsResumeRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef
)

/** `runs_write mark`: sets a named mark on a run. */
@Serializable
data class RunsMarkRequest(
    @Doc(RUN_ID_DOC) val runId: IdRef,
    @Doc("The mark key: lowercase letters, digits, - and _, starting with a letter, at most 64 characters.") val key: String
)

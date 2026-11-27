package io.github.jpicklyk.mcptask.current.itemwrite.internal.wire

import io.github.jpicklyk.mcptask.current.item.WorkItemRepository
import io.github.jpicklyk.mcptask.current.itemwrite.CascadeSummary
import io.github.jpicklyk.mcptask.current.lifecycle.AdvanceCascadeEvent
import io.github.jpicklyk.mcptask.current.model.config.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.platform.call.CallContext
import io.github.jpicklyk.mcptask.current.platform.sqlite.PersistenceFaults
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Optional fields an `update` can set to null. */
@Serializable
enum class ClearField {
    @SerialName("description")
    DESCRIPTION,

    @SerialName("statusLabel")
    STATUS_LABEL,

    @SerialName("metadata")
    METADATA,

    @SerialName("complexity")
    COMPLEXITY,

    @SerialName("rank")
    RANK,

    @SerialName("properties")
    PROPERTIES,

    @SerialName("parentId")
    PARENT_ID
}

/** Field documentation shared by the ops: a field in two ops must carry the same text (one generated schema). */
internal object ItemDocs {
    const val ID = "Item UUID or 8+ hex prefix."
    const val TITLE = "Item title, max 500 chars."
    const val TYPE = "Declared item type; required on create."
    const val PARENT_ID = "Parent id; omit for a root. Update moves the item."
    const val DESCRIPTION = "Long description."
    const val SUMMARY = "Short summary, max 2000 chars."
    const val STATUS_LABEL = "Status label."
    const val PRIORITY = "Create defaults to medium."
    const val COMPLEXITY = "1 to 10."
    const val RANK = "Order in next; lower first."
    const val REQUIRES_VERIFICATION = "Completion needs verification."
    const val METADATA = "Metadata text."
    const val TAGS = "Lowercase, hyphenated. Update: [] clears."
    const val TRAITS = "Declared traits, max 100 chars each. Update: [] clears."
    const val TREE_TAGS = "Lowercase, hyphenated."
    const val TREE_TRAITS = "Declared traits, max 100 chars each."
    const val PROPERTIES = "JSON object; no traits key."
    const val EXPECTED_VERSION = "Fails version_conflict if the item is at another version."
    const val CLEAR = "Update only: optional fields to set to null."
    const val RECURSIVE = "Delete the subtree; required when the item has children."
}

/**
 * The cascades an old parent ran; an unapplied one carries the catalog code that blocked it (`not_claim_holder` for a
 * parent another principal holds, checked first). Under a restricted scope a parent the scope does not admit is
 * reduced to `{itemId}` (one [ScopeGuard.admitted] read); an unrestricted scope reads nothing.
 */
internal suspend fun cascadeSummaries(
    events: List<AdvanceCascadeEvent>,
    ctx: CallContext,
    repo: WorkItemRepository
): List<CascadeSummary> {
    if (events.isEmpty()) return emptyList()
    val admitted = if (ctx.scope.isUnrestricted) null else ScopeGuard.admitted(ctx.scope, events.mapTo(LinkedHashSet()) { it.itemId }, repo)
    return events.map { event ->
        if (admitted != null && event.itemId !in admitted) return@map CascadeSummary.redacted(event.itemId)
        CascadeSummary(
            itemId = event.itemId,
            from = event.previousRole.name.lowercase(),
            to = event.targetRole.name.lowercase(),
            applied = event.applied,
            reason =
                when {
                    event.applied -> null
                    event.ownershipBlocked -> ErrorCode.NOT_CLAIM_HOLDER.wire
                    event.seatBlocked -> ErrorCode.SEAT_FORBIDDEN.wire
                    event.gateBlocked -> ErrorCode.GATE_BLOCKED.wire
                    event.resourceBlocked -> ErrorCode.RESOURCE_UNAVAILABLE.wire
                    event.dependencyBlocked -> ErrorCode.DEPENDENCY_UNMET.wire
                    else -> ErrorCode.INVALID_TRANSITION.wire
                }
        )
    }
}

/**
 * Runs [block] as the element's body (the dispatcher has already installed the caller as the event actor): a per-root
 * config fault escaping the service fails this element as `unavailable` instead of `internal`.
 */
internal suspend fun <T> itemWriteBody(
    ctx: CallContext,
    block: suspend () -> Outcome<T>
): Outcome<T> =
    try {
        block()
    } catch (e: PerRootConfigUnavailableException) {
        Outcome.Err(
            DomainError(
                ErrorCode.UNAVAILABLE,
                e.message,
                ErrorDetail.Unavailable(PersistenceFaults.BUSY_RETRY_AFTER_MS)
            )
        )
    }

/** Joins [tags] as the stored comma-separated column; an empty list is no tags. */
internal fun joinTags(tags: List<String>): String? = tags.joinToString(",").takeIf { it.isNotBlank() }

/**
 * The `forbidden{root}` error for a depth-0 write (create a root, move an item to the top level) the caller's scope does
 * not own. `required` is the sorted listed root ids, or empty under a tag-only scope.
 */
internal fun rootWriteForbidden(ctx: CallContext): DomainError =
    ScopeGuard.forbidden(
        ForbiddenScope.ROOT,
        ctx.scope.rootIds
            ?.map { it.toString() }
            .orEmpty()
    )

package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.time.Instant
import java.util.UUID

data class WorkItem(
    val id: UUID = UUID.randomUUID(),
    val parentId: UUID? = null,
    /**
     * Denormalized id of this item's depth-0 ancestor (its own [id] when [depth] is 0).
     * Maintained by the application layer: stamped on create (ItemCommandService, behind
     * manage_items, ItemWriteRoutes POST /items and create_work_tree) and restamped on reparent,
     * for the moved item and every descendant, in the same statement as the depth cascade
     * (ItemCommandService via HierarchyStore.restampSubtree, behind manage_items update and
     * ItemWriteRoutes PATCH).
     *
     * Every one of those write paths resolves this value (together with [depth]) from the
     * parent's CURRENT row via
     * [io.github.jpicklyk.mcptask.current.application.port.HierarchyStore.resolveChildPlacement],
     * called inside the same transaction as the write that stamps it — never from a parent read
     * taken in an earlier, separate transaction. See that method's KDoc for why (AR-19): a parent
     * reparented or deleted between an earlier read and a later write would otherwise leave this
     * field stamped with stale data.
     *
     * Nullable and NOT validated against [depth] or [parentId] here — rows written before
     * the root_id backfill migration (or in fixtures that construct [WorkItem]
     * directly without going through the create/reparent paths) may legitimately have a
     * null or stale value. Treat this as a best-effort denormalization for read-path scope
     * filtering, not a structural invariant enforced at the domain layer.
     */
    val rootId: UUID? = null,
    val title: String,
    val description: String? = null,
    val summary: String = "",
    val role: Role = Role.QUEUE,
    val statusLabel: String? = null,
    val previousRole: Role? = null,
    val priority: Priority = Priority.MEDIUM,
    val complexity: Int? = null,
    val requiresVerification: Boolean = false,
    val depth: Int = 0,
    val metadata: String? = null,
    val tags: String? = null,
    val type: String? = null,
    val properties: String? = null,
    val createdAt: Instant = Instant.now(),
    val modifiedAt: Instant = Instant.now(),
    val roleChangedAt: Instant = Instant.now(),
    val version: Long = 1,
    /** Opaque agent identifier that currently holds this item. Null when unclaimed. */
    val claimedBy: String? = null,
    /** When the current claim was placed (refreshes on re-claim). Stored as UTC in SQLite. */
    val claimedAt: Instant? = null,
    /**
     * TTL-based expiry for this claim: the claim instant plus the TTL, computed in Kotlin from the bound
     * clock and stored as canonical UTC text. An instant at or before "now" is expired (see [ClaimState]).
     */
    val claimExpiresAt: Instant? = null,
    /**
     * Timestamp of the first claim by the current agent — preserved across re-claims by the same
     * agent; reset when a different agent takes over the item.
     */
    val originalClaimedAt: Instant? = null,
    /**
     * Domain-invariant violations found when this item was rehydrated from storage (see [violations]);
     * null for a valid item, whether built by application code or read from a valid row - both validate on
     * construction. A stored row is never dropped for failing validation: the mapper returns it with its
     * (non-empty) violations here, copies of it skip construction-time validation, and every write validates
     * explicitly.
     */
    val diagnostics: List<String>? = null,
) {
    init {
        // A row rehydrated from storage arrives with [diagnostics] set (possibly empty): the total row
        // mapper must return every stored row, so it never throws here. Writes validate explicitly.
        if (diagnostics == null) validate()
    }

    /** Throws [ValidationException] for the first violation in [violations], if any. */
    fun validate() {
        violations().firstOrNull()?.let { throw ValidationException(it) }
    }

    /** Every domain-invariant violation of this item, in check order; empty when valid. */
    fun violations(): List<String> {
        val out = mutableListOf<String>()
        if (title.isBlank()) out += "Title must not be blank"
        if (title.length > 500) out += "Title must not exceed 500 characters"
        complexity?.let { if (it !in 1..10) out += "complexity must be between 1 and 10 if provided" }
        if (summary.length > 2000) out += "Summary must not exceed 2000 characters"
        if (depth < 0) out += "Depth must be non-negative"
        if (parentId == null && depth != 0) out += "Root items must have depth 0"
        if (parentId != null && depth < 1) out += "Child items must have depth >= 1"
        description?.let {
            if (it.isBlank()) out += "Description, if provided, must not be blank"
        }
        tags?.let { out += tagViolations(it) }

        // --- Claim-field invariants ---
        claimedBy?.let {
            if (it.isBlank()) out += "claimedBy must not be blank when set"
            if (it.length > 500) out += "claimedBy must not exceed 500 characters"
        }
        out += ClaimState.orderingViolations(claimedAt, claimExpiresAt, originalClaimedAt)
        // All-or-nothing coherence: all four claim fields must be null together or non-null together
        val claimFieldNullCount = listOf(claimedBy, claimedAt, claimExpiresAt, originalClaimedAt).count { it == null }
        if (claimFieldNullCount != 0 && claimFieldNullCount != 4) {
            out += "Claim fields (claimedBy, claimedAt, claimExpiresAt, originalClaimedAt) must all be set or all be null"
        }
        return out
    }

    /**
     * Create a new version with updated modifiedAt timestamp.
     * Ensures monotonic timestamp progression.
     */
    fun update(builder: (WorkItem) -> WorkItem): WorkItem {
        val updated = builder(this)
        val now = Instant.now()
        val newModifiedAt = if (now.isAfter(updated.modifiedAt)) now else updated.modifiedAt.plusMillis(1)
        return updated.copy(modifiedAt = newModifiedAt)
    }

    /** Parse comma-separated tags into a list. */
    fun tagList(): List<String> =
        tags
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    companion object {
        private val TAG_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")

        private fun tagViolations(tagString: String): List<String> =
            tagString
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { TAG_PATTERN.matches(it) }
                .map { "Tag '$it' is invalid. Tags must be lowercase alphanumeric with hyphens only." }
    }
}

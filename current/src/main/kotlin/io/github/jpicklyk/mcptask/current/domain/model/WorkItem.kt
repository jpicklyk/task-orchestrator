package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import java.time.Instant
import java.util.UUID

data class WorkItem(
    val id: UUID = UUID.randomUUID(),
    val parentId: UUID? = null,
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
     * TTL-based expiry for this claim. Computed DB-side via `datetime('now', '+N seconds')` to
     * keep time semantics consistent. Agents inspecting rows directly should treat this as UTC.
     */
    val claimExpiresAt: Instant? = null,
    /**
     * Timestamp of the first claim by the current agent — preserved across re-claims by the same
     * agent; reset when a different agent takes over the item.
     */
    val originalClaimedAt: Instant? = null,
) {
    init {
        validate()
    }

    fun validate() {
        if (title.isBlank()) throw ValidationException("Title must not be blank")
        if (title.length > 500) throw ValidationException("Title must not exceed 500 characters")
        complexity?.let { if (it !in 1..10) throw ValidationException("complexity must be between 1 and 10 if provided") }
        if (summary.length > 2000) throw ValidationException("Summary must not exceed 2000 characters")
        if (depth < 0) throw ValidationException("Depth must be non-negative")
        if (parentId == null && depth != 0) throw ValidationException("Root items must have depth 0")
        if (parentId != null && depth < 1) throw ValidationException("Child items must have depth >= 1")
        description?.let {
            if (it.isBlank()) throw ValidationException("Description, if provided, must not be blank")
        }
        tags?.let { validateTags(it) }

        // --- Claim-field invariants ---
        claimedBy?.let {
            if (it.isBlank()) throw ValidationException("claimedBy must not be blank when set")
            if (it.length > 500) throw ValidationException("claimedBy must not exceed 500 characters")
        }
        if (claimedAt != null && claimExpiresAt != null) {
            if (claimedAt.isAfter(claimExpiresAt)) {
                throw ValidationException("claimedAt must not be after claimExpiresAt")
            }
        }
        if (originalClaimedAt != null && claimedAt != null) {
            if (originalClaimedAt.isAfter(claimedAt)) {
                throw ValidationException("originalClaimedAt must not be after claimedAt")
            }
        }
        // All-or-nothing coherence: all four claim fields must be null together or non-null together
        val claimFieldNullCount = listOf(claimedBy, claimedAt, claimExpiresAt, originalClaimedAt).count { it == null }
        if (claimFieldNullCount != 0 && claimFieldNullCount != 4) {
            throw ValidationException(
                "Claim fields (claimedBy, claimedAt, claimExpiresAt, originalClaimedAt) must all be set or all be null"
            )
        }
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

        private fun validateTags(tagString: String) {
            val parsed = tagString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            parsed.forEach { tag ->
                if (!TAG_PATTERN.matches(tag)) {
                    throw ValidationException("Tag '$tag' is invalid. Tags must be lowercase alphanumeric with hyphens only.")
                }
            }
        }
    }
}

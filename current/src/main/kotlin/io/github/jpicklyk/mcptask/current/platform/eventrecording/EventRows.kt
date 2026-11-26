package io.github.jpicklyk.mcptask.current.platform.eventrecording

import io.github.jpicklyk.mcptask.current.model.item.WorkItem

/*
 * The typed rows the write services record (plan section 3.7). Each service records its own rows through
 * `WriteScope.events`, inside its own unit; these builders keep the payload shapes in one place.
 */

/**
 * The item fields whose change an `item.updated` row reports (bookkeeping fields excluded: version, timestamps).
 * `role` is reported only when it changes outside a transition: a lifecycle flip at a type change, which writes no
 * transition row (a transition never reaches this function). `["*"]` when there is no prior row.
 */
internal fun itemChangedFields(
    old: WorkItem?,
    new: WorkItem
): List<String> {
    if (old == null) return listOf("*")
    return buildList {
        if (old.title != new.title) add("title")
        if (old.description != new.description) add("description")
        if (old.summary != new.summary) add("summary")
        if (old.role != new.role) add("role")
        if (old.statusLabel != new.statusLabel) add("statusLabel")
        if (old.previousRole != new.previousRole) add("previousRole")
        if (old.priority != new.priority) add("priority")
        if (old.complexity != new.complexity) add("complexity")
        if (old.requiresVerification != new.requiresVerification) add("requiresVerification")
        if (old.rootId != new.rootId) add("rootId")
        if (old.depth != new.depth) add("depth")
        if (old.metadata != new.metadata) add("metadata")
        if (old.tags != new.tags) add("tags")
        if (old.type != new.type) add("type")
        if (old.properties != new.properties) add("properties")
        if (old.traits != new.traits) add("traits")
        if (old.claimedBy != new.claimedBy) add("claimedBy")
        if (old.claimExpiresAt != new.claimExpiresAt) add("claimExpiresAt")
    }
}

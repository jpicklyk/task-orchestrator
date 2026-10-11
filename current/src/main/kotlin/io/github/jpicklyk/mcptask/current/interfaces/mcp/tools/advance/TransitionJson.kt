package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.application.service.AdvanceCascadeEvent
import io.github.jpicklyk.mcptask.current.application.service.AdvanceUnblockedItem
import io.github.jpicklyk.mcptask.current.application.service.BlockerInfo
import io.github.jpicklyk.mcptask.current.domain.model.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The wire shapes of one applied transition's parts, shared by the `advance` `transition` op and the `items_write`
 * `complete_tree` op so a cascade or an unblocked item reads the same in both.
 */
internal object TransitionJson {
    /** A role on the wire: lowercase. */
    fun role(role: Role): String = role.name.lowercase()

    /** The `cascades` array, or null when there is none. */
    fun cascades(events: List<AdvanceCascadeEvent>): JsonArray? =
        events.takeIf { it.isNotEmpty() }?.let { list -> JsonArray(list.map(::cascade)) }

    /** The `unblocked` array, or null when there is none. */
    fun unblocked(items: List<AdvanceUnblockedItem>): JsonArray? =
        items.takeIf { it.isNotEmpty() }?.let { list ->
            buildJsonArray {
                list.forEach {
                    add(
                        buildJsonObject {
                            put("itemId", it.itemId.toString())
                            put("title", it.title)
                        }
                    )
                }
            }
        }

    /** One cascade: the parent, its roles, whether it applied, and what blocked it when it did not. */
    fun cascade(event: AdvanceCascadeEvent): JsonObject =
        buildJsonObject {
            put("itemId", event.itemId.toString())
            put("previousRole", role(event.previousRole))
            put("targetRole", role(event.targetRole))
            put("applied", event.applied)
            val blocked =
                when {
                    event.gateBlocked -> "gate"
                    event.resourceBlocked -> "resource"
                    event.roleBlocked -> "role"
                    event.dependencyBlocked -> "dependency"
                    else -> null
                }
            blocked?.let { put("blocked", it) }
            if (event.gateBlocked && event.gateMissingNotes.isNotEmpty()) {
                put(
                    "missing",
                    buildJsonArray {
                        event.gateMissingNotes.forEach { note ->
                            add(
                                buildJsonObject {
                                    put("key", note.key)
                                    put("role", role(note.role))
                                    note.seat?.let { put("seat", it) }
                                }
                            )
                        }
                    }
                )
            }
            if (event.resourceBlocked && event.contendedResources.isNotEmpty()) {
                put("contended", JsonArray(event.contendedResources.map { JsonPrimitive(it) }))
            }
            if (event.dependencyBlocked && event.blockers.isNotEmpty()) {
                put(
                    "blockers",
                    buildJsonArray {
                        event.blockers.forEach { blocker ->
                            add(
                                buildJsonObject {
                                    put("id", blocker.fromItemId.toString())
                                    put("role", blocker.currentRole?.let(::role) ?: BlockerInfo.UNKNOWN_ROLE)
                                }
                            )
                        }
                    }
                )
            }
        }
}

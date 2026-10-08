package io.github.jpicklyk.mcptask.current.test

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The response without what legitimately differs between a first call and its replay: the
 * `replayed` marker a stored result carries and the top-level per-call `metadata` envelope block.
 * Nested `metadata` (for example an item's own metadata) is preserved.
 */
fun JsonElement.sansReplay(): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(filterKeys { it != "metadata" }).stripReplayed()
        else -> stripReplayed()
    }

private fun JsonElement.stripReplayed(): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(filterKeys { it != "replayed" }.mapValues { (_, v) -> v.stripReplayed() })
        is JsonArray -> JsonArray(map { it.stripReplayed() })
        else -> this
    }

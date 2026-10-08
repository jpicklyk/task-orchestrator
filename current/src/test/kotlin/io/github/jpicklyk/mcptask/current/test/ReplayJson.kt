package io.github.jpicklyk.mcptask.current.test

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The response without what legitimately differs between a first call and its replay: the
 * `replayed` marker a stored result carries and the per-call `metadata` envelope block.
 */
fun JsonElement.sansReplay(): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(filterKeys { it != "replayed" && it != "metadata" }.mapValues { (_, v) -> v.sansReplay() })
        is JsonArray -> JsonArray(map { it.sansReplay() })
        else -> this
    }

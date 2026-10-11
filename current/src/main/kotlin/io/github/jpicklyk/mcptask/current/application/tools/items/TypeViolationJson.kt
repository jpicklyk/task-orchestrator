package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [this] element failure with the `availableTypes` / `availableTraits` a type or trait rejection ([ItemCommandErrors.isTypeViolation]) lists, so a
 * caller can correct the `type` without a second round trip. Present only when the rejection carries a non-empty set
 * (a schema-free server has no types to offer).
 */
internal fun JsonObject.withAvailableTypes(error: DomainError): JsonObject {
    val types = ItemCommandErrors.availableTypes(error)?.takeIf { it.isNotEmpty() }
    val traits = ItemCommandErrors.availableTraits(error)?.takeIf { it.isNotEmpty() }
    if (types == null && traits == null) return this
    val extra = mutableMapOf<String, JsonElement>()
    types?.let { extra["availableTypes"] = JsonArray(it.map { name -> JsonPrimitive(name) }) }
    traits?.let { extra["availableTraits"] = JsonArray(it.map { name -> JsonPrimitive(name) }) }
    return JsonObject(this + extra)
}

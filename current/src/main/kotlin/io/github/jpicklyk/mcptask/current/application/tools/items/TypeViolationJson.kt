package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [this] element failure with the `availableTypes` a type rejection ([ItemCommandErrors.isTypeViolation]) lists, so a
 * caller can correct the `type` without a second round trip. Present only when the rejection carries a non-empty set
 * (a schema-free server has no types to offer).
 */
internal fun JsonObject.withAvailableTypes(error: DomainError): JsonObject {
    val available = ItemCommandErrors.availableTypes(error)?.takeIf { it.isNotEmpty() } ?: return this
    return JsonObject(this + ("availableTypes" to JsonArray(available.map { JsonPrimitive(it) })))
}

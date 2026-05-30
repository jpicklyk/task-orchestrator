package io.github.jpicklyk.mcptask.current.application.service.rest

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * RFC 7396 JSON Merge Patch implementation.
 *
 * Rules:
 * - If the patch value is NOT a JsonObject (null/scalar/array), it replaces the target wholesale.
 * - If the patch value IS a JsonObject, iterate its keys:
 *   - JsonNull value → remove the key from target
 *   - JsonObject value → recurse merge on the sub-object
 *   - Any other value (scalar/array) → replace the key value wholesale
 * - Keys absent from the patch are left untouched in the target.
 */
object MergePatchApplier {
    /** RFC 7396 JSON Merge Patch. Arrays replace; null deletes; nested objects merge recursively. */
    fun apply(target: JsonElement, patch: JsonElement): JsonElement {
        if (patch !is JsonObject) return patch // scalar/array/null replaces wholesale
        val base = (target as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        for ((key, patchValue) in patch) {
            when {
                patchValue is JsonNull -> base.remove(key) // null = delete
                patchValue is JsonObject -> {
                    val sub = base[key]
                    base[key] = if (sub is JsonObject) apply(sub, patchValue) else patchValue
                }
                else -> base[key] = patchValue // scalar/array overwrite
            }
        }
        return JsonObject(base)
    }
}

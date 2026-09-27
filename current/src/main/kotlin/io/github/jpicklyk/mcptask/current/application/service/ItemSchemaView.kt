package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.SchemaMatch
import io.github.jpicklyk.mcptask.current.application.config.ServerFeatures
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * The ONE builder behind BOTH `query_items`'s `schema` operation (MCP) and
 * `GET /api/v1/items/{id}/schema` (REST, A1c) — a shared REST route must reuse [buildItemSchemaJson]
 * rather than replicate this shape, per A1 task-scope §6 AC3. Pure and JSON-in/JSON-out: no tool or
 * route framework types appear here, so REST can call it directly.
 *
 * Response shape (both entry points): `{ type, configFingerprint, configSource, notes: [...],
 * dispatch?, resources?, seats?, dispatchBySeat?, features }`. Every field beyond the pre-A1 set
 * (`seats`, `dispatchBySeat`, per-note `seat`/`independentOf`) is present only for a seat-aware
 * schema, EXCEPT `features`, which is always present (A1 task-scope §6, §9 "Alternatives" (f)).
 */
object ItemSchemaView {
    /**
     * Builds the schema-view JSON for [item] via [resolver], or `null` when the item is in
     * schema-free mode (no schema matches its type/tags). Resolves dispatch and resource
     * requirements from the ITEM's own traits (per-item traits first, then the resolved schema's
     * `defaultTraits` — see [EffectiveConfigResolver.resolveDispatchProfile]).
     */
    suspend fun buildItemSchemaJson(
        item: WorkItem,
        resolver: EffectiveConfigResolver
    ): JsonObject? {
        val resolved = resolver.resolveSchemaWithSource(item) ?: return null
        val schema = resolved.schema
        val dispatchByRole = resolver.resolveDispatchProfiles(item, schema)
        val resourcesList = resolver.resolveResourceRequirements(item)
        val dispatchBySeat = resolver.resolveDispatchBySeat(item, schema)
        return buildSchemaJson(resolved, dispatchByRole, resourcesList, dispatchBySeat)
    }

    /**
     * Type-only counterpart to [buildItemSchemaJson]: no item, so dispatch/resources resolve from
     * [WorkItemSchema.defaultTraits] alone (no per-item trait merge, no per-item trait order). `null`
     * when neither config layer defines [type] under [rootId]'s effective `schema_resolution` mode.
     */
    suspend fun buildTypeSchemaJson(
        type: String,
        rootId: UUID?,
        resolver: EffectiveConfigResolver
    ): JsonObject? {
        val resolved = resolver.resolveTypeSchema(type, rootId) ?: return null
        val schema = resolved.schema
        val dispatchByRole = resolver.resolveDispatchProfilesForType(schema.defaultTraits, rootId)
        val resourcesList = resolver.resolveResourceRequirementsForType(schema.defaultTraits, rootId)
        val dispatchBySeat = resolver.resolveDispatchBySeatForType(schema.defaultTraits, schema.seats, rootId)
        return buildSchemaJson(resolved, dispatchByRole, resourcesList, dispatchBySeat)
    }

    private fun buildSchemaJson(
        resolved: SchemaMatch,
        dispatchByRole: Map<Role, DispatchProfile>,
        resourcesList: List<ResourceRequirement>,
        dispatchBySeat: Map<Role, Map<String, DispatchProfile>>
    ): JsonObject {
        val (schema, source, fingerprint) = resolved
        val seatAware = schema.isSeatAware()
        return buildJsonObject {
            put("type", JsonPrimitive(schema.type))
            put("configFingerprint", fingerprint?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("configSource", JsonPrimitive(if (source == ConfigSource.PER_ROOT) "per-root" else "global"))
            put("notes", buildFullSchemaEntriesJson(schema.notes, seatAware))
            buildDispatchByRoleJson(dispatchByRole)?.let { put("dispatch", it) }
            buildResourcesJson(resourcesList)?.let { put("resources", it) }
            if (seatAware) {
                buildSeatsJson(schema.seats)?.let { put("seats", it) }
                buildDispatchBySeatJson(dispatchBySeat)?.let { put("dispatchBySeat", it) }
            }
            put("features", JsonArray(ServerFeatures.ADVERTISED.map { JsonPrimitive(it) }))
        }
    }
}

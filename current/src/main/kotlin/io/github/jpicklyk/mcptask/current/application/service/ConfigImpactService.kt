package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.LayeredConfig
import io.github.jpicklyk.mcptask.current.application.config.TraitResolver
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.util.UUID

/** One schema side of an impact comparison: the base schema's key and layer (null = schema-free), lifecycle and gate notes. */
data class ImpactSide(
    val key: String?,
    val layer: ConfigSource?,
    val lifecycle: String?,
    val notes: Map<String, Pair<Role, Boolean>>,
)

/** An in-flight item whose resolved schema would change on repin after the candidate is pushed. */
data class AffectedItem(
    val itemId: UUID,
    val changedNotes: List<String>,
    val schemaFrom: ImpactSide?,
    val schemaTo: ImpactSide?,
    val schemaChanged: Boolean,
    val lifecycleFrom: String?,
    val lifecycleTo: String?,
    val lifecycleChanged: Boolean,
)

/** The in-flight impact of a candidate config: the exact [count] of affected visible items and the first [sample]. */
data class InFlightImpact(
    val count: Int,
    val sample: List<AffectedItem>
)

/**
 * Which non-terminal items of a root's subtree would resolve a different schema if [candidate] were pushed and the
 * items repinned. The "now" side is [EffectiveConfigResolver.forItem] (pin, else heads); the "after" side is the Q9
 * precedence walk over the candidate as the per-root layer and the current server head (what a repin would pin to).
 * A note counts as changed only when its key is added or removed, or its role or required flag changes.
 *
 * Config read faults propagate unchanged (fail closed, no partial result).
 */
class ConfigImpactService(
    private val resolver: EffectiveConfigResolver,
    private val unitOfWork: UnitOfWork,
) {
    suspend fun affectedInFlight(
        rootId: UUID,
        candidate: ConfigDocument,
        scope: AccessScope,
    ): InFlightImpact =
        unitOfWork.read {
            val store = stores.itemStore()
            val candidateConfig =
                LayeredConfig(rootId, ConfigLayer(candidate, null, ConfigSource.PER_ROOT), resolver.layered(rootId).global)
            val nowConfigs = HashMap<List<Any?>, LayeredConfig>()
            val nowSides = HashMap<List<Any?>, ImpactSide>()
            val afterSides = HashMap<List<Any?>, ImpactSide>()
            var count = 0
            val sample = ArrayList<AffectedItem>()
            var offset = 0
            while (true) {
                val page = store.findByRoles(IN_FLIGHT, setOf(rootId), PAGE_SIZE, offset, scope)
                for (item in page.items) {
                    val pin = item.pin
                    val groupKey: List<Any?> =
                        if (pin == null) {
                            listOf("head", item.rootId)
                        } else {
                            listOf("pin", item.rootId, pin.serverVersionId, pin.rootVersionId, pin.schemaKey, pin.schemaLayer)
                        }
                    val nowConfig = nowConfigs[groupKey] ?: resolver.forItem(item).also { nowConfigs[groupKey] = it }
                    val typeKey = groupKey + listOf(item.type, item.traits)
                    val now = nowSides.getOrPut(typeKey) { side(nowConfig, item) }
                    val after = afterSides.getOrPut(listOf(item.type, item.traits)) { side(candidateConfig, item) }
                    val affected = compare(item.id, now, after) ?: continue
                    count++
                    if (sample.size < SAMPLE_CAP) sample += affected
                }
                offset += page.items.size
                if (page.items.isEmpty() || offset >= page.total) break
            }
            InFlightImpact(count, sample)
        }

    private fun side(
        config: LayeredConfig,
        item: WorkItem
    ): ImpactSide {
        val base = config.baseSchemaEntry(item.type) ?: return ImpactSide(null, null, null, emptyMap())
        val merged = TraitResolver(config).mergeSchema(base.schema, item.traits, item.id)
        return ImpactSide(
            key = base.key,
            layer = base.source,
            lifecycle = merged.lifecycleMode.name,
            notes = merged.notes.associate { it.key to (it.role to it.required) },
        )
    }

    private fun compare(
        id: UUID,
        now: ImpactSide,
        after: ImpactSide
    ): AffectedItem? {
        val changed = (now.notes.keys + after.notes.keys).filter { now.notes[it] != after.notes[it] }.sorted()
        val schemaChanged = now.key != after.key || now.layer != after.layer
        val lifecycleChanged = now.lifecycle != after.lifecycle
        if (changed.isEmpty() && !schemaChanged && !lifecycleChanged) return null
        return AffectedItem(id, changed, now, after, schemaChanged, now.lifecycle, after.lifecycle, lifecycleChanged)
    }

    private companion object {
        val IN_FLIGHT = setOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.BLOCKED)
        const val PAGE_SIZE = 500
        const val SAMPLE_CAP = 20
    }
}

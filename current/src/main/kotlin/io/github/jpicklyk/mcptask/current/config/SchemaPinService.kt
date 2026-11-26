package io.github.jpicklyk.mcptask.current.config

import io.github.jpicklyk.mcptask.current.config.internal.app.TraitResolver
import io.github.jpicklyk.mcptask.current.item.PinViews
import io.github.jpicklyk.mcptask.current.model.event.DomainEvent
import io.github.jpicklyk.mcptask.current.model.event.PinReason
import io.github.jpicklyk.mcptask.current.model.event.PinRef
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.schema.PinLayer
import io.github.jpicklyk.mcptask.current.model.schema.PinView
import io.github.jpicklyk.mcptask.current.model.schema.SchemaPin
import java.time.Instant
import java.util.UUID

/**
 * THE pin writer (plan section 3.8): the only application code that constructs a [SchemaPin]. A pin records the config
 * heads an item resolves against (the server and its root's current versions) and the base schema the Q9 rule gives
 * its type under them, plus that schema's lifecycle and whether it builds once merged with the item's traits. The pin points (create, type change, trait change, cross-root
 * reparent, reopen, `repin`) call [pinFor]; [view] renders a pin for the read surfaces.
 *
 * Reads go through [configResolver] (session-memoised heads, cached versions); a config read fault propagates as
 * `PerRootConfigUnavailableException` (fail closed: an item is never pinned to a guessed version). With pins off
 * ([enabled] false: the resolver cannot read versions by id) callers write no pins.
 *
 * [configStore], when given, answers a pinned version's number for a version the resolver cannot load as a layer.
 */
class SchemaPinService(
    private val configResolver: EffectiveConfigResolver,
    private val configStore: ConfigStore? = null
) : PinViews {
    /** True when items can be pinned (the resolver reads stored versions by id). */
    val enabled: Boolean get() = configResolver.pinsEnabled

    /**
     * The pin [item] gets under [rootId] at [now]: the current server and root head version ids (null for a scope with
     * no config or a reset head), the base schema the precedence rule gives [item]'s type at those heads (key and
     * layer; both null when schema-free), that schema's lifecycle, and its `builds` merged with [item]'s traits.
     */
    suspend fun pinFor(
        item: WorkItem,
        rootId: UUID?,
        now: Instant
    ): SchemaPin = pinsFor(listOf(item), rootId, now).getValue(item.id)

    /**
     * The pins [items] get under [rootId] at [now], by item id: [pinFor] for each, over ONE read of the heads and one
     * base-schema resolution per distinct (type, traits) pair (a moved subtree re-pins without a per-item config read).
     */
    suspend fun pinsFor(
        items: Collection<WorkItem>,
        rootId: UUID?,
        now: Instant
    ): Map<UUID, SchemaPin> {
        if (items.isEmpty()) return emptyMap()
        val layered = configResolver.layered(rootId)
        val serverVersionId = layered.global.versionId()
        val rootVersionId = layered.perRoot?.versionId
        val traitResolver = TraitResolver(layered)
        val byTypeAndTraits = HashMap<Pair<String?, List<String>>, SchemaPin>()
        return items.associate { item ->
            item.id to
                byTypeAndTraits.getOrPut(item.type to item.traits) {
                    val base = layered.baseSchemaEntry(item.type)
                    SchemaPin(
                        serverVersionId = serverVersionId,
                        rootVersionId = rootVersionId,
                        schemaKey = base?.key,
                        schemaLayer = base?.source?.let { layerOf(it) },
                        lifecycle = base?.schema?.lifecycleMode,
                        pinnedAt = now,
                        builds = base?.let { traitResolver.mergeSchema(it.schema, item.traits, item.id).builds }
                    )
                }
        }
    }

    /**
     * The read-side view of [item]'s pin, or null when it is unpinned. Stale compares each pinned version id with the
     * current head of that scope (a missing or reset head counts as none). A pinned version that IS the head takes its
     * number from the head already read (no version lookup).
     */
    override suspend fun view(item: WorkItem): PinView? {
        val pin = item.pin ?: return null
        val head = configResolver.layered(item.rootId)
        val serverHead = head.global.versionId()
        val rootHead = head.perRoot?.versionId
        return PinView(
            serverVersion = if (pin.serverVersionId == serverHead) head.global.version() else versionNumber(pin.serverVersionId),
            rootVersion = if (pin.rootVersionId == rootHead) head.perRoot?.version else versionNumber(pin.rootVersionId),
            key = pin.schemaKey,
            layer = pin.schemaLayer,
            stale = pin.serverVersionId != serverHead || pin.rootVersionId != rootHead
        )
    }

    /**
     * The `server:<n|none>/root:<n|none>` labels of [item]'s pinned versions and of the current heads of its root, as
     * `schema_pinned_conflict` reports them. [item] must be pinned.
     */
    suspend fun versionLabels(item: WorkItem): Pair<String, String> {
        val pin = requireNotNull(item.pin) { "item ${item.id} is not pinned" }
        val head = configResolver.layered(item.rootId)
        val serverNow = head.global.version()
        val rootNow = head.perRoot?.version
        val server = if (pin.serverVersionId == head.global.versionId()) serverNow else versionNumber(pin.serverVersionId)
        val root = if (pin.rootVersionId == head.perRoot?.versionId) rootNow else versionNumber(pin.rootVersionId)
        return label(server, root) to label(serverNow, rootNow)
    }

    private fun label(
        server: Int?,
        root: Int?
    ): String = "server:${server ?: "none"}/root:${root ?: "none"}"

    /** The version number of stored version [versionId], or null when [versionId] is null or unknown. */
    private suspend fun versionNumber(versionId: UUID?): Int? {
        if (versionId == null) return null
        return configResolver.versionLayer(versionId)?.version ?: configStore?.version(versionId)?.version
    }

    companion object {
        /**
         * True when [a] and [b] pin the same versions, base schema, lifecycle and builds (the pin instant is ignored). A null
         * builds (a pin taken before V38) reads as true, so it equals a pin whose resolved builds is the default.
         */
        fun samePin(
            a: SchemaPin?,
            b: SchemaPin?
        ): Boolean =
            a?.serverVersionId == b?.serverVersionId &&
                a?.rootVersionId == b?.rootVersionId &&
                a?.schemaKey == b?.schemaKey &&
                a?.schemaLayer == b?.schemaLayer &&
                a?.lifecycle == b?.lifecycle &&
                (a?.builds ?: true) == (b?.builds ?: true) &&
                (a == null) == (b == null)

        /**
         * The event side of [pin]. `builds` is read as [samePin] reads it (a NULL on a keyed pin is `true`), so two
         * sides differ in `builds` exactly when [samePin]'s `builds` comparison sees a change; a schema-free pin has none.
         */
        fun refOf(pin: SchemaPin): PinRef =
            PinRef(
                pin.serverVersionId,
                pin.rootVersionId,
                pin.schemaKey,
                pin.schemaLayer,
                pin.schemaKey?.let { pin.builds ?: true }
            )

        /** The `item.pinned` row for [itemId] (under [rootId]) moving from [from] to [to] for [reason]. */
        fun pinnedEvent(
            itemId: UUID,
            rootId: UUID,
            from: SchemaPin?,
            to: SchemaPin,
            reason: PinReason
        ): DomainEvent.ItemPinned = DomainEvent.ItemPinned(itemId, rootId, from?.let { refOf(it) }, refOf(to), reason)

        private fun layerOf(source: ConfigSource): PinLayer =
            when (source) {
                ConfigSource.PER_ROOT -> PinLayer.ROOT
                ConfigSource.GLOBAL -> PinLayer.SERVER
            }
    }
}

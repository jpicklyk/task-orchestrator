package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.event.PinReason
import io.github.jpicklyk.mcptask.current.domain.event.PinRef
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.PinLayer
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SchemaPin
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import java.time.Instant
import java.util.UUID

/**
 * The read-side view of an item's pin: the pinned server and root config version NUMBERS, the pinned base schema
 * [key] and [layer], and whether the pin is [stale] (either pinned version differs from that scope's current head).
 */
data class PinView(
    val serverVersion: Int?,
    val rootVersion: Int?,
    val key: String?,
    val layer: PinLayer?,
    val stale: Boolean
)

/**
 * THE pin writer (plan section 3.8): the only application code that constructs a [SchemaPin]. A pin records the config
 * heads an item resolves against (the server and its root's current versions) and the base schema the Q9 rule gives
 * its type under them, plus that schema's lifecycle. The pin points (create, type change, trait change, cross-root
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
) {
    /** True when items can be pinned (the resolver reads stored versions by id). */
    val enabled: Boolean get() = configResolver.pinsEnabled

    /**
     * The pin [item] gets under [rootId] at [now]: the current server and root head version ids (null for a scope with
     * no config or a reset head), the base schema the precedence rule gives [item]'s type at those heads (key and
     * layer; both null when schema-free) and that schema's lifecycle.
     */
    suspend fun pinFor(
        item: WorkItem,
        rootId: UUID?,
        now: Instant
    ): SchemaPin = pinsFor(listOf(item), rootId, now).getValue(item.id)

    /**
     * The pins [items] get under [rootId] at [now], by item id: [pinFor] for each, over ONE read of the heads and one
     * base-schema resolution per distinct type (a moved subtree re-pins without a per-item config read).
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
        val byType = HashMap<String?, SchemaPin>()
        return items.associate { item ->
            item.id to
                byType.getOrPut(item.type) {
                    val base = layered.baseSchemaEntry(item.type)
                    SchemaPin(
                        serverVersionId = serverVersionId,
                        rootVersionId = rootVersionId,
                        schemaKey = base?.key,
                        schemaLayer = base?.source?.let { layerOf(it) },
                        lifecycle = base?.schema?.lifecycleMode,
                        pinnedAt = now
                    )
                }
        }
    }

    /**
     * The read-side view of [item]'s pin, or null when it is unpinned. Stale compares each pinned version id with the
     * current head of that scope (a missing or reset head counts as none). A pinned version that IS the head takes its
     * number from the head already read (no version lookup).
     */
    suspend fun view(item: WorkItem): PinView? {
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
        /** True when [a] and [b] pin the same versions, base schema and lifecycle (the pin instant is ignored). */
        fun samePin(
            a: SchemaPin?,
            b: SchemaPin?
        ): Boolean =
            a?.serverVersionId == b?.serverVersionId &&
                a?.rootVersionId == b?.rootVersionId &&
                a?.schemaKey == b?.schemaKey &&
                a?.schemaLayer == b?.schemaLayer &&
                a?.lifecycle == b?.lifecycle &&
                (a == null) == (b == null)

        /**
         * True when storing [pin] on an item with [role] would move it across the lifecycle class boundary: a
         * lifecycle-free item (null role) takes a pin whose lifecycle is not [LifecycleMode.NONE], or a role-bearing
         * item takes a NONE pin. Only a type change and `repin` may flip an item's class; every other pin point
         * rejects a crossing pin.
         */
        fun crossesLifecycleClass(
            role: Role?,
            pin: SchemaPin
        ): Boolean = (role == null) != (pin.lifecycle == LifecycleMode.NONE)

        /** The event side of [pin]. */
        fun refOf(pin: SchemaPin): PinRef = PinRef(pin.serverVersionId, pin.rootVersionId, pin.schemaKey, pin.schemaLayer)

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

package io.github.jpicklyk.mcptask.current.domain.model

import java.time.Instant
import java.util.UUID

/** Which config layer supplied a pinned base schema. [wire] is the stored `pinned_schema_layer` value. */
enum class PinLayer(
    val wire: String
) {
    SERVER("server"),
    ROOT("root");

    companion object {
        /** The layer named by [wire] (exactly `server` or `root`), else null. */
        fun fromWire(wire: String?): PinLayer? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The config an item is pinned to (plan section 3.8): the server and per-root config VERSIONS it resolves against, and
 * the base schema (key + layer) chosen when the pin was taken, plus that schema's lifecycle.
 *
 * - [serverVersionId] / [rootVersionId]: the `config_versions` row of each scope that was the head when the pin was
 *   taken; null when that scope had no config (no version, or a reset head).
 * - [schemaKey] / [schemaLayer]: the base schema the item resolved to; both null when it resolved schema-free.
 * - [lifecycle]: that schema's lifecycle mode; null when schema-free.
 * - [pinnedAt]: when the pin was taken.
 *
 * Only the application's pin writer (`SchemaPinService`) and the `pin-backfill` data step produce pins; everything else
 * reads them. The invariants are checked by [WorkItem.violations].
 */
data class SchemaPin(
    val serverVersionId: UUID?,
    val rootVersionId: UUID?,
    val schemaKey: String?,
    val schemaLayer: PinLayer?,
    val lifecycle: LifecycleMode?,
    val pinnedAt: Instant
) {
    /** Every invariant violation of this pin, in check order; empty when valid. */
    fun violations(): List<String> {
        val out = mutableListOf<String>()
        if ((schemaKey == null) != (schemaLayer == null)) out += "Pin schema key and schema layer must be set or null together"
        if (lifecycle != null && schemaKey == null) out += "Pin lifecycle may be set only with a schema key"
        return out
    }
}

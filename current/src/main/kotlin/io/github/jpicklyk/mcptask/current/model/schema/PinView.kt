package io.github.jpicklyk.mcptask.current.model.schema

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

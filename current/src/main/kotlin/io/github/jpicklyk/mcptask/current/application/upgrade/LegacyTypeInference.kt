package io.github.jpicklyk.mcptask.current.application.upgrade

/**
 * FROZEN copy of the 3.x tag step of schema resolution, used once by the `type-backfill` data step to give an item
 * that has no `type` the schema key the 3.x server would have resolved it to by its tags (plan section 3.7, U8a).
 *
 * It deliberately re-implements the rule instead of calling `LayeredConfig`: the production tag fallback is deleted
 * later, and this upgrade behaviour must outlive it unchanged. It owns its own mode enum and layer shape for the same
 * reason, and imports nothing from the config or infrastructure packages.
 *
 * The invariant it upholds, per mode: for any layers and tags, resolving an item with no type by its tags before the
 * upgrade picks the same base schema as resolving it by the returned type after the upgrade. In LEGACY the 3.x type
 * step consults the per-root `default` BEFORE the global layer, so the per-root `default` is inferred before any
 * global tag there; LAYERED tries both layers' tags before either `default`; ISOLATED never looks at the global layer.
 * A tag that matches nothing leaves the item [UNTYPED], which resolves exactly as no type did.
 */
object LegacyTypeInference {
    /** The residue value for an item no schema key matches; a reserved value, not a creatable type. */
    const val UNTYPED = "untyped"

    /** The schema key every layer may declare as its catch-all. */
    const val DEFAULT_KEY = "default"

    /** The 3.x `schema_resolution` modes. */
    enum class Mode { LEGACY, LAYERED, ISOLATED }

    /** One config layer as the 3.x server saw it: its schema keys and its own `schema_resolution` (null = absent). */
    data class Layer(
        val schemaKeys: Set<String>,
        val mode: Mode? = null
    )

    /** The mode named by [raw]: exactly `legacy`, `layered` or `isolated` (lowercase), else null. */
    fun parseMode(raw: String?): Mode? =
        when (raw) {
            "legacy" -> Mode.LEGACY
            "layered" -> Mode.LAYERED
            "isolated" -> Mode.ISOLATED
            else -> null
        }

    /** The tags of a comma-separated tag column: split on commas, trimmed, blanks dropped, order and duplicates kept. */
    fun parseTags(csv: String?): List<String> =
        csv
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** The per-root mode wins; else the global one (a global ISOLATED has no layer above it to isolate from, so LAYERED); else LEGACY. */
    fun effectiveMode(
        perRoot: Layer?,
        global: Layer?
    ): Mode =
        perRoot?.mode
            ?: global?.mode?.let { if (it == Mode.ISOLATED) Mode.LAYERED else it }
            ?: Mode.LEGACY

    /** The type the 3.x tag step resolved an untyped item carrying [tags] to under [perRoot] and [global], or [UNTYPED]. */
    fun infer(
        tags: List<String>,
        perRoot: Layer?,
        global: Layer?
    ): String = resolve(tags, perRoot, global)?.key ?: UNTYPED

    /** A 3.x resolution: the matched schema [key] and whether the per-root layer ([perRoot] true) or the global one supplied it. */
    data class Resolved(
        val key: String,
        val perRoot: Boolean
    )

    /**
     * The schema key the 3.x tag step resolved [tags] to under [perRoot] and [global], with the layer that declared it;
     * null when nothing matched. [infer] is this without the layer (and [UNTYPED] for no match). The `pin-backfill`
     * data step uses it with an item's `type` as the only tag to find the base schema a 3.x server gave that item.
     */
    fun resolve(
        tags: List<String>,
        perRoot: Layer?,
        global: Layer?
    ): Resolved? {
        val perRootKeys = perRoot?.schemaKeys
        val globalKeys = global?.schemaKeys
        val inPerRoot = { key: String? -> key?.let { Resolved(it, perRoot = true) } }
        val inGlobal = { key: String? -> key?.let { Resolved(it, perRoot = false) } }
        return when (effectiveMode(perRoot, global)) {
            Mode.LEGACY ->
                inPerRoot(firstTag(tags, perRootKeys))
                    ?: inPerRoot(defaultIn(perRootKeys))
                    ?: inGlobal(firstTag(tags, globalKeys))
                    ?: inGlobal(defaultIn(globalKeys))
            Mode.LAYERED ->
                inPerRoot(firstTag(tags, perRootKeys))
                    ?: inGlobal(firstTag(tags, globalKeys))
                    ?: inPerRoot(defaultIn(perRootKeys))
                    ?: inGlobal(defaultIn(globalKeys))
            Mode.ISOLATED -> inPerRoot(firstTag(tags, perRootKeys)) ?: inPerRoot(defaultIn(perRootKeys))
        }
    }

    private fun firstTag(
        tags: List<String>,
        keys: Set<String>?
    ): String? = if (keys == null) null else tags.firstOrNull { it in keys }

    private fun defaultIn(keys: Set<String>?): String? = if (keys != null && DEFAULT_KEY in keys) DEFAULT_KEY else null
}

package io.github.jpicklyk.mcptask.current.application.config

/**
 * What changes between the head config version of a root and a proposed document, per kind of entry: [types]
 * (`work_item_schemas` / `note_schemas` entries), [traits] (`traits` entries) and [settings] (every other top-level
 * key, for example `note_limits` or `extensions`).
 */
data class ConfigDiff(
    val types: ConfigDelta,
    val traits: ConfigDelta,
    val settings: ConfigDelta,
) {
    /** True when the proposed document is identical to the head in every kind. */
    val isEmpty: Boolean get() = types.isEmpty && traits.isEmpty && settings.isEmpty

    companion object {
        private val TYPE_SECTIONS = setOf("work_item_schemas", "note_schemas")

        /**
         * Diffs [proposed] against [head] (null or empty = no head, so everything is added). An entry is `changed`
         * when its canonical JSON differs; both lists of names are sorted.
         */
        fun between(
            head: Map<String, Any?>?,
            proposed: Map<String, Any?>?,
        ): ConfigDiff =
            ConfigDiff(
                types = delta(types(head), types(proposed)),
                traits = delta(entries(head, "traits"), entries(proposed, "traits")),
                settings = delta(settings(head), settings(proposed)),
            )

        private fun types(root: Map<String, Any?>?): Map<String, Any?> = TYPE_SECTIONS.fold(emptyMap()) { acc, s -> acc + entries(root, s) }

        private fun entries(
            root: Map<String, Any?>?,
            section: String,
        ): Map<String, Any?> = (root?.get(section) as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()

        private fun settings(root: Map<String, Any?>?): Map<String, Any?> =
            root?.filterKeys { it !in TYPE_SECTIONS && it != "traits" } ?: emptyMap()

        private fun delta(
            before: Map<String, Any?>,
            after: Map<String, Any?>,
        ): ConfigDelta =
            ConfigDelta(
                added = (after.keys - before.keys).sorted(),
                removed = (before.keys - after.keys).sorted(),
                changed = (after.keys intersect before.keys).filter { canonical(before[it]) != canonical(after[it]) }.sorted(),
            )

        private fun canonical(value: Any?): String = CanonicalConfigJson.encode(mapOf("v" to value))
    }
}

/** The entry names added, removed and changed (sorted) between two config documents, for one kind of entry. */
data class ConfigDelta(
    val added: List<String>,
    val removed: List<String>,
    val changed: List<String>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

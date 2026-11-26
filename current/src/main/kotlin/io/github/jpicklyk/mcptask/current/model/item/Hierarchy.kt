package io.github.jpicklyk.mcptask.current.model.item

/**
 * Hard bound on how many levels any repository-level hierarchy traversal will walk.
 *
 * Item depth is intentionally unbounded from V7 onward, so this is not a product limit — it is a
 * corruption backstop. A cyclic `parent_id` edge (reachable through pre-V7 rows, before the
 * cycle-check triggers were installed) otherwise makes every recursive descent run forever.
 * The value sits far above any plausible real tree, so hitting it means the data is cyclic, not deep.
 *
 * Traversals that feed destructive or whole-subtree work (`HierarchyStore.findDescendants`,
 * scope resolution behind `ItemStore.find` / `ItemStore.count`)
 * fail loud (throw) when the bound is hit — a silently short list is
 * worse than an error there. Search-scope traversals are merely bounded, since degraded results
 * beat failing every search on a corrupt database.
 */
const val MAX_TRAVERSAL_DEPTH: Int = 1000

package io.github.jpicklyk.mcptask.current.application.config

/**
 * Server-wide feature flags advertised to clients (A1) — `features` on `query_items`'s `schema`
 * operation, `GET /api/v1/info`, and the `.well-known` service descriptor.
 *
 * Advertised UNCONDITIONALLY (even for a seat-less config): a tools-only or REST-only client needs
 * a way to tell an old server (which never emits `seat`/`dispatchBySeat`/`missingBySeat` at all)
 * apart from a new server whose current config simply declares no seats — see A1 task-scope
 * "Alternatives" (f). Only ENFORCED/served facets are listed here — `independent_of` parses and
 * serves in A1 but is not enforced until A2, so it is deliberately absent (A1 non-goals; advertising
 * an unenforced facet would be the same silent-drop failure mode this list exists to prevent).
 */
object ServerFeatures {
    val ADVERTISED: List<String> = listOf("seats", "dispatchBySeat")
}

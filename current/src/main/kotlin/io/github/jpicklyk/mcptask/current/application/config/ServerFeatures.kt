package io.github.jpicklyk.mcptask.current.application.config

/**
 * Server-wide feature flags advertised to clients (A1) — `features` on `query_items`'s `schema`
 * operation, `GET /api/v1/info`, and the `.well-known` service descriptor.
 *
 * Advertised UNCONDITIONALLY (even for a seat-less config): a tools-only or REST-only client needs
 * a way to tell an old server (which never emits `seat`/`dispatchBySeat`/`missingBySeat` at all)
 * apart from a new server whose current config simply declares no seats — see A1 task-scope
 * "Alternatives" (f). Only ENFORCED/served facets are listed here — `independent_of` parsed and
 * served but was NOT enforced in A1 (A1 non-goals). A2 (independence attestation gate) now
 * enforces it, so `"independent_of"` joins the advertised list (A2-D2 = (a); A3 adds no feature
 * flag of its own).
 */
object ServerFeatures {
    val ADVERTISED: List<String> = listOf("seats", "dispatchBySeat", "independent_of")
}

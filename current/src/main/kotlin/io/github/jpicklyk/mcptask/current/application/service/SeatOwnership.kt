package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.OwningSeat
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema

/**
 * Reserved seat-bucket name for a required note with no owning seat (DEC-11): a note whose
 * [io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry.seat] is null, names a seat the
 * schema does not declare, or names a seat declared for a DIFFERENT phase than the note's own
 * [io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry.role]. A seat may never be named
 * this (a load-time structural error — `YamlSchemaParser.parseRoot`'s F3 check).
 */
const val UNOWNED_SEAT_BUCKET = "unowned"

/**
 * Buckets [missingKeys] — required note keys still missing for some gate check — by the seat that
 * owns each one, for the `missingBySeat` response field served by `items_read` `select=context` and the
 * `advance` `transition` gate-failure payloads.
 *
 * Returns `null` when [schema] is `null` or not seat-aware ([WorkItemSchema.isSeatAware] false) —
 * a seat-less schema never serves a `missingBySeat` key (byte-identity with pre-A1 responses).
 * Returns an empty map (never `null`) when [schema] is seat-aware but [missingKeys] is empty.
 *
 * A key's owner is its [OwningSeat] (the entry is looked up by key in [WorkItemSchema.notes]); a key
 * with no owning seat — no declared seat, an unknown seat name, or a phase mismatch — buckets under
 * [UNOWNED_SEAT_BUCKET].
 *
 * Buckets are non-empty only, ordered by [WorkItemSchema.seats]' merged order with
 * [UNOWNED_SEAT_BUCKET] always last; keys within a bucket keep [missingKeys]' own order.
 */
fun computeMissingBySeat(
    schema: WorkItemSchema?,
    missingKeys: List<String>
): Map<String, List<String>>? {
    if (schema == null || !schema.isSeatAware()) return null
    if (missingKeys.isEmpty()) return emptyMap()

    val notesByKey = schema.notes.associateBy { it.key }

    // Seed buckets in merged-seat order so non-empty ones surface in that order once filtered below.
    val buckets = LinkedHashMap<String, MutableList<String>>()
    for (seat in schema.seats) buckets.getOrPut(seat.name) { mutableListOf() }
    val unowned = mutableListOf<String>()

    for (key in missingKeys) {
        val owner = OwningSeat.of(notesByKey[key], schema.seats)
        if (owner != null) {
            buckets.getOrPut(owner) { mutableListOf() }.add(key)
        } else {
            unowned.add(key)
        }
    }

    val result = LinkedHashMap<String, List<String>>()
    for ((name, keys) in buckets) {
        if (keys.isNotEmpty()) result[name] = keys
    }
    if (unowned.isNotEmpty()) result[UNOWNED_SEAT_BUCKET] = unowned
    return result
}

package io.github.jpicklyk.mcptask.current.application.port

import java.time.Instant

/**
 * The ledger of applied once data steps (the `data_steps` table). Both methods run on the ambient unit when there is
 * one; [record] is a write and must run inside a write unit.
 */
interface DataStepStore {
    /** The names of every recorded step. */
    suspend fun appliedNames(): Set<String>

    /** Records [name] as applied. A name already recorded is a constraint failure (thrown). */
    suspend fun record(
        name: String,
        appliedAt: Instant,
        rowsAffected: Int,
        binaryVersion: String
    )
}

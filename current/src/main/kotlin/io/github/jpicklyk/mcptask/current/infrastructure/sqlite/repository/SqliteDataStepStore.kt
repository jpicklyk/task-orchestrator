package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.DataStepStore
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.DataStepsTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

/**
 * SQLite implementation of [DataStepStore] over [DataStepsTable]. Both methods join the ambient unit; [record] is a
 * write, so outside a unit it is subject to the outside-unit write policy. A duplicate name fails (plain INSERT).
 */
class SqliteDataStepStore(
    private val databaseManager: DatabaseManager
) : DataStepStore {
    override suspend fun appliedNames(): Set<String> =
        databaseManager.readTx {
            DataStepsTable.selectAll().map { it[DataStepsTable.name] }.toSet()
        }

    override suspend fun record(
        name: String,
        appliedAt: Instant,
        rowsAffected: Int,
        binaryVersion: String
    ) {
        databaseManager.writeTx("DataStepStore.record") {
            DataStepsTable.insert {
                it[DataStepsTable.name] = name
                it[DataStepsTable.appliedAt] = appliedAt
                it[DataStepsTable.rowsAffected] = rowsAffected
                it[DataStepsTable.binaryVersion] = binaryVersion
            }
        }
    }
}

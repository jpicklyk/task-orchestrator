package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.ProjectConfig
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ProjectConfigTable
import io.github.jpicklyk.mcptask.current.infrastructure.security.sha256Hex
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [ProjectConfigRepository], backed by [ProjectConfigTable].
 *
 * Upsert uses the same atomic `INSERT … ON CONFLICT DO UPDATE` pattern as
 * [SQLiteNoteRepository.upsertRow] (keyed on the unique `root_item_id` column here instead of
 * `(work_item_id, key)`) to avoid a SELECT-then-branch TOCTOU race between concurrent writers
 * deciding row EXISTENCE. Computing the new `fingerprint_history` value, however, needs the row's
 * prior `fingerprint`/`fingerprint_history` state, so [upsert] reads those two columns first,
 * inside the same `suspendedTransaction` as the upsert itself. This table is one row per project
 * root — tiny and low-write-frequency (config pushes, not hot-path reads) — so the extra read
 * inside the transaction is not a meaningful bottleneck or race window in practice.
 */
class SQLiteProjectConfigRepository(
    private val databaseManager: DatabaseManager
) : ProjectConfigRepository {
    override suspend fun upsert(
        rootItemId: UUID,
        configYaml: String
    ): Result<ProjectConfig> =
        databaseManager.suspendedTransaction("Failed to upsert ProjectConfig") {
            val fingerprint = computeFingerprint(configYaml)
            val now = Instant.now()

            val existing =
                ProjectConfigTable
                    .select(ProjectConfigTable.fingerprint, ProjectConfigTable.fingerprintHistory)
                    .where { ProjectConfigTable.rootItemId eq rootItemId }
                    .singleOrNull()

            // Unchanged-fingerprint re-push leaves history untouched; a changed fingerprint
            // prepends the OUTGOING (previous current) fingerprint, newest first, pruned to 20.
            val newFingerprintHistory =
                when {
                    existing == null -> null
                    existing[ProjectConfigTable.fingerprint] == fingerprint -> existing[ProjectConfigTable.fingerprintHistory]
                    else -> {
                        val previousFingerprint = existing[ProjectConfigTable.fingerprint]
                        val previousHistory = decodeHistory(existing[ProjectConfigTable.fingerprintHistory])
                        encodeHistory((listOf(previousFingerprint) + previousHistory).take(MAX_HISTORY_SIZE))
                    }
                }

            ProjectConfigTable.upsert(
                keys = arrayOf(ProjectConfigTable.rootItemId),
                onUpdate = {
                    it[ProjectConfigTable.configYaml] = configYaml
                    it[ProjectConfigTable.fingerprint] = fingerprint
                    it[ProjectConfigTable.updatedAt] = now
                    it[ProjectConfigTable.fingerprintHistory] = newFingerprintHistory
                },
            ) {
                it[ProjectConfigTable.rootItemId] = rootItemId
                it[ProjectConfigTable.configYaml] = configYaml
                it[ProjectConfigTable.fingerprint] = fingerprint
                it[ProjectConfigTable.updatedAt] = now
                it[ProjectConfigTable.fingerprintHistory] = newFingerprintHistory
            }

            Result.Success(
                ProjectConfig(
                    rootItemId = rootItemId,
                    configYaml = configYaml,
                    fingerprint = fingerprint,
                    updatedAt = now
                )
            )
        }

    override suspend fun get(rootItemId: UUID): Result<ProjectConfig?> =
        databaseManager.suspendedTransaction("Failed to get ProjectConfig") {
            val row =
                ProjectConfigTable
                    .selectAll()
                    .where { ProjectConfigTable.rootItemId eq rootItemId }
                    .singleOrNull()
            Result.Success(row?.let { mapRowToProjectConfig(it) })
        }

    override suspend fun getFingerprint(rootItemId: UUID): Result<String?> =
        databaseManager.suspendedTransaction("Failed to get ProjectConfig fingerprint") {
            val fingerprint =
                ProjectConfigTable
                    .select(ProjectConfigTable.fingerprint)
                    .where { ProjectConfigTable.rootItemId eq rootItemId }
                    .singleOrNull()
                    ?.get(ProjectConfigTable.fingerprint)
            Result.Success(fingerprint)
        }

    override suspend fun delete(rootItemId: UUID): Result<Boolean> =
        databaseManager.suspendedTransaction("Failed to delete ProjectConfig") {
            val deletedCount = ProjectConfigTable.deleteWhere { ProjectConfigTable.rootItemId eq rootItemId }
            Result.Success(deletedCount > 0)
        }

    override fun computeFingerprint(configYaml: String): String = sha256Hex(configYaml.toByteArray(Charsets.UTF_8))

    override suspend fun classifyFingerprint(
        rootItemId: UUID,
        fingerprint: String
    ): Result<FingerprintRelation> =
        databaseManager.suspendedTransaction("Failed to classify ProjectConfig fingerprint") {
            val row =
                ProjectConfigTable
                    .select(ProjectConfigTable.fingerprint, ProjectConfigTable.fingerprintHistory)
                    .where { ProjectConfigTable.rootItemId eq rootItemId }
                    .singleOrNull()

            val relation =
                when {
                    row == null -> FingerprintRelation.UNKNOWN
                    row[ProjectConfigTable.fingerprint] == fingerprint -> FingerprintRelation.CURRENT
                    decodeHistory(row[ProjectConfigTable.fingerprintHistory]).contains(fingerprint) -> FingerprintRelation.SUPERSEDED
                    else -> FingerprintRelation.UNKNOWN
                }
            Result.Success(relation)
        }

    private fun mapRowToProjectConfig(row: ResultRow): ProjectConfig =
        ProjectConfig(
            rootItemId = row[ProjectConfigTable.rootItemId],
            configYaml = row[ProjectConfigTable.configYaml],
            fingerprint = row[ProjectConfigTable.fingerprint],
            updatedAt = row[ProjectConfigTable.updatedAt]
        )

    /** Serializes a fingerprint list (newest first) to the `fingerprint_history` TEXT column's JSON array shape. */
    private fun encodeHistory(history: List<String>): String = buildJsonArray { history.forEach { add(JsonPrimitive(it)) } }.toString()

    /** Parses the `fingerprint_history` TEXT column; null/blank/malformed JSON all decode to "no known ancestors". */
    private fun decodeHistory(historyJson: String?): List<String> {
        if (historyJson.isNullOrBlank()) return emptyList()
        return try {
            Json.parseToJsonElement(historyJson).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception
        ) {
            emptyList()
        }
    }

    companion object {
        /** Fingerprint history is pruned to this many entries (newest first) on every changed-fingerprint upsert. */
        private const val MAX_HISTORY_SIZE = 20
    }
}

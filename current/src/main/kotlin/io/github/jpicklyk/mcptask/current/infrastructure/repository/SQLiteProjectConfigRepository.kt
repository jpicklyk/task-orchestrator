package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.ProjectConfig
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ProjectConfigTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [ProjectConfigRepository], backed by [ProjectConfigTable].
 *
 * Upsert uses the same atomic `INSERT … ON CONFLICT DO UPDATE` pattern as
 * [SQLiteNoteRepository.upsertRow] (keyed on the unique `root_item_id` column here instead of
 * `(work_item_id, key)`) to avoid a SELECT-then-branch TOCTOU race between concurrent writers for
 * the same root.
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

            ProjectConfigTable.upsert(
                keys = arrayOf(ProjectConfigTable.rootItemId),
                onUpdate = {
                    it[ProjectConfigTable.configYaml] = configYaml
                    it[ProjectConfigTable.fingerprint] = fingerprint
                    it[ProjectConfigTable.updatedAt] = now
                },
            ) {
                it[ProjectConfigTable.rootItemId] = rootItemId
                it[ProjectConfigTable.configYaml] = configYaml
                it[ProjectConfigTable.fingerprint] = fingerprint
                it[ProjectConfigTable.updatedAt] = now
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

    private fun mapRowToProjectConfig(row: ResultRow): ProjectConfig =
        ProjectConfig(
            rootItemId = row[ProjectConfigTable.rootItemId],
            configYaml = row[ProjectConfigTable.configYaml],
            fingerprint = row[ProjectConfigTable.fingerprint],
            updatedAt = row[ProjectConfigTable.updatedAt]
        )

    /** Mirrors [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService.getConfigFingerprint]'s SHA-256 approach. */
    private fun computeFingerprint(configYaml: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(configYaml.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.GuardedUpsertOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ProjectConfig
import io.github.jpicklyk.mcptask.current.infrastructure.security.configFingerprint
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.ProjectConfigTable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [ProjectConfigStore], backed by [ProjectConfigTable].
 *
 * [upsert] uses the same atomic `INSERT ... ON CONFLICT DO UPDATE` pattern as
 * [SQLiteNoteRepository.upsertRow] (keyed on the unique `root_item_id` column here instead of
 * `(work_item_id, key)`) to avoid a SELECT-then-branch TOCTOU race between concurrent writers
 * deciding row EXISTENCE. Computing the new `fingerprint_history` value, however, needs the row
 * prior `fingerprint`/`fingerprint_history` state, so [upsert] reads those two columns first,
 * inside the same transaction as the upsert itself. This table is one row per project
 * root -- tiny and low-write-frequency (config pushes, not hot-path reads) -- so the extra read
 * inside the transaction is not a meaningful bottleneck in practice, but [upsert] itself is
 * UNCONDITIONAL: it has no way to reject a write based on the row it just read. [upsertGuarded]
 * is the compare-and-set-aware sibling that closes that gap -- see its KDoc.
 *
 * @param beforeGuardedWrite test-only hook invoked by [upsertGuarded], inside its transaction,
 *   after the guard read and before the conditional write. Lets a race test run a competing write
 *   in that window (e.g. a joined write that changes the row), so the write below observes a lost
 *   compare-and-set and the call fails (single attempt). Always null in production.
 */
class SQLiteProjectConfigRepository(
    private val databaseManager: DatabaseManager,
    private val beforeGuardedWrite: (suspend (UUID) -> Unit)? = null
) : ProjectConfigStore {
    override suspend fun upsert(
        rootItemId: UUID,
        configYaml: String
    ): ProjectConfig =
        databaseManager.writeTx("ProjectConfigStore.upsert") {
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

            ProjectConfig(
                rootItemId = rootItemId,
                configYaml = configYaml,
                fingerprint = fingerprint,
                updatedAt = now
            )
        }

    override suspend fun upsertGuarded(
        rootItemId: UUID,
        configYaml: String,
        expectedFingerprint: String?,
        rejectSuperseded: Boolean
    ): GuardedUpsertOutcome {
        // ONE attempt, inside the caller's write unit (IMMEDIATE, single writer): no other writer can
        // commit between the guard read and the conditional write, so a 0-row write is an invariant
        // violation, not a lost race. SQLITE_BUSY is retried by the unit itself.
        return databaseManager.writeTx("ProjectConfigStore.upsertGuarded") {
            attemptGuardedUpsert(rootItemId, configYaml, expectedFingerprint, rejectSuperseded)
        } ?: throw IllegalStateException(
            "Guarded upsert of the project config for root $rootItemId lost its compare-and-set inside one unit"
        )
    }

    /**
     * Runs the guarded upsert inside an already-open transaction: reads the row, evaluates the
     * [rejectSuperseded] and [expectedFingerprint] guards against that SAME read, then writes
     * conditionally on the row being unchanged since the read. Returns the outcome, or null when
     * the conditional write affected zero rows (the caller treats that as an invariant violation).
     */
    private suspend fun attemptGuardedUpsert(
        rootItemId: UUID,
        configYaml: String,
        expectedFingerprint: String?,
        rejectSuperseded: Boolean
    ): GuardedUpsertOutcome? {
        val fingerprint = computeFingerprint(configYaml)
        val now = Instant.now()

        val existing =
            ProjectConfigTable
                .select(ProjectConfigTable.fingerprint, ProjectConfigTable.fingerprintHistory, ProjectConfigTable.updatedAt)
                .where { ProjectConfigTable.rootItemId eq rootItemId }
                .singleOrNull()

        beforeGuardedWrite?.invoke(rootItemId)

        if (existing == null) {
            // Nothing to compare expectedFingerprint or supersession against -- a first push is a
            // create.
            ProjectConfigTable.insert {
                it[ProjectConfigTable.rootItemId] = rootItemId
                it[ProjectConfigTable.configYaml] = configYaml
                it[ProjectConfigTable.fingerprint] = fingerprint
                it[ProjectConfigTable.updatedAt] = now
                it[ProjectConfigTable.fingerprintHistory] = null
            }
            return GuardedUpsertOutcome.Applied(
                ProjectConfig(rootItemId = rootItemId, configYaml = configYaml, fingerprint = fingerprint, updatedAt = now)
            )
        }

        val observedFingerprint = existing[ProjectConfigTable.fingerprint]

        if (rejectSuperseded) {
            val relation =
                when {
                    observedFingerprint == fingerprint -> FingerprintRelation.CURRENT
                    decodeHistory(existing[ProjectConfigTable.fingerprintHistory]).contains(fingerprint) -> FingerprintRelation.SUPERSEDED
                    else -> FingerprintRelation.UNKNOWN
                }
            if (relation == FingerprintRelation.SUPERSEDED) {
                return GuardedUpsertOutcome.Superseded(existing[ProjectConfigTable.updatedAt])
            }
        }

        if (expectedFingerprint != null && expectedFingerprint != observedFingerprint) {
            return GuardedUpsertOutcome.PreconditionFailed(observedFingerprint)
        }

        val newFingerprintHistory =
            if (observedFingerprint == fingerprint) {
                existing[ProjectConfigTable.fingerprintHistory]
            } else {
                val previousHistory = decodeHistory(existing[ProjectConfigTable.fingerprintHistory])
                encodeHistory((listOf(observedFingerprint) + previousHistory).take(MAX_HISTORY_SIZE))
            }

        // Conditional on the row still having the fingerprint we just observed: this is the
        // compare-and-set. A concurrent writer that committed a different fingerprint between our
        // read above and this statement makes the WHERE match zero rows (or, under SQLite's WAL
        // snapshot isolation, throws a snapshot-conflict exception instead) -- either way the
        // caller retries against the winner's now-current row.
        val updatedRows =
            ProjectConfigTable.update({
                (ProjectConfigTable.rootItemId eq rootItemId) and (ProjectConfigTable.fingerprint eq observedFingerprint)
            }) {
                it[ProjectConfigTable.configYaml] = configYaml
                it[ProjectConfigTable.fingerprint] = fingerprint
                it[ProjectConfigTable.updatedAt] = now
                it[ProjectConfigTable.fingerprintHistory] = newFingerprintHistory
            }

        if (updatedRows == 0) return null

        return GuardedUpsertOutcome.Applied(
            ProjectConfig(rootItemId = rootItemId, configYaml = configYaml, fingerprint = fingerprint, updatedAt = now)
        )
    }

    override suspend fun get(rootItemId: UUID): ProjectConfig? =
        databaseManager.readTx {
            val row =
                ProjectConfigTable
                    .selectAll()
                    .where { ProjectConfigTable.rootItemId eq rootItemId }
                    .singleOrNull()
            row?.let { mapRowToProjectConfig(it) }
        }

    override suspend fun getFingerprint(rootItemId: UUID): String? =
        databaseManager.readTx {
            val fingerprint =
                ProjectConfigTable
                    .select(ProjectConfigTable.fingerprint)
                    .where { ProjectConfigTable.rootItemId eq rootItemId }
                    .singleOrNull()
                    ?.get(ProjectConfigTable.fingerprint)
            fingerprint
        }

    override suspend fun delete(rootItemId: UUID): Boolean =
        databaseManager.writeTx("ProjectConfigStore.delete") {
            val deletedCount = ProjectConfigTable.deleteWhere { ProjectConfigTable.rootItemId eq rootItemId }
            deletedCount > 0
        }

    override fun computeFingerprint(configYaml: String): String = configFingerprint(configYaml)

    override suspend fun classifyFingerprint(
        rootItemId: UUID,
        fingerprint: String
    ): FingerprintRelation =
        databaseManager.readTx {
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
            relation
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

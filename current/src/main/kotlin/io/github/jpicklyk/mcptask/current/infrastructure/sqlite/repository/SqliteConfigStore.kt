package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.ConfigAppend
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigHead
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersion
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.infrastructure.security.configFingerprint
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.ConfigHeadsTable
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.ConfigKnownFingerprintsTable
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.ConfigVersionsTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of [ConfigStore] over [ConfigVersionsTable], [ConfigHeadsTable] and
 * [ConfigKnownFingerprintsTable]. Every method joins the ambient unit; [append] and [fillCanonical] are writes.
 */
class SqliteConfigStore(
    private val databaseManager: DatabaseManager
) : ConfigStore {
    override suspend fun head(
        scope: ConfigScope,
        rootId: UUID?
    ): ConfigHead? =
        databaseManager.readTx {
            readHead(scope, rootId)
        }

    override suspend fun version(id: UUID): ConfigVersion? =
        databaseManager.readTx {
            versionRow(id)
        }

    override suspend fun versionAt(
        scope: ConfigScope,
        rootId: UUID?,
        version: Int
    ): ConfigVersion? =
        databaseManager.readTx {
            ConfigVersionsTable
                .selectAll()
                .where { scopeMatch(scope, rootId) and (ConfigVersionsTable.version eq version) }
                .singleOrNull()
                ?.let(::mapVersion)
        }

    override suspend fun history(
        scope: ConfigScope,
        rootId: UUID?,
        limit: Int,
        before: Int?
    ): List<ConfigVersion> {
        require(limit in 1..MAX_HISTORY_LIMIT) { "limit must be between 1 and $MAX_HISTORY_LIMIT" }
        return databaseManager.readTx {
            ConfigVersionsTable
                .selectAll()
                .where {
                    val match = scopeMatch(scope, rootId)
                    if (before != null) match and (ConfigVersionsTable.version less before) else match
                }.orderBy(ConfigVersionsTable.version, SortOrder.DESC)
                .limit(limit)
                .map(::mapVersion)
        }
    }

    override suspend fun append(
        scope: ConfigScope,
        rootId: UUID?,
        append: ConfigAppend,
        expectedVersion: Int?,
        expectedFingerprint: String?,
        rejectSuperseded: Boolean,
        now: Instant
    ): ConfigAppendOutcome {
        require(scope == ConfigScope.ROOT) { "Server-scope config versions cannot be appended yet" }
        val root = requireNotNull(rootId) { "rootId is required for the root scope" }
        return databaseManager.writeTx("ConfigStore.append") {
            val head = readHead(scope, root)
            val headVersion = head?.let { versionRow(it.versionId) }

            if (head != null && headVersion != null) {
                if (rejectSuperseded && classify(root, append.fingerprint, head) == FingerprintRelation.SUPERSEDED) {
                    return@writeTx ConfigAppendOutcome.Superseded(head.version, headVersion.createdAt)
                }
                if ((expectedVersion != null && expectedVersion != head.version) ||
                    (expectedFingerprint != null && expectedFingerprint != head.fingerprint)
                ) {
                    return@writeTx ConfigAppendOutcome.PreconditionFailed(head.version, head.fingerprint)
                }
                // A reset is never "identical" to the head, and nothing is identical to a reset head: the empty
                // content of a RESET version is absence, not a config someone pushed.
                if (append.fingerprint == head.fingerprint &&
                    append.source != ConfigVersionSource.RESET &&
                    head.source != ConfigVersionSource.RESET
                ) {
                    return@writeTx ConfigAppendOutcome.Applied(headVersion, appended = false)
                }
            }

            val lastVersion =
                ConfigVersionsTable
                    .select(ConfigVersionsTable.version.max())
                    .where { scopeMatch(scope, root) }
                    .single()[ConfigVersionsTable.version.max()] ?: 0
            val newId = UUID.randomUUID()
            val created =
                ConfigVersion(
                    id = newId,
                    scope = scope,
                    rootId = root,
                    version = lastVersion + 1,
                    yaml = append.yaml,
                    fingerprint = append.fingerprint,
                    canonicalJson = append.canonicalJson,
                    modelVersion = append.modelVersion,
                    source = append.source,
                    principalId = append.principalId,
                    reqId = append.reqId,
                    createdAt = now,
                    warnings = append.warnings
                )
            ConfigVersionsTable.insert {
                it[ConfigVersionsTable.id] = newId
                it[ConfigVersionsTable.scope] = scope.wire
                it[ConfigVersionsTable.rootItemId] = root
                it[ConfigVersionsTable.version] = created.version
                it[ConfigVersionsTable.yaml] = created.yaml
                it[ConfigVersionsTable.fingerprint] = created.fingerprint
                it[ConfigVersionsTable.canonicalJson] = created.canonicalJson
                it[ConfigVersionsTable.modelVersion] = created.modelVersion
                it[ConfigVersionsTable.versionSource] = created.source.wire
                it[ConfigVersionsTable.principalId] = created.principalId
                it[ConfigVersionsTable.reqId] = created.reqId
                it[ConfigVersionsTable.createdAt] = now
                it[ConfigVersionsTable.warningsJson] = encodeWarnings(created.warnings)
            }
            if (head == null) {
                ConfigHeadsTable.insert {
                    it[ConfigHeadsTable.scope] = scope.wire
                    it[ConfigHeadsTable.rootItemId] = root
                    it[ConfigHeadsTable.versionId] = newId
                    it[ConfigHeadsTable.updatedAt] = now
                }
            } else {
                ConfigHeadsTable.update({ headMatch(scope, root) }) {
                    it[ConfigHeadsTable.versionId] = newId
                    it[ConfigHeadsTable.updatedAt] = now
                }
            }
            ConfigAppendOutcome.Applied(created, appended = true)
        }
    }

    override suspend fun classifyFingerprint(
        rootId: UUID,
        fingerprint: String
    ): FingerprintRelation =
        databaseManager.readTx {
            classify(rootId, fingerprint, readHead(ConfigScope.ROOT, rootId))
        }

    override suspend fun fillCanonical(
        id: UUID,
        canonicalJson: String,
        modelVersion: Int
    ): Boolean =
        databaseManager.writeTx("ConfigStore.fillCanonical") {
            ConfigVersionsTable.update({ (ConfigVersionsTable.id eq id) and ConfigVersionsTable.canonicalJson.isNull() }) {
                it[ConfigVersionsTable.canonicalJson] = canonicalJson
                it[ConfigVersionsTable.modelVersion] = modelVersion
            } > 0
        }

    override suspend fun versionsMissingCanonical(limit: Int): List<ConfigVersion> =
        databaseManager.readTx {
            ConfigVersionsTable
                .selectAll()
                .where { ConfigVersionsTable.canonicalJson.isNull() }
                .orderBy(ConfigVersionsTable.createdAt, SortOrder.ASC)
                .limit(limit)
                .map(::mapVersion)
        }

    override fun computeFingerprint(yaml: String): String = configFingerprint(yaml)

    // ---- inside-transaction helpers (callers hold a transaction) ----

    private fun scopeMatch(
        scope: ConfigScope,
        rootId: UUID?
    ): Op<Boolean> =
        if (rootId == null) {
            (ConfigVersionsTable.scope eq scope.wire) and ConfigVersionsTable.rootItemId.isNull()
        } else {
            (ConfigVersionsTable.scope eq scope.wire) and (ConfigVersionsTable.rootItemId eq rootId)
        }

    private fun headMatch(
        scope: ConfigScope,
        rootId: UUID?
    ): Op<Boolean> =
        if (rootId == null) {
            (ConfigHeadsTable.scope eq scope.wire) and ConfigHeadsTable.rootItemId.isNull()
        } else {
            (ConfigHeadsTable.scope eq scope.wire) and (ConfigHeadsTable.rootItemId eq rootId)
        }

    private fun readHead(
        scope: ConfigScope,
        rootId: UUID?
    ): ConfigHead? {
        val pointer = ConfigHeadsTable.selectAll().where { headMatch(scope, rootId) }.singleOrNull() ?: return null
        val versionId = pointer[ConfigHeadsTable.versionId]
        val row =
            ConfigVersionsTable
                .select(ConfigVersionsTable.version, ConfigVersionsTable.fingerprint, ConfigVersionsTable.versionSource)
                .where { ConfigVersionsTable.id eq versionId }
                .singleOrNull() ?: return null
        return ConfigHead(
            scope = scope,
            rootId = rootId,
            versionId = versionId,
            version = row[ConfigVersionsTable.version],
            fingerprint = row[ConfigVersionsTable.fingerprint],
            source = ConfigVersionSource.fromWire(row[ConfigVersionsTable.versionSource])
        )
    }

    private fun versionRow(id: UUID): ConfigVersion? =
        ConfigVersionsTable
            .selectAll()
            .where { ConfigVersionsTable.id eq id }
            .singleOrNull()
            ?.let(::mapVersion)

    /**
     * CURRENT when [fingerprint] is the head's; SUPERSEDED when a version after the last reset has it, or (no reset
     * yet) the root's pre-V25 history knew it; otherwise UNKNOWN. A reset starts a new epoch.
     */
    private fun classify(
        rootId: UUID,
        fingerprint: String,
        head: ConfigHead?
    ): FingerprintRelation {
        if (head != null && head.fingerprint == fingerprint) return FingerprintRelation.CURRENT
        val rootMatch = scopeMatch(ConfigScope.ROOT, rootId)
        val lastReset =
            ConfigVersionsTable
                .select(ConfigVersionsTable.version.max())
                .where { rootMatch and (ConfigVersionsTable.versionSource eq ConfigVersionSource.RESET.wire) }
                .single()[ConfigVersionsTable.version.max()] ?: 0
        val inEpoch =
            ConfigVersionsTable
                .select(ConfigVersionsTable.id)
                .where {
                    rootMatch and (ConfigVersionsTable.fingerprint eq fingerprint) and
                        (ConfigVersionsTable.version greater lastReset)
                }.limit(1)
                .any()
        if (inEpoch) return FingerprintRelation.SUPERSEDED
        if (lastReset == 0) {
            val known =
                ConfigKnownFingerprintsTable
                    .select(ConfigKnownFingerprintsTable.fingerprint)
                    .where {
                        (ConfigKnownFingerprintsTable.rootItemId eq rootId) and
                            (ConfigKnownFingerprintsTable.fingerprint eq fingerprint)
                    }.any()
            if (known) return FingerprintRelation.SUPERSEDED
        }
        return FingerprintRelation.UNKNOWN
    }

    private fun mapVersion(row: ResultRow): ConfigVersion =
        ConfigVersion(
            id = row[ConfigVersionsTable.id].value,
            scope = ConfigScope.entries.first { it.wire == row[ConfigVersionsTable.scope] },
            rootId = row[ConfigVersionsTable.rootItemId],
            version = row[ConfigVersionsTable.version],
            yaml = row[ConfigVersionsTable.yaml],
            fingerprint = row[ConfigVersionsTable.fingerprint],
            canonicalJson = row[ConfigVersionsTable.canonicalJson],
            modelVersion = row[ConfigVersionsTable.modelVersion],
            source = ConfigVersionSource.fromWire(row[ConfigVersionsTable.versionSource]),
            principalId = row[ConfigVersionsTable.principalId],
            reqId = row[ConfigVersionsTable.reqId],
            createdAt = row[ConfigVersionsTable.createdAt],
            warnings = decodeWarnings(row[ConfigVersionsTable.warningsJson])
        )

    private fun encodeWarnings(warnings: List<String>): String? =
        if (warnings.isEmpty()) null else buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } }.toString()

    private fun decodeWarnings(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception
        ) {
            emptyList()
        }
    }

    companion object {
        private const val MAX_HISTORY_LIMIT = 200
    }
}

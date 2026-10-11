package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.model.ConfigAppendOutcome
import io.github.jpicklyk.mcptask.current.domain.model.ConfigHead
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersion
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import java.time.Instant
import java.util.UUID

/** The content and provenance of one version to append (see [ConfigStore.append]). */
data class ConfigAppend(
    val yaml: String,
    val fingerprint: String,
    val canonicalJson: String?,
    val modelVersion: Int?,
    val source: ConfigVersionSource,
    val principalId: String?,
    val reqId: String?,
    val warnings: List<String>
)

/**
 * The versioned config store: immutable version rows plus a head pointer per scope.
 *
 * Versions are never updated or deleted; the only in-place write is [fillCanonical] (NULL -> value).
 */
interface ConfigStore {
    /** The head of ([scope], [rootId]), or null when the scope has no version at all. */
    suspend fun head(
        scope: ConfigScope,
        rootId: UUID?
    ): ConfigHead?

    suspend fun version(id: UUID): ConfigVersion?

    suspend fun versionAt(
        scope: ConfigScope,
        rootId: UUID?,
        version: Int
    ): ConfigVersion?

    /** Newest first; [before] = exclusive upper bound on version; limit 1..200. */
    suspend fun history(
        scope: ConfigScope,
        rootId: UUID?,
        limit: Int,
        before: Int? = null
    ): List<ConfigVersion>

    /**
     * Inside the ambient WRITE unit. Appends head+1 and moves the head in that unit.
     * [expectedVersion]: compare-and-set on head.version (ignored when no head). [expectedFingerprint]: legacy
     * compare-and-set on head.fingerprint (ignored when no head). [rejectSuperseded]: fast-forward guard.
     * Identical content (fingerprint == head.fingerprint) returns Applied(head, appended=false) and writes nothing.
     * `scope = ROOT` requires a non-null [rootId]; `scope = SERVER` requires a null one (both throw
     * [IllegalArgumentException] otherwise). For SERVER, [rejectSuperseded] is ignored: the server scope has no
     * known-fingerprint epoch ([classifyFingerprint] and `config_known_fingerprints` are root-only); [expectedVersion]
     * and [expectedFingerprint] still work.
     */
    suspend fun append(
        scope: ConfigScope,
        rootId: UUID?,
        append: ConfigAppend,
        expectedVersion: Int? = null,
        expectedFingerprint: String? = null,
        rejectSuperseded: Boolean = false,
        now: Instant
    ): ConfigAppendOutcome

    suspend fun classifyFingerprint(
        rootId: UUID,
        fingerprint: String
    ): FingerprintRelation

    /** The only in-place write: NULL -> value. Returns false when the row is missing or already filled. */
    suspend fun fillCanonical(
        id: UUID,
        canonicalJson: String,
        modelVersion: Int
    ): Boolean

    suspend fun versionsMissingCanonical(limit: Int = 500): List<ConfigVersion>

    /** The config fingerprint algorithm (SHA-256 over BOM-stripped, CRLF-normalized text). */
    fun computeFingerprint(yaml: String): String
}

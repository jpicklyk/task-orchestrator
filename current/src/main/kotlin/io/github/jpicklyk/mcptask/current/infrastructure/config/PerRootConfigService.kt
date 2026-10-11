package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.CanonicalConfigJson
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.UnitElement
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.model.ConfigHead
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.ConfigVersionSource
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Serves the per-root config document of each project root from the versioned [ConfigStore], exposing the same
 * schema/trait surface as [YamlWorkItemSchemaService] but scoped to a single project root (a depth-0 WorkItem UUID)
 * instead of the single global `.taskorchestrator/config.yaml`.
 *
 * This is the storage + service layer ONLY. Nothing here decides *when* a per-root config should override the
 * global one, or merges the two: that resolution logic belongs to the application-layer
 * `EffectiveConfigResolver`/`LayeredConfig`, which consume this class through [PerRootConfigSource.layer].
 *
 * ## Hot-reload contract
 *
 * Every read goes through [layer], which issues one cheap head read ([ConfigStore.head]) and then serves the
 * document of the head version from an immutable, bounded cache keyed by version id (a version never changes, so
 * its entry is never invalidated). A config pushed from this process or from another one sharing the SQLite file
 * becomes visible on the very next read: the head moved, the new version is loaded once. No restart, no explicit
 * cache-bust call.
 *
 * The document is built from the version's stored `canonical_json` (decoded map handed to the parser), never by
 * re-parsing the YAML.
 *
 * ## Failure handling
 *
 * Three distinct failure modes are handled differently, and the difference is load-bearing:
 *
 *  - **Absence.** The read succeeds and there is no head, or the head is a reset version. Both are "no per-root
 *    config": [layer] drops any last-known-good entry and returns null, and callers fall through to the global
 *    `.taskorchestrator/config.yaml` loader. NOT cached as a negative result.
 *  - **Invalid data.** The head version has NULL or undecodable `canonical_json`. This is NOT absence and never
 *    falls through to the global layer: [layer] throws [PerRootConfigInvalidException].
 *  - **Read failure.** A store read itself throws (e.g. a transient database error). [layer] logs a WARN naming the
 *    root and the error, and serves the last-known-good head+document for that root, if one exists, WITHOUT evicting
 *    it. Inside a unit of work no last-known-good is served (a cached document would hide a read fault the unit
 *    boundary translates and retries). With nothing to serve, [layer] throws [PerRootConfigUnavailableException] -
 *    this class does NOT silently fall back to the global layer on a read failure. Every public accessor propagates
 *    these exceptions unchanged; callers that need to translate them into a tool/HTTP outcome catch them at their
 *    own boundary.
 */
class PerRootConfigService(
    private val store: ConfigStore,
    private val parser: ConfigDocumentParser = YamlConfigDocumentParser,
) : PerRootConfigSource,
    ServerConfigSource {
    private val logger = LoggerFactory.getLogger(PerRootConfigService::class.java)

    private data class LastKnownGood(
        val head: ConfigHead,
        val document: ConfigDocument
    )

    /** The last head + document successfully served for each root; evicted only by a confirmed absence. */
    private val lastKnownGood = ConcurrentHashMap<UUID, LastKnownGood>()

    /** Immutable cache: version id -> its document. Versions never change, so entries are never invalidated. */
    private val documents: MutableMap<UUID, ConfigDocument> =
        Collections.synchronizedMap(
            object : LinkedHashMap<UUID, ConfigDocument>(DOCUMENT_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, ConfigDocument>): Boolean = size > DOCUMENT_CACHE_SIZE
            }
        )

    /**
     * Returns [rootId]'s current [ConfigLayer] (document + head fingerprint, tagged [ConfigSource.PER_ROOT]), or
     * `null` when there is no config for [rootId] or its head is a reset (both fall through to the global layer).
     * Callers needing several facets for the same root should call this once and read the document locally.
     * Throws [PerRootConfigInvalidException] / [PerRootConfigUnavailableException] per the class kdoc.
     */
    override suspend fun layer(rootId: UUID): ConfigLayer? = readLayer(ConfigScope.ROOT, rootId)

    /**
     * The server-scope counterpart of [layer] (the `(SERVER, null)` head, tagged [ConfigSource.GLOBAL]) with exactly
     * the same absence / invalid-data / read-failure rules; the exceptions carry a null root id.
     */
    override suspend fun layer(): ConfigLayer? = readLayer(ConfigScope.SERVER, null)

    private suspend fun readLayer(
        scope: ConfigScope,
        rootId: UUID?
    ): ConfigLayer? {
        val key = rootId ?: SERVER_KEY
        val head =
            try {
                store.head(scope, rootId)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                return lastKnownGoodOrThrow(scope, rootId, e)
            }
        if (head == null || head.source == ConfigVersionSource.RESET) {
            lastKnownGood.remove(key)
            return null
        }

        val document =
            documents[head.versionId] ?: loadDocument(rootId, head)?.also { documents[head.versionId] = it }
                ?: return lastKnownGoodOrThrow(scope, rootId, IllegalStateException("version row unreadable"))
        lastKnownGood.put(key, LastKnownGood(head, document))
        return ConfigLayer(document = document, fingerprint = head.fingerprint, source = sourceOf(scope))
    }

    private fun sourceOf(scope: ConfigScope): ConfigSource = if (scope == ConfigScope.SERVER) ConfigSource.GLOBAL else ConfigSource.PER_ROOT

    /** Loads and builds the document of [head]'s version; null only when the store read itself failed under LKG rules. */
    private suspend fun loadDocument(
        rootId: UUID?,
        head: ConfigHead
    ): ConfigDocument? {
        val version =
            try {
                store.version(head.versionId)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                return null
            } ?: throw PerRootConfigInvalidException(rootId, head.version, "the head version row is missing")
        val json = version.canonicalJson ?: throw PerRootConfigInvalidException(rootId, head.version, "no canonical JSON stored")
        val root =
            CanonicalConfigJson.decode(json) ?: throw PerRootConfigInvalidException(rootId, head.version, "canonical JSON is undecodable")
        return try {
            @Suppress("UNCHECKED_CAST")
            parser.fromRoot(root as Map<String, Any>, warnOnMissingSchemas = false)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            throw PerRootConfigInvalidException(rootId, head.version, "canonical JSON does not build a config document: ${e.message}")
        }
    }

    /** Returns the resolved `work_item_schemas` map for [rootItemId], or null when it has no config. */
    suspend fun getSchemas(rootItemId: UUID): Map<String, WorkItemSchema>? = layer(rootItemId)?.document?.workItemSchemas

    /** Returns the [WorkItemSchema] for [type] under [rootItemId]'s config, or null if no config/type match. */
    suspend fun getSchemaForType(
        rootItemId: UUID,
        type: String
    ): WorkItemSchema? = layer(rootItemId)?.document?.workItemSchemas?.get(type)

    /** Returns the note schema entries for trait [traitName] under [rootItemId]'s config, or null. */
    suspend fun getTraitNotes(
        rootItemId: UUID,
        traitName: String
    ): List<NoteSchemaEntry>? = layer(rootItemId)?.document?.traits?.get(traitName)

    /** Returns all trait definitions for [rootItemId]'s config, or null when it has no config. */
    suspend fun getAllTraits(rootItemId: UUID): Map<String, List<NoteSchemaEntry>>? = layer(rootItemId)?.document?.traits

    /**
     * Returns the head config fingerprint for [rootItemId], or null when it has no config. Always reflects the head
     * read by this call, so it is never stale after a concurrent push.
     */
    suspend fun getFingerprint(rootItemId: UUID): String? = layer(rootItemId)?.fingerprint

    /**
     * Returns [rootItemId]'s explicitly-configured `note_limits.mode`, or null when it has no config or its document
     * has no top-level `note_limits` key at all - see [ConfigDocument.noteLimitsMode]. Callers treat null as "fall
     * through to the global note-limits mode".
     */
    suspend fun getNoteLimitsMode(rootItemId: UUID): String? = layer(rootItemId)?.document?.noteLimitsMode

    /**
     * Returns [rootItemId]'s explicitly-configured `status_labels` trigger->label map, or null when it has no config
     * or no top-level `status_labels` key. A non-null return may still be a PARTIAL map - see
     * [ConfigDocument.statusLabels].
     */
    suspend fun getStatusLabels(rootItemId: UUID): Map<String, String?>? = layer(rootItemId)?.document?.statusLabels

    /**
     * Handles a fault thrown by a store read in [layer]: logs a WARN naming [rootItemId] and [error], and serves the
     * last-known-good head+document for that root WITHOUT evicting it. Throws [PerRootConfigUnavailableException]
     * when there is nothing to serve (cold cache) or when called inside a unit of work.
     */
    private suspend fun lastKnownGoodOrThrow(
        scope: ConfigScope,
        rootItemId: UUID?,
        error: Throwable
    ): ConfigLayer {
        val insideUnit = coroutineContext[UnitElement] != null
        val subject = if (rootItemId == null) "server config" else "per-root config for root $rootItemId"
        logger.warn("Config read failed for {}: {}", subject, error.message)
        if (!insideUnit) {
            lastKnownGood[rootItemId ?: SERVER_KEY]?.let {
                return ConfigLayer(document = it.document, fingerprint = it.head.fingerprint, source = sourceOf(scope))
            }
        }
        // The full store error (which may carry SQL/driver text) stays in the server log above; the exception
        // message reaches MCP and REST clients, so it names only the root.
        throw PerRootConfigUnavailableException(
            rootItemId,
            (if (rootItemId == null) "Server config" else "Per-root config for root $rootItemId") +
                " is temporarily unavailable " +
                if (insideUnit) "(read failed inside a unit of work)" else "(read failed; no last-known-good config cached)",
            error
        )
    }

    private companion object {
        const val DOCUMENT_CACHE_SIZE = 128

        /** Sentinel key of the server scope in the last-known-good map (no real root has the nil UUID). */
        val SERVER_KEY = UUID(0L, 0L)
    }
}

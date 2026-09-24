package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses and caches per-root config YAML documents (stored via [ProjectConfigRepository]),
 * exposing the same schema/trait surface as [YamlWorkItemSchemaService] but scoped to a single
 * project root (a depth-0 WorkItem UUID) instead of the single global `.taskorchestrator/config.yaml`.
 *
 * This is the storage + service layer ONLY. Nothing here decides *when* a per-root config should
 * override the global one, or merges the two — that resolution logic belongs to
 * `ToolExecutionContext.resolveSchema()` (a follow-on task), which is deliberately not touched by
 * this class.
 *
 * ## Hot-reload contract
 *
 * Every read ([getSchemas], [getSchemaForType], [getTraitNotes], [getAllTraits]) goes through
 * [resolve], which:
 *  1. Issues a cheap fingerprint-only read ([ProjectConfigRepository.getFingerprint]) — this never
 *     touches the `config_yaml` TEXT column.
 *  2. Compares it against this instance's in-memory cache for that root.
 *  3. On a match, returns the cached parse with no further I/O.
 *  4. On a mismatch (including "no cache entry yet"), reads the full row, re-parses, and replaces
 *     the cache entry.
 *
 * Because step 1 runs on *every* call rather than relying on a push-based invalidation signal, a
 * config pushed by [ProjectConfigRepository.upsert] — from this process or from a different one
 * entirely (e.g. another server instance sharing the same SQLite file) — becomes visible on the
 * very next read. No restart, no explicit cache-bust call, no coordination between instances
 * beyond the shared DB row. This is what "hot-reload" means for this service: it is a property of
 * every read path, not a separate mechanism that must be remembered to invoke.
 *
 * ## Failure handling
 *
 * Two distinct failure modes are handled differently, and the difference is load-bearing:
 *
 *  - **Absence.** The repository read succeeds and reports "no row for this root" (a fingerprint
 *    or row of `null`), or the stored YAML fails to parse (malformed document). Both are logged
 *    (parse failures as a warning) and treated as "no per-root config" — [resolve] evicts any
 *    cached entry and returns null, and callers fall through to the global
 *    `.taskorchestrator/config.yaml` loader. This is unchanged from before per-root error handling
 *    existed, and is NOT cached as a negative result — a cold or config-less root re-checks on
 *    every call.
 *  - **Read failure.** The repository read itself fails (`Result.Error`, e.g. a transient database
 *    error) — this is NOT the same as absence and must never be treated as "no per-root config".
 *    [resolve] logs a WARN naming the root and the error, and serves the last-known-good (LKG)
 *    cached parse for that root, if one exists, WITHOUT evicting it (an error can never evict —
 *    only a confirmed absence or a fresher fingerprint can replace an LKG entry). The LKG entry has
 *    no TTL; the next successful read refreshes it via the normal fingerprint-comparison path. When
 *    there is no LKG entry to serve (a cold cache, e.g. this process's first read for this root),
 *    [resolve] throws [PerRootConfigUnavailableException] — this class does NOT silently fall back
 *    to the global layer on a read failure. Every public accessor on this class propagates that
 *    exception unchanged; callers that need to translate it into a specific tool/HTTP outcome catch
 *    it at their own boundary.
 */
class PerRootConfigService(
    private val repository: ProjectConfigRepository
) {
    private val logger = LoggerFactory.getLogger(PerRootConfigService::class.java)

    private data class CacheEntry(
        val fingerprint: String,
        val parsed: YamlSchemaParser.ParsedConfig
    )

    /** In-memory cache keyed by root item UUID. Populated lazily on first [resolve] per root. */
    private val cache = ConcurrentHashMap<UUID, CacheEntry>()

    /**
     * A single-pass, single-root view combining every per-root config facet a caller might need
     * (schemas, traits, note-limits mode, status labels) plus the fingerprint it was resolved
     * against — everything [resolve] already parses in one pass, bundled instead of split across
     * the individual accessor methods below. Callers needing several of these facets for the same
     * [rootItemId] (e.g. [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext])
     * should call [getSnapshot] once and read the fields locally, instead of calling the
     * single-facet accessors once each — each of those independently re-invokes [resolve], which
     * costs at least one fingerprint-only DB read apiece even when the cache is warm.
     */
    data class Snapshot(
        val workItemSchemas: Map<String, WorkItemSchema>,
        val traits: Map<String, List<NoteSchemaEntry>>,
        val noteLimitsModeExplicit: String?,
        val statusLabels: Map<String, String?>?,
        val fingerprint: String,
        val traitResources: Map<String, List<ResourceRequirement>> = emptyMap(),
        val resourceRegistry: Map<String, ResourceDefinition> = emptyMap(),
        val traitDispatch: Map<String, Map<Role, DispatchProfile>> = emptyMap()
    )

    /**
     * Returns a [Snapshot] of every per-root config facet for [rootItemId] from a SINGLE [resolve]
     * pass, or null under the same conditions as every other accessor on this class: no config row
     * for [rootItemId], or the stored YAML fails to parse (both fall through to the global layer).
     * A repository READ error is different: it serves the last-known-good entry, or throws
     * [PerRootConfigUnavailableException] when none is cached — it never falls through.
     */
    suspend fun getSnapshot(rootItemId: UUID): Snapshot? {
        val parsed = resolve(rootItemId) ?: return null
        val fingerprint = cache[rootItemId]?.fingerprint ?: return null
        return Snapshot(
            workItemSchemas = parsed.workItemSchemas,
            traits = parsed.traits,
            noteLimitsModeExplicit = parsed.noteLimitsModeExplicit,
            statusLabels = parsed.statusLabels,
            fingerprint = fingerprint,
            traitResources = parsed.traitResources,
            resourceRegistry = parsed.resourceRegistry,
            traitDispatch = parsed.traitDispatch
        )
    }

    /** Returns the resolved `work_item_schemas` map for [rootItemId], or null when no config row exists or it fails to parse. */
    suspend fun getSchemas(rootItemId: UUID): Map<String, WorkItemSchema>? = resolve(rootItemId)?.workItemSchemas

    /** Returns the [WorkItemSchema] for [type] under [rootItemId]'s config, or null if no row/parse/type match. */
    suspend fun getSchemaForType(
        rootItemId: UUID,
        type: String
    ): WorkItemSchema? = resolve(rootItemId)?.workItemSchemas?.get(type)

    /** Returns the note schema entries for trait [traitName] under [rootItemId]'s config, or null. */
    suspend fun getTraitNotes(
        rootItemId: UUID,
        traitName: String
    ): List<NoteSchemaEntry>? = resolve(rootItemId)?.traits?.get(traitName)

    /** Returns all trait definitions for [rootItemId]'s config, or null when no config row exists or it fails to parse. */
    suspend fun getAllTraits(rootItemId: UUID): Map<String, List<NoteSchemaEntry>>? = resolve(rootItemId)?.traits

    /**
     * Returns the cached config fingerprint for [rootItemId], or null when no config row exists or
     * it fails to parse; a read error serves the last-known-good fingerprint or throws
     * [PerRootConfigUnavailableException]. Goes through [resolve]'s normal fingerprint-check hot-reload path first
     * (so this never returns a stale fingerprint after a concurrent push) — callers needing to
     * report which config version supplied a resolved schema (e.g. `query_items`'s `schema`
     * operation) should call this immediately after a [getSchemaForType]/[getSchemas] lookup that
     * resolved from this root's per-root layer.
     */
    suspend fun getFingerprint(rootItemId: UUID): String? {
        resolve(rootItemId) ?: return null
        return cache[rootItemId]?.fingerprint
    }

    /**
     * Returns [rootItemId]'s explicitly-configured `note_limits.mode`, or null when there is no
     * config row for this root, the row fails to parse, or the row's document has no top-level
     * `note_limits` key at all — see [YamlSchemaParser.ParsedConfig.noteLimitsModeExplicit] for the
     * absent-vs-explicit distinction this preserves. Callers (see
     * [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext.resolveNoteLimitsMode])
     * treat a null return as "fall through to the global note-limits mode", not as "warn".
     */
    suspend fun getNoteLimitsMode(rootItemId: UUID): String? = resolve(rootItemId)?.noteLimitsModeExplicit

    /**
     * Returns [rootItemId]'s explicitly-configured `status_labels` trigger→label map, or null when
     * there is no config row for this root, the row fails to parse, or the row's document has no
     * top-level `status_labels` key at all. A non-null return may still be a PARTIAL map — see
     * [YamlSchemaParser.ParsedConfig.statusLabels] — callers fall through to the global status label
     * service on a per-trigger basis when a trigger key is absent from this map (see
     * [io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext.resolveStatusLabel]).
     */
    suspend fun getStatusLabels(rootItemId: UUID): Map<String, String?>? = resolve(rootItemId)?.statusLabels

    /**
     * Returns the parsed config for [rootItemId], reusing the cached parse when the DB
     * fingerprint hasn't changed since it was cached. Returns null when there is no config row
     * for this root, or the stored YAML fails to parse (see class doc — both are absence, and fall
     * through to the global loader). Serves the last-known-good cached parse, without evicting it,
     * when the repository read itself fails — and throws [PerRootConfigUnavailableException] if
     * there is no cached parse to serve in that case (see class doc "Failure handling").
     */
    private suspend fun resolve(rootItemId: UUID): YamlSchemaParser.ParsedConfig? {
        val fingerprintResult = repository.getFingerprint(rootItemId)
        val currentFingerprint =
            when (fingerprintResult) {
                is Result.Success -> fingerprintResult.data
                is Result.Error -> return lastKnownGoodOrThrow(rootItemId, fingerprintResult.error)
            }
        if (currentFingerprint == null) {
            // No config row for this root — drop any stale cache entry (e.g. the row was deleted
            // since we last cached it) and report "no config".
            cache.remove(rootItemId)
            return null
        }

        cache[rootItemId]?.let { cached ->
            if (cached.fingerprint == currentFingerprint) return cached.parsed
        }

        val rowResult = repository.get(rootItemId)
        val stored =
            when (rowResult) {
                is Result.Success -> rowResult.data
                is Result.Error -> return lastKnownGoodOrThrow(rootItemId, rowResult.error)
            }
        if (stored == null) {
            cache.remove(rootItemId)
            return null
        }

        val parsed =
            parseYaml(rootItemId, stored.configYaml) ?: run {
                cache.remove(rootItemId)
                return null
            }

        cache[rootItemId] = CacheEntry(stored.fingerprint, parsed)
        return parsed
    }

    /**
     * Handles a [Result.Error] from either read in [resolve]: logs a WARN naming [rootItemId] and
     * [error], and serves the last-known-good cached parse for that root WITHOUT evicting it — an
     * error must never evict a cache entry, only a confirmed absence or a fresher fingerprint can.
     * Throws [PerRootConfigUnavailableException] when there is no cached entry to serve (a cold
     * cache), since silently falling back to "no per-root config" would let the global layer's
     * gates/traits/leases apply where this root's config should have governed instead.
     */
    private fun lastKnownGoodOrThrow(
        rootItemId: UUID,
        error: RepositoryError
    ): YamlSchemaParser.ParsedConfig? {
        logger.warn("Per-root config read failed for root {}: {}", rootItemId, error)
        cache[rootItemId]?.let { return it.parsed }
        // The full repository error (which may carry SQL/driver text) stays in the server log above;
        // the exception message reaches MCP and REST clients, so it names only the root.
        throw PerRootConfigUnavailableException(
            rootItemId,
            "Per-root config for root $rootItemId is temporarily unavailable (read failed; no last-known-good config cached)",
            (error as? RepositoryError.DatabaseError)?.cause
        )
    }

    /**
     * Parses [configYaml] via the shared [YamlSchemaParser] (same schema/trait structures as
     * [YamlWorkItemSchemaService]). Unknown top-level keys (e.g. a `project:` block used by other
     * per-root settings) are ignored silently — [YamlSchemaParser] only reads the keys it knows
     * about. Passes `warnOnMissingSchemas = false`: unlike the global config file, a per-root
     * document legitimately may carry no `work_item_schemas:`/`note_schemas:` section at all (it
     * might exist purely for other per-root settings), so this must NOT emit the global loader's
     * "no schemas loaded" warning.
     *
     * Parses via [SafeConstructor] rather than SnakeYAML's default `Constructor`: [configYaml]
     * originates from [ProjectConfigRepository], which stores whatever a caller pushed over the
     * MCP protocol (see `ManageProjectConfigTool`) — attacker-reachable input, not a trusted local
     * file. The default `Constructor` will instantiate an arbitrary Java type named by a `!!`-tag
     * (CWE-502); `SafeConstructor` only ever builds plain maps/lists/scalars, which is all this
     * document format needs, and rejects anything else as a parse failure (caught below).
     */
    private fun parseYaml(
        rootItemId: UUID,
        configYaml: String
    ): YamlSchemaParser.ParsedConfig? =
        try {
            @Suppress("UNCHECKED_CAST")
            val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(configYaml)
            if (root == null) {
                YamlSchemaParser.ParsedConfig(emptyMap(), emptyMap(), emptyList())
            } else {
                YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            logger.warn("Failed to parse per-root config for root {}: {}", rootItemId, e.message)
            null
        }
}

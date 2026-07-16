package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
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
 * Parse failures (malformed YAML) are logged as warnings and treated as "no per-root config" —
 * [resolve] returns null, callers fall through to the global `.taskorchestrator/config.yaml`
 * loader. This class never throws on a read.
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
     * it fails to parse. Goes through [resolve]'s normal fingerprint-check hot-reload path first
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
     * for this root, or the stored YAML fails to parse (see class doc — failures fall through to
     * the global loader rather than throwing).
     */
    private suspend fun resolve(rootItemId: UUID): YamlSchemaParser.ParsedConfig? {
        val currentFingerprint = (repository.getFingerprint(rootItemId) as? Result.Success)?.data
        if (currentFingerprint == null) {
            // No config row for this root (or the fingerprint read failed) — drop any stale
            // cache entry (e.g. the row was deleted since we last cached it) and report "no config".
            cache.remove(rootItemId)
            return null
        }

        cache[rootItemId]?.let { cached ->
            if (cached.fingerprint == currentFingerprint) return cached.parsed
        }

        val stored = (repository.get(rootItemId) as? Result.Success)?.data
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

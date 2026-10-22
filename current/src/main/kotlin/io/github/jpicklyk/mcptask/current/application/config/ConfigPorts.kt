package io.github.jpicklyk.mcptask.current.application.config

import java.util.UUID

/**
 * The single, process-wide global config layer. Implementations parse the global
 * `.taskorchestrator/config.yaml` (or an equivalent process-scoped floor config) exactly ONCE at
 * startup and cache the result for the life of the process — restart to reload. Returns `null`
 * when no global config file is present (schema-free mode), never on a parse error: a malformed
 * file fails closed with an exception from the first [layer] call (see `GlobalConfigFile`; startup
 * forces that call), not by returning `null` here.
 */
fun interface GlobalConfigSource {
    fun layer(): ConfigLayer?
}

/**
 * A per-root config layer, keyed by a project root's WorkItem UUID. `null` = absence: no config
 * version exists for [rootId], or its head is a reset version — both fall through to the
 * global layer. A head whose stored canonical form is missing or undecodable throws
 * `PerRootConfigInvalidException` (fail closed; never absence). A transient read failure with no last-known-good entry to serve throws
 * `PerRootConfigUnavailableException` (contract unchanged from `PerRootConfigService`) rather than
 * returning `null` — callers must not confuse "no per-root config" with "per-root config
 * temporarily unreadable".
 */
interface PerRootConfigSource {
    suspend fun layer(rootId: UUID): ConfigLayer?
}

/**
 * Stored config versions by id, for resolving an item against the versions it is pinned to (plan section 3.8). A
 * version never changes, so implementations may cache a loaded layer for the life of the process.
 *
 * [layerAt] returns the layer built from version [versionId] (tagged [ConfigSource.GLOBAL] for a server-scope version,
 * [ConfigSource.PER_ROOT] for a root-scope one, with its fingerprint, id and number), or null when no such row exists
 * or the row is a reset version (no config). A row whose stored canonical form is missing or undecodable throws
 * `PerRootConfigInvalidException`; a read fault throws `PerRootConfigUnavailableException` (fail closed: a pinned
 * item never resolves against a guessed version).
 */
interface ConfigVersionSource {
    suspend fun layerAt(versionId: UUID): ConfigLayer?
}

/**
 * Shared YAML -> [ConfigDocument] parse for per-root config readers and writers (currently
 * `PerRootConfigService` and `ConfigService`), so there is exactly one
 * SafeConstructor-parsing implementation for attacker-reachable (pushed) config YAML, instead of
 * one per caller.
 */
interface ConfigDocumentParser {
    /** Outcome of a single [parse] call. */
    sealed interface Outcome {
        /**
         * [document] is the parsed document (an empty/comment-only document parses to
         * [ConfigDocument.EMPTY], never a null document — see the type's non-nullability).
         * [rawRoot] is the SafeConstructor-parsed root map before [ConfigDocument] extraction, or
         * `null` for an empty/blank document; callers that need the raw map (e.g. an
         * embedded-rootId guard) reuse it instead of re-parsing.
         */
        data class Parsed(
            val document: ConfigDocument,
            val rawRoot: Map<String, Any>?
        ) : Outcome

        /**
         * [detail] is the parse failure message (YAML syntax error, non-mapping root, structural seat error, ...).
         * [violations] carries the same failure with JSON Pointer paths when the failure has one (a structural seat
         * error), else it is empty.
         */
        data class Failed(
            val detail: String,
            val violations: List<ConfigViolation> = emptyList(),
        ) : Outcome
    }

    /**
     * Parses [yaml] into an [Outcome]. [warnOnMissingSchemas] controls whether a document with
     * neither `work_item_schemas:` nor `note_schemas:` records a "no schemas loaded" warning — see
     * `YamlSchemaParser.parseRoot` for why the global loader wants this warning and per-root
     * documents do not. [mode] classifies former soft skips as errors ([ParseMode.STRICT]) or warnings
     * ([ParseMode.MIGRATION], the default: reading config that already exists never rejects it).
     */
    fun parse(
        yaml: String,
        warnOnMissingSchemas: Boolean,
        mode: ParseMode = ParseMode.MIGRATION,
    ): Outcome

    /**
     * Builds a [ConfigDocument] from an already-parsed document [root] (a null root yields [ConfigDocument.EMPTY]).
     * Used to rebuild a stored version from its canonical JSON without re-parsing YAML.
     */
    fun fromRoot(
        root: Map<String, Any>?,
        warnOnMissingSchemas: Boolean,
        mode: ParseMode = ParseMode.MIGRATION,
    ): ConfigDocument = throw UnsupportedOperationException("This parser cannot build a document from a parsed root")
}
